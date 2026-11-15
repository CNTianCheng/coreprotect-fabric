package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.util.NaturalBreakCause;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Attributes the respawn anchor that explodes in a wrong dimension to #explosion (L3-13).
 *
 * <p>{@code RespawnAnchorBlock#explode} removes the anchor with
 * {@code level.removeBlock(pos, false)} before it spawns the explosion
 * ({@code RespawnAnchorBlock.java:154}), so the anchor itself - the block the player is looking
 * at after the bang - was never recorded.</p>
 */
@Mixin(RespawnAnchorBlock.class)
public abstract class RespawnAnchorBlockMixin {

    @Inject(method = "explode(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;)V",
            at = @At("HEAD"))
    private void coreprotect$anchorExplodeStart(BlockState state, ServerLevel world, BlockPos pos, CallbackInfo ci) {
        NaturalBreakCause.set("#explosion");
    }

    // RETURN, not TAIL: the window has to close even when the vanilla body returns early.
    @Inject(method = "explode(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;)V",
            at = @At("RETURN"))
    private void coreprotect$anchorExplodeEnd(BlockState state, ServerLevel world, BlockPos pos, CallbackInfo ci) {
        NaturalBreakCause.clear("#explosion");
    }
}
