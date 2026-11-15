package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.util.NaturalBreakCause;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Attributes the two halves of a bed that explode in the Nether/End to #explosion (L3-13).
 *
 * <p>{@code BedBlock#useWithoutItem} removes BOTH halves with {@code level.removeBlock} and only
 * then calls {@code level.explode} ({@code BedBlock.java:97, :100, :104}). The removal therefore
 * happened before the explosion window was opened, so a bed - i.e. two blocks - disappeared from
 * the world without a single audit row. The window is opened for the whole use action; the other
 * writes of the method are bed-state changes of the same block, which the hook filters by block
 * identity.</p>
 */
@Mixin(BedBlock.class)
public abstract class BedBlockMixin {

    @Inject(method = "useWithoutItem(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;",
            at = @At("HEAD"))
    private void coreprotect$bedUseStart(BlockState state, Level world, BlockPos pos, Player player,
                                         BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        NaturalBreakCause.set("#explosion");
    }

    @Inject(method = "useWithoutItem(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;",
            at = @At("RETURN"))
    private void coreprotect$bedUseEnd(BlockState state, Level world, BlockPos pos, Player player,
                                       BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        NaturalBreakCause.clear("#explosion");
    }
}
