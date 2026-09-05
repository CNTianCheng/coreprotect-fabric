package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.util.NaturalBreakCause;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.LavaFluid;
import net.minecraft.world.level.material.WaterFluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Attributes blocks washed away by flowing water/lava to #water / #lava. */
@Mixin(FlowingFluid.class)
public abstract class FlowableFluidMixin {

    @Inject(method = "spreadTo(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Lnet/minecraft/world/level/material/FluidState;)V",
            at = @At("HEAD"))
    private void coreprotect$fluidStart(LevelAccessor level, BlockPos pos, BlockState state, Direction direction,
                                        FluidState fluidState, CallbackInfo ci) {
        NaturalBreakCause.set(causeName());
    }

    @Inject(method = "spreadTo(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Lnet/minecraft/world/level/material/FluidState;)V",
            at = @At("TAIL"))
    private void coreprotect$fluidEnd(LevelAccessor level, BlockPos pos, BlockState state, Direction direction,
                                      FluidState fluidState, CallbackInfo ci) {
        NaturalBreakCause.clear(causeName());
    }

    private String causeName() {
        Object self = this;
        if (self instanceof WaterFluid) return "#water";
        if (self instanceof LavaFluid) return "#lava";
        return "#fluid";
    }
}
