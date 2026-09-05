package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabric;
import net.coreprotect.fabric.util.NaturalBreakCause;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.PistonBlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Attributes blocks moved by pistons to #piston: the cause marker suppresses the
 * mechanism noise (piston head / moving ghost states) while the tick runs, and the
 * moved block is logged directly ("removed" at the source, "placed" at the destination)
 * when the movement finishes.
 */
@Mixin(PistonBlockEntity.class)
public abstract class PistonBlockEntityMixin {

    @Inject(method = "tick(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;Lnet/minecraft/block/entity/PistonBlockEntity;)V",
            at = @At("HEAD"))
    private static void coreprotect$pistonStart(World world, BlockPos pos, BlockState state, PistonBlockEntity blockEntity,
                                                CallbackInfo ci) {
        NaturalBreakCause.set("#piston");
    }

    @Inject(method = "tick(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;Lnet/minecraft/block/entity/PistonBlockEntity;)V",
            at = @At("TAIL"))
    private static void coreprotect$pistonEnd(World world, BlockPos pos, BlockState state, PistonBlockEntity blockEntity,
                                              CallbackInfo ci) {
        NaturalBreakCause.clear("#piston");
        CoreProtectFabric.logPistonMove(world, pos, state, blockEntity);
    }
}

