package com.irlmc.directorserver;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-side cinematic director. Moves camera entities/players with plain
 * spectator + teleport — vanilla clients, no client mod, no render hacks.
 * The server knows every player's position, so follow works map-wide
 * (no client tracking-range limit).
 */
public final class DirectorServer {
    private static final Logger LOG = LoggerFactory.getLogger("irlmc-director-server");
    private static final DirectorServer INSTANCE = new DirectorServer();

    public static DirectorServer get() {
        return INSTANCE;
    }

    /** Saved state so /director off (or login) restores exactly. */
    public record Snapshot(GameType gameType, ResourceKey<net.minecraft.world.level.Level> dim,
                           Vec3 pos, float yaw, float pitch) {}

    private static final class Session {
        Snapshot saved;
        DirectorTarget current;
        ShotType currentType;
        Shots.Shot shot;
        Shots.Pose pose;
        Shots.Pose blendFrom;
        int blendTick;
        long ticksSinceSwitch;
        long shotTick;
        long holdTicks;
        /** Current spring-arm retract scale (1 = fully extended). */
        double armScale = 1.0;
        long lastArmLog = 0L;
    }

    private final Random random = new Random();
    private final ShotScheduler scheduler = new ShotScheduler(random);
    private final Map<UUID, Session> sessions = new HashMap<>();
    private int roundRobin = -1;
    private boolean configLoaded = false;

    // ---- lifecycle ----

    public boolean isDirecting(UUID uuid) {
        synchronized (sessions) {
            return sessions.containsKey(uuid);
        }
    }

    public String status(MinecraftServer server, UUID uuid) {
        synchronized (sessions) {
            Session s = sessions.get(uuid);
            if (s == null) return "director OFF";
            ServerPlayer cam = server.getPlayerList().getPlayer(uuid);
            String who = cam == null ? "offline" : cam.getName().getString();
            return "director ON for " + who + " target="
                    + (s.current == null ? "none" : s.current.name()) + " shot=" + s.currentType;
        }
    }

    public void start(MinecraftServer server, ServerPlayer cam) {
        synchronized (sessions) {
            if (sessions.containsKey(cam.getUUID())) return;
            Session s = new Session();
            s.saved = new Snapshot(cam.gameMode.getGameModeForPlayer(),
                    cam.level().dimension(), cam.position(), cam.getYRot(), cam.getXRot());
            sessions.put(cam.getUUID(), s);
        }
        cam.setGameMode(GameType.SPECTATOR);
        cam.setInvulnerable(true);
        cam.setDeltaMovement(Vec3.ZERO);
        cam.fallDistance = 0f;
        saveSessions(server);
        cam.sendSystemMessage(Component.literal("Director ON — you are the camera (hands off WASD). /director off to stop."));
        LOG.info("Director started for {}", cam.getName().getString());
    }

    /**
     * Make the dedicated camera account the camera again, ignoring any stale
     * session left over from a crash or an abrupt client kill. A headless camera
     * must never be "restored" into survival (which is how it used to die).
     */
    public void startCamera(MinecraftServer server, ServerPlayer cam) {
        synchronized (sessions) {
            sessions.remove(cam.getUUID());
        }
        start(server, cam);
        cam.setInvulnerable(true);
        cam.setDeltaMovement(Vec3.ZERO);
    }

    public void stop(MinecraftServer server, UUID uuid, boolean restore) {
        Session s;
        synchronized (sessions) {
            s = sessions.remove(uuid);
        }
        if (s == null) return;
        saveSessions(server);
        if (restore) {
            ServerPlayer cam = server.getPlayerList().getPlayer(uuid);
            if (cam != null && s.saved != null) {
                ServerLevel level = server.getLevel(s.saved.dim());
                if (level == null) level = server.overworld();
                cam.teleportTo(level, s.saved.pos().x, s.saved.pos().y, s.saved.pos().z,
                        EnumSet.noneOf(Relative.class), s.saved.yaw(), s.saved.pitch(), true);
                cam.setGameMode(s.saved.gameType());
                cam.sendSystemMessage(Component.literal("Director OFF — restored."));
            }
        }
        ServerPlayer cam = server.getPlayerList().getPlayer(uuid);
        LOG.info("Director stopped for {}", cam == null ? uuid : cam.getName().getString());
    }

    public void nextNow(UUID uuid) {
        synchronized (sessions) {
            Session s = sessions.get(uuid);
            if (s != null) s.ticksSinceSwitch = Long.MAX_VALUE / 2;
        }
    }

    // ---- per-tick ----

