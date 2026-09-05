package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.util.NaturalBreakCause;
import net.minecraft.block.BlockState;
import net.minecraft.block.LeavesBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Attributes blocks removed by leaf decay to #decay. */
@Mixin(LeavesBlock.class)
public abstract class LeavesBlockMixin {

    @Inject(method = "randomTick(Lnet/minecraft/block/BlockState;Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/random/Random;)V",
            at = @At("HEAD"))
    private void coreprotect$decayStart(BlockState state, ServerWorld world, BlockPos pos, Random random, CallbackInfo ci) {
        NaturalBreakCause.set("#decay");
    }

    @Inject(method = "randomTick(Lnet/minecraft/block/BlockState;Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/random/Random;)V",
            at = @At("TAIL"))
    private void coreprotect$decayEnd(BlockState state, ServerWorld world, BlockPos pos, Random random, CallbackInfo ci) {
        NaturalBreakCause.clear("#decay");
    }
}
