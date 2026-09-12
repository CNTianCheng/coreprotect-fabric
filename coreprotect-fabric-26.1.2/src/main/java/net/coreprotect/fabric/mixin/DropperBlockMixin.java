package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabric;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.DropperBlock;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
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

    @Inject(method = "dispenseFrom(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)V",
            at = @At("TAIL"))
    private void coreprotect$dropperDispensed(ServerLevel world, BlockState state, BlockPos pos, CallbackInfo ci) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null) return;
        mod.dispenserTracker().onDispensed(world, pos);
    }
}