    public void tick(MinecraftServer server) {
        if (!configLoaded) {
            configLoaded = true;
            SConfig.get().load();
        }
        SConfig cfg = SConfig.get();
        List<UUID> ids;
        synchronized (sessions) {
            ids = new ArrayList<>(sessions.keySet());
        }
        for (UUID id : ids) tickOne(server, id, cfg);
    }

    private void tickOne(MinecraftServer server, UUID id, SConfig cfg) {
        ServerPlayer cam = server.getPlayerList().getPlayer(id);
        if (cam == null) return; // offline: hold session, resume on login
        Session s;
        synchronized (sessions) {
            s = sessions.get(id);
        }
        if (s == null) return;
        s.ticksSinceSwitch++;
        s.shotTick++;

        List<DirectorTarget> candidates = collect(server, cam, cfg);
        if (candidates.isEmpty()) {
            if (s.current != null) {
                s.current = null;
                s.shot = null;
                s.pose = null;
            }
            return;
        }
        DirectorTarget locked = lockedTarget(server, cfg);
        long interval = Math.max(40L,
                s.holdTicks > 0 ? s.holdTicks : Math.max(5, cfg.rotationIntervalSec) * 20L);
        if (locked != null) {
            ServerPlayer lt = locked.resolve(server);
            boolean newTarget = s.current == null || !locked.uuid().equals(s.current.uuid());
            if (lt != null && (newTarget || s.ticksSinceSwitch >= interval)) {
                // Same player, but time to roll a new angle. Keep rotating even
                // when locked (locking pins the *player*, not the shot).
                beginShot(s, locked, lt, cfg);
                s.ticksSinceSwitch = 0;
                s.shotTick = 0;
                announce(cam, s);
            }
        } else {
            boolean stale = s.current == null || !s.current.isValid(server);
            if (stale || s.ticksSinceSwitch >= interval) {
                roundRobin = (roundRobin + 1) % candidates.size();
                DirectorTarget pick = candidates.get(Math.floorMod(roundRobin, candidates.size()));
                if (s.current != null && candidates.size() > 1) {
                    for (int i = 0; i < candidates.size(); i++) {
                        DirectorTarget c = candidates.get(Math.floorMod(roundRobin + i, candidates.size()));
                        if (!c.uuid().equals(s.current.uuid())) {
                            pick = c;
                            roundRobin += i;
                            break;
                        }
                    }
                }
                ServerPlayer pt = pick.resolve(server);
                if (pt != null) beginShot(s, pick, pt, cfg);
                s.ticksSinceSwitch = 0;
                s.shotTick = 0;
                announce(cam, s);
            }
        }

        ServerPlayer target = s.current == null ? null : s.current.resolve(server);
        if (target == null || s.shot == null) return;
        Shots.Pose dest = s.shot.next(target, s.pose, s.shotTick, cfg);
        if (cfg.smoothTicks > 0 && s.blendFrom != null && s.blendTick < cfg.smoothTicks) {
            s.blendTick++;
            double t = Shots.smoothstep((double) s.blendTick / cfg.smoothTicks);
            s.pose = new Shots.Pose(
                    s.blendFrom.pos().lerp(dest.pos(), t),
                    Shots.lerpAngle(s.blendFrom.yaw(), dest.yaw(), t),
                    (float) (s.blendFrom.pitch() + (dest.pitch() - s.blendFrom.pitch()) * t));
            if (s.blendTick >= cfg.smoothTicks) s.blendFrom = null;
        } else {
            s.pose = dest;
        }
        // Spring-arm collision: keep the camera clear of geometry and in line of
        // sight, retract fast / extend slow so it never pops.
        s.pose = springArm(target, s, cfg);

        ServerLevel targetLevel = target.level() instanceof ServerLevel sl ? sl : server.overworld();
        cam.setDeltaMovement(Vec3.ZERO);
        cam.teleportTo(targetLevel, s.pose.pos().x, s.pose.pos().y, s.pose.pos().z,
                EnumSet.noneOf(Relative.class), s.pose.yaw(), s.pose.pitch(), false);
        // A headless camera must never fall out of spectator or take damage:
        // a crash/restart can otherwise "restore" it into survival and kill it.
        if (cam.gameMode.getGameModeForPlayer() != GameType.SPECTATOR) {
            cam.setGameMode(GameType.SPECTATOR);
        }
        if (!cam.isInvulnerable()) {
            cam.setInvulnerable(true);
        }
        cam.fallDistance = 0f;
    }

