package net.coreprotect.fabric.container;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.coreprotect.fabric.CoreProtectFabric;
import net.coreprotect.fabric.database.DatabaseManager;
import net.coreprotect.fabric.util.TimeUtil;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;

/**
 * Tracks container interactions CoreProtect-style: when a player opens a
 * container, its contents are snapshotted; when the screen closes (or the
 * player leaves/dies/opens another container), the diff is logged as one row
 * per changed item type.
 */
public final class ContainerTracker {
    private final Map<UUID, Watch> watches = new ConcurrentHashMap<>();

    private record Watch(String userName, ServerLevel world, BlockPos pos, Map<String, Integer> before) {
    }

    public void onPlayerOpened(ServerPlayer player, ServerLevel world, BlockPos pos) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null || !mod.config().logging.container) return;
        finalizeWatch(player);
        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof Container inv) {
            watches.put(player.getUUID(), new Watch(player.getGameProfile().name(), world, pos, snapshot(inv)));
        }
    }

    public void onScreenClosed(ServerPlayer player) {
        finalizeWatch(player);
    }

    public void onDisconnect(ServerPlayer player) {
        finalizeWatch(player);
    }

    public void onDeath(ServerPlayer player) {
        finalizeWatch(player);
    }

    public void finalizeAll() {
        for (Watch w : new ArrayList<>(watches.values())) {
            diffAndLog(w);
        }
        watches.clear();
    }

    private void finalizeWatch(ServerPlayer player) {
        Watch w = watches.remove(player.getUUID());
        if (w != null) {
            diffAndLog(w);
        }
    }

    private void diffAndLog(Watch w) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null) return;
        BlockEntity be = w.world().getBlockEntity(w.pos());
        if (!(be instanceof Container inv)) return;
        Map<String, Integer> after = snapshot(inv);
        Set<String> keys = new HashSet<>();
        keys.addAll(w.before().keySet());
        keys.addAll(after.keySet());
        long time = TimeUtil.now();
        String wid = w.world().dimension().identifier().toString();
        for (String id : keys) {
            int delta = after.getOrDefault(id, 0) - w.before().getOrDefault(id, 0);
            if (delta == 0) continue;
            mod.database().insertContainerAsync(new DatabaseManager.ContainerLog(
                    0, time, w.userName(), wid,
                    w.pos().getX(), w.pos().getY(), w.pos().getZ(),
                    delta > 0 ? DatabaseManager.CONTAINER_DEPOSIT : DatabaseManager.CONTAINER_WITHDRAW,
                    id, Math.abs(delta)));
        }
    }

    private static Map<String, Integer> snapshot(Container inv) {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            map.merge(id, stack.getCount(), Integer::sum);
        }
        return map;
    }
}
