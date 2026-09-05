package net.coreprotect.fabric.event;

import net.coreprotect.fabric.CoreProtectFabric;
import net.coreprotect.fabric.database.DatabaseManager;
import net.coreprotect.fabric.util.TimeUtil;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * Logs entity kills and player deaths (CoreProtect co_entity). Only events
 * involving a player are recorded.
 */
public final class EntityEventListener {

    private EntityEventListener() {
    }

    public static void register() {
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            CoreProtectFabric mod = CoreProtectFabric.instance();
            if (mod == null || !mod.config().logging.entity) return;
            if (!(entity.getEntityWorld() instanceof ServerWorld world)) return;

            Entity attacker = damageSource.getAttacker();
            String user = null;
            String data = null;
            String action = null;

            if (entity instanceof ServerPlayerEntity victim) {
                mod.containerTracker().onDeath(victim);
                user = victim.getGameProfile().name();
                if (attacker instanceof ServerPlayerEntity ap) {
                    data = ap.getGameProfile().name();
                } else if (attacker instanceof LivingEntity le) {
                    data = Registries.ENTITY_TYPE.getId(le.getType()).toString();
                } else {
                    data = damageSource.getName();
                }
                action = "death";
            } else if (attacker instanceof ServerPlayerEntity ap) {
                user = ap.getGameProfile().name();
                data = Registries.ENTITY_TYPE.getId(entity.getType()).toString();
                action = "kill";
            }

            if (user == null) return;
            BlockPos pos = entity.getBlockPos();
            mod.database().insertEntityAsync(new DatabaseManager.EntityLog(
                    0, TimeUtil.now(), user,
                    world.getRegistryKey().getValue().toString(),
                    pos.getX(), pos.getY(), pos.getZ(), data, action));
        });
    }
}
