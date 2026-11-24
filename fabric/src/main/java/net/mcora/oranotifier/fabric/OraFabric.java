// Fabric dedicated-server entry point. Starts polling once the server is up; commands run on the server thread.
// NuVotifier has no Fabric event we can rely on, so rewards come from the commands in config.properties.
// 玩家紀錄: join / quit, chat, and once a second each player's position and view direction (Fabric API has no movement
// event) as activity for AFK detection; /mcora for players. Fabric has no permission nodes without an extra mod, so
// oranotifier.players.ignore isn't checked here.
package net.mcora.oranotifier.fabric;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.mcora.oranotifier.Lang;
import net.mcora.oranotifier.OraCore;
import net.mcora.oranotifier.Platform;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class OraFabric implements DedicatedServerModInitializer, Platform {
    private static final Logger LOG = LoggerFactory.getLogger("OraNotifier");
    private MinecraftServer server;
    private OraCore core;
    private final Map<UUID, double[]> lastPose = new HashMap<>();
    private int ticks;

    @Override
    public void onInitializeServer() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            core = new OraCore(this);
            core.start();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            if (core != null) core.stop();
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
            if (core != null) core.playerJoin(handler.getPlayer().getUUID(), handler.getPlayer().getName().getString());
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> {
            lastPose.remove(handler.getPlayer().getUUID());
            if (core != null) core.playerQuit(handler.getPlayer().getUUID());
        });
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            if (core != null) core.playerActivity(sender.getUUID());
        });
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (core == null || ++ticks % 20 != 0) return;
            for (ServerPlayer p : s.getPlayerList().getPlayers()) {
                double[] pose = {p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot()};
                double[] before = lastPose.put(p.getUUID(), pose);
                if (before != null && !java.util.Arrays.equals(before, pose)) core.playerActivity(p.getUUID());
            }
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> dispatcher.register(Commands.literal("mcora")
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

    @Override public String name() { return "Fabric " + server.getServerVersion(); }
    @Override public Path dataDir() { return FabricLoader.getInstance().getConfigDir().resolve("oranotifier"); }
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
