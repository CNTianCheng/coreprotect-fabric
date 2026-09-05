package net.coreprotect.fabric.util;

import net.minecraft.util.math.BlockPos;

/**
 * Thread-local marker used by the natural-event mixins. A cause is set right
 * before vanilla code modifies the world (explosion, fire spread, fluid flow,
 * piston, enderman, leaf decay) and cleared right after, so the {@code World}
 * hooks can attribute the block change to the correct "user" (e.g. {@code #fire}).
 *
 * <p>Set/clear are reference-counted per cause type so recursive callers
 * (fire spread calls itself) do not clear an outer cause early.</p>
 *
 * <p>Note: this class intentionally lives OUTSIDE the mixin package so the
 * loader does not try to transform it as a mixin.</p>
 */
public final class NaturalBreakCause {
    private static final ThreadLocal<Cause> CURRENT = new ThreadLocal<>();

    private NaturalBreakCause() {
    }

    public static final class Cause {
        public final String type;
        public int depth = 1;
        /** Position just logged through a break/remove hook; guards nested setBlockState calls. */
        public BlockPos lastBreakPos;
        /** Position just logged through a setBlockState hook; guards delegated overloads. */
        public BlockPos lastSetPos;

        public Cause(String type) {
            this.type = type;
        }
    }

    public static void set(String type) {
        Cause current = CURRENT.get();
        if (current != null && current.type.equals(type)) {
            current.depth++;
            return;
        }
        CURRENT.set(new Cause(type));
    }

    public static Cause get() {
        return CURRENT.get();
    }

    public static void clear(String type) {
        Cause current = CURRENT.get();
        if (current == null || !current.type.equals(type)) return;
        if (--current.depth <= 0) {
            CURRENT.remove();
        }
    }
}
