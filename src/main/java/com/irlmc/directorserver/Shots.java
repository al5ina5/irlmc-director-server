package com.irlmc.directorserver;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Cinematic shot math, ported from the whole-minecraft-cameraman Paper plugin
 * (MIT) via our Fabric prototype. Pure Vec3/yaw/pitch math — no loader APIs.
 */
public final class Shots {
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

    public static Shot create(ShotType type) {
        return switch (type) {
            case ORBIT -> new Orbit();
            case FLYBY -> new Flyby(600);
            case CRANE -> new Crane(600);
            case DYNAMIC_BEHIND, DYNAMIC_FRONT, DYNAMIC_POV -> new Dynamic(type);
            case FRONT -> new Static(ShotType.FRONT);
            case BEHIND -> new Static(ShotType.BEHIND);
            case MOVE -> new Move();
            case FIX -> new Static(ShotType.FIX);
        };
    }

    /** Circles the target at fixed radius/height. */
    static final class Orbit implements Shot {
        private double angle = 0;

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

    /** Slow dolly from a behind-target start. */
    static final class Move implements Shot {
        @Override
        public Pose next(Player target, Pose cur, long tick, SConfig cfg) {
            Vec3 tp = target.position();
            float yaw = target.getYRot();
            Vec3 start = tp.subtract(forward(yaw).scale(cfg.followDistance)).add(0, cfg.followHeight, 0);
            Vec3 drift = right(yaw).scale(-1).add(forward(yaw).scale(-0.1)).normalize().scale(0.02 * tick);
            Vec3 cam = start.add(drift);
            float[] look = lookAt(cam, new Vec3(tp.x, tp.y + 1.5, tp.z));
            return new Pose(cam, look[0], look[1]);
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
