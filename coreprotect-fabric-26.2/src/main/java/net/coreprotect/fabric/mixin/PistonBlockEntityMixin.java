package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.util.NaturalBreakCause;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Attributes blocks moved by pistons to #piston. */
@Mixin(PistonMovingBlockEntity.class)
public abstract class PistonBlockEntityMixin {

    @Inject(method = "tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/piston/PistonMovingBlockEntity;)V",
            at = @At("HEAD"))
    private static void coreprotect$pistonStart(Level level, BlockPos pos, BlockState state,
                                                PistonMovingBlockEntity blockEntity, CallbackInfo ci) {
        NaturalBreakCause.set("#piston");
    }

    @Inject(method = "tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/piston/PistonMovingBlockEntity;)V",
            at = @At("TAIL"))
    private static void coreprotect$pistonEnd(Level level, BlockPos pos, BlockState state,
                                              PistonMovingBlockEntity blockEntity, CallbackInfo ci) {
        NaturalBreakCause.clear("#piston");
    }
}
