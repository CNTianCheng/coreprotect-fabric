package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.util.NaturalBreakCause;
import net.minecraft.block.BlockState;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Attributes the respawn anchor that explodes in a wrong dimension to #explosion (L3-13).
 *
 * <p>{@code RespawnAnchorBlock#explode} removes the anchor with
 * {@code world.removeBlock(explodedPos, false)} before it spawns the explosion
 * ({@code RespawnAnchorBlock.java:148}), so the anchor itself - the block the player is looking
 * at after the bang - was never recorded.</p>
 */
@Mixin(RespawnAnchorBlock.class)
public abstract class RespawnAnchorBlockMixin {

    @Inject(method = "explode(Lnet/minecraft/block/BlockState;Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/util/math/BlockPos;)V",
            at = @At("HEAD"))
    private void coreprotect$anchorExplodeStart(BlockState state, ServerWorld world, BlockPos pos, CallbackInfo ci) {
        NaturalBreakCause.set("#explosion");
    }

    // RETURN, not TAIL: the window has to close even when the vanilla body returns early.
    @Inject(method = "explode(Lnet/minecraft/block/BlockState;Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/util/math/BlockPos;)V",
            at = @At("RETURN"))
    private void coreprotect$anchorExplodeEnd(BlockState state, ServerWorld world, BlockPos pos, CallbackInfo ci) {
        NaturalBreakCause.clear("#explosion");
    }
}
