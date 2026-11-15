package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.util.NaturalBreakCause;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Attributes farmland turned back into dirt by a landing entity to #trample (L3-14).
 *
 * <p>{@code FarmlandBlock#fallOn} calls {@code turnToDirt}
 * ({@code FarmlandBlock.java:115-121}), and {@code turnToDirt} first pushes the crops above it
 * out of the way ({@code :128 pushEntitiesUp}) before it writes dirt
 * ({@code :129 setBlockAndUpdate}). Both the crop removal and the farmland change were
 * invisible.</p>
 *
 * <p>Deliberately NOT hooked on {@code turnToDirt}: the same method is also reached from the
 * random tick when farmland dries out, which must stay #random_tick, and it is a static method
 * that is also called by other blocks. {@code fallOn} is the player/entity caused path only.</p>
 */
@Mixin(FarmlandBlock.class)
public abstract class FarmlandBlockMixin {

    @Inject(method = "fallOn(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/Entity;D)V",
            at = @At("HEAD"))
    private void coreprotect$trampleStart(Level world, BlockState state, BlockPos pos, Entity entity,
                                          double fallDistance, CallbackInfo ci) {
        NaturalBreakCause.set("#trample");
    }

    // RETURN, not TAIL: the vanilla body bails out early for some entity/fall combinations.
    @Inject(method = "fallOn(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/Entity;D)V",
            at = @At("RETURN"))
    private void coreprotect$trampleEnd(Level world, BlockState state, BlockPos pos, Entity entity,
                                        double fallDistance, CallbackInfo ci) {
        NaturalBreakCause.clear("#trample");
    }
}
