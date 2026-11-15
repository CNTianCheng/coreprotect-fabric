package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.util.NaturalBreakCause;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Attributes blocks burned by fire spread to #fire. 1.21.9+ moved the spread
 * logic out of {@code trySpreadingFire} into the scheduled tick, so the cause
 * marker now wraps the scheduled tick ({@code FireBlock#tick}).
 */
@Mixin(FireBlock.class)
public abstract class FireBlockMixin {

    @Inject(method = "tick",
            at = @At("HEAD"))
    private void coreprotect$fireStart(BlockState state, ServerLevel level, BlockPos pos, RandomSource random,
                                       CallbackInfo ci) {
        NaturalBreakCause.set("#fire");
    }

    // L3-02: @At("TAIL") only matches the LAST return instruction, but the scheduled tick has
    // several normal returns (one when nothing around is burnable, one when the fire is at its
    // last age and cannot spread down). Every earlier return skipped this injector, so the #fire
    // window stayed open and its depth never fell back to 0 - every later block change on this
    // server thread was then attributed to #fire. RETURN is applied to every return.
    @Inject(method = "tick",
            at = @At("RETURN"))
    private void coreprotect$fireEnd(BlockState state, ServerLevel level, BlockPos pos, RandomSource random,
                                     CallbackInfo ci) {
        NaturalBreakCause.clear("#fire");
    }
}