    private void beginShot(Session s, DirectorTarget pick, ServerPlayer target, SConfig cfg) {
        s.blendFrom = s.pose;
        s.blendTick = 0;
        s.armScale = 1.0;
        s.current = pick;
        s.currentType = scheduler.pick(cfg, s.currentType);
        s.holdTicks = Math.max(5, cfg.rotationIntervalSec) * 20L;
        // Start an orbit from the camera's current bearing around the target so
        // it glides in instead of swinging to a fixed eastward start.
        double orbitStart = 0.0;
        if (s.pose != null && target != null) {
            Vec3 tp = target.position();
            orbitStart = Math.toDegrees(Math.atan2(
                    s.pose.pos().z - tp.z, s.pose.pos().x - tp.x));
        }
        s.shot = Shots.create(s.currentType, s.holdTicks, orbitStart);
    }

    private void announce(ServerPlayer cam, Session s) {
        cam.sendSystemMessage(Component.literal(
                "Following " + s.current.name() + " — " + s.currentType));
        LOG.info("Director ({}): following {} — {}", cam.getName().getString(),
                s.current.name(), s.currentType);
    }

    private List<DirectorTarget> collect(MinecraftServer server, ServerPlayer cam, SConfig cfg) {
        List<DirectorTarget> out = new ArrayList<>();
        var players = server.getPlayerList().getPlayers();
        for (ServerPlayer p : players) {
            if (p.getUUID().equals(cam.getUUID())) continue;
            if (p.isSpectator() || p.isRemoved()) continue;
            if (players.size() > 2 && p.position().distanceTo(cam.position()) < cfg.minRotationDistance) {
                // NOTE: distance filter is only a tie-break; server sees everyone.
                continue;
            }
            out.add(DirectorTarget.of(p));
        }
        return out;
    }

