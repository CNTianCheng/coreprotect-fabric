package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.util.NaturalBreakCause;
import net.minecraft.block.BlockState;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Attributes the respawn anchor that explodes in a wrong dimension to #explosion (L3-13).
 *
 * <p>{@code RespawnAnchorBlock#explode} removes the anchor with
 * {@code world.removeBlock(explodedPos, false)} ({@code RespawnAnchorBlock.java:145}) before it
 * spawns the explosion ({@code :157}), so the anchor itself - the block the player is looking at
 * after the bang - was never recorded.</p>
 *
 * <p>1.21 mapping note: {@code explode} takes the {@code World} here
 * ({@code RespawnAnchorBlock.java:144}); 1.21.11 passes a {@code ServerWorld}, so the descriptor
 * must end in {@code Lnet/minecraft/world/World;}.</p>
 */
@Mixin(RespawnAnchorBlock.class)
public abstract class RespawnAnchorBlockMixin {

    @Inject(method = "explode(Lnet/minecraft/block/BlockState;Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;)V",
            at = @At("HEAD"))
    private void coreprotect$anchorExplodeStart(BlockState state, World world, BlockPos pos, CallbackInfo ci) {
        NaturalBreakCause.set("#explosion");
    }

    // RETURN, not TAIL: the window has to close even when the vanilla body returns early.
    @Inject(method = "explode(Lnet/minecraft/block/BlockState;Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;)V",
            at = @At("RETURN"))
    private void coreprotect$anchorExplodeEnd(BlockState state, World world, BlockPos pos, CallbackInfo ci) {
        NaturalBreakCause.clear("#explosion");
    }
}
