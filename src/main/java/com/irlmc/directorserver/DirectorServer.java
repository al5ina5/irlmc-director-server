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
        /** Previous camera position, for swept collision between ticks. */
        Vec3 lastCam;
    }

    private final Random random = new Random();
    private final ShotScheduler scheduler = new ShotScheduler(random);
    private final Map<UUID, Session> sessions = new HashMap<>();
    private int roundRobin = -1;
    private boolean configLoaded = false;

    /** Per-player activity, for AFK detection. */
    private static final class Activity {
        long lastActiveMs;
        double x, y, z;
        float yaw, pitch;
        boolean init;
    }
    private final Map<UUID, Activity> activity = new HashMap<>();

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
        updateActivity(server);
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
        long shotInterval = Math.max(40L, Math.max(5, cfg.rotationIntervalSec) * 20L);
        long targetInterval = Math.max(5, cfg.targetRotateSec) * 20L;
        if (locked != null) {
            ServerPlayer lt = locked.resolve(server);
            boolean newTarget = s.current == null || !locked.uuid().equals(s.current.uuid());
            boolean poolRotates = cfg.pool.size() > 1 && s.ticksSinceSwitch >= shotInterval;
            if (lt != null && (newTarget || poolRotates)) {
                beginShot(s, locked, lt, cfg, newTarget);
                s.ticksSinceSwitch = 0;
                s.shotTick = 0;
                announce(cam, s);
            }
        } else {
            boolean activeAvailable = false;
            for (DirectorTarget c : candidates) {
                if (!isAfk(c.uuid())) {
                    activeAvailable = true;
                    break;
                }
            }
            boolean stale = s.current == null || !s.current.isValid(server)
                    || (activeAvailable && isAfk(s.current.uuid()));
            // Group mode: rotate the followed player on a slow timer (default 3 min),
            // separately from the shot hold. Switch with a hard cut.
            boolean targetDue = candidates.size() > 1 && s.ticksSinceSwitch >= targetInterval;
            boolean shotDue = cfg.pool.size() > 1 && s.ticksSinceSwitch >= shotInterval;
            if (stale || targetDue || shotDue) {
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
                boolean targetChanged = s.current == null || !pick.uuid().equals(s.current.uuid());
                ServerPlayer pt = pick.resolve(server);
                if (pt != null) beginShot(s, pick, pt, cfg, targetChanged);
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
        // The client renders the camera at the spectator's EYE = feet + eyeHeight.
        // All our shot/collision math treats s.pose as the eye position, so place
        // the player's feet at eye - eyeHeight; otherwise the rendered camera sits
        // ~1.6 blocks too high and the look angles aim over the target's head.
        double eyeHeight = cam.getEyeHeight();
        cam.teleportTo(targetLevel, s.pose.pos().x, s.pose.pos().y - eyeHeight, s.pose.pos().z,
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

    private void beginShot(Session s, DirectorTarget pick, ServerPlayer target, SConfig cfg, boolean cut) {
        // Always blend: a hard cut reads as a warp to viewers. For a far target
        // switch the blend becomes a fast glide, but it still interpolates.
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
        List<DirectorTarget> all = new ArrayList<>();
        List<DirectorTarget> active = new ArrayList<>();
        var players = server.getPlayerList().getPlayers();
        for (ServerPlayer p : players) {
            if (p.getUUID().equals(cam.getUUID())) continue;
            if (p.isSpectator() || p.isRemoved()) continue;
            if (players.size() > 2 && p.position().distanceTo(cam.position()) < cfg.minRotationDistance) {
                // NOTE: distance filter is only a tie-break; server sees everyone.
                continue;
            }
            DirectorTarget t = DirectorTarget.of(p);
            all.add(t);
            if (!isAfk(p.getUUID())) active.add(t);
        }
        // Never auto-target AFK players while at least one active player exists.
        return active.isEmpty() ? all : active;
    }

    /** Track movement/rotation per player so we can detect AFK. */
    private void updateActivity(MinecraftServer server) {
        long now = System.currentTimeMillis();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            Activity a = activity.computeIfAbsent(p.getUUID(), k -> new Activity());
            if (!a.init) {
                a.init = true;
                a.x = p.getX(); a.y = p.getY(); a.z = p.getZ();
                a.yaw = p.getYRot(); a.pitch = p.getXRot();
                a.lastActiveMs = now;
                continue;
            }
            double moved = Math.abs(p.getX() - a.x) + Math.abs(p.getY() - a.y) + Math.abs(p.getZ() - a.z);
            double turned = Math.abs(Mth.wrapDegrees(p.getYRot() - a.yaw)) + Math.abs(p.getXRot() - a.pitch);
            if (moved > 0.03 || turned > 1.5) {
                a.lastActiveMs = now;
            }
            a.x = p.getX(); a.y = p.getY(); a.z = p.getZ();
            a.yaw = p.getYRot(); a.pitch = p.getXRot();
        }
    }

    private boolean isAfk(UUID id) {
        Activity a = activity.get(id);
        if (a == null) return false;
        return System.currentTimeMillis() - a.lastActiveMs > SConfig.get().afkSeconds * 1000L;
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
        Vec3 anchor = new Vec3(tp.x, tp.y + cfg.camAimHeight, tp.z);
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
        Vec3[] offs = {
                Vec3.ZERO,
                right.scale(r), right.scale(-r), upv.scale(r), upv.scale(-r),
                right.scale(r).add(upv.scale(r)), right.scale(-r).add(upv.scale(r)),
                right.scale(r).add(upv.scale(-r)), right.scale(-r).add(upv.scale(-r)),
        };
        double hitFrac = 1.0;
        for (Vec3 off : offs) {
            var hit = target.level().clip(new ClipContext(anchor.add(off), desired.add(off),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, target));
            if (hit.getType() == HitResult.Type.BLOCK) {
                double f = anchor.distanceTo(hit.getLocation()) / idealLen;
                if (f < hitFrac) hitFrac = f;
            }
        }

        // Hard ceiling on how far the arm may extend: never past the obstruction.
        // (The old code clamped to armMin, which pushed the camera *through* close
        // walls — that was the "goes through walls" bug.)
        double maxScale = Mth.clamp(hitFrac - cfg.armMargin / idealLen, 0.0, 1.0);
        // Ease in BOTH directions (retract faster than extend). The old instant
        // retract + slow extend made the camera creep out then snap back, which
        // read as "freeze then teleport".
        double factor = maxScale < s.armScale ? cfg.armRetract : cfg.armExtend;
        s.armScale += (maxScale - s.armScale) * Mth.clamp(factor, 0.0, 1.0);
        s.armScale = Mth.clamp(s.armScale, 0.0, 1.0);
        // Hard floor: never closer than armMin to the target, so the camera can
        // never pass through / collide with the player model.
        double minScale = Mth.clamp(cfg.armMin / idealLen, 0.0, 1.0);
        s.armScale = Math.max(s.armScale, minScale);

        long now = System.currentTimeMillis();
        if (s.armScale < 0.85 && now - s.lastArmLog > 1000) {
            s.lastArmLog = now;
            LOG.info("SpringArm retract: scale={} (hitFrac={}) target={}",
                    String.format("%.2f", s.armScale), String.format("%.2f", hitFrac),
                    target.getName().getString());
        }

        Vec3 cam = anchor.add(dir.scale(idealLen * s.armScale));
        // Resolve overlaps, then apply vertical limits (ground AND ceiling), then
        // re-resolve. No swept guard: holding the previous position and then
        // snapping when clear reads as a "warp", so we rely on per-tick clearance
        // plus the vertical clamp instead.
        cam = resolveOverlap(target, anchor, cam, dir);
        cam = clampVertical(target, cam, cfg);
        cam = resolveOverlap(target, anchor, cam, dir);

        float[] look = Shots.lookAt(cam, anchor);
        return new Shots.Pose(cam, look[0], look[1]);
    }

    /** Keep the camera within the vertical gap at its position (ground .. ceiling). */
    private Vec3 clampVertical(ServerPlayer target, Vec3 cam, SConfig cfg) {
        var down = target.level().clip(new ClipContext(cam, cam.subtract(0, 8, 0),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, target));
        if (down.getType() == HitResult.Type.BLOCK) {
            double gy = down.getLocation().y + cfg.armMargin;
            if (cam.y < gy) cam = new Vec3(cam.x, gy, cam.z);
        }
        var up = target.level().clip(new ClipContext(cam, cam.add(0, cfg.armMargin + 1.0, 0),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, target));
        if (up.getType() == HitResult.Type.BLOCK) {
            double cy = up.getLocation().y - cfg.armMargin;
            if (cam.y > cy) cam = new Vec3(cam.x, cy, cam.z);
        }
        // Lowering for a ceiling can push below the floor; re-apply ground.
        down = target.level().clip(new ClipContext(cam, cam.subtract(0, 8, 0),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, target));
        if (down.getType() == HitResult.Type.BLOCK) {
            double gy = down.getLocation().y + cfg.armMargin;
            if (cam.y < gy) cam = new Vec3(cam.x, gy, cam.z);
        }
        return cam;
    }

    private Vec3 resolveOverlap(ServerPlayer target, Vec3 anchor, Vec3 cam, Vec3 dir) {
        for (int i = 0; i < 10 && insideSolid(target, cam); i++) {
            if (cam.distanceToSqr(anchor) < 0.16) {
                return anchor.add(0, 0.4, 0);
            }
            cam = cam.subtract(dir.scale(0.3));
        }
        return cam;
    }

    private boolean insideSolid(ServerPlayer target, Vec3 p) {
        var bp = net.minecraft.core.BlockPos.containing(p);
        var state = target.level().getBlockState(bp);
        if (state.isAir()) return false;
        return !state.getCollisionShape(target.level(), bp).isEmpty();
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
