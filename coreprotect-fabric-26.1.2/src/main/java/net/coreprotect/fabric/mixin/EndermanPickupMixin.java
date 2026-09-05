package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.util.NaturalBreakCause;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Attributes block pickup by endermen to #enderman. */
@Mixin(targets = "net.minecraft.world.entity.monster.EnderMan$EndermanTakeBlockGoal")
public abstract class EndermanPickupMixin {

    @Inject(method = "tick()V", at = @At("HEAD"))
    private void coreprotect$pickupStart(CallbackInfo ci) {
        NaturalBreakCause.set("#enderman");
    }

    @Inject(method = "tick()V", at = @At("TAIL"))
    private void coreprotect$pickupEnd(CallbackInfo ci) {
        NaturalBreakCause.clear("#enderman");
    }
}
