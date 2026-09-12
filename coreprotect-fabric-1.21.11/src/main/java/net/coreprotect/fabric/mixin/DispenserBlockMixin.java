package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabric;
import net.minecraft.block.BlockState;
import net.minecraft.block.DispenserBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
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

    @Inject(method = "scheduledTick(Lnet/minecraft/block/BlockState;Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/random/Random;)V",
            at = @At("HEAD"))
    private void coreprotect$dispenserScheduledTick(BlockState state, ServerWorld world, BlockPos pos, Random random, CallbackInfo ci) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null) return;
        mod.dispenserTracker().onScheduledTick(world, pos);
    }

    @Inject(method = "dispense(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/block/BlockState;Lnet/minecraft/util/math/BlockPos;)V",
            at = @At("TAIL"))
    private void coreprotect$dispenserDispensed(ServerWorld world, BlockState state, BlockPos pos, CallbackInfo ci) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null) return;
        mod.dispenserTracker().onDispensed(world, pos);
    }
}
