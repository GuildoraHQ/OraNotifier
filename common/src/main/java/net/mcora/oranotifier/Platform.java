// What OraNotifier needs from the server it runs on. Each platform module (paper, velocity, bungee, fabric,
// neoforge) implements this; OraCore does everything else.
package net.mcora.oranotifier;

import java.nio.file.Path;
import java.util.List;

public interface Platform {
    /** e.g. "Paper 1.21.4"; sent to Mcora so the owner sees what is connected. */
    String name();

    /** Folder for config.properties. */
    Path dataDir();

    /** Runs on the server thread (commands and events must not run on the polling thread). */
    void runOnMain(Runnable task);

    /** Runs a console command (without the leading slash). */
    void runCommand(String command);

    /**
     * Fires NuVotifier's vote event if NuVotifier is installed, so existing reward plugins (VotingPlugin, SuperbVote, …)
     * work unchanged. `nuVote` is a com.vexsoftware.votifier.model.Vote. Returns false when there is no such event here.
     */
    boolean fireVotifierEvent(Object nuVote);

    /** Names of the players online here; called on the server thread. */
    List<String> onlinePlayers();

    void info(String message);

    /** Sends a chat message to one online player (no-op if they left). */
    void message(java.util.UUID player, String text);

    /**
     * Whether this platform sees movement, so idle time can be told apart from playing. Proxies (Velocity, BungeeCord)
     * don't: a quiet player would look AFK, so they report the whole session as active.
     */
    default boolean afkDetection() { return true; }

    void warn(String message);
}
