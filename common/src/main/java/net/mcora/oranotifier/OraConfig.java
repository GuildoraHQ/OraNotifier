// config.properties: the token from the Mcora editor, the API address and the reward commands.
// Plain .properties (read as UTF-8) so every platform can use the same file without a YAML/TOML library.
package net.mcora.oranotifier;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public final class OraConfig {
    /** Mcora's API on its own host (Guildora PLAN §63); OraCore falls back to OLD_API while it can't be reached. */
    public static final String DEFAULT_API = "https://api.guildora.net";
    public static final String OLD_API = "https://mcora.net";

    static final String DEFAULT = String.join("\n",
            "# OraNotifier: delivers Mcora votes to this server. https://mcora.net/docs/minecraft/votifier",
            "#",
            "# 1. On mcora.net, open your server's edit page -> 連結與通知 -> OraNotifier -> 產生 Token.",
            "# 2. Paste it below and restart the server.",
            "token=",
            "",
            "# Commands run from the console for every vote, in order. {player} = the voter's Minecraft name,",
            "# {service} = Mcora. Leave them all empty if a reward plugin (e.g. VotingPlugin) listens to NuVotifier's",
            "# vote event instead; OraNotifier fires it when NuVotifier is installed.",
            "command.1=say {player} 在 Mcora 投票支持了伺服器，謝謝！",
            "command.2=",
            "command.3=",
            "",
            "# Several servers on one token (a network): give each one its own name, shown on your Mcora edit page.",
            "server-name=default",
            "",
            "# Who runs a vote:",
            "#   always  once per network, by whichever server picks it up first, even if the player is offline (one server).",
            "#   online  once per network, on the server where the player is online; waits (up to 7 days) until they join one.",
            "#           Install on the proxy, or on every backend, with this mode.",
            "#   every   on this server for every vote, whatever the other servers do (e.g. per-server currency).",
            "deliver=always",
            "",
            "# Seconds between checks for new votes (5-300).",
            "poll-seconds=5",
            "",
            "# 玩家紀錄 (opt-in): report who played and for how long to Mcora, shown on mcora.net/player pages and your",
            "# server's 玩家 tab. Only UUID, name, edition and play time are sent; never chat, IPs, positions or inventories.",
            "# Your server must be verified on Mcora. On a network, turn it on in ONE place (the proxy, or each backend with",
            "# its own server-name), or the same play time is counted twice. https://mcora.net/docs/minecraft/players",
            "players.report=false",
            "# Minutes without moving, chatting or typing a command before time stops counting as played (servers only;",
            "# proxies can't see movement and count the whole session).",
            "players.afkMinutes=5",
            "# Tell each player once, on their first join, that play time is reported (and how to hide it: /mcora hide).",
            "players.notice=true",
            "# Players with the permission oranotifier.players.ignore (staff, vanish) are never reported.",
            "",
            "# Language of the plugin's messages (Minecraft's codes: zh_tw, zh_hk, zh_cn, ja_jp, ko_kr, en_us).",
            "language=zh_tw",
            "",
            "# Only change this for testing. Older configs say https://mcora.net, which keeps working.",
            "api=" + DEFAULT_API,
            "");

    public final String token;
    public final String api;
    public final int pollSeconds;
    public final List<String> commands;
    public final String serverName;
    /** always | online | every */
    public final String deliver;
    public final boolean playersReport;
    public final int afkMinutes;
    public final boolean playersNotice;
    /** Minecraft language code for Lang, e.g. zh_tw. */
    public final String language;

    private OraConfig(Properties p) {
        token = p.getProperty("token", "").trim();
        api = p.getProperty("api", DEFAULT_API).trim().replaceAll("/+$", "");
        int seconds;
        try {
            seconds = Integer.parseInt(p.getProperty("poll-seconds", "5").trim());
        } catch (NumberFormatException e) {
            seconds = 5;
        }
        pollSeconds = Math.max(5, Math.min(300, seconds));
        List<String> cmds = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            String c = p.getProperty("command." + i, "").trim();
            if (c.startsWith("/")) c = c.substring(1);
            if (!c.isEmpty()) cmds.add(c);
        }
        commands = List.copyOf(cmds);
        String name = p.getProperty("server-name", "default").trim();
        serverName = name.matches("[A-Za-z0-9_.-]{1,32}") ? name : "default";
        String mode = p.getProperty("deliver", "always").trim().toLowerCase();
        deliver = mode.equals("online") || mode.equals("every") ? mode : "always";
        playersReport = p.getProperty("players.report", "false").trim().equalsIgnoreCase("true");
        int afk;
        try {
            afk = Integer.parseInt(p.getProperty("players.afkMinutes", "5").trim());
        } catch (NumberFormatException e) {
            afk = 5;
        }
        afkMinutes = Math.max(1, Math.min(60, afk));
        playersNotice = !p.getProperty("players.notice", "true").trim().equalsIgnoreCase("false");
        language = p.getProperty("language", "zh_tw").trim();
    }

    /** Reads dataDir/config.properties, writing the default file first if there is none. */
    public static OraConfig load(Path dataDir) throws IOException {
        Path file = dataDir.resolve("config.properties");
        if (!Files.exists(file)) {
            Files.createDirectories(dataDir);
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                w.write(DEFAULT);
            }
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return new OraConfig(p);
    }
}
