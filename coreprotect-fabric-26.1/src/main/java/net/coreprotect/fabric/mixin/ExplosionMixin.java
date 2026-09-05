package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.util.NaturalBreakCause;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Attributes blocks destroyed by explosions to the correct cause (#creeper, #tnt, ...). */
@Mixin(ServerExplosion.class)
public abstract class ExplosionMixin {

    @Inject(method = "explode()I", at = @At("HEAD"))
    private void coreprotect$explosionStart(CallbackInfoReturnable<Integer> cir) {
        NaturalBreakCause.set(causeName());
    }

    @Inject(method = "explode()I", at = @At("TAIL"))
    private void coreprotect$explosionEnd(CallbackInfoReturnable<Integer> cir) {
        NaturalBreakCause.clear(causeName());
    }

    private String causeName() {
        Entity entity = ((ServerExplosion) (Object) this).getDirectSourceEntity();
        if (entity instanceof Creeper) return "#creeper";
        if (entity instanceof PrimedTnt) return "#tnt";
        if (entity instanceof WitherBoss || entity instanceof WitherSkull) return "#wither";
        if (entity instanceof EndCrystal) return "#end_crystal";
        return "#explosion";
    }
}
