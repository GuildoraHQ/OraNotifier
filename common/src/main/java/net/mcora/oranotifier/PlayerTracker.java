// 玩家追蹤 (docs/mc-players.md): with players.report=true, records each player's session on this server (join → quit,
// minus AFK time) and hands finished sessions, who is online, and the /mcora hide and /mcora link requests to OraCore's
// poll. Finished sessions are kept in sessions.json until Mcora accepts them, so restarts and outages lose nothing.
// Only UUID, name, edition and times are kept: never chat, IPs, positions or inventories.
// Platforms call join / quit / activity from their event threads and the poll reads from its own: everything is synchronized.
package net.mcora.oranotifier;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PlayerTracker {
    private static final long DAY = 24L * 3600 * 1000;
    // The API refuses sessions over 24 h, so long ones are cut into pieces well before that.
    private static final long SPLIT_AFTER = 12L * 3600 * 1000;

    private static final class Live {
        final String name, edition, xuid;
        long joinedAt, lastActivity, afkMs;

        Live(String name, String edition, String xuid, long now) {
            this.name = name;
            this.edition = edition;
            this.xuid = xuid;
            this.joinedAt = now;
            this.lastActivity = now;
        }
    }

    private final Platform platform;
    private final long afkMs;
    private final boolean afkDetection;
    private final boolean notice;
    private final Path sessionsFile, seenFile;
    private final Map<UUID, Live> live = new LinkedHashMap<>();
    private final List<JsonObject> finished = new ArrayList<>();
    private final Set<String> hide = new HashSet<>();
    private final List<JsonObject> links = new ArrayList<>();
    private final Set<String> seen = new HashSet<>();
    private String lastRefusal;

    PlayerTracker(Platform platform, OraConfig config) {
        this.platform = platform;
        this.afkMs = config.afkMinutes * 60_000L;
        this.afkDetection = platform.afkDetection();
        this.notice = config.playersNotice;
        this.sessionsFile = platform.dataDir().resolve("sessions.json");
        this.seenFile = platform.dataDir().resolve("notified.txt");
        try {
            if (Files.exists(sessionsFile)) {
                for (JsonElement e : JsonParser.parseString(Files.readString(sessionsFile, StandardCharsets.UTF_8)).getAsJsonArray()) {
                    finished.add(e.getAsJsonObject());
                }
            }
            if (Files.exists(seenFile)) seen.addAll(Files.readAllLines(seenFile, StandardCharsets.UTF_8));
        } catch (Exception e) {
            platform.warn(Lang.t("players.cacheReadFailed", "error", e.getMessage()));
        }
    }

    /** Floodgate gives Bedrock players a UUID whose top 64 bits are 0 and whose bottom 64 bits are the XUID. */
    static boolean isBedrock(UUID uuid) {
        return uuid.getMostSignificantBits() == 0;
    }

    /** A player joined. Returns the one-time notice to show them, or null. */
    public synchronized String join(UUID uuid, String name) {
        long now = System.currentTimeMillis();
        boolean bedrock = isBedrock(uuid);
        live.put(uuid, new Live(name, bedrock ? "bedrock" : "java", bedrock ? Long.toUnsignedString(uuid.getLeastSignificantBits()) : null, now));
        if (!notice || !seen.add(uuid.toString())) return null;
        try {
            Files.writeString(seenFile, uuid + "\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            platform.warn(Lang.t("players.notifiedWriteFailed", "error", e.getMessage()));
        }
        return Lang.t("players.notice");
    }

    /** Movement, chat or a command: the player isn't AFK. Gaps longer than afkMinutes count as AFK beyond the first afkMinutes. */
    public synchronized void activity(UUID uuid) {
        Live l = live.get(uuid);
        if (l == null) return;
        long now = System.currentTimeMillis();
        addIdle(l, now);
        l.lastActivity = now;
    }

    private void addIdle(Live l, long now) {
        long gap = now - Math.max(l.lastActivity, l.joinedAt);
        if (afkDetection && gap > afkMs) l.afkMs += gap - afkMs;
    }

    public synchronized void quit(UUID uuid) {
        Live l = live.remove(uuid);
        if (l != null) end(uuid, l, System.currentTimeMillis());
        save();
    }

    private void end(UUID uuid, Live l, long now) {
        addIdle(l, now);
        long length = now - l.joinedAt;
        if (length < 1000) return;
        JsonObject s = new JsonObject();
        s.addProperty("uuid", uuid.toString());
        s.addProperty("name", l.name);
        s.addProperty("edition", l.edition);
        if (l.xuid != null) s.addProperty("xuid", l.xuid);
        s.addProperty("joinedAt", l.joinedAt);
        s.addProperty("leftAt", now);
        s.addProperty("activeSeconds", Math.max(0, (length - l.afkMs) / 1000));
        finished.add(s);
    }

    /** The server is stopping: everyone's session ends now. */
    public synchronized void stop() {
        long now = System.currentTimeMillis();
        live.forEach((uuid, l) -> end(uuid, l, now));
        live.clear();
        save();
    }

    public synchronized void requestHide(UUID uuid) {
        hide.add(uuid.toString());
    }

    public synchronized void requestLink(UUID uuid, String name, String code) {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", uuid.toString());
        o.addProperty("name", name);
        o.addProperty("code", code);
        links.add(o);
    }

    /** What goes into the poll under "players"; `sent` remembers it so accepted() drops exactly that. */
    synchronized JsonObject body(Sent sent) {
        long now = System.currentTimeMillis();
        // Split sessions that have run very long into finished pieces.
        live.forEach((uuid, l) -> {
            if (now - l.joinedAt > SPLIT_AFTER) {
                end(uuid, l, now);
                l.joinedAt = now;
                l.lastActivity = now;
                l.afkMs = 0;
            }
        });
        JsonObject o = new JsonObject();
        JsonArray sessions = new JsonArray();
        finished.stream().limit(2000).forEach(sessions::add);
        o.add("sessions", sessions);
        JsonArray online = new JsonArray();
        live.entrySet().stream().limit(5000).forEach(e -> {
            JsonObject p = new JsonObject();
            p.addProperty("uuid", e.getKey().toString());
            p.addProperty("name", e.getValue().name);
            p.addProperty("edition", e.getValue().edition);
            p.addProperty("since", e.getValue().joinedAt);
            online.add(p);
        });
        o.add("online", online);
        JsonArray h = new JsonArray();
        hide.forEach(h::add);
        o.add("hide", h);
        JsonArray l = new JsonArray();
        links.forEach(l::add);
        o.add("links", l);
        sent.sessions = sessions.size();
        sent.hide = new HashSet<>(hide);
        sent.links = links.size();
        return o;
    }

    static final class Sent {
        int sessions, links;
        Set<String> hide = Set.of();
    }

    /** Mcora's answer to the poll: drop what it took, or (refused) keep only what is still young enough to send later. */
    synchronized void answered(Sent sent, JsonObject players) {
        boolean accepted = players != null && players.has("accepted") && players.get("accepted").getAsBoolean();
        if (accepted) {
            finished.subList(0, Math.min(sent.sessions, finished.size())).clear();
            lastRefusal = null;
        } else {
            String reason = players != null && players.has("reason") ? players.get("reason").getAsString() : Lang.t("players.refusedDefault");
            if (!reason.equals(lastRefusal)) platform.warn(Lang.t("players.refused", "reason", reason));
            lastRefusal = reason;
            long cutoff = System.currentTimeMillis() - 7 * DAY;
            finished.removeIf(s -> s.get("leftAt").getAsLong() < cutoff);
        }
        // Commands are answered by Mcora's messages either way; don't resend them.
        hide.removeAll(sent.hide);
        links.subList(0, Math.min(sent.links, links.size())).clear();
        save();
    }

    private void save() {
        try {
            JsonArray a = new JsonArray();
            finished.forEach(a::add);
            Files.createDirectories(sessionsFile.getParent());
            Files.writeString(sessionsFile, a.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            platform.warn(Lang.t("players.sessionsWriteFailed", "error", e.getMessage()));
        }
    }

    /** Self-check for the AFK maths and Bedrock detection: java -cp … net.mcora.oranotifier.PlayerTracker */
    public static void main(String[] args) {
        if (!isBedrock(new UUID(0, 2535432196048835L))) throw new AssertionError("floodgate uuid");
        if (isBedrock(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"))) throw new AssertionError("java uuid");
        Live l = new Live("x", "java", null, 0);
        PlayerTracker t = new PlayerTracker(0);
        t.addIdle(l, 10 * 60_000); // 10 minutes without activity, afk after 5 → 5 minutes AFK
        if (l.afkMs != 5 * 60_000) throw new AssertionError("afk " + l.afkMs);
        System.out.println("ok");
    }

    private PlayerTracker(long afkMinutes) { // test only
        this.platform = null;
        this.afkMs = afkMinutes == 0 ? 5 * 60_000 : afkMinutes;
        this.afkDetection = true;
        this.notice = false;
        this.sessionsFile = null;
        this.seenFile = null;
    }
}
