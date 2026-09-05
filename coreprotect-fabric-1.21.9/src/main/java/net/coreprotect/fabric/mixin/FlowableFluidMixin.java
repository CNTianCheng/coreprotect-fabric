package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.util.NaturalBreakCause;
import net.minecraft.block.BlockState;
import net.minecraft.fluid.FlowableFluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.LavaFluid;
import net.minecraft.fluid.WaterFluid;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.WorldAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Attributes blocks washed away by flowing water/lava to #water / #lava. */
@Mixin(FlowableFluid.class)
public abstract class FlowableFluidMixin {

    @Inject(method = "flow(Lnet/minecraft/world/WorldAccess;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;Lnet/minecraft/util/math/Direction;Lnet/minecraft/fluid/FluidState;)V",
            at = @At("HEAD"))
    private void coreprotect$fluidStart(WorldAccess world, BlockPos pos, BlockState state, Direction direction,
                                        FluidState fluidState, CallbackInfo ci) {
        NaturalBreakCause.set(causeName());
    }

    @Inject(method = "flow(Lnet/minecraft/world/WorldAccess;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;Lnet/minecraft/util/math/Direction;Lnet/minecraft/fluid/FluidState;)V",
            at = @At("TAIL"))
    private void coreprotect$fluidEnd(WorldAccess world, BlockPos pos, BlockState state, Direction direction,
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
