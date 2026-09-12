package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.util.NaturalBreakCause;
import net.minecraft.block.BlockState;
import net.minecraft.block.FireBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Attributes blocks burned by fire spread to #fire. 1.21.9+ moved the spread
 * logic out of {@code trySpreadingFire} into the scheduled tick, so the cause
 * marker now wraps {@code scheduledTick}.
 */
@Mixin(FireBlock.class)
public abstract class FireBlockMixin {

    @Inject(method = "scheduledTick(Lnet/minecraft/block/BlockState;Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/random/Random;)V",
            at = @At("HEAD"))
    private void coreprotect$fireStart(BlockState state, ServerWorld world, BlockPos pos, Random random,
                                       CallbackInfo ci) {
        NaturalBreakCause.set("#fire");
    }

    @Inject(method = "scheduledTick(Lnet/minecraft/block/BlockState;Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/random/Random;)V",
            at = @At("TAIL"))
    private void coreprotect$fireEnd(BlockState state, ServerWorld world, BlockPos pos, Random random,
                                     CallbackInfo ci) {
        NaturalBreakCause.clear("#fire");
    }
}
