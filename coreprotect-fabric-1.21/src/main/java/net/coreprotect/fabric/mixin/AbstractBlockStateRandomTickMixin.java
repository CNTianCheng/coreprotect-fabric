package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.util.NaturalBreakCause;
import net.minecraft.block.AbstractBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Attributes block changes caused by a random tick to #random_tick (L3-18).
 *
 * <p>Ice and snow melting, snow/grass spreading, copper oxidising, coral dying, crops and vines
 * growing: none of those went through a cause window, so the {@code World} hooks dropped them.
 * Only leaf decay had a mixin of its own.</p>
 *
 * <p>The injection point is {@code AbstractBlock.AbstractBlockState#randomTick}, the single
 * implementation that every random-tickable block goes through
 * ({@code AbstractBlock.java:1465}): it delegates to the per-block {@code randomTick} override
 * ({@code AbstractBlock.java:941}), so one injector covers all of them and keeps nesting correct -
 * a {@code #decay} window opened by {@code LeavesBlock} inside it restores {@code #random_tick}
 * when it closes.</p>
 *
 * <p>Changes that merely create a block in air (tree, mushroom, crop growth) are filtered in
 * {@code CoreProtectFabric#hookSetBlockState} - they are not a change of an existing block, and
 * logging them would flood the table with growth rows.</p>
 */
@Mixin(AbstractBlock.AbstractBlockState.class)
public abstract class AbstractBlockStateRandomTickMixin {

    @Inject(method = "randomTick(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/random/Random;)V",
            at = @At("HEAD"))
    private void coreprotect$randomTickStart(ServerWorld world, BlockPos pos, Random random, CallbackInfo ci) {
        NaturalBreakCause.set("#random_tick");
    }

    // RETURN, not TAIL: the closing injector then runs on every exit path of the vanilla body (a
    // per-block random tick that returns early must not leak its window), so the cause can never
    // mis-attribute every later block change on this server thread.
    @Inject(method = "randomTick(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/random/Random;)V",
            at = @At("RETURN"))
    private void coreprotect$randomTickEnd(ServerWorld world, BlockPos pos, Random random, CallbackInfo ci) {
        NaturalBreakCause.clear("#random_tick");
    }
}
