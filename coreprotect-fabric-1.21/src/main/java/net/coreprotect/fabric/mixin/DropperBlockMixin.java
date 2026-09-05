package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabric;
import net.minecraft.block.BlockState;
import net.minecraft.block.DropperBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code DropperBlock} overrides {@link DispenserBlock#dispense}, so the dropper
 * needs its own dispense hook feeding the {@code #dropper} tracker.
 */
@Mixin(DropperBlock.class)
public abstract class DropperBlockMixin {

    @Inject(method = "dispense(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/block/BlockState;Lnet/minecraft/util/math/BlockPos;)V",
            at = @At("TAIL"))
    private void coreprotect$dropperDispensed(ServerWorld world, BlockState state, BlockPos pos, CallbackInfo ci) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null) return;
        mod.dispenserTracker().onDispensed(world, pos);
    }
}
