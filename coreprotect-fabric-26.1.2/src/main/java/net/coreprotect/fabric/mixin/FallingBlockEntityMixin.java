package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.util.NaturalBreakCause;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Attributes falling blocks (sand, gravel, concrete powder, anvil, ...) to #gravity (L3-12).
 *
 * <p>Two vanilla writes created a block change that no hook saw: {@code fall}
 * ({@code FallingBlockEntity.java:91}) clears the origin with
 * {@code level.setBlock(pos, state.getFluidState().createLegacyBlock(), 3)}
 * ({@code :99}), and the entity writes the block back at the landing position in
 * {@code tick()} ({@code :192}). A falling sand column therefore left a hole and a new block in
 * the log-less void. Both writes happen on the server thread while the window is open.</p>
 *
 * <p>{@code fall} is also used by brushable blocks, pointed dripstone and scaffolding,
 * all of which are genuine falling-block starts, so the window is correct for every caller.</p>
 */
@Mixin(FallingBlockEntity.class)
public abstract class FallingBlockEntityMixin {

    @Inject(method = "fall(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/entity/item/FallingBlockEntity;",
            at = @At("HEAD"))
    private static void coreprotect$fallingStart(Level level, BlockPos pos, BlockState state,
                                                 CallbackInfoReturnable<FallingBlockEntity> cir) {
        NaturalBreakCause.set("#gravity");
    }

    @Inject(method = "fall(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/entity/item/FallingBlockEntity;",
            at = @At("RETURN"))
    private static void coreprotect$fallingEnd(Level level, BlockPos pos, BlockState state,
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
