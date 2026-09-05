package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.util.NaturalBreakCause;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Attributes block placement by endermen to #enderman. */
@Mixin(targets = "net.minecraft.entity.mob.EndermanEntity$PlaceBlockGoal")
public abstract class EndermanPlaceMixin {

    @Inject(method = "tick()V", at = @At("HEAD"))
    private void coreprotect$placeStart(CallbackInfo ci) {
        NaturalBreakCause.set("#enderman");
    }

    @Inject(method = "tick()V", at = @At("TAIL"))
    private void coreprotect$placeEnd(CallbackInfo ci) {
        NaturalBreakCause.clear("#enderman");
    }
}
