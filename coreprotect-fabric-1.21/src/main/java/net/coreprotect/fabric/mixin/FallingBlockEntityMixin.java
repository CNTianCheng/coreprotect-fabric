package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.util.NaturalBreakCause;
import net.minecraft.block.BlockState;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Attributes falling blocks (sand, gravel, concrete powder, anvil, ...) to #gravity (L3-12).
 *
 * <p>Two vanilla writes created a block change that no hook saw: {@code spawnFromBlock}
 * ({@code FallingBlockEntity.java:84}) clears the origin with
 * {@code world.setBlockState(pos, state.getFluidState().getBlockState(), Block.NOTIFY_ALL)}
 * ({@code :92}), and the entity writes the block back at the landing position in
 * {@code tick()} ({@code :171}). A falling sand column therefore left a hole and a new block in
 * the log-less void. Both writes happen on the server thread while the window is open.</p>
 *
 * <p>{@code spawnFromBlock} is also used by brushable blocks, pointed dripstone and scaffolding,
 * all of which are genuine falling-block starts, so the window is correct for every caller.</p>
 */
@Mixin(FallingBlockEntity.class)
public abstract class FallingBlockEntityMixin {

    @Inject(method = "spawnFromBlock(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;)Lnet/minecraft/entity/FallingBlockEntity;",
            at = @At("HEAD"))
    private static void coreprotect$fallingStart(World world, BlockPos pos, BlockState state,
                                                 CallbackInfoReturnable<FallingBlockEntity> cir) {
        NaturalBreakCause.set("#gravity");
    }

    @Inject(method = "spawnFromBlock(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;)Lnet/minecraft/entity/FallingBlockEntity;",
            at = @At("RETURN"))
    private static void coreprotect$fallingEnd(World world, BlockPos pos, BlockState state,
                                               CallbackInfoReturnable<FallingBlockEntity> cir) {
        NaturalBreakCause.clear("#gravity");
    }

    @Inject(method = "tick()V", at = @At("HEAD"))
    private void coreprotect$landingStart(CallbackInfo ci) {
        NaturalBreakCause.set("#gravity");
    }

    // RETURN, not TAIL: the entity despawns early in some states; the window has to close anyway.
    @Inject(method = "tick()V", at = @At("RETURN"))
    private void coreprotect$landingEnd(CallbackInfo ci) {
        NaturalBreakCause.clear("#gravity");
    }
}
