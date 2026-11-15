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

    // L3-02: @At("TAIL") only matches the LAST return instruction, but scheduledTick has two
    // normal returns (FireBlock.java:157 when nothing around is burnable, :162 when the fire is
    // at its last age and cannot spread down). Every earlier return skipped this injector, so
    // the #fire window stayed open and its depth never fell back to 0 - every later block change
    // on this server thread was then attributed to #fire. RETURN is applied to every return.
    @Inject(method = "scheduledTick(Lnet/minecraft/block/BlockState;Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/random/Random;)V",
            at = @At("RETURN"))
    private void coreprotect$fireEnd(BlockState state, ServerWorld world, BlockPos pos, Random random,
                                     CallbackInfo ci) {
        NaturalBreakCause.clear("#fire");
    }
}
