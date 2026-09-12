package com.irlmc.directorserver;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * All game-bus wiring. Works with vanilla clients (spectator + teleport only).
 */
@EventBusSubscriber(modid = IrlmcDirectorServer.MOD_ID)
public final class DirectorServerEvents {

    private static boolean rigStarted = false;

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        // Single-mod orchestration: on the first tick, bring the headless camera
        // rig up (ServerStartedEvent isn't reliable across loaders/versions here).
        // A shutdown hook takes it down when the server exits.
        if (!rigStarted) {
            rigStarted = true;
            SConfig cfg = SConfig.get();
            if (cfg.rigEnabled) {
                RigOrchestrator.get().start(cfg.rigStart());
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    if (SConfig.get().rigEnabled) {
                        RigOrchestrator.get().stop(SConfig.get().rigStop());
                    }
                }, "irlmc-rig-stop"));
            }
        }
        DirectorServer.get().tick(event.getServer());
    }

    /** And take it down with the server (no camera client left running). */
    @SubscribeEvent
    public static void onServerStopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        SConfig cfg = SConfig.get();
        if (cfg.rigEnabled) RigOrchestrator.get().stop(cfg.rigStop());
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        // Hold the session; it resumes (or restores on next login).
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer pl)) return;
        var server = ServerLifecycleHooks.getCurrentServer();
        SConfig cfg = SConfig.get();
        boolean isCamera = cfg.autoCamera && !cfg.cameraAccount.isEmpty()
                && pl.getName().getString().equalsIgnoreCase(cfg.cameraAccount);
        if (isCamera) {
            // A headless camera account is never "restored" into survival — that
            // is how it used to respawn and die. Just make it the camera again.
            DirectorServer.get().startCamera(server, pl);
            return;
        }
        if (DirectorServer.get().isDirecting(pl.getUUID())) {
            // Logged out mid-direct: restore where they were.
            DirectorServer.get().stop(server, pl.getUUID(), true);
            return;
        }
        DirectorServer.get().restoreOne(server, pl.getUUID());
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        var d = event.getDispatcher();
        d.register(Commands.literal("director")
                .requires(DirectorServerEvents::permitted)
                .executes(DirectorServerEvents::status)
                .then(Commands.literal("on").executes(DirectorServerEvents::on))
                .then(Commands.literal("off").executes(DirectorServerEvents::off))
                .then(Commands.literal("next").executes(DirectorServerEvents::next))
                .then(Commands.literal("status").executes(DirectorServerEvents::status))
                .then(Commands.literal("interval")
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(5, 300))
                                .executes(DirectorServerEvents::interval)))
                .then(Commands.literal("rotate")
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(5, 3600))
                                .executes(DirectorServerEvents::rotate)))
                .then(Commands.literal("afk")
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(10, 3600))
                                .executes(DirectorServerEvents::afk)))
                .then(Commands.literal("rig")
                        .executes(DirectorServerEvents::rigStatus)
                        .then(Commands.literal("status").executes(DirectorServerEvents::rigStatus))
                        .then(Commands.literal("start").executes(ctx -> {
                            RigOrchestrator.get().start(SConfig.get().rigStart());
                            ctx.getSource().sendSuccess(() -> Component.literal("camera rig starting"), false);
                            return 1;
                        }))
                        .then(Commands.literal("stop").executes(ctx -> {
                            RigOrchestrator.get().stop(SConfig.get().rigStop());
                            ctx.getSource().sendSuccess(() -> Component.literal("camera rig stopping"), false);
                            return 1;
                        })))
                .then(Commands.literal("target")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                        ctx.getSource().getOnlinePlayerNames(), b))
                                .executes(DirectorServerEvents::target))
                        .then(Commands.literal("clear").executes(DirectorServerEvents::targetClear)))
                .then(Commands.literal("cam")
                        .executes(DirectorServerEvents::camStatus)
                        .then(Commands.literal("status").executes(DirectorServerEvents::camStatus))
                        .then(Commands.literal("mode")
                                .then(Commands.argument("mode", StringArgumentType.word())
                                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                                new String[]{"steady", "velocity"}, b))
                                        .executes(ctx -> {
                                            SConfig c = SConfig.get();
                                            c.followMode = StringArgumentType.getString(ctx, "mode");
                                            c.save();
                                            ctx.getSource().sendSuccess(() -> Component.literal(
                                                    "camera mode = " + c.followMode), false);
                                            return 1;
                                        })))
                        .then(Commands.literal("distance")
                                .then(Commands.argument("blocks", DoubleArgumentType.doubleArg(1.0, 20.0))
                                        .executes(ctx -> camSet(ctx, "distance",
                                                DoubleArgumentType.getDouble(ctx, "blocks")))))
                        .then(Commands.literal("height")
                                .then(Commands.argument("blocks", DoubleArgumentType.doubleArg(-3.0, 25.0))
                                        .executes(ctx -> camSet(ctx, "height",
                                                DoubleArgumentType.getDouble(ctx, "blocks")))))
                        .then(Commands.literal("angle")
                                .then(Commands.argument("degrees", DoubleArgumentType.doubleArg(0.0, 359.0))
                                        .executes(ctx -> camSet(ctx, "angle",
                                                DoubleArgumentType.getDouble(ctx, "degrees")))))
                        .then(Commands.literal("aim")
                                .then(Commands.argument("blocks", DoubleArgumentType.doubleArg(0.5, 3.0))
                                        .executes(ctx -> camSet(ctx, "aim",
                                                DoubleArgumentType.getDouble(ctx, "blocks")))))
                        .then(Commands.literal("lowheight")
                                .then(Commands.argument("blocks", DoubleArgumentType.doubleArg(0.5, 5.0))
                                        .executes(ctx -> camSet(ctx, "lowheight",
                                                DoubleArgumentType.getDouble(ctx, "blocks")))))
                        .then(Commands.literal("lowdist")
                                .then(Commands.argument("blocks", DoubleArgumentType.doubleArg(1.0, 10.0))
                                        .executes(ctx -> camSet(ctx, "lowdist",
                                                DoubleArgumentType.getDouble(ctx, "blocks")))))
                        .then(Commands.literal("min")
                                .then(Commands.argument("blocks", DoubleArgumentType.doubleArg(0.5, 6.0))
                                        .executes(ctx -> camSet(ctx, "min",
                                                DoubleArgumentType.getDouble(ctx, "blocks")))))
                        .then(Commands.literal("align")
                                .then(Commands.argument("factor", DoubleArgumentType.doubleArg(0.005, 1.0))
                                        .executes(ctx -> camSet(ctx, "align",
                                                DoubleArgumentType.getDouble(ctx, "factor")))))));
    }

    private static boolean permitted(CommandSourceStack src) {
        int level = Math.max(0, Math.min(4, SConfig.get().permissionLevel));
        PermissionLevel[] levels = PermissionLevel.values();
        return src.permissions().hasPermission(
                new Permission.HasCommandLevel(levels[Math.min(level, levels.length - 1)]));
    }

    private static int needPlayer(CommandSourceStack src) {
        if (!(src.getEntity() instanceof ServerPlayer)) {
            src.sendFailure(Component.literal("Only a player can use /director."));
            return -1;
        }
        return 1;
    }

    private static int on(CommandContext<CommandSourceStack> ctx) {
        if (needPlayer(ctx.getSource()) < 0) return 0;
        ServerPlayer cam = (ServerPlayer) ctx.getSource().getEntity();
        DirectorServer.get().start(ctx.getSource().getServer(), cam);
        return 1;
    }

    private static int off(CommandContext<CommandSourceStack> ctx) {
        if (needPlayer(ctx.getSource()) < 0) return 0;
        ServerPlayer cam = (ServerPlayer) ctx.getSource().getEntity();
        DirectorServer.get().stop(ctx.getSource().getServer(), cam.getUUID(), true);
        return 1;
    }

    private static int next(CommandContext<CommandSourceStack> ctx) {
        if (needPlayer(ctx.getSource()) < 0) return 0;
        ServerPlayer cam = (ServerPlayer) ctx.getSource().getEntity();
        DirectorServer.get().nextNow(cam.getUUID());
        ctx.getSource().sendSuccess(() -> Component.literal("Switching target…"), false);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        if (src.getEntity() instanceof ServerPlayer cam) {
            src.sendSuccess(() -> Component.literal(
                    DirectorServer.get().status(src.getServer(), cam.getUUID())), false);
        } else {
            // Console: list all active directors.
            src.sendSuccess(() -> Component.literal("Use in-game. Active directors are listed in console log."), false);
        }
        return 1;
    }

    private static int interval(CommandContext<CommandSourceStack> ctx) {
        int s = IntegerArgumentType.getInteger(ctx, "seconds");
        SConfig.get().rotationIntervalSec = s;
        SConfig.get().save();
        ctx.getSource().sendSuccess(() -> Component.literal("Director interval = " + s + "s"), false);
        return 1;
    }

    private static int rotate(CommandContext<CommandSourceStack> ctx) {
        int s = IntegerArgumentType.getInteger(ctx, "seconds");
        SConfig.get().targetRotateSec = s;
        SConfig.get().save();
        ctx.getSource().sendSuccess(() -> Component.literal("Target rotates every " + s + "s"), false);
        return 1;
    }

    private static int afk(CommandContext<CommandSourceStack> ctx) {
        int s = IntegerArgumentType.getInteger(ctx, "seconds");
        SConfig.get().afkSeconds = s;
        SConfig.get().save();
        ctx.getSource().sendSuccess(() -> Component.literal("AFK timeout = " + s + "s (auto-rotation skips AFK players)"), false);
        return 1;
    }

    private static int rigStatus(CommandContext<CommandSourceStack> ctx) {
        SConfig c = SConfig.get();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "camera rig: enabled=" + c.rigEnabled
                + " running=" + RigOrchestrator.get().isRunning()
                + " start=" + c.rigStart()), false);
        return 1;
    }

    private static int target(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "player");
        var server = ctx.getSource().getServer();
        boolean online = server.getPlayerList().getPlayers().stream()
                .anyMatch(p -> p.getName().getString().equalsIgnoreCase(name));
        if (!online) {
            ctx.getSource().sendFailure(Component.literal("Player '" + name + "' is not online."));
            return 0;
        }
        SConfig.get().targetName = name;
        SConfig.get().save();
        ctx.getSource().sendSuccess(() -> Component.literal("Director locked to " + name), false);
        return 1;
    }

    private static int targetClear(CommandContext<CommandSourceStack> ctx) {
        SConfig.get().targetName = "";
        SConfig.get().save();
        ctx.getSource().sendSuccess(() -> Component.literal("Director back to auto-rotate."), false);
        return 1;
    }

    private static int camStatus(CommandContext<CommandSourceStack> ctx) {
        SConfig c = SConfig.get();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "camera: mode=" + c.followMode
                + " dist=" + c.steadyDistance
                + " height=" + c.steadyHeight
                + " angle=" + c.steadyAngleDeg
                + " align=" + c.followAlign
                + " armMin=" + c.armMin
                + " margin=" + c.armMargin), false);
        return 1;
    }

    private static int camSet(CommandContext<CommandSourceStack> ctx, String field, double value) {
        SConfig c = SConfig.get();
        switch (field) {
            case "distance" -> c.steadyDistance = value;
            case "height" -> c.steadyHeight = value;
            case "angle" -> c.steadyAngleDeg = value;
            case "align" -> c.followAlign = value;
            case "aim" -> c.camAimHeight = value;
            case "lowheight" -> c.camLowHeight = value;
            case "lowdist" -> c.camLowDistance = value;
            case "min" -> c.armMin = value;
            default -> { }
        }
        c.save();
        ctx.getSource().sendSuccess(() -> Component.literal("camera " + field + " = " + value), false);
        return 1;
    }
}
