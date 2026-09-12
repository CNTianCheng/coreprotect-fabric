package net.coreprotect.fabric.event;

import net.coreprotect.fabric.CoreProtectFabric;
import net.coreprotect.fabric.database.DatabaseManager;
import net.coreprotect.fabric.util.TimeUtil;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;

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
            if (!(entity.level() instanceof ServerLevel world)) return;

            Entity attacker = damageSource.getEntity();
            String user = null;
            String data = null;
            String action = null;

            if (entity instanceof ServerPlayer victim) {
                mod.containerTracker().onDeath(victim);
                user = victim.getGameProfile().name();
                if (attacker instanceof ServerPlayer ap) {
                    data = ap.getGameProfile().name();
                } else if (attacker instanceof LivingEntity le) {
                    data = BuiltInRegistries.ENTITY_TYPE.getKey(le.getType()).toString();
                } else {
                    data = damageSource.getMsgId();
                }
                action = "death";
            } else if (attacker instanceof ServerPlayer ap) {
                user = ap.getGameProfile().name();
                data = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
                action = "kill";
            }

            if (user == null) return;
            BlockPos pos = entity.blockPosition();
            mod.database().insertEntityAsync(new DatabaseManager.EntityLog(
                    0, TimeUtil.now(), user,
                    world.dimension().identifier().toString(),
                    pos.getX(), pos.getY(), pos.getZ(), data, action));
        });
    }
}
