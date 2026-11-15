package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.util.NaturalBreakCause;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Attributes the block writes of a piston activation to #piston (L3-06).
 *
 * <p>{@code PistonBaseBlock#moveBlocks} destroys the blocks it cannot push
 * ({@code PistonBaseBlock.java:298}: {@code dropResources} plus
 * {@code level.setBlock(blockPos, Blocks.AIR.defaultBlockState(), 18)} at {@code :303}) BEFORE
 * the piston block entity exists, so those removals happened outside any #piston window and were
 * never logged - a piston could silently delete a chest full of items with no audit trace. With
 * the window open the write goes through the ordinary {@code Level} hook and is recorded as
 * {@code #piston}.</p>
 *
 * <p>The other writes of the same method need no record of their own: the source cell is cleared
 * with {@code Block.UPDATE_MOVE_BY_PISTON} (filtered in the hook, the movement is logged from the
 * block entity), and the moving-ghost/piston-head writes are filtered as mechanism noise. The
 * destroyed blocks carry no {@code UPDATE_MOVE_BY_PISTON} flag, which is exactly what makes them
 * visible here.</p>
 */
@Mixin(PistonBaseBlock.class)
public abstract class PistonBlockMixin {

    @Inject(method = "moveBlocks(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Z)Z",
            at = @At("HEAD"))
    private void coreprotect$pistonMoveStart(Level world, BlockPos pos, Direction dir, boolean extend,
                                             CallbackInfoReturnable<Boolean> cir) {
        NaturalBreakCause.set("#piston");
    }

    // RETURN, not TAIL: the window must also close on an early return of the vanilla body,
    // otherwise the leaked #piston cause would re-attribute every later block change.
    @Inject(method = "moveBlocks(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Z)Z",
            at = @At("RETURN"))
    private void coreprotect$pistonMoveEnd(Level world, BlockPos pos, Direction dir, boolean extend,
                                           CallbackInfoReturnable<Boolean> cir) {
        NaturalBreakCause.clear("#piston");
    }
}
