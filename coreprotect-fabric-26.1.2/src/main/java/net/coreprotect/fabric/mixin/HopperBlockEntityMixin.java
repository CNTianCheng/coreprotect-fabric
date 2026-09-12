package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabric;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Feeds hopper ticks into the {@code #hopper} container transaction tracker. */
@Mixin(HopperBlockEntity.class)
public abstract class HopperBlockEntityMixin {

    @Inject(method = "pushItemsTick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/HopperBlockEntity;)V",
            at = @At("HEAD"))
    private static void coreprotect$hopperTick(Level world, BlockPos pos, BlockState state,
                                               HopperBlockEntity blockEntity, CallbackInfo ci) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null) return;
        mod.hopperTracker().onHopperTick(world, pos, state, blockEntity);
    }
}
