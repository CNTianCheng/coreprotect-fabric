package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.util.NaturalBreakCause;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Attributes the two halves of a bed that explode in the Nether/End to #explosion (L3-13).
 *
 * <p>{@code BedBlock#onUse} removes BOTH halves with {@code world.removeBlock} and only then
 * calls {@code world.createExplosion} ({@code BedBlock.java:95, :98, :102}). The removal therefore
 * happened before the explosion window was opened, so a bed - i.e. two blocks - disappeared from
 * the world without a single audit row. The window is opened for the whole use action; the other
 * writes of the method are bed-state changes of the same block, which the hook filters by block
 * identity.</p>
 */
@Mixin(BedBlock.class)
public abstract class BedBlockMixin {

    @Inject(method = "onUse(Lnet/minecraft/block/BlockState;Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/entity/player/PlayerEntity;Lnet/minecraft/util/hit/BlockHitResult;)Lnet/minecraft/util/ActionResult;",
            at = @At("HEAD"))
    private void coreprotect$bedUseStart(BlockState state, World world, BlockPos pos, PlayerEntity player,
                                        BlockHitResult hit, CallbackInfoReturnable<ActionResult> cir) {
        NaturalBreakCause.set("#explosion");
    }

    @Inject(method = "onUse(Lnet/minecraft/block/BlockState;Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/entity/player/PlayerEntity;Lnet/minecraft/util/hit/BlockHitResult;)Lnet/minecraft/util/ActionResult;",
            at = @At("RETURN"))
    private void coreprotect$bedUseEnd(BlockState state, World world, BlockPos pos, PlayerEntity player,
                                      BlockHitResult hit, CallbackInfoReturnable<ActionResult> cir) {
        NaturalBreakCause.clear("#explosion");
    }
}
