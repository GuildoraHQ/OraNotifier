// BungeeCord / Waterfall entry point. Commands run from the proxy console; NuVotifier's proxy event fires if installed.
// 玩家紀錄: join / quit only; a proxy sees no movement, so whole sessions count as active (afkDetection false).
package net.mcora.oranotifier.bungee;

import net.mcora.oranotifier.NuVotifier;
import net.mcora.oranotifier.Lang;
import net.mcora.oranotifier.OraCore;
import net.mcora.oranotifier.Platform;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.api.plugin.Event;
import net.md_5.bungee.api.plugin.Plugin;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

public final class OraBungee extends Plugin implements Platform, Listener {
    private OraCore core;

    @Override
    public void onEnable() {
        core = new OraCore(this);
        core.start();
        getProxy().getPluginManager().registerListener(this, this);
        getProxy().getPluginManager().registerCommand(this, new Command("mcora") {
            @Override
            public void execute(CommandSender sender, String[] args) {
                if (!(sender instanceof ProxiedPlayer p)) {
                    sender.sendMessage(new TextComponent(Lang.t("command.playersOnly")));
                    return;
                }
                p.sendMessage(new TextComponent(core.command(p.getUniqueId(), p.getName(), args)));
            }
        });
    }

    @EventHandler
    public void onLogin(PostLoginEvent e) {
        if (!e.getPlayer().hasPermission("oranotifier.players.ignore")) core.playerJoin(e.getPlayer().getUniqueId(), e.getPlayer().getName());
    }

    @EventHandler
    public void onDisconnect(PlayerDisconnectEvent e) { core.playerQuit(e.getPlayer().getUniqueId()); }

    @Override public boolean afkDetection() { return false; }

    @Override
    public void message(UUID player, String text) {
        ProxiedPlayer p = ProxyServer.getInstance().getPlayer(player);
        if (p != null) p.sendMessage(new TextComponent(text));
    }

    @Override
    public void onDisable() {
        if (core != null) core.stop();
    }

    @Override public String name() { return ProxyServer.getInstance().getName() + " " + ProxyServer.getInstance().getVersion(); }
    @Override public Path dataDir() { return getDataFolder().toPath(); }
    // BungeeCord has no main thread; events and console commands may run from any thread.
    @Override public void runOnMain(Runnable task) { task.run(); }
    @Override public void runCommand(String command) { ProxyServer.getInstance().getPluginManager().dispatchCommand(ProxyServer.getInstance().getConsole(), command); }
    @Override public List<String> onlinePlayers() { return ProxyServer.getInstance().getPlayers().stream().map(p -> p.getName()).toList(); }
    @Override public void info(String message) { getLogger().info(message); }
    @Override public void warn(String message) { getLogger().warning(message); }

    @Override
    public boolean fireVotifierEvent(Object nuVote) {
        Object event = NuVotifier.event("com.vexsoftware.votifier.bungee.events.VotifierEvent", nuVote, getClass().getClassLoader());
        if (!(event instanceof Event e)) return false;
        ProxyServer.getInstance().getPluginManager().callEvent(e);
        return true;
    }
}
