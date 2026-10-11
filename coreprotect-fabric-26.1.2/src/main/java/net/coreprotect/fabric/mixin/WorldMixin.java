package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabric;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.Entity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hooks every world-level block mutation entry point. Only natural events
 * (attributed through {@link NaturalBreakCause}) are logged here; player actions
 * are handled by Fabric events, so this adds almost no overhead.
 *
 * <p>All four injectors stay at HEAD on purpose: the hooks need the block state BEFORE
 * the write. At RETURN {@code Level#getBlockState} already reports the new state, so every
 * natural change would compare equal and disappear from the log, and the return value of
 * {@code removeBlock}/{@code breakBlock} says nothing about a rejected write either. The
 * writes vanilla rejects are filtered inside the hooks instead - see
 * {@code CoreProtectFabric#hookSetBlockState} (E11).</p>
 */
@Mixin(Level.class)
public abstract class WorldMixin {

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"))
    private void coreprotect$hookSetBlockState4(BlockPos pos, BlockState state, int flags, int maxUpdateDepth,
                                                CallbackInfoReturnable<Boolean> cir) {
        CoreProtectFabric.hookSetBlockState((Level) (Object) this, pos, state);
    }

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z",
            at = @At("HEAD"))
    private void coreprotect$hookSetBlockState3(BlockPos pos, BlockState state, int flags,
                                                CallbackInfoReturnable<Boolean> cir) {
        CoreProtectFabric.hookSetBlockState((Level) (Object) this, pos, state);
    }

    @Inject(method = "destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;I)Z",
            at = @At("HEAD"))
    private void coreprotect$hookBreakBlock(BlockPos pos, boolean drop, Entity breakingEntity, int maxUpdateDepth,
                                            CallbackInfoReturnable<Boolean> cir) {
        CoreProtectFabric.hookBreakBlock((Level) (Object) this, pos);
    }

    @Inject(method = "removeBlock(Lnet/minecraft/core/BlockPos;Z)Z", at = @At("HEAD"))
    private void coreprotect$hookRemoveBlock(BlockPos pos, boolean move, CallbackInfoReturnable<Boolean> cir) {
        CoreProtectFabric.hookBreakBlock((Level) (Object) this, pos);
    }
}
