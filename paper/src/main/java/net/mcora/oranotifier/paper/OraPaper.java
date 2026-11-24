// Paper / Spigot / Purpur entry point. Commands and NuVotifier's VotifierEvent run on the main thread.
// 玩家紀錄: join / quit, and movement, chat and commands as activity (AFK detection); /mcora for players.
package net.mcora.oranotifier.paper;

import net.mcora.oranotifier.NuVotifier;
import net.mcora.oranotifier.Lang;
import net.mcora.oranotifier.OraCore;
import net.mcora.oranotifier.Platform;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.Event;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

public final class OraPaper extends JavaPlugin implements Platform, Listener {
    private OraCore core;

    @Override
    public void onEnable() {
        core = new OraCore(this);
        core.start();
        getServer().getPluginManager().registerEvents(this, this);
        for (Player p : Bukkit.getOnlinePlayers()) join(p); // /reload
    }

    private static boolean ignored(Player p) { return p.hasPermission("oranotifier.players.ignore"); }

    private void join(Player p) { if (!ignored(p)) core.playerJoin(p.getUniqueId(), p.getName()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) { join(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) { core.playerQuit(e.getPlayer().getUniqueId()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        // Looking around counts too; standing still while being pushed (no rotation, same block) still does, which is fine.
        if (e.getTo() != null && (e.getFrom().getYaw() != e.getTo().getYaw() || e.getFrom().getPitch() != e.getTo().getPitch()
                || e.getFrom().getBlockX() != e.getTo().getBlockX() || e.getFrom().getBlockZ() != e.getTo().getBlockZ())) {
            core.playerActivity(e.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    @SuppressWarnings("deprecation") // AsyncPlayerChatEvent: still fired on Paper, and the only chat event on Spigot
    public void onChat(AsyncPlayerChatEvent e) { core.playerActivity(e.getPlayer().getUniqueId()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) { core.playerActivity(e.getPlayer().getUniqueId()); }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Lang.t("command.playersOnly"));
            return true;
        }
        p.sendMessage(core.command(p.getUniqueId(), p.getName(), args));
        return true;
    }

    @Override
    public void onDisable() {
        if (core != null) core.stop();
    }

    @Override
    public void message(UUID player, String text) {
        Player p = Bukkit.getPlayer(player);
        if (p != null) p.sendMessage(text);
    }

    @Override public String name() { return Bukkit.getName() + " " + Bukkit.getBukkitVersion().replace("-R0.1-SNAPSHOT", ""); }
    @Override public Path dataDir() { return getDataFolder().toPath(); }
    @Override public void runOnMain(Runnable task) { Bukkit.getScheduler().runTask(this, task); }
    @Override public void runCommand(String command) { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command); }
    @Override public List<String> onlinePlayers() { return Bukkit.getOnlinePlayers().stream().map(p -> p.getName()).toList(); }
    @Override public void info(String message) { getLogger().info(message); }
    @Override public void warn(String message) { getLogger().warning(message); }

    @Override
    public boolean fireVotifierEvent(Object nuVote) {
        Object event = NuVotifier.event("com.vexsoftware.votifier.model.VotifierEvent", nuVote, getClassLoader());
        if (!(event instanceof Event e)) return false;
        Bukkit.getPluginManager().callEvent(e);
        return true;
    }
}
