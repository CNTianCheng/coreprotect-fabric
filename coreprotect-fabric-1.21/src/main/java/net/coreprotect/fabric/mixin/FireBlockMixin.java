package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.util.NaturalBreakCause;
import net.minecraft.block.FireBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Attributes blocks burned by fire spread to #fire. */
@Mixin(FireBlock.class)
public abstract class FireBlockMixin {

    @Inject(method = "trySpreadingFire(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;ILnet/minecraft/util/math/random/Random;I)V",
            at = @At("HEAD"))
    private void coreprotect$fireStart(World world, BlockPos pos, int spreadFactor, Random random, int currentAge,
                                       CallbackInfo ci) {
        NaturalBreakCause.set("#fire");
    }

    @Inject(method = "trySpreadingFire(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;ILnet/minecraft/util/math/random/Random;I)V",
            at = @At("TAIL"))
    private void coreprotect$fireEnd(World world, BlockPos pos, int spreadFactor, Random random, int currentAge,
                                     CallbackInfo ci) {
        NaturalBreakCause.clear("#fire");
    }
}
