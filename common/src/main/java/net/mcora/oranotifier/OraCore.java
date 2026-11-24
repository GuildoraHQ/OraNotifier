// The part shared by every platform: polls POST <api>/api/ora/poll with the token, claims the votes it got with
// POST <api>/api/ora/claim, then runs each on the server thread (reward commands + NuVotifier's event).
// The plugin only makes outgoing HTTPS requests, so the server needs no open port (unlike Votifier).
// With players.report=true the same poll also carries play sessions (PlayerTracker, docs/mc-players.md).
// Claiming first means two servers on one network (same token) never both reward a vote; the cost is that a vote
// claimed right before a crash is lost. Votes nobody claimed wait on Mcora for 7 days (server down, player offline).
package net.mcora.oranotifier;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class OraCore {
    public static final String VERSION = "1.1.0";

    private final Platform platform;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private ScheduledExecutorService scheduler;
    private OraConfig config;
    /** Seconds until the next poll: the configured interval, longer after errors (up to 5 minutes). */
    private int delay;
    private String lastProblem;
    /** Null unless players.report=true. */
    private volatile PlayerTracker tracker;

    public OraCore(Platform platform) {
        this.platform = platform;
    }

    public void start() {
        try {
            config = OraConfig.load(platform.dataDir());
        } catch (Exception e) {
            platform.warn(Lang.t("config.readFailed", "error", e.getMessage()));
            return;
        }
        Lang.use(config.language);
        if (config.token.isEmpty()) {
            platform.warn(Lang.t("config.noToken", "path", platform.dataDir().resolve("config.properties")));
            return;
        }
        delay = config.pollSeconds;
        if (config.playersReport) tracker = new PlayerTracker(platform, config);
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "OraNotifier");
            t.setDaemon(true);
            return t;
        });
        scheduler.schedule(this::tick, 2, TimeUnit.SECONDS);
        platform.info(Lang.t(tracker != null ? "core.startedWithPlayers" : "core.started",
                "version", VERSION, "server", config.serverName, "deliver", config.deliver, "seconds", config.pollSeconds));
    }

    public void stop() {
        if (scheduler != null) scheduler.shutdownNow();
        scheduler = null;
        PlayerTracker t = tracker;
        if (t != null) t.stop(); // ends open sessions; they're sent on the next start
    }

    // ---- Player events and /mcora, called by the platform. Players with oranotifier.players.ignore are filtered there.

    public void playerJoin(java.util.UUID uuid, String name) {
        PlayerTracker t = tracker;
        String notice = t == null ? null : t.join(uuid, name);
        if (notice != null) platform.message(uuid, notice);
    }

    public void playerQuit(java.util.UUID uuid) {
        PlayerTracker t = tracker;
        if (t != null) t.quit(uuid);
    }

    public void playerActivity(java.util.UUID uuid) {
        PlayerTracker t = tracker;
        if (t != null) t.activity(uuid);
    }

    /** /mcora hide | /mcora link <code>, typed by a player. Returns the reply to show them. */
    public String command(java.util.UUID uuid, String name, String[] args) {
        PlayerTracker t = tracker;
        if (t == null) return Lang.t("command.disabled");
        if (args.length == 1 && args[0].equalsIgnoreCase("hide")) {
            t.requestHide(uuid);
            return Lang.t("command.hideSent");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("link") && args[1].matches("[A-Za-z0-9]{4,12}")) {
            t.requestLink(uuid, name, args[1].toUpperCase());
            return Lang.t("command.linkSent");
        }
        return Lang.t("command.usage");
    }

    private void tick() {
        try {
            poll();
            if (lastProblem != null) platform.info(Lang.t("core.reconnected"));
            lastProblem = null;
            delay = config.pollSeconds;
        } catch (Problem p) {
            if (!p.getMessage().equals(lastProblem)) platform.warn(p.getMessage());
            lastProblem = p.getMessage();
            delay = Math.min(300, p.fatal ? 300 : Math.max(delay * 2, 15));
        } catch (Exception e) {
            String type = e.getClass().getSimpleName();
            String msg = Lang.t("core.unreachable", "error", e.getMessage() == null ? type : Lang.t("core.errorDetail", "type", type, "message", e.getMessage()));
            if (!msg.equals(lastProblem)) platform.warn(msg);
            lastProblem = msg;
            delay = Math.min(300, Math.max(delay * 2, 15));
        }
        ScheduledExecutorService s = scheduler;
        if (s != null && !s.isShutdown()) s.schedule(this::tick, delay, TimeUnit.SECONDS);
    }

    private JsonObject node() {
        JsonObject o = new JsonObject();
        o.addProperty("server", config.serverName);
        o.addProperty("mode", config.deliver);
        return o;
    }

    /** Runs on the server thread and waits for the result (player lists and commands aren't thread-safe). */
    private <T> T onMain(Supplier<T> task) throws Exception {
        CompletableFuture<T> f = new CompletableFuture<>();
        platform.runOnMain(() -> {
            try {
                f.complete(task.get());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return f.get(30, TimeUnit.SECONDS);
    }

    private void poll() throws Exception {
        JsonObject body = node();
        body.addProperty("platform", platform.name() + " / OraNotifier " + VERSION);
        PlayerTracker t = tracker;
        if (config.deliver.equals("online")) {
            List<String> online = onMain(platform::onlinePlayers);
            if (online.isEmpty() && t == null) return; // nobody here to reward; the votes wait on Mcora
            JsonArray names = new JsonArray();
            online.stream().limit(5000).forEach(names::add);
            body.add("online", names);
        }
        PlayerTracker.Sent sent = new PlayerTracker.Sent();
        if (t != null) body.add("players", t.body(sent));
        JsonObject res = post("/api/ora/poll", body);
        if (t != null) {
            t.answered(sent, res.has("players") && res.get("players").isJsonObject() ? res.getAsJsonObject("players") : null);
            if (res.has("messages") && res.get("messages").isJsonArray()) {
                for (JsonElement el : res.getAsJsonArray("messages")) {
                    JsonObject m = el.getAsJsonObject();
                    java.util.UUID to = java.util.UUID.fromString(m.get("uuid").getAsString());
                    String text = m.get("text").getAsString();
                    platform.runOnMain(() -> platform.message(to, text));
                }
            }
        }
        String service = res.has("serviceName") ? res.get("serviceName").getAsString() : "Mcora";
        JsonArray votes = res.getAsJsonArray("votes");
        if (votes == null || votes.isEmpty()) return;

        JsonObject claim = node();
        JsonArray ids = new JsonArray();
        for (JsonElement el : votes) ids.add(el.getAsJsonObject().get("id").getAsInt());
        claim.add("ids", ids);
        Set<Integer> claimed = new HashSet<>();
        for (JsonElement el : post("/api/ora/claim", claim).getAsJsonArray("claimed")) claimed.add(el.getAsInt());

        for (JsonElement el : votes) {
            JsonObject v = el.getAsJsonObject();
            if (!claimed.contains(v.get("id").getAsInt())) continue; // another server on the network took it
            String player = v.get("username").getAsString();
            long timestamp = v.get("timestamp").getAsLong();
            try {
                onMain(() -> {
                    deliver(service, player, timestamp);
                    return null;
                });
            } catch (Exception e) {
                platform.warn(Lang.t("vote.failed", "player", player, "error", e.getMessage()));
            }
        }
    }

    private void deliver(String service, String player, long timestamp) {
        platform.info(Lang.t("vote.received", "player", player, "service", service));
        Object nuVote = NuVotifier.vote(service, player, timestamp);
        if (nuVote != null) platform.fireVotifierEvent(nuVote);
        for (String c : config.commands) {
            platform.runCommand(c.replace("{player}", player).replace("{service}", service));
        }
    }

    private JsonObject post(String path, JsonObject body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(config.api + path))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + config.token)
                .header("Content-Type", "application/json")
                .header("Accept-Language", Lang.httpLanguage())
                .header("User-Agent", "OraNotifier/" + VERSION + " (" + platform.name() + ")")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 401) throw new Problem(Lang.t("api.badToken"), true);
        JsonObject json;
        try {
            json = JsonParser.parseString(res.body()).getAsJsonObject();
        } catch (Exception e) {
            throw new Problem(Lang.t("api.badResponse", "status", res.statusCode()), false);
        }
        if (res.statusCode() >= 400) {
            String err = json.has("error") ? json.get("error").getAsString() : "HTTP " + res.statusCode();
            throw new Problem(Lang.t("api.refused", "error", err), false);
        }
        return json;
    }

    private static final class Problem extends Exception {
        final boolean fatal;

        Problem(String message, boolean fatal) {
            super(message);
            this.fatal = fatal;
        }
    }
}
