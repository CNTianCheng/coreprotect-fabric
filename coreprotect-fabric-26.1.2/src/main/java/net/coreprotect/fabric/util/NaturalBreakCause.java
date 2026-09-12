package net.coreprotect.fabric.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

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
 * <p>Note: this class intentionally lives OUTSIDE the mixin package so the
 * loader does not try to transform it as a mixin.</p>
 */
public final class NaturalBreakCause {
    private static final ThreadLocal<Cause> CURRENT = new ThreadLocal<>();

    private NaturalBreakCause() {
    }

    public static final class Cause {
        public final String type;
        /** The cause that was active when this one was pushed (restored by clear()). */
        public final Cause previous;
        public int depth = 1;
        /** Change just logged through a break/remove hook; guards delegated calls. */
        public BlockPos lastBreakPos;
        public BlockState lastBreakOld;
        /** Change just logged through a setBlock hook; guards delegated overloads. */
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

        /** True when exactly this removal was already logged in the current cause scope. */
        public boolean alreadyLoggedBreak(BlockPos pos, BlockState oldState) {
            return lastBreakPos != null && lastBreakPos.equals(pos) && oldState.equals(lastBreakOld);
        }
    }

    public static void set(String type) {
        Cause current = CURRENT.get();
        if (current != null && current.type.equals(type)) {
            current.depth++;
            return;
        }
        CURRENT.set(new Cause(type, current));
    }

    public static Cause get() {
        return CURRENT.get();
    }

    public static void clear(String type) {
        Cause current = CURRENT.get();
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
