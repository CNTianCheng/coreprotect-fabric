package net.coreprotect.fabric.util;

import net.coreprotect.fabric.CoreProtectFabric;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;

/**
 * Thread-local marker used by the natural-event mixins. A cause is set right
 * before vanilla code modifies the world (explosion, fire spread, fluid flow,
 * piston, enderman, leaf decay) and cleared right after, so the {@code Level}
 * hooks can attribute the block change to the correct "user" (e.g. {@code #fire}).
 *
 * <p>Nesting is a stack: a different cause raised while another one is active
 * (e.g. fire burning a block that then explodes) is restored by {@code clear()}
 * instead of leaving the outer marker behind, which used to mis-attribute every
 * later block change on that server thread. Same-type recursion is counted by
 * {@code depth}.</p>
 *
 * <p>A window whose end is never reached (an exception inside the vanilla body skips the
 * closing TAIL injector) is dropped by {@link #pruneStale()} once it is older than
 * {@link #MAX_WINDOW_NANOS}: otherwise a leaked cause would keep mis-attributing every
 * later block change on that server thread.</p>
 *
 * <p>Note: this class intentionally lives OUTSIDE the mixin package so the
 * loader does not try to transform it as a mixin.</p>
 */
public final class NaturalBreakCause {
    private static final ThreadLocal<Cause> CURRENT = new ThreadLocal<>();

    /**
     * No vanilla cause window lasts longer than a tick; a window older than this one was
     * never closed (see the class javadoc).
     */
    private static final long MAX_WINDOW_NANOS = 5_000_000_000L;
    /** Minimum delay between two "leaked cause" warnings, so one broken window cannot flood the log. */
    private static final long STALE_WARN_INTERVAL_NANOS = 60_000_000_000L;

    private static long lastStaleWarn;

    private NaturalBreakCause() {
    }

    public static final class Cause {
        public final String type;
        /** The cause that was active when this one was pushed (restored by clear()). */
        public final Cause previous;
        public int depth = 1;
        /** Nanotime this window was opened at; see {@link NaturalBreakCause#MAX_WINDOW_NANOS}. */
        public final long startedAtNanos = System.nanoTime();
        /** Change just logged through a break/remove hook; guards delegated calls. */
        public BlockPos lastBreakPos;
        public BlockState lastBreakOld;
        /** Change just logged through a setBlockState hook; guards delegated overloads. */
        public BlockPos lastSetPos;
        public BlockState lastSetOld;
        public BlockState lastSetNew;

        public Cause(String type, Cause previous) {
            this.type = type;
            this.previous = previous;
        }

        /** True when exactly this change was already logged in the current cause scope. */
        public boolean alreadyLoggedSet(BlockPos pos, BlockState oldState, BlockState newState) {
            return lastSetPos != null && lastSetPos.equals(pos)
                    && oldState.equals(lastSetOld) && newState.equals(lastSetNew);
        }

        /**
         * True when exactly this removal was already logged in the current cause scope.
         * The setBlockState hook asks the same question (E6): {@code Level#removeBlock}
         * fires the break hook first and then delegates to {@code setBlockState}, so one
         * removal would otherwise be recorded twice.
         */
        public boolean alreadyLoggedBreak(BlockPos pos, BlockState oldState) {
            return lastBreakPos != null && lastBreakPos.equals(pos) && oldState.equals(lastBreakOld);
        }
    }

    /**
     * Returns the active cause, dropping it first when its window is older than
     * {@link #MAX_WINDOW_NANOS} (a TAIL injector that never ran). Called from every
     * entry point so a leaked cause cannot survive into unrelated block changes.
     */
    private static Cause pruneStale() {
        Cause current = CURRENT.get();
        if (current == null || System.nanoTime() - current.startedAtNanos <= MAX_WINDOW_NANOS) {
            return current;
        }
        CURRENT.remove();
        long now = System.nanoTime();
        if (now - lastStaleWarn > STALE_WARN_INTERVAL_NANOS) {
            lastStaleWarn = now;
            CoreProtectFabric.LOGGER.warn("[CoreProtect] Dropped a '{}' cause window that was never closed;"
                    + " block changes inside it were not attributed to it.", current.type);
        }
        return null;
    }

    public static void set(String type) {
        Cause current = pruneStale();
        if (current != null && current.type.equals(type)) {
            current.depth++;
            return;
        }
        CURRENT.set(new Cause(type, current));
    }

    public static Cause get() {
        return pruneStale();
    }

    public static void clear(String type) {
        Cause current = pruneStale();
        if (current == null || !current.type.equals(type)) return;
        if (--current.depth <= 0) {
            if (current.previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(current.previous); // restore the outer cause
            }
        }
    }
}
