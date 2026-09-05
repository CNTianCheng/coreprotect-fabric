package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabric;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hooks every world-level block mutation entry point. Only natural events
 * (attributed through {@link NaturalBreakCause}) are logged here; player actions
 * are handled by Fabric events, so this adds almost no overhead.
 */
@Mixin(World.class)
public abstract class WorldMixin {

    @Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)Z",
            at = @At("HEAD"))
    private void coreprotect$hookSetBlockState4(BlockPos pos, BlockState state, int flags, int maxUpdateDepth,
                                                CallbackInfoReturnable<Boolean> cir) {
        CoreProtectFabric.hookSetBlockState((World) (Object) this, pos, state);
    }

    @Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;I)Z",
            at = @At("HEAD"))
    private void coreprotect$hookSetBlockState3(BlockPos pos, BlockState state, int flags,
                                                CallbackInfoReturnable<Boolean> cir) {
        CoreProtectFabric.hookSetBlockState((World) (Object) this, pos, state);
    }

    @Inject(method = "breakBlock(Lnet/minecraft/util/math/BlockPos;ZLnet/minecraft/entity/Entity;I)Z",
            at = @At("HEAD"))
    private void coreprotect$hookBreakBlock(BlockPos pos, boolean drop, Entity breakingEntity, int maxUpdateDepth,
                                            CallbackInfoReturnable<Boolean> cir) {
        CoreProtectFabric.hookBreakBlock((World) (Object) this, pos);
    }

    @Inject(method = "removeBlock(Lnet/minecraft/util/math/BlockPos;Z)Z", at = @At("HEAD"))
    private void coreprotect$hookRemoveBlock(BlockPos pos, boolean move, CallbackInfoReturnable<Boolean> cir) {
        CoreProtectFabric.hookBreakBlock((World) (Object) this, pos);
    }
}
