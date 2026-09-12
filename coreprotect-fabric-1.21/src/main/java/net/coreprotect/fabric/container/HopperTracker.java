package net.coreprotect.fabric.container;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.coreprotect.fabric.CoreProtectFabric;
import net.coreprotect.fabric.database.DatabaseManager;
import net.coreprotect.fabric.util.TimeUtil;
import net.minecraft.block.BlockState;
import net.minecraft.block.HopperBlock;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

/**
 * CoreProtect-style {@code #hopper} logging: on every hopper tick the hopper's
 * own inventory, the container above it (source) and the container it faces
 * (target) are diffed against the previous snapshot; changes are logged as
 * user {@code #hopper} at the affected container's position.
 */
public final class HopperTracker {
        /** Per-container baseline, keyed by dimension + position so worlds never mix. */
    private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();

    /** A container that was not observed for this long is treated as a fresh baseline. */
    private static final long STALE_SECONDS = 120L;
    private static final int MAX_SNAPSHOTS = 20000;

    private static final class Snapshot {
        final Map<String, Integer> counts;
        final long time;

        Snapshot(Map<String, Integer> counts, long time) {
            this.counts = counts;
            this.time = time;
        }
    }

    private static String key(String wid, BlockPos pos) {
        return wid + "|" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    public void onHopperTick(World world, BlockPos pos, BlockState state, HopperBlockEntity blockEntity) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null || !mod.config().logging.hopper) return;
        if (!(world instanceof ServerWorld sw)) return;
        long time = TimeUtil.now();
        String wid = world.getRegistryKey().getValue().toString();

        // the hopper's own inventory
        diffAndLog(mod, sw, wid, pos, blockEntity, time);
        // source container above the hopper
        Inventory input = HopperBlockEntity.getInventoryAt(world, pos.up());
        if (input != null && !(input instanceof HopperBlockEntity)) {
            diffAndLog(mod, sw, wid, pos.up(), input, time);
        }
        // target container at the hopper's facing
        Direction facing = state.get(HopperBlock.FACING);
        Inventory output = HopperBlockEntity.getInventoryAt(world, pos.offset(facing));
        if (output != null && !(output instanceof HopperBlockEntity)) {
            diffAndLog(mod, sw, wid, pos.offset(facing), output, time);
        }
    }

    private void diffAndLog(CoreProtectFabric mod, ServerWorld world, String wid, BlockPos pos, Inventory inv, long time) {
        Map<String, Integer> after = snapshot(inv);
        String key = key(wid, pos);
        Snapshot previous = snapshots.put(key, new Snapshot(after, time));
        if (snapshots.size() > MAX_SNAPSHOTS) snapshots.clear(); // bounded growth for long uptimes
        // first observation, or the container was unloaded/ticking again after a long pause:
        // only establish a new baseline, never diff against a stale one
        if (previous == null || time - previous.time > STALE_SECONDS) return;
        Map<String, Integer> before = previous.counts;
        for (Map.Entry<String, Integer> entry : after.entrySet()) {
            String id = entry.getKey();
            int delta = entry.getValue() - before.getOrDefault(id, 0);
            if (delta == 0) continue;
            mod.database().insertContainerAsync(new DatabaseManager.ContainerLog(
                    0, time, "#hopper", wid,
                    pos.getX(), pos.getY(), pos.getZ(),
                    delta > 0 ? DatabaseManager.CONTAINER_DEPOSIT : DatabaseManager.CONTAINER_WITHDRAW,
                    id, Math.abs(delta)));
        }
        for (String id : before.keySet()) {
            if (after.containsKey(id)) continue;
            mod.database().insertContainerAsync(new DatabaseManager.ContainerLog(
                    0, time, "#hopper", wid,
                    pos.getX(), pos.getY(), pos.getZ(),
                    DatabaseManager.CONTAINER_WITHDRAW, id, before.get(id)));
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
