package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.util.NaturalBreakCause;
import net.minecraft.block.BlockState;
import net.minecraft.block.FarmlandBlock;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Attributes farmland turned back into dirt by a landing entity to #trample (L3-14).
 *
 * <p>{@code FarmlandBlock#onLandedUpon} calls {@code setToDirt}
 * ({@code FarmlandBlock.java:104-110}), and {@code setToDirt} first pushes the crops above it out
 * of the way ({@code :117 pushEntitiesUpBeforeBlockChange}) before it writes dirt
 * ({@code :118}). Both the crop removal and the farmland change were invisible.</p>
 *
 * <p>Deliberately NOT hooked on {@code setToDirt}: the same method is also reached from the
 * random tick when farmland dries out ({@code :90}), which must stay #random_tick, and it is a
 * static method that is also called by other blocks. {@code onLandedUpon} is the player/entity
 * caused path only.</p>
 */
@Mixin(FarmlandBlock.class)
public abstract class FarmlandBlockMixin {

    @Inject(method = "onLandedUpon(Lnet/minecraft/world/World;Lnet/minecraft/block/BlockState;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/entity/Entity;D)V",
            at = @At("HEAD"))
    private void coreprotect$trampleStart(World world, BlockState state, BlockPos pos, Entity entity,
                                          double fallDistance, CallbackInfo ci) {
        NaturalBreakCause.set("#trample");
    }

    // RETURN, not TAIL: the vanilla body bails out early for some entity/fall combinations.
    @Inject(method = "onLandedUpon(Lnet/minecraft/world/World;Lnet/minecraft/block/BlockState;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/entity/Entity;D)V",
            at = @At("RETURN"))
    private void coreprotect$trampleEnd(World world, BlockState state, BlockPos pos, Entity entity,
                                        double fallDistance, CallbackInfo ci) {
        NaturalBreakCause.clear("#trample");
    }
}
