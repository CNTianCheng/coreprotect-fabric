package net.coreprotect.fabric.container;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.coreprotect.fabric.CoreProtectFabric;
import net.coreprotect.fabric.database.DatabaseManager;
import net.coreprotect.fabric.util.ItemStackCodec;
import net.coreprotect.fabric.util.TimeUtil;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.DispenserBlockEntity;
import net.minecraft.block.entity.DropperBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryWrapper;
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

    /** A container that was not observed for this long is dropped when the snapshot map has to be pruned. */
    private static final long STALE_SECONDS = 120L;
    private static final int MAX_SNAPSHOTS = 20000;

    private static final class Snapshot {
        final Map<String, Tally> counts;
        final long time;
        /** Identity of the observed container; a different instance means a chunk reload or a replaced block. */
        final Object container;

        Snapshot(Map<String, Tally> counts, long time, Object container) {
            this.counts = counts;
            this.time = time;
            this.container = container;
        }
    }

    /**
     * One snapshot entry: a representative stack (its data components decide whether two stacks of
     * the same item are interchangeable) and the summed count of everything matching it.
     */
    private record Tally(ItemStack sample, int count) {
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

        Map<String, Tally> after = snapshot(world, dispenser);
        Snapshot previous = snapshots.put(key(wid, pos), new Snapshot(after, time, dispenser));
        prune(time);
        // first observation, or a different container instance at this position (chunk
        // reload, replaced block): establish a fresh baseline instead of diffing against
        // the contents of another container
        if (previous == null || previous.container != dispenser) return;
        // while a player has this container open the player watch owns the diff, so only
        // the baseline is kept up to date here
        if (mod.containerTracker().watchedAt(world, pos)) return;
        Map<String, Tally> before = previous.counts;
        boolean logged = false;
        for (Map.Entry<String, Tally> entry : after.entrySet()) {
            String id = entry.getKey();
            Tally now = entry.getValue();
            Tally old = before.get(id);
            int delta = now.count() - (old == null ? 0 : old.count());
            if (delta == 0) continue;
            logged = true;
            mod.database().insertContainerAsync(new DatabaseManager.ContainerLog(
                    0, time, user, wid,
                    pos.getX(), pos.getY(), pos.getZ(),
                    delta > 0 ? DatabaseManager.CONTAINER_DEPOSIT : DatabaseManager.CONTAINER_WITHDRAW,
                    Registries.ITEM.getId(now.sample().getItem()).toString(), Math.abs(delta),
                    ItemStackCodec.encodeIdentity(now.sample(), world.getRegistryManager())));
        }
        for (Map.Entry<String, Tally> entry : before.entrySet()) {
            if (after.containsKey(entry.getKey())) continue;
            logged = true;
            Tally old = entry.getValue();
            mod.database().insertContainerAsync(new DatabaseManager.ContainerLog(
                    0, time, user, wid,
                    pos.getX(), pos.getY(), pos.getZ(),
                    DatabaseManager.CONTAINER_WITHDRAW,
                    Registries.ITEM.getId(old.sample().getItem()).toString(), old.count(),
                    ItemStackCodec.encodeIdentity(old.sample(), world.getRegistryManager())));
        }
        // the dispenser moved items here: a player watching this container must not be
        // blamed for them when the screen closes
        if (logged) ContainerTracker.rebaseline(world, pos);
    }

    /**
     * Keeps the snapshot map bounded. Stale entries go first; if the map is still
     * too large the oldest baselines are dropped, so one busy period no longer
     * discards the baseline of every other container.
     */
    private void prune(long time) {
        if (snapshots.size() <= MAX_SNAPSHOTS) return;
        snapshots.entrySet().removeIf(entry -> time - entry.getValue().time > STALE_SECONDS);
        int target = MAX_SNAPSHOTS - MAX_SNAPSHOTS / 10;
        if (snapshots.size() <= target) return;
        List<Map.Entry<String, Snapshot>> entries = new ArrayList<>(snapshots.entrySet());
        entries.sort((first, second) -> Long.compare(first.getValue().time, second.getValue().time));
        for (int i = 0; i < entries.size() - target; i++) {
            snapshots.remove(entries.get(i).getKey());
        }
    }

    /**
     * Snapshots a dispenser/dropper. Two stacks of the same item count as one entry only when they
     * carry the same data components, so dispensing an enchanted item stays restorable.
     */
    private static Map<String, Tally> snapshot(ServerWorld world, DispenserBlockEntity dispenser) {
        Map<String, Tally> map = new HashMap<>();
        RegistryWrapper.WrapperLookup registries = world.getRegistryManager();
        for (int i = 0; i < dispenser.size(); i++) {
            ItemStack stack = dispenser.getStack(i);
            if (stack.isEmpty()) continue;
            String id = ItemStackCodec.encodeIdentity(stack, registries);
            if (id == null) {
                // a stack whose components cannot be encoded is still tracked, just without them
                id = Registries.ITEM.getId(stack.getItem()).toString();
            }
            Tally tally = map.get(id);
            if (tally == null) {
                map.put(id, new Tally(stack.copyWithCount(1), stack.getCount()));
            } else {
                map.put(id, new Tally(tally.sample(), tally.count() + stack.getCount()));
            }
        }
        return map;
    }
}
