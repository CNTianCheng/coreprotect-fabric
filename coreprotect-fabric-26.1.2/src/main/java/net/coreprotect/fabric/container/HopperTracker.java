package net.coreprotect.fabric.container;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.coreprotect.fabric.CoreProtectFabric;
import net.coreprotect.fabric.util.TimeUtil;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.Container;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

/**
 * CoreProtect-style {@code #hopper} logging: on every hopper tick the hopper's
 * own inventory, the container above it (source) and the container it faces
 * (target) are diffed against the previous snapshot; changes are logged as
 * user {@code #hopper} at the affected container's position.
 *
 * <p>A double chest is one 54 slot container: the baseline is keyed by the
 * canonical half ({@link ContainerTracker#canonicalContainerPos}) and rows are
 * written at that position, so two hoppers touching the two halves share one
 * baseline and a change is recorded once instead of twice.
 *
 * <p>Cost per tick is kept low: a cheap contents fingerprint
 * ({@link ContainerTracker#digest}) is compared first and the full snapshot -
 * which encodes every stack as SNBT - is only taken when the contents really
 * changed. A container that changed is deep processed at most once per tick;
 * a second change within the same tick is not dropped, only recorded by the
 * next observation, which diffs against the baseline that was left behind.
 */
public final class HopperTracker {
    /** User recorded for container changes caused by a hopper. */
    private static final String USER = "#hopper";

    /** Per-container baseline, keyed by dimension + canonical position so worlds never mix. */
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
        /** Server tick of the last deep pass; {@code Long.MIN_VALUE} when none happened yet. */
        long deepTick = Long.MIN_VALUE;
        /** Identity of the observed container; a different instance means a chunk reload or a replaced block. */
        final Object container;

        Snapshot(Map<String, ContainerTracker.Tally> counts, long digest, long time, Object container, long deepTick) {
            this.counts = counts;
            this.digest = digest;
            this.time = time;
            this.container = container;
            this.deepTick = deepTick;
        }
    }

    private static String key(String wid, BlockPos pos) {
        return wid + "|" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    public void onHopperTick(Level world, BlockPos pos, BlockState state, HopperBlockEntity blockEntity) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null || !mod.config().logging.hopper) return;
        if (!(world instanceof ServerLevel serverWorld)) return;
        long time = TimeUtil.now();
        long tick = serverWorld.getGameTime();
        String wid = world.dimension().identifier().toString();

        // the hopper's own inventory; while a player has this container open the
        // player watch owns this container, so only the baseline is kept up to date
        boolean watched = mod.containerTracker().watchedAt(serverWorld, pos);
        diffAndLog(mod, serverWorld, wid, pos, blockEntity, time, tick, watched);
        // source container above the hopper
        Container input = HopperBlockEntity.getContainerAt(world, pos.above());
        if (input != null && !trackedSeparately(input)) {
            diffAndLog(mod, serverWorld, wid, pos.above(), input, time, tick, false);
        }
        // target container at the hopper's facing
        Direction facing = state.getValue(HopperBlock.FACING);
        Container output = HopperBlockEntity.getContainerAt(world, pos.relative(facing));
        if (output != null && !trackedSeparately(output)) {
            diffAndLog(mod, serverWorld, wid, pos.relative(facing), output, time, tick, false);
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
            long time, long tick, boolean suppressLog) {
        // a double chest is one container: key and log it at the canonical half, otherwise each
        // hopper keeps its own baseline and every transfer is written twice
        BlockPos canonical = ContainerTracker.canonicalContainerPos(world, pos);
        // The merged inventory is read from its canonical half as well: the same double chest seen
        // through the other half hands out the two 27 slot halves in the opposite order, which
        // would make the fingerprint oscillate and force a deep pass on every single tick.
        Container observed = ContainerTracker.containerAt(world, canonical);
        if (observed == null) observed = inv;
        String key = key(wid, canonical);
        Object container = world.getBlockEntity(canonical);
        // inventories without a block entity (entities, provider wrappers) are identified by type
        if (container == null) container = observed.getClass();
        long digest = ContainerTracker.digest(observed);

        Snapshot snapshot = snapshots.get(key);
        if (snapshot == null || snapshot.container != container) {
            // first observation, or a different container instance at this position (chunk
            // reload, replaced block): establish a fresh baseline instead of diffing against
            // the contents of another container
            snapshot = new Snapshot(ContainerTracker.snapshot(world, observed), digest, time, container, tick);
            snapshots.put(key, snapshot);
            prune(time);
            return;
        }
        snapshot.time = time;
        // cheap equality first: unchanged contents need no snapshot and no SNBT encoding at all
        if (snapshot.digest == digest) return;
        // at most one deep pass per container and tick. A change that happened after that pass is
        // not dropped: the baseline is left behind, so the next observation of this container
        // records the cumulative difference.
        if (snapshot.deepTick == tick) return;
        Map<String, ContainerTracker.Tally> after = ContainerTracker.snapshot(world, observed);
        snapshot.deepTick = tick;
        if (suppressLog) {
            // a player has this container open: the player watch owns the row, so only the
            // baseline is kept up to date here
            snapshot.counts = after;
            snapshot.digest = digest;
            return;
        }
        boolean logged = ContainerTracker.logDiff(mod, USER, world, wid, canonical, snapshot.counts, after, time);
        snapshot.counts = after;
        snapshot.digest = digest;
        // the hopper moved items here: a player watching this container must not be
        // blamed for them when the screen closes
        if (logged) ContainerTracker.rebaseline(world, canonical);
    }

    /**
     * Drops the baseline of a container that is being removed, so a later observation of that
     * position establishes a fresh baseline instead of diffing against contents that are gone.
     */
    void forget(ServerLevel world, BlockPos pos) {
        if (world == null || pos == null) return;
        String wid = world.dimension().identifier().toString();
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
