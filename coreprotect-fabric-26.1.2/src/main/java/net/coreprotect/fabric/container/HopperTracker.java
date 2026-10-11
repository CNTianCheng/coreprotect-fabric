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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

/**
 * CoreProtect-style {@code #hopper} logging: on every hopper tick the hopper's
 * own inventory, the container above it (source) and the container it faces
 * (target) are diffed against the previous snapshot; changes are logged as
 * user {@code #hopper} at the affected container's position.
 */
public final class HopperTracker {
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

    public void onHopperTick(Level world, BlockPos pos, BlockState state, HopperBlockEntity blockEntity) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null || !mod.config().logging.hopper) return;
        if (!(world instanceof ServerLevel sw)) return;
        long time = TimeUtil.now();
        String wid = world.dimension().identifier().toString();

        // the hopper's own inventory; while a player has this hopper's screen open the
        // player watch owns this container, so only the baseline is kept up to date
        boolean watched = mod.containerTracker().watchedAt(sw, pos);
        diffAndLog(mod, sw, wid, pos, blockEntity, time, watched);
        // source container above the hopper
        Container input = HopperBlockEntity.getContainerAt(world, pos.above());
        if (input != null && !trackedSeparately(input)) {
            diffAndLog(mod, sw, wid, pos.above(), input, time, false);
        }
        // target container at the hopper's facing
        Direction facing = state.getValue(HopperBlock.FACING);
        Container output = HopperBlockEntity.getContainerAt(world, pos.relative(facing));
        if (output != null && !trackedSeparately(output)) {
            diffAndLog(mod, sw, wid, pos.relative(facing), output, time, false);
        }
    }

    /**
     * Hoppers, dispensers and droppers log their own contents, so diffing them
     * here as well would log every transfer twice.
     *
     * @return whether this container is diffed by its own tracker
     */
    private static boolean trackedSeparately(Container inv) {
        return inv instanceof HopperBlockEntity || inv instanceof DispenserBlockEntity;
    }

    private void diffAndLog(CoreProtectFabric mod, ServerLevel world, String wid, BlockPos pos, Container inv,
            long time, boolean suppressLog) {
        Map<String, Tally> after = snapshot(world, inv);
        Object container = world.getBlockEntity(pos);
        // inventories without a block entity (entities, provider wrappers) are identified by type
        if (container == null) container = inv.getClass();
        Snapshot previous = snapshots.put(key(wid, pos), new Snapshot(after, time, container));
        prune(time);
        // first observation, or a different container instance at this position (chunk
        // reload, replaced block): establish a fresh baseline instead of diffing against
        // the contents of another container
        if (previous == null || previous.container != container) return;
        if (suppressLog) return;
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
                    0, time, "#hopper", wid,
                    pos.getX(), pos.getY(), pos.getZ(),
                    delta > 0 ? DatabaseManager.CONTAINER_DEPOSIT : DatabaseManager.CONTAINER_WITHDRAW,
                    BuiltInRegistries.ITEM.getKey(now.sample().getItem()).toString(), Math.abs(delta),
                    ItemStackCodec.encodeIdentity(now.sample(), world.registryAccess())));
        }
        for (Map.Entry<String, Tally> entry : before.entrySet()) {
            if (after.containsKey(entry.getKey())) continue;
            logged = true;
            Tally old = entry.getValue();
            mod.database().insertContainerAsync(new DatabaseManager.ContainerLog(
                    0, time, "#hopper", wid,
                    pos.getX(), pos.getY(), pos.getZ(),
                    DatabaseManager.CONTAINER_WITHDRAW,
                    BuiltInRegistries.ITEM.getKey(old.sample().getItem()).toString(), old.count(),
                    ItemStackCodec.encodeIdentity(old.sample(), world.registryAccess())));
        }
        // the hopper moved items here: a player watching this container must not be
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
     * Snapshots a container. Two stacks of the same item count as one entry only when they carry the
     * same data components, so a hopper moving an enchanted item is logged and restorable as one.
     */
    private static Map<String, Tally> snapshot(ServerLevel world, Container inv) {
        Map<String, Tally> map = new HashMap<>();
        HolderLookup.Provider registries = world.registryAccess();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            String id = ItemStackCodec.encodeIdentity(stack, registries);
            if (id == null) {
                // a stack whose components cannot be encoded is still tracked, just without them
                id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
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
