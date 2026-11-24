// Velocity entry point. Commands run from the proxy console; NuVotifier's proxy event fires if it is installed.
// For in-game rewards on the backend servers, install OraNotifier on one backend instead (one token = one place).
// 玩家紀錄: join / quit only; a proxy sees no movement, so whole sessions count as active (afkDetection false).
package net.mcora.oranotifier.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import net.mcora.oranotifier.NuVotifier;
import net.mcora.oranotifier.Lang;
import net.mcora.oranotifier.OraCore;
import net.mcora.oranotifier.Platform;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

@Plugin(id = "oranotifier", name = "OraNotifier", version = OraCore.VERSION, url = "https://mcora.net/docs/minecraft/votifier",
        description = "Delivers Mcora (mcora.net) votes. No open port needed.", authors = {"Mcora"},
        dependencies = {@Dependency(id = "nuvotifier", optional = true)})
public final class OraVelocity implements Platform {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDir;
    private final OraCore core = new OraCore(this);

    @Inject
    public OraVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDir) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDir = dataDir;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent e) {
        core.start();
        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("mcora").plugin(this).build(), (SimpleCommand) inv -> {
            if (!(inv.source() instanceof Player p)) {
                inv.source().sendMessage(Component.text(Lang.t("command.playersOnly")));
                return;
            }
            p.sendMessage(Component.text(core.command(p.getUniqueId(), p.getUsername(), inv.arguments())));
        });
    }

    @Subscribe
    public void onLogin(PostLoginEvent e) {
        if (!e.getPlayer().hasPermission("oranotifier.players.ignore")) core.playerJoin(e.getPlayer().getUniqueId(), e.getPlayer().getUsername());
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent e) { core.playerQuit(e.getPlayer().getUniqueId()); }

    @Override public boolean afkDetection() { return false; }

    @Override
    public void message(UUID player, String text) { proxy.getPlayer(player).ifPresent(p -> p.sendMessage(Component.text(text))); }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent e) { core.stop(); }

    @Override public String name() { return "Velocity " + proxy.getVersion().getVersion(); }
    @Override public Path dataDir() { return dataDir; }
    // Velocity has no main thread; its event and command APIs are thread-safe.
    @Override public void runOnMain(Runnable task) { task.run(); }
    @Override public void runCommand(String command) { proxy.getCommandManager().executeAsync(proxy.getConsoleCommandSource(), command); }
    @Override public List<String> onlinePlayers() { return proxy.getAllPlayers().stream().map(p -> p.getUsername()).toList(); }
    @Override public void info(String message) { logger.info(message); }
    @Override public void warn(String message) { logger.warn(message); }

    @Override
    public boolean fireVotifierEvent(Object nuVote) {
        Object event = NuVotifier.event("com.vexsoftware.votifier.velocity.event.VotifierEvent", nuVote, getClass().getClassLoader());
        if (event == null) return false;
        proxy.getEventManager().fireAndForget(event);
        return true;
    }
}
