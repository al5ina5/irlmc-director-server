package com.irlmc.directorserver;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/**
 * Weighted, no-repeat shot picker.
 *
 * The old director picked uniformly at random from the pool, so it could repeat
 * the same angle twice in a row and had no notion of rhythm. This picks by
 * weight, refuses to reuse any shot type from the last {@code noRepeatWindow}
 * picks (so consecutive cuts always change), and never repeats the immediately
 * previous type unless there is genuinely nothing else enabled.
 */
public final class ShotScheduler {
    private final Random random;
    private final Deque<ShotType> recent = new ArrayDeque<>();

    public ShotScheduler(Random random) {
        this.random = random;
    }

    /** Choose the next shot. {@code avoid} is the current type (may be null). */
    public ShotType pick(SConfig cfg, ShotType avoid) {
        List<ShotType> pool = cfg.pool;
        if (pool == null || pool.isEmpty()) return ShotType.ORBIT;

        int window = Math.max(0, cfg.noRepeatWindow);
        ShotType chosen = choose(pool, cfg, type -> recent.contains(type));
        if (chosen == null) {
            // No-repeat exhausted every option: relax to "not the current shot".
            chosen = choose(pool, cfg, type -> avoid != null && type == avoid);
        }
        if (chosen == null) {
            chosen = choose(pool, cfg, type -> false);
        }
        if (chosen == null) return ShotType.ORBIT;

        recent.addLast(chosen);
        while (recent.size() > Math.max(1, window)) recent.removeFirst();
        return chosen;
    }

    private interface Excluder {
        boolean excluded(ShotType type);
    }

    private ShotType choose(List<ShotType> pool, SConfig cfg, Excluder excluder) {
        List<ShotType> candidates = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        double total = 0.0;
        for (ShotType type : pool) {
            if (excluder.excluded(type)) continue;
            double w = cfg.weightOf(type);
            if (w <= 0.0) continue; // weight 0 = disabled
            candidates.add(type);
            weights.add(w);
            total += w;
        }
        if (candidates.isEmpty()) return null;
        double roll = random.nextDouble() * total;
        for (int i = 0; i < candidates.size(); i++) {
            roll -= weights.get(i);
            if (roll <= 0.0) return candidates.get(i);
        }
        return candidates.get(candidates.size() - 1);
    }
}
