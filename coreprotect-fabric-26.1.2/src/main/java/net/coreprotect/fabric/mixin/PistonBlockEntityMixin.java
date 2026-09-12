package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabric;
import net.coreprotect.fabric.util.NaturalBreakCause;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Attributes blocks moved by pistons to #piston: the cause marker suppresses the
 * mechanism noise (piston head / moving ghost states) while the tick runs, and the
 * moved block is logged directly ("removed" at the source, "placed" at the destination)
 * when the movement finishes.
 */
@Mixin(PistonMovingBlockEntity.class)
public abstract class PistonBlockEntityMixin {

    @Inject(method = "tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/piston/PistonMovingBlockEntity;)V",
            at = @At("HEAD"))
    private static void coreprotect$pistonStart(Level world, BlockPos pos, BlockState state, PistonMovingBlockEntity blockEntity,
                                                CallbackInfo ci) {
        NaturalBreakCause.set("#piston");
    }

    @Inject(method = "tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/piston/PistonMovingBlockEntity;)V",
            at = @At("TAIL"))
    private static void coreprotect$pistonEnd(Level world, BlockPos pos, BlockState state, PistonMovingBlockEntity blockEntity,
                                              CallbackInfo ci) {
        NaturalBreakCause.clear("#piston");
        CoreProtectFabric.logPistonMove(world, pos, state, blockEntity);
    }
}

