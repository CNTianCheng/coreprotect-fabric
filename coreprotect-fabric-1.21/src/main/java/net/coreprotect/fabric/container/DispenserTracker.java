package net.coreprotect.fabric.container;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.coreprotect.fabric.CoreProtectFabric;
import net.coreprotect.fabric.util.TimeUtil;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.DispenserBlockEntity;
import net.minecraft.block.entity.DropperBlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * CoreProtect-style {@code #dropper} / {@code #dispenser} logging: the 9-slot
 * inventory is diffed against the previous snapshot at the start of each
 * scheduled tick (catches deposits and previous dispenses) and again right
 * after a dispense (catches one-shot activations). Both blocks are covered
 * because {@code DropperBlock} inherits {@code DispenserBlock#scheduledTick}.
 *
 * <p>The cheap contents fingerprint ({@link ContainerTracker#digest}) is
 * compared before any full snapshot is taken, so a scheduled tick that changes
 * nothing costs no item encoding. The post-dispense observation is deliberately
 * not capped per tick (unlike {@link HopperTracker}): a one-shot activation may
 * never be followed by another scheduled tick in time, so deferring it would
 * turn a logged dispense into an unlogged one.
 */
public final class DispenserTracker {
    /** Per-container baseline, keyed by dimension + position so worlds never mix. */
    private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();

    /** A container that was not observed for this long is dropped when the snapshot map has to be pruned. */
    private static final long STALE_SECONDS = 120L;
    private static final int MAX_SNAPSHOTS = 20000;

    /**
     * The state a container was last seen in. Mutable on purpose: an unchanged observation only
     * refreshes {@link #time}, so the common case allocates nothing.
     */
    private static final class Snapshot {
        /** Aggregated contents the next diff is computed against. */
        Map<String, ContainerTracker.Tally> counts;
        /** Fingerprint of {@link #counts}; see {@link ContainerTracker#digest}. */
        long digest;
        /** Wall clock time of the last observation, used by {@link #prune}. */
        long time;
        /** Identity of the observed container; a different instance means a chunk reload or a replaced block. */
        final Object container;

        Snapshot(Map<String, ContainerTracker.Tally> counts, long digest, long time, Object container) {
            this.counts = counts;
            this.digest = digest;
            this.time = time;
            this.container = container;
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
        long digest = ContainerTracker.digest(dispenser);

        String key = key(wid, pos);
        Snapshot previous = snapshots.get(key);
        if (previous == null || previous.container != dispenser) {
            // first observation, or a different container instance at this position (chunk
            // reload, replaced block): establish a fresh baseline instead of diffing against
            // the contents of another container
            snapshots.put(key, new Snapshot(ContainerTracker.snapshot(world, dispenser), digest, time, dispenser));
            prune(time);
            return;
        }
        previous.time = time;
        // cheap equality first: the scheduled tick that changed nothing needs no snapshot at all
        if (previous.digest == digest) return;
        Map<String, ContainerTracker.Tally> before = previous.counts;
        Map<String, ContainerTracker.Tally> after = ContainerTracker.snapshot(world, dispenser);
        previous.counts = after;
        previous.digest = digest;
        // while a player has this container open the player watch owns the diff, so only
        // the baseline is kept up to date here
        if (mod.containerTracker().watchedAt(world, pos)) return;
        boolean logged = ContainerTracker.logDiff(mod, user, world, wid, pos, before, after, time);
        // the dispenser moved items here: a player watching this container must not be
        // blamed for them when the screen closes
        if (logged) ContainerTracker.rebaseline(world, pos);
    }

    /**
     * Drops the baseline of a container that is being removed, so a later observation of that
     * position establishes a fresh baseline instead of diffing against contents that are gone.
     */
    void forget(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) return;
        String wid = world.getRegistryKey().getValue().toString();
        snapshots.remove(key(wid, ContainerTracker.canonicalContainerPos(world, pos)));
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
}
