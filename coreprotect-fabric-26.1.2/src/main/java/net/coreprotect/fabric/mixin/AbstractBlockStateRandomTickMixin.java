package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.util.NaturalBreakCause;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Attributes block changes caused by a random tick to #random_tick (L3-18).
 *
 * <p>Ice and snow melting, snow/grass spreading, copper oxidising, coral dying, crops and vines
 * growing: none of those went through a cause window, so the {@code Level} hooks dropped them.
 * Only leaf decay had a mixin of its own.</p>
 *
 * <p>The injection point is {@code BlockBehaviour.BlockStateBase#randomTick}, the single
 * implementation that every random-tickable block goes through
 * ({@code BlockBehaviour.java:762}, where it delegates to the per-block override), so one
 * injector covers all of them and keeps nesting correct - a {@code #decay} window opened by
 * {@code LeavesBlockMixin} inside it restores {@code #random_tick} when it closes.</p>
 *
 * <p>Changes that merely create a block in air (tree, mushroom, crop growth) are filtered in
 * {@code CoreProtectFabric#hookSetBlockState} - they are not a change of an existing block, and
 * logging them would flood the table with growth rows.</p>
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class AbstractBlockStateRandomTickMixin {

    @Inject(method = "randomTick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V",
            at = @At("HEAD"))
    private void coreprotect$randomTickStart(ServerLevel world, BlockPos pos, RandomSource random, CallbackInfo ci) {
        NaturalBreakCause.set("#random_tick");
    }

    // RETURN, not TAIL: several blocks return early from their random tick, and a window that is
    // not closed would mis-attribute every later block change on this server thread.
    @Inject(method = "randomTick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V",
            at = @At("RETURN"))
    private void coreprotect$randomTickEnd(ServerLevel world, BlockPos pos, RandomSource random, CallbackInfo ci) {
        NaturalBreakCause.clear("#random_tick");
    }
}
