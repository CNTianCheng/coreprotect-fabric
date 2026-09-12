package net.coreprotect.fabric.container;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.coreprotect.fabric.CoreProtectFabric;
import net.coreprotect.fabric.database.DatabaseManager;
import net.coreprotect.fabric.util.TimeUtil;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.DispenserBlockEntity;
import net.minecraft.block.entity.DropperBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * CoreProtect-style {@code #dropper} / {@code #dispenser} logging: the 9-slot
 * inventory is diffed against the previous snapshot at the start of each
 * scheduled tick (catches deposits and previous dispenses) and again right
 * after a dispense (catches one-shot activations). Both blocks are covered
 * because {@code DropperBlock} inherits {@code DispenserBlock#scheduledTick}.
 */
public final class DispenserTracker {
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

    /** Called at the start of {@code DispenserBlock#scheduledTick} (both block types). */
    public void onScheduledTick(ServerWorld world, BlockPos pos) {
        observe(world, pos);
    }

    /** Called right after a dispense (both block types). */
    public void onDispensed(ServerWorld world, BlockPos pos) {
        observe(world, pos);
    }

    private void observe(ServerWorld world, BlockPos pos) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null || !mod.config().logging.dispenser) return;
        BlockEntity be = world.getBlockEntity(pos);
        if (!(be instanceof DispenserBlockEntity dispenser)) return;
        String user = be instanceof DropperBlockEntity ? "#dropper" : "#dispenser";
        long time = TimeUtil.now();
        String wid = world.getRegistryKey().getValue().toString();

        Map<String, Integer> after = snapshot(dispenser);
        Snapshot previous = snapshots.put(key(wid, pos), new Snapshot(after, time));
        if (snapshots.size() > MAX_SNAPSHOTS) snapshots.clear(); // bounded growth for long uptimes
        // first observation, or the container ticked again after a long pause (chunk reload):
        // establish a fresh baseline instead of diffing against a stale one
        if (previous == null || time - previous.time > STALE_SECONDS) return;
        Map<String, Integer> before = previous.counts;
        for (Map.Entry<String, Integer> entry : after.entrySet()) {
            String id = entry.getKey();
            int delta = entry.getValue() - before.getOrDefault(id, 0);
            if (delta == 0) continue;
            mod.database().insertContainerAsync(new DatabaseManager.ContainerLog(
                    0, time, user, wid,
                    pos.getX(), pos.getY(), pos.getZ(),
                    delta > 0 ? DatabaseManager.CONTAINER_DEPOSIT : DatabaseManager.CONTAINER_WITHDRAW,
                    id, Math.abs(delta)));
        }
        for (String id : before.keySet()) {
            if (after.containsKey(id)) continue;
            mod.database().insertContainerAsync(new DatabaseManager.ContainerLog(
                    0, time, user, wid,
                    pos.getX(), pos.getY(), pos.getZ(),
                    DatabaseManager.CONTAINER_WITHDRAW, id, before.get(id)));
        }
    }

    private static Map<String, Integer> snapshot(DispenserBlockEntity dispenser) {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < dispenser.size(); i++) {
            ItemStack stack = dispenser.getStack(i);
            if (stack.isEmpty()) continue;
            String id = Registries.ITEM.getId(stack.getItem()).toString();
            map.merge(id, stack.getCount(), Integer::sum);
        }
        return map;
    }
}
