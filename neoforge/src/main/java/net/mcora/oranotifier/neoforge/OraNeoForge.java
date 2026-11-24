// NeoForge entry point. Starts polling once the server is up; commands run on the server thread.
// Server-only: on a client it never starts, because ServerStartedEvent also fires for single-player worlds, so it checks.
// 玩家紀錄: login / logout, chat, and once a second each player's position and view direction (NeoForge has no cheap
// movement event) as activity for AFK detection; /mcora for players. oranotifier.players.ignore isn't checked here
// (NeoForge's permission API needs nodes registered by a permission mod).
package net.mcora.oranotifier.neoforge;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import net.mcora.oranotifier.Lang;
import net.mcora.oranotifier.OraCore;
import net.mcora.oranotifier.Platform;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Mod("oranotifier")
public final class OraNeoForge implements Platform {
    private static final Logger LOG = LogUtils.getLogger();
    private MinecraftServer server;
    private OraCore core;
    private final Map<UUID, double[]> lastPose = new HashMap<>();
    private int ticks;

    public OraNeoForge() {
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, e -> {
            if (!e.getServer().isDedicatedServer()) return;
            server = e.getServer();
            core = new OraCore(this);
            core.start();
        });
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent.class, e -> {
            if (core != null) core.stop();
        });
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent.class, e -> {
            if (core != null) core.playerJoin(e.getEntity().getUUID(), e.getEntity().getName().getString());
        });
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent.class, e -> {
            lastPose.remove(e.getEntity().getUUID());
            if (core != null) core.playerQuit(e.getEntity().getUUID());
        });
        NeoForge.EVENT_BUS.addListener(ServerChatEvent.class, e -> {
            if (core != null) core.playerActivity(e.getPlayer().getUUID());
        });
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, e -> {
            if (core == null || ++ticks % 20 != 0) return;
            for (ServerPlayer p : e.getServer().getPlayerList().getPlayers()) {
                double[] pose = {p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot()};
                double[] before = lastPose.put(p.getUUID(), pose);
                if (before != null && !Arrays.equals(before, pose)) core.playerActivity(p.getUUID());
            }
        });
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, e -> e.getDispatcher().register(Commands.literal("mcora")
                .then(Commands.literal("hide").executes(ctx -> reply(ctx.getSource(), new String[]{"hide"})))
                .then(Commands.literal("link").then(Commands.argument("code", StringArgumentType.word())
                        .executes(ctx -> reply(ctx.getSource(), new String[]{"link", StringArgumentType.getString(ctx, "code")}))))
                .executes(ctx -> reply(ctx.getSource(), new String[0]))));
    }

    private int reply(CommandSourceStack source, String[] args) {
        ServerPlayer p = source.getPlayer();
        if (p == null || core == null) {
            source.sendSystemMessage(Component.literal(Lang.t("command.playersOnly")));
            return 0;
        }
        p.sendSystemMessage(Component.literal(core.command(p.getUUID(), p.getName().getString(), args)));
        return 1;
    }

    @Override public String name() { return "NeoForge " + server.getServerVersion(); }
    @Override public Path dataDir() { return FMLPaths.CONFIGDIR.get().resolve("oranotifier"); }
    @Override public void runOnMain(Runnable task) { server.execute(task); }
    @Override public void runCommand(String command) { server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command); }
    @Override public boolean fireVotifierEvent(Object nuVote) { return false; }
    @Override public List<String> onlinePlayers() { return server.getPlayerList().getPlayers().stream().map(p -> p.getName().getString()).toList(); }
    @Override public void info(String message) { LOG.info(message); }
    @Override public void warn(String message) { LOG.warn(message); }

    @Override
    public void message(UUID player, String text) {
        ServerPlayer p = server.getPlayerList().getPlayer(player);
        if (p != null) p.sendSystemMessage(Component.literal(text));
    }
}
