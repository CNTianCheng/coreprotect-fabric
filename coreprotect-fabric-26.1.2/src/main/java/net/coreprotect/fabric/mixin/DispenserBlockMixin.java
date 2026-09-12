package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabric;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds dispenser/dropper activity into the {@code #dispenser} / {@code #dropper}
 * container transaction tracker: the scheduled tick (shared by both blocks via
 * inheritance) establishes the baseline, and the dispense hook logs the item
 * that just left the inventory.
 */
@Mixin(DispenserBlock.class)
public abstract class DispenserBlockMixin {

    @Inject(method = "tick(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V",
            at = @At("HEAD"))
    private void coreprotect$dispenserScheduledTick(BlockState state, ServerLevel world, BlockPos pos, RandomSource random, CallbackInfo ci) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null) return;
        mod.dispenserTracker().onScheduledTick(world, pos);
    }

    @Inject(method = "dispenseFrom(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)V",
            at = @At("TAIL"))
    private void coreprotect$dispenserDispensed(ServerLevel world, BlockState state, BlockPos pos, CallbackInfo ci) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null) return;
        mod.dispenserTracker().onDispensed(world, pos);
    }
}
