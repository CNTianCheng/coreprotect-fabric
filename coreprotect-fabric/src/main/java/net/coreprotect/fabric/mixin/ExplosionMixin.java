package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.util.NaturalBreakCause;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.projectile.WitherSkullEntity;
import net.minecraft.entity.TntEntity;
import net.minecraft.world.explosion.Explosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Attributes blocks destroyed by explosions to the correct cause (#creeper, #tnt, ...). */
@Mixin(Explosion.class)
public abstract class ExplosionMixin {

    @Inject(method = "affectWorld(Z)V", at = @At("HEAD"))
    private void coreprotect$explosionStart(boolean particles, CallbackInfo ci) {
        NaturalBreakCause.set(causeName());
    }

    @Inject(method = "affectWorld(Z)V", at = @At("TAIL"))
    private void coreprotect$explosionEnd(boolean particles, CallbackInfo ci) {
        NaturalBreakCause.clear(causeName());
    }

    private String causeName() {
        Entity entity = ((Explosion) (Object) this).getEntity();
        if (entity instanceof CreeperEntity) return "#creeper";
        if (entity instanceof TntEntity) return "#tnt";
        if (entity instanceof WitherEntity || entity instanceof WitherSkullEntity) return "#wither";
        if (entity instanceof EndCrystalEntity) return "#end_crystal";
        return "#explosion";
    }
}
