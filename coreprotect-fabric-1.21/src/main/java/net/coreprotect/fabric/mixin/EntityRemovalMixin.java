package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabric;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records the contents of a container entity when the entity is killed (L3-16).
 *
 * <p>A chest minecart, hopper minecart, chest boat, donkey chest or item frame takes its items
 * with it when it is destroyed, and no Fabric event reports the contents. The item frame is
 * destroyed by a player hit, the vehicles by damage or a direct {@code /kill}; the last player
 * that hit the entity is remembered through {@code CoreProtectFabric#noteEntityAttacker} so the
 * rows name the killer, and {@code #entity} is used for every other removal.</p>
 *
 * <p>Only {@code RemovalReason#KILLED} is logged: a chunk unload ({@code UNLOADED_TO_CHUNK}),
 * a dimension change and the discard of a fully picked-up item stack
 * ({@code DISCARDED}, already recorded by the pickup hook) must not produce rows.</p>
 */
@Mixin(Entity.class)
public abstract class EntityRemovalMixin {

    @Inject(method = "remove(Lnet/minecraft/entity/Entity$RemovalReason;)V", at = @At("HEAD"))
    private void coreprotect$onRemove(Entity.RemovalReason reason, CallbackInfo ci) {
        if (reason != Entity.RemovalReason.KILLED) return;
        CoreProtectFabric.hookEntityRemoval((Entity) (Object) this);
    }
}
