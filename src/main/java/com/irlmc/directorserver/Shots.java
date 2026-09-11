package com.irlmc.directorserver;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Cinematic shot math, ported from the whole-minecraft-cameraman Paper plugin
 * (MIT) via our Fabric prototype. Pure Vec3/yaw/pitch math — no loader APIs.
 */
public final class Shots {
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("irlmc-director-server");

    private Shots() {}

    public record Pose(Vec3 pos, float yaw, float pitch) {}

    public interface Shot {
        Pose next(Player target, Pose current, long tick, SConfig cfg);
    }

    /** yaw=0 faces +Z (south); pitch -90=up, +90=down. */
    public static float[] lookAt(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Mth.wrapDegrees(Math.toDegrees(Math.atan2(-dx, dz)));
        float pitch = horiz < 1e-6 ? (dy > 0 ? -90f : 90f)
                : (float) -Math.toDegrees(Math.atan2(dy, horiz));
        return new float[]{yaw, pitch};
    }

    public static float lerpAngle(float a, float b, double t) {
        float diff = (float) (b - a);
        while (diff < -180f) diff += 360f;
        while (diff >= 180f) diff -= 360f;
        return (float) (a + diff * t);
    }

    public static double smoothstep(double t) {
        t = Mth.clamp(t, 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    /** Horizontal basis from a player's look yaw. */
    static Vec3 forward(float yawDeg) {
        double r = Math.toRadians(yawDeg);
        return new Vec3(-Math.sin(r), 0, Math.cos(r));
    }

    static Vec3 right(float yawDeg) {
        double r = Math.toRadians(yawDeg);
        return new Vec3(-Math.cos(r), 0, -Math.sin(r));
    }

    /**
     * Build a shot.
     *
     * @param durationTicks how long the shot will actually be held, so ping-pong
     *                      shots (flyby/crane) complete exactly one arc instead
     *                      of always showing the first third of a fixed 30s loop.
     * @param orbitStartDeg orbit angle to begin at (usually the current camera
     *                      bearing) so the camera does not swing to a fixed
     *                      eastward start on every orbit.
     */
    public static Shot create(ShotType type, long durationTicks, double orbitStartDeg) {
        long d = Math.max(40, durationTicks);
        return switch (type) {
            case FOLLOW -> new Follow();
            case STEADY -> new Steady();
            case ORBIT -> new Orbit(orbitStartDeg);
            case FLYBY -> new Flyby(d);
            case CRANE -> new Crane(d);
            case DYNAMIC_BEHIND, DYNAMIC_FRONT, DYNAMIC_POV -> new Dynamic(type);
            case FRONT -> new Static(ShotType.FRONT);
            case BEHIND -> new Static(ShotType.BEHIND);
            case MOVE -> new Move();
            case FIX -> new Static(ShotType.FIX);
        };
    }

    /** Circles the target at fixed radius/height. */
    static final class Orbit implements Shot {
        private double angle;

        Orbit(double startDeg) {
            this.angle = startDeg;
        }

        @Override
        public Pose next(Player target, Pose cur, long tick, SConfig cfg) {
            angle = (angle + cfg.orbitSpeedDegPerTick) % 360;
            double r = Math.toRadians(angle);
            Vec3 tp = target.position();
            Vec3 cam = new Vec3(tp.x + Math.cos(r) * cfg.orbitRadius,
                    tp.y + cfg.followHeight + 1.5, tp.z + Math.sin(r) * cfg.orbitRadius);
            float[] look = lookAt(cam, new Vec3(tp.x, tp.y + 1.5, tp.z));
            return new Pose(cam, look[0], look[1]);
        }
    }

    /** Drone-style ping-pong pass in target space, always looking at target. */
    static final class Flyby implements Shot {
        private final long duration;

        Flyby(long duration) {
            this.duration = Math.max(20, duration);
        }

        @Override
        public Pose next(Player target, Pose cur, long tick, SConfig cfg) {
            Vec3 tp = target.position();
            float yaw = target.getYRot();
            Vec3 start = tp.add(right(yaw).scale(3)).add(0, 1, 0).add(forward(yaw).scale(3));
            Vec3 end = tp.add(right(yaw).scale(-3)).add(0, 5, 0).add(forward(yaw).scale(-3));
            long phase = tick % (2 * duration);
            double t = phase < duration ? (double) phase / duration
                    : (double) (2 * duration - phase) / duration;
            Vec3 cam = start.lerp(end, smoothstep(t));
            float[] look = lookAt(cam, new Vec3(tp.x, tp.y + 1.5, tp.z));
            return new Pose(cam, look[0], look[1]);
        }
    }

    /** Vertical crane sweep behind the target. */
    static final class Crane implements Shot {
        private final long duration;

        Crane(long duration) {
            this.duration = Math.max(20, duration);
        }

        @Override
        public Pose next(Player target, Pose cur, long tick, SConfig cfg) {
            Vec3 tp = target.position();
            long phase = tick % (2 * duration);
            double t = phase < duration ? (double) phase / duration
                    : (double) (2 * duration - phase) / duration;
            double h = 1.0 + 4.0 * smoothstep(t);
            Vec3 cam = tp.subtract(forward(target.getYRot()).scale(cfg.followDistance)).add(0, h, 0);
            float[] look = lookAt(cam, new Vec3(tp.x, tp.y + 1.0, tp.z));
            return new Pose(cam, look[0], look[1]);
        }
    }

    /** Spring-arm follow with smoothing (behind / front / POV). */
    static final class Dynamic implements Shot {
        private final ShotType type;

        Dynamic(ShotType type) {
            this.type = type;
        }

        @Override
        public Pose next(Player target, Pose cur, long tick, SConfig cfg) {
            Vec3 tp = target.position();
            Vec3 fwd = forward(target.getYRot());
            double s = cfg.dynamicSmoothness;
            Vec3 ideal;
            float[] look;
            if (type == ShotType.DYNAMIC_FRONT) {
                ideal = tp.add(fwd.scale(cfg.followDistance)).add(0, cfg.followHeight, 0);
                look = lookAt(ideal, new Vec3(tp.x, tp.y + 1.5, tp.z));
            } else if (type == ShotType.DYNAMIC_POV) {
                ideal = target.getEyePosition().add(0, 0.5, 0);
                look = new float[]{target.getYRot(), target.getXRot()};
            } else {
                ideal = tp.subtract(fwd.scale(cfg.followDistance)).add(0, cfg.followHeight, 0);
                look = lookAt(ideal, new Vec3(tp.x, tp.y + 1.5, tp.z));
            }
            if (tick == 0 || cur == null) return new Pose(ideal, look[0], look[1]);
            Vec3 blended = cur.pos().lerp(ideal, s);
            return new Pose(blended, lerpAngle(cur.yaw(), look[0], s),
                    (float) (cur.pitch() + (look[1] - cur.pitch()) * s));
        }
    }

    /** Slow, bounded side-arc behind the target (no unbounded drift). */
    static final class Move implements Shot {
        private static final double SPAN_DEG = 55.0;
        private static final double SWEEP_TICKS = 240.0; // ~12s ease across the arc

        @Override
        public Pose next(Player target, Pose cur, long tick, SConfig cfg) {
            Vec3 tp = target.position();
            float yaw = target.getYRot();
            double t = smoothstep(Math.min(tick, (long) SWEEP_TICKS) / SWEEP_TICKS);
            double angle = yaw + SPAN_DEG * (t * 2.0 - 1.0);
            double radius = cfg.followDistance + 1.0;
            Vec3 cam = tp.subtract(forward((float) angle).scale(radius))
                    .add(0, cfg.followHeight + 0.5, 0);
            float[] look = lookAt(cam, new Vec3(tp.x, tp.y + 1.5, tp.z));
            return new Pose(cam, look[0], look[1]);
        }
    }

    /**
     * Follow camera with a heavily-damped heading.
     *
     * The camera sits at a fixed distance/height, in a horizontal direction
     * {@code camDir} that:
     *   - "steady" mode: stays locked to a fixed compass angle, or
     *   - "velocity" mode: slowly settles behind the direction of travel.
     *
     * It never looks at or tracks the target's head/view, so turning the view
     * does not move the camera. The slow {@code followAlign} makes it tolerant of
     * quick direction changes while still leading the target around corners.
     *
     * Collision (spring arm) is applied by the caller.
     */
    static final class Follow implements Shot {
        private double dirX = 0.0;
        private double dirZ = 1.0;
        private boolean init = false;
        private Vec3 lastPos;
        private double weaveOffsetDeg = 0.0;
        private long lastModeLog = 0L;

        @Override
        public Pose next(Player target, Pose cur, long tick, SConfig cfg) {
            Vec3 tp = target.position();

            if (!init) {
                double r = Math.toRadians(cfg.steadyAngleDeg);
                dirX = -Math.sin(r);
                dirZ = Math.cos(r);
                init = true;
            }

            boolean velocity = "velocity".equalsIgnoreCase(cfg.followMode);
            if (velocity && lastPos != null) {
                double vx = tp.x - lastPos.x;
                double vz = tp.z - lastPos.z;
                double sp = Math.hypot(vx, vz);
                if (sp > cfg.followDeadzone) {
                    // desired camera direction = opposite the movement direction
                    double dx = -vx / sp;
                    double dz = -vz / sp;
                    double k = Mth.clamp(cfg.followAlign, 0.0, 1.0);
                    double nx = dirX + (dx - dirX) * k;
                    double nz = dirZ + (dz - dirZ) * k;
                    double n = Math.hypot(nx, nz);
                    if (n > 1.0e-6) {
                        dirX = nx / n;
                        dirZ = nz / n;
                    }
                }
            }
            lastPos = tp;

            Vec3 aim = new Vec3(tp.x, tp.y + cfg.camAimHeight, tp.z);

            // How much clear vertical room do we have above the aim point? This is
            // the single most important signal: not enough headroom -> the camera
            // must hug the ground, no matter how open it looks horizontally. The
            // ray reaches well past the max height so open sky reads as "lots".
            double headReach = Math.max(cfg.steadyHeight + 4.0, 12.0);
            double headClear = clearFraction(target, aim, aim.add(0, headReach, 0));
            double headroom = headClear * headReach;
            double ceilingY = aim.y + headroom;

            // Only rise to the high/wide view when there is a LOT of headroom
            // (genuinely open sky). Anything with a low-ish ceiling — rooms, woods,
            // caves — clamps hard to the low person-height shot.
            double openness = Mth.clamp((headroom - 6.0) / 4.0, 0.0, 1.0);
            openness = openness * openness * (3.0 - 2.0 * openness); // smoothstep

            double desiredHeight = Mth.lerp(openness, cfg.camLowHeight, cfg.steadyHeight);
            double maxHeight = (ceilingY - cfg.armMargin) - tp.y;
            double height = Math.max(0.5, Math.min(desiredHeight, maxHeight));
            double dist = Mth.lerp(openness, cfg.camLowDistance, cfg.steadyDistance);

            // Weave: try a wide fan of headings and steer toward the clearest gap,
            // biased to the current heading so it doesn't oscillate. This is what
            // lets a ground-level camera slip between trunks instead of jamming
            // into one and collapsing against the player.
            double applied = weaveOffsetDeg;
            if (cfg.camWeave) {
                double[] offs = {0, 25, -25, 50, -50, 75, -75, 100, -100, 125, -125, 180};
                double bestOff = weaveOffsetDeg;
                double bestScore = -Double.MAX_VALUE;
                for (double off : offs) {
                    double f = clearFraction(target, aim, camAt(tp, dirX, dirZ, off, dist, height));
                    double score = f - Math.abs(off) / 180.0 * cfg.camWeavePenalty;
                    // Strong bias to the current heading so it doesn't flip-flop
                    // between equal gaps (which swings the camera sideways).
                    score += (1.0 - Math.min(1.0, Math.abs(off - weaveOffsetDeg) / 90.0)) * 0.25;
                    if (score > bestScore) {
                        bestScore = score;
                        bestOff = off;
                    }
                }
                // Ease toward the chosen heading slowly (no lateral snaps).
                applied = weaveOffsetDeg + Mth.wrapDegrees((float) (bestOff - weaveOffsetDeg)) * 0.08;
            }
            weaveOffsetDeg = applied;

            Vec3 cam = camAt(tp, dirX, dirZ, applied, dist, height);
            if (openness < 0.9 && System.currentTimeMillis() - lastModeLog > 1000) {
                lastModeLog = System.currentTimeMillis();
                LOG.info(String.format(
                        "Follow: headroom=%.1f open=%.2f height=%.2f (desired=%.2f cap=%.2f) dist=%.1f weave=%.0f",
                        headroom, openness, height, desiredHeight, maxHeight, dist, applied));
            }
            float[] look = lookAt(cam, aim);
            return new Pose(cam, look[0], look[1]);
        }

        private static Vec3 camAt(Vec3 tp, double dx, double dz, double offDeg,
                                  double dist, double height) {
            if (offDeg == 0.0) {
                return new Vec3(tp.x + dx * dist, tp.y + height, tp.z + dz * dist);
            }
            double a = Math.toRadians(offDeg);
            double cx = dx * Math.cos(a) - dz * Math.sin(a);
            double cz = dx * Math.sin(a) + dz * Math.cos(a);
            return new Vec3(tp.x + cx * dist, tp.y + height, tp.z + cz * dist);
        }

        private static double clearFraction(Player target, Vec3 from, Vec3 to) {
            double len = from.distanceTo(to);
            if (len < 1.0e-4) return 1.0;
            var hit = target.level().clip(new ClipContext(from, to,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, target));
            if (hit.getType() == HitResult.Type.BLOCK) {
                return Mth.clamp(from.distanceTo(hit.getLocation()) / len, 0.0, 1.0);
            }
            return 1.0;
        }
    }

    /**
     * Fixed world-locked follow camera (no heading change at all).
     */
    static final class Steady implements Shot {
        @Override
        public Pose next(Player target, Pose cur, long tick, SConfig cfg) {
            double r = Math.toRadians(cfg.steadyAngleDeg);
            Vec3 tp = target.position();
            Vec3 ideal = new Vec3(
                    tp.x - Math.sin(r) * cfg.steadyDistance,
                    tp.y + cfg.steadyHeight,
                    tp.z + Math.cos(r) * cfg.steadyDistance);
            Vec3 aim = new Vec3(tp.x, tp.y + cfg.steadyLookHeight, tp.z);
            float[] look = lookAt(ideal, aim);
            if (cur == null) return new Pose(ideal, look[0], look[1]);

            double f = Mth.clamp(cfg.steadyFollow, 0.001, 1.0);
            Vec3 cam = cur.pos().lerp(ideal, f);
            float[] look2 = lookAt(cam, aim);
            float yaw = cur.yaw() + Mth.wrapDegrees(look2[0] - cur.yaw()) * (float) f;
            float pitch = cur.pitch() + (look2[1] - cur.pitch()) * (float) f;
            return new Pose(cam, yaw, pitch);
        }
    }

    /** Static tripod (front / behind / fixed). */
    static final class Static implements Shot {
        private final ShotType type;

        Static(ShotType type) {
            this.type = type;
        }

        @Override
        public Pose next(Player target, Pose cur, long tick, SConfig cfg) {
            Vec3 tp = target.position();
            Vec3 fwd = forward(target.getYRot());
            Vec3 cam = switch (type) {
                case FRONT -> tp.add(fwd.scale(cfg.followDistance)).add(0, cfg.followHeight, 0);
                case BEHIND -> tp.subtract(fwd.scale(cfg.followDistance)).add(0, cfg.followHeight, 0);
                default -> tp.add(0, cfg.followHeight, 0);
            };
            float[] look = lookAt(cam, new Vec3(tp.x, tp.y + 1.5, tp.z));
            return new Pose(cam, look[0], look[1]);
        }
    }
}
