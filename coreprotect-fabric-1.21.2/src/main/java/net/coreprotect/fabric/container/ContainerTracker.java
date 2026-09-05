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
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * Tracks container interactions CoreProtect-style: when a player opens a
 * container, its contents are snapshotted; when the screen closes (or the
 * player leaves/dies/opens another container), the diff is logged as one row
 * per changed item type.
 */
public final class ContainerTracker {
    private final Map<UUID, Watch> watches = new ConcurrentHashMap<>();

    private record Watch(String userName, ServerWorld world, BlockPos pos, Map<String, Integer> before) {
    }

    public void onPlayerOpened(ServerPlayerEntity player, ServerWorld world, BlockPos pos) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null || !mod.config().logging.container) return;
        finalizeWatch(player);
        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof Inventory inv) {
            watches.put(player.getUuid(), new Watch(player.getGameProfile().getName(), world, pos, snapshot(inv)));
        }
    }

    public void onScreenClosed(ServerPlayerEntity player) {
        finalizeWatch(player);
    }

    public void onDisconnect(ServerPlayerEntity player) {
        finalizeWatch(player);
    }

    public void onDeath(ServerPlayerEntity player) {
        finalizeWatch(player);
    }

    public void finalizeAll() {
        for (Watch w : new ArrayList<>(watches.values())) {
            diffAndLog(w);
        }
        watches.clear();
    }

    private void finalizeWatch(ServerPlayerEntity player) {
        Watch w = watches.remove(player.getUuid());
        if (w != null) {
            diffAndLog(w);
        }
    }

    private void diffAndLog(Watch w) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null) return;
        BlockEntity be = w.world().getBlockEntity(w.pos());
        if (!(be instanceof Inventory inv)) return;
        Map<String, Integer> after = snapshot(inv);
        Set<String> keys = new HashSet<>();
        keys.addAll(w.before().keySet());
        keys.addAll(after.keySet());
        long time = TimeUtil.now();
        String wid = w.world().getRegistryKey().getValue().toString();
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

    private static Map<String, Integer> snapshot(Inventory inv) {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            String id = Registries.ITEM.getId(stack.getItem()).toString();
            map.merge(id, stack.getCount(), Integer::sum);
        }
        return map;
    }
}
