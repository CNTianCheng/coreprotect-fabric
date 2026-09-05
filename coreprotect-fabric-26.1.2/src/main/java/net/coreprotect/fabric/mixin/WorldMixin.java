package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabric;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hooks every world-level block mutation entry point. Only natural events
 * (attributed through {@link net.coreprotect.fabric.util.NaturalBreakCause})
 * are logged here; player actions are handled by Fabric events.
 */
@Mixin(Level.class)
public abstract class WorldMixin {

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"))
    private void coreprotect$hookSetBlock4(BlockPos pos, BlockState state, int flags, int updateLimit,
                                           CallbackInfoReturnable<Boolean> cir) {
        CoreProtectFabric.hookSetBlockState((Level) (Object) this, pos, state);
    }

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z",
            at = @At("HEAD"))
    private void coreprotect$hookSetBlock3(BlockPos pos, BlockState state, int flags,
                                           CallbackInfoReturnable<Boolean> cir) {
        CoreProtectFabric.hookSetBlockState((Level) (Object) this, pos, state);
    }

    @Inject(method = "destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;I)Z",
            at = @At("HEAD"))
    private void coreprotect$hookDestroyBlock(BlockPos pos, boolean dropResources, Entity breaker, int updateLimit,
                                              CallbackInfoReturnable<Boolean> cir) {
        CoreProtectFabric.hookBreakBlock((Level) (Object) this, pos);
    }

    @Inject(method = "removeBlock(Lnet/minecraft/core/BlockPos;Z)Z", at = @At("HEAD"))
    private void coreprotect$hookRemoveBlock(BlockPos pos, boolean movedByPiston, CallbackInfoReturnable<Boolean> cir) {
        CoreProtectFabric.hookBreakBlock((Level) (Object) this, pos);
    }
}