    private DirectorTarget lockedTarget(MinecraftServer server, SConfig cfg) {
        if (cfg.targetName == null || cfg.targetName.isEmpty()) return null;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.isSpectator() || p.isRemoved()) continue;
            if (p.getName().getString().equalsIgnoreCase(cfg.targetName)) {
                return DirectorTarget.of(p);
            }
        }
        return null;
    }

    /**
     * Spring-arm collision. Casts a small sphere (5 rays) from the target's
     * aim point to the ideal camera position, keeps the camera clear of any
     * obstruction by a margin, and hammers in fast / eases out slow so it never
     * pops. Because the center ray ends at the camera, the target stays in line
     * of sight. A downward ray keeps the camera above the ground.
     */
    private Shots.Pose springArm(ServerPlayer target, Session s, SConfig cfg) {
        if (s.pose == null) return null;
        Vec3 tp = target.position();
        Vec3 anchor = new Vec3(tp.x, tp.y + cfg.steadyLookHeight, tp.z);
        Vec3 desired = s.pose.pos();
        Vec3 delta = desired.subtract(anchor);
        double idealLen = delta.length();
        if (idealLen < 1.0e-4) return s.pose;
        Vec3 dir = delta.normalize();

        Vec3 right = dir.cross(new Vec3(0, 1, 0));
        if (right.lengthSqr() < 1.0e-6) right = new Vec3(1, 0, 0);
        right = right.normalize();
        Vec3 upv = right.cross(dir).normalize();

        double r = cfg.armRadius;
        Vec3[] offs = {Vec3.ZERO, right.scale(r), right.scale(-r), upv.scale(r), upv.scale(-r)};
        double hitFrac = 1.0;
        for (Vec3 off : offs) {
            var hit = target.level().clip(new ClipContext(anchor.add(off), desired.add(off),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, target));
            if (hit.getType() == HitResult.Type.BLOCK) {
                double f = anchor.distanceTo(hit.getLocation()) / idealLen;
                if (f < hitFrac) hitFrac = f;
            }
        }

        double minScale = Mth.clamp(cfg.armMin / idealLen, 0.0, 1.0);
        double maxScale = Mth.clamp(hitFrac - cfg.armMargin / idealLen, minScale, 1.0);
        if (maxScale < s.armScale) {
            s.armScale += (maxScale - s.armScale) * Mth.clamp(cfg.armRetract, 0.0, 1.0);
        } else {
            s.armScale += (maxScale - s.armScale) * Mth.clamp(cfg.armExtend, 0.0, 1.0);
        }
        s.armScale = Mth.clamp(s.armScale, minScale, 1.0);

        long now = System.currentTimeMillis();
        if (s.armScale < 0.85 && now - s.lastArmLog > 1000) {
            s.lastArmLog = now;
            LOG.info("SpringArm retract: scale={} (hitFrac={}) target={}",
                    String.format("%.2f", s.armScale), String.format("%.2f", hitFrac),
                    target.getName().getString());
        }

        Vec3 cam = anchor.add(dir.scale(idealLen * s.armScale));
        var down = target.level().clip(new ClipContext(cam, cam.subtract(0, 8, 0),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, target));
        if (down.getType() == HitResult.Type.BLOCK) {
            double gy = down.getLocation().y + cfg.armMargin;
            if (cam.y < gy) cam = new Vec3(cam.x, gy, cam.z);
        }
        float[] look = Shots.lookAt(cam, anchor);
        return new Shots.Pose(cam, look[0], look[1]);
    }

    // ---- persistence (survive server restarts) ----

    private Path sessionsPath() {
        return FMLPaths.CONFIGDIR.get().resolve("irlmc-director-server-sessions.properties");
    }

    public synchronized void saveSessions(MinecraftServer server) {
        Properties props = new Properties();
        synchronized (sessions) {
            int i = 0;
            for (Map.Entry<UUID, Session> e : sessions.entrySet()) {
                Session s = e.getValue();
                if (s.saved == null) continue;
                String p = i + ".";
                props.setProperty(p + "uuid", e.getKey().toString());
                props.setProperty(p + "game", s.saved.gameType().getName());
                props.setProperty(p + "dim", s.saved.dim().identifier().toString());
                props.setProperty(p + "x", Double.toString(s.saved.pos().x));
                props.setProperty(p + "y", Double.toString(s.saved.pos().y));
                props.setProperty(p + "z", Double.toString(s.saved.pos().z));
                props.setProperty(p + "yaw", Float.toString(s.saved.yaw()));
                props.setProperty(p + "pitch", Float.toString(s.saved.pitch()));
                i++;
            }
            props.setProperty("count", Integer.toString(i));
        }
        try {
            Files.createDirectories(sessionsPath().getParent());
            try (OutputStream out = Files.newOutputStream(sessionsPath())) {
                props.store(out, "irlmc-director-server sessions");
            }
        } catch (IOException e) {
            LOG.warn("Failed to save director sessions", e);
        }
    }

    /** Restore one stranded director (e.g. server restarted mid-session) on login. */
    public void restoreOne(MinecraftServer server, UUID uuid) {
        Path p = sessionsPath();
        if (!Files.exists(p)) return;
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(p)) {
            props.load(in);
        } catch (Exception e) {
            LOG.warn("Failed to load director sessions", e);
            return;
        }
        int count = Integer.parseInt(props.getProperty("count", "0"));
        Properties keep = new Properties();
        int j = 0;
        boolean found = false;
        for (int i = 0; i < count; i++) {
            String q = i + ".";
            if (!uuid.toString().equals(props.getProperty(q + "uuid"))) {
                String r = j + ".";
                for (String k : List.of("uuid", "game", "dim", "x", "y", "z", "yaw", "pitch")) {
                    keep.setProperty(r + k, props.getProperty(q + k, ""));
                }
                j++;
                continue;
            }
            found = true;
            try {
                ServerPlayer pl = server.getPlayerList().getPlayer(uuid);
                if (pl == null) {
                    // Still offline? keep the entry.
                    String r = j + ".";
                    for (String k : List.of("uuid", "game", "dim", "x", "y", "z", "yaw", "pitch")) {
                        keep.setProperty(r + k, props.getProperty(q + k, ""));
                    }
                    j++;
                    continue;
                }
                GameType game = GameType.byName(props.getProperty(q + "game", "survival"));
                ResourceKey<net.minecraft.world.level.Level> dim = ResourceKey.create(
                        Registries.DIMENSION,
                        Identifier.parse(props.getProperty(q + "dim", "minecraft:overworld")));
                ServerLevel level = server.getLevel(dim);
                if (level == null) level = server.overworld();
                pl.teleportTo(level,
                        Double.parseDouble(props.getProperty(q + "x", "0")),
                        Double.parseDouble(props.getProperty(q + "y", "100")),
                        Double.parseDouble(props.getProperty(q + "z", "0")),
                        EnumSet.noneOf(Relative.class), Float.parseFloat(props.getProperty(q + "yaw", "0")),
                        Float.parseFloat(props.getProperty(q + "pitch", "0")), true);
                pl.setGameMode(game);
                pl.sendSystemMessage(Component.literal("Director was ON across a restart — restored and stopped."));
                LOG.info("Director: restored stranded session for {}", pl.getName().getString());
            } catch (Exception e) {
                LOG.warn("Failed to restore director session for {}", uuid, e);
            }
        }
        if (!found && j == count) return; // nothing changed
        keep.setProperty("count", Integer.toString(j));
        try (OutputStream out = Files.newOutputStream(p)) {
            keep.store(out, "irlmc-director-server sessions");
        } catch (IOException e) {
            LOG.warn("Failed to update director sessions", e);
        }
    }
}
