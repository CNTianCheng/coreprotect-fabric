package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.util.NaturalBreakCause;
import net.minecraft.block.PistonBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Attributes the block writes of a piston activation to #piston (L3-06).
 *
 * <p>{@code PistonBlock#move} destroys the blocks it cannot push
 * ({@code PistonBlock.java:283-295}: {@code dropStacks} plus
 * {@code setBlockState(blockPos3, AIR, NOTIFY_LISTENERS | FORCE_STATE)}) BEFORE the piston block
 * entity exists, so those removals happened outside any #piston window and were never logged -
 * a piston could silently delete a chest full of items with no audit trace. With the window open
 * the write goes through the ordinary {@code World} hook and is recorded as {@code #piston}.</p>
 *
 * <p>The other writes of the same method need no record of their own: the source cell is cleared
 * with {@code Block.MOVED} (filtered in the hook, the movement is logged from the block entity),
 * and the moving-ghost/piston-head writes are filtered as mechanism noise. The destroyed blocks
 * carry no {@code Block.MOVED} flag, which is exactly what makes them visible here.</p>
 */
@Mixin(PistonBlock.class)
public abstract class PistonBlockMixin {

    @Inject(method = "move(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/Direction;Z)Z",
            at = @At("HEAD"))
    private void coreprotect$pistonMoveStart(World world, BlockPos pos, Direction dir, boolean extend,
                                             CallbackInfoReturnable<Boolean> cir) {
        NaturalBreakCause.set("#piston");
    }

    // RETURN, not TAIL: the window must also close on an early return of the vanilla body,
    // otherwise the leaked #piston cause would re-attribute every later block change.
    @Inject(method = "move(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/Direction;Z)Z",
            at = @At("RETURN"))
    private void coreprotect$pistonMoveEnd(World world, BlockPos pos, Direction dir, boolean extend,
                                           CallbackInfoReturnable<Boolean> cir) {
        NaturalBreakCause.clear("#piston");
    }
}
