package net.coreprotect.fabric.container;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.coreprotect.fabric.CoreProtectFabric;
import net.coreprotect.fabric.database.DatabaseManager;
import net.coreprotect.fabric.util.ItemStackCodec;
import net.coreprotect.fabric.util.TimeUtil;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.DoubleInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Tracks container interactions CoreProtect-style: when a player opens a
 * container, its contents are snapshotted; when the screen closes (or the
 * player leaves/dies/opens another container), the diff is logged as one row
 * per changed item type.
 *
 * <p>A double chest is tracked as one 54 slot container: the snapshot merges
 * both halves, so items moved into the half that was not clicked are recorded
 * as well. Rows are always written at the canonical half of that merged
 * container, so {@link #containerAt} resolves a row's position back to the same
 * 54 slot inventory the change was observed on.
 *
 * <p>A change that no player can be blamed for - the container was removed while
 * its screen was open, two players shared one container, or contents vanished
 * with the block - is recorded under the {@value #ANONYMOUS_USER} name instead
 * of the player who happened to have the screen open.
 */
public final class ContainerTracker {
    /** User recorded for changes that cannot be attributed to a player. */
    private static final String ANONYMOUS_USER = "#container";

    /** The horizontal neighbours a chest half can be merged with. */
    private static final Direction[] HORIZONTALS = {
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    /** Bound for the "already captured in this tick" map, so a busy world cannot grow it forever. */
    private static final int MAX_CAPTURES = 4096;

    private final Map<UUID, Watch> watches = new ConcurrentHashMap<>();

    /**
     * Containers already captured before removal, so break + explosion (or both halves of a
     * double chest) report one container only once per tick.
     */
    private final Map<String, Capture> captures = new ConcurrentHashMap<>();

    /**
     * An open container. {@code digest} is the cheap contents fingerprint the snapshot was taken
     * at, so closing an untouched container needs no comparison or item encoding at all.
     */
    private record Watch(String userName, ServerWorld world, BlockPos pos, Map<String, Tally> before,
            long digest) {
    }

    private record Capture(Object container, long tick) {
    }

    /**
     * One snapshot entry: a representative stack (its data components decide whether two stacks of
     * the same item are interchangeable) and the summed count of everything matching it.
     */
    record Tally(ItemStack sample, int count) {
    }

    public void onPlayerOpened(ServerPlayerEntity player, ServerWorld world, BlockPos pos) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (player == null) return;
        if (mod == null || !mod.config().logging.container) return;
        if (world == null || pos == null) return;
        finalizeWatch(player);
        // a double chest is one container: store and log it at the canonical half so a row always
        // resolves back to the merged inventory
        BlockPos canonical = canonicalContainerPos(world, pos);
        Inventory inv = containerAt(world, canonical);
        if (inv != null) {
            watches.put(player.getUuid(), new Watch(player.getGameProfile().getName(), world, canonical,
                    snapshot(world, inv), digest(inv)));
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
        for (UUID uuid : new ArrayList<>(watches.keySet())) {
            settle(uuid);
        }
        watches.clear();
    }

    /**
     * Refreshes the baseline snapshot ("before") of every watch covering
     * {@code pos} to the container's current contents, so that a change made
     * internally - a rollback restoring items, or a hopper/dispenser moving
     * them - is not attributed to the player who has the container open.
     *
     * <p>Does nothing when no player is watching that container, and never
     * throws: it may be called from the server thread as well as from async
     * threads.
     */
    public static void rebaseline(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) return;
        try {
            CoreProtectFabric mod = CoreProtectFabric.instance();
            if (mod == null) return;
            mod.containerTracker().rebaselineAt(world, pos);
        } catch (Exception e) {
            CoreProtectFabric.LOGGER.warn("[CoreProtect] Failed to rebaseline container watch at {}", pos, e);
        }
    }

    /**
     * Records the contents of a container that is about to disappear - broken, exploded, or pushed
     * away by a piston - before the block entity is gone, so a rollback can restore what was
     * inside. The contents are logged as withdrawals under the anonymous
     * {@value #ANONYMOUS_USER} name, because the container was not emptied by the player who
     * happened to have its screen open.
     *
     * <p>Safe to call for any block, from any caller: it never throws, does nothing when the
     * position holds no container, and repeated calls for the same container in the same tick
     * (both halves of a double chest, break + explosion) log the contents only once.
     */
    public static void captureBeforeRemoval(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) return;
        try {
            CoreProtectFabric mod = CoreProtectFabric.instance();
            if (mod == null) return;
            mod.containerTracker().captureRemoval(world, pos.toImmutable());
        } catch (Throwable t) {
            // called from block removal paths (break, explosion, piston): the container log must
            // never make those fail, so even an unexpected error is swallowed here
            CoreProtectFabric.LOGGER.warn(
                    "[CoreProtect] Failed to capture container contents at {} before removal", pos, t);
        }
    }

    /** {@return whether a player currently has this exact container open} */
    boolean watchedAt(ServerWorld world, BlockPos pos) {
        if (watches.isEmpty() || world == null || pos == null) return false;
        BlockPos target = canonicalContainerPos(world, pos);
        for (Watch w : watches.values()) {
            if (w.pos().equals(target) && w.world().getRegistryKey().equals(world.getRegistryKey())) {
                return true;
            }
        }
        return false;
    }

    void rebaselineAt(ServerWorld world, BlockPos pos) {
        if (watches.isEmpty() || world == null || pos == null) return;
        BlockPos canonical = canonicalContainerPos(world, pos);
        Inventory inv = containerAt(world, canonical);
        if (inv == null) return;
        Map<String, Tally> current = snapshot(world, inv);
        long currentDigest = digest(inv);
        for (UUID uuid : watches.keySet()) {
            Watch w = watches.get(uuid);
            if (w == null || !w.world().getRegistryKey().equals(world.getRegistryKey())) continue;
            // watches are stored at the canonical half; the fallback covers a chest that was
            // merged after the screen was opened
            if (!w.pos().equals(canonical) && !(inv instanceof DoubleInventory merged
                    && w.world().getBlockEntity(w.pos()) instanceof Inventory half && merged.isPart(half))) {
                continue;
            }
            watches.replace(uuid, w, new Watch(w.userName(), w.world(), w.pos(), current, currentDigest));
        }
    }

    private void finalizeWatch(ServerPlayerEntity player) {
        settle(player.getUuid());
    }

    /** Logs and forgets one watch. Its user is resolved before the diff is written. */
    private void settle(UUID uuid) {
        Watch w = watches.remove(uuid);
        if (w == null) return;
        try {
            // Two players on one container cannot be told apart: the aggregated diff is recorded
            // anonymously instead of blaming whichever screen closed first, and the remaining
            // watch is refreshed below so their later changes are still logged as theirs.
            String user = hasWatchAt(w.world(), w.pos()) ? ANONYMOUS_USER : w.userName();
            diffAndLog(w, user);
            // another player may have the same container (or the other half of a double
            // chest) open: refresh their baseline so this change is not logged twice
            rebaselineAt(w.world(), w.pos());
        } catch (Exception e) {
            CoreProtectFabric.LOGGER.warn("[CoreProtect] Failed to refresh container watch at {}", w.pos(), e);
        }
    }

    /** {@return whether any remaining watch covers the same merged container as {@code canonical}} */
    private boolean hasWatchAt(ServerWorld world, BlockPos canonical) {
        if (watches.isEmpty() || world == null) return false;
        for (Watch w : watches.values()) {
            if (!w.world().getRegistryKey().equals(world.getRegistryKey())) continue;
            if (w.pos().equals(canonical) || canonicalContainerPos(w.world(), w.pos()).equals(canonical)) {
                return true;
            }
        }
        return false;
    }

    private void diffAndLog(Watch w, String user) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null) return;
        try {
            long time = TimeUtil.now();
            String wid = w.world().getRegistryKey().getValue().toString();
            Inventory inv = containerAt(w.world(), w.pos());
            if (inv == null) {
                // A container that cannot be read is not an emptied one. Before, every remembered
                // stack was logged as a full withdrawal blamed on the player who had the screen
                // open whenever the block entity was unavailable - an unloaded chunk, or a block
                // that a rollback had just set to air. Only a container that is really gone is
                // logged, and then anonymously instead of as that player's doing.
                if (!w.world().isChunkLoaded(w.pos().getX() >> 4, w.pos().getZ() >> 4)) {
                    return; // chunk not loaded: the contents are unknown, not gone
                }
                if (w.world().getBlockEntity(w.pos()) != null
                        || w.world().getBlockState(w.pos()).hasBlockEntity()) {
                    return; // still a container block, only its inventory is unavailable
                }
                logDiff(mod, ANONYMOUS_USER, w.world(), wid, w.pos(), w.before(), Map.of(), time);
                return;
            }
            // cheap equality first: closing an untouched container costs no item encoding at all
            if (digest(inv) == w.digest()) return;
            logDiff(mod, user, w.world(), wid, w.pos(), w.before(), snapshot(w.world(), inv), time);
        } catch (Exception e) {
            CoreProtectFabric.LOGGER.warn("[CoreProtect] Failed to log container changes at {}", w.pos(), e);
        }
    }

    /**
     * Writes one row per changed item type between {@code before} and {@code after}, at
     * {@code pos}. The position is always a canonical (merged container) position, so the
     * rollback side resolves it back to the same inventory the change was observed on.
     *
     * @return whether at least one row was written
     */
    static boolean logDiff(CoreProtectFabric mod, String user, ServerWorld world, String wid, BlockPos pos,
            Map<String, Tally> before, Map<String, Tally> after, long time) {
        DatabaseManager database = mod.database();
        if (database == null) return false;
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());
        boolean logged = false;
        for (String key : keys) {
            Tally beforeTally = before.get(key);
            Tally afterTally = after.get(key);
            int delta = (afterTally == null ? 0 : afterTally.count())
                    - (beforeTally == null ? 0 : beforeTally.count());
            if (delta == 0) continue;
            ItemStack sample = afterTally != null ? afterTally.sample() : beforeTally.sample();
            database.insertContainerAsync(new DatabaseManager.ContainerLog(
                    0, time, user, wid,
                    pos.getX(), pos.getY(), pos.getZ(),
                    delta > 0 ? DatabaseManager.CONTAINER_DEPOSIT : DatabaseManager.CONTAINER_WITHDRAW,
                    Registries.ITEM.getId(sample.getItem()).toString(), Math.abs(delta),
                    ItemStackCodec.encodeIdentity(sample, world.getRegistryManager())));
            logged = true;
        }
        return logged;
    }

    /**
     * Logs what the block at {@code pos} still holds, then forgets every baseline that pointed at
     * it. Reuses {@link #logDiff} - the same diff and row writing a screen close uses - with the
     * current contents as the "before" state and an empty container as the "after" state.
     *
     * <p>Only the inventory owned by this very block is logged: for one half of a double chest
     * that is its 27 slots, while the other half keeps its own contents and is captured when it
     * is removed itself. Writing the merged 54 here would record the surviving half twice.
     */
    private void captureRemoval(ServerWorld world, BlockPos pos) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null || mod.database() == null) return;
        // respect the same switch the screen-close path honours: a server with container logging
        // turned off must not start writing withdrawals merely because a chest was broken
        if (!mod.config().logging.container) return;
        if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) return;
        if (!(world.getBlockEntity(pos) instanceof Inventory removing)) {
            return; // not a container, or its block entity is already gone
        }
        BlockPos canonical = canonicalContainerPos(world, pos);
        long tick = world.getTime();
        String wid = world.getRegistryKey().getValue().toString();
        // one capture per removed block and tick: a break that also triggers an explosion (or two
        // listeners reporting the same removal) must not record the contents twice
        String key = wid + "|" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
        Capture previous = captures.get(key);
        if (previous != null && previous.tick() == tick && previous.container() == removing) {
            return;
        }
        if (captures.size() > MAX_CAPTURES) {
            captures.entrySet().removeIf(entry -> entry.getValue().tick() != tick);
            if (captures.size() > MAX_CAPTURES) captures.clear();
        }
        captures.put(key, new Capture(removing, tick));

        // A watch on this container is dropped without a diff: everything left inside is logged
        // below, and keeping the watch would log the same withdrawal again when the screen closes.
        Inventory merged = containerAt(world, pos);
        for (UUID uuid : new ArrayList<>(watches.keySet())) {
            Watch w = watches.get(uuid);
            if (w == null || !w.world().getRegistryKey().equals(world.getRegistryKey())) continue;
            if (!w.pos().equals(canonical) && !(merged instanceof DoubleInventory doubleChest
                    && w.world().getBlockEntity(w.pos()) instanceof Inventory half && doubleChest.isPart(half))) {
                continue;
            }
            watches.remove(uuid, w);
        }

        logDiff(mod, ANONYMOUS_USER, world, wid, canonical, snapshot(world, removing), Map.of(), TimeUtil.now());

        // refresh the tracker baselines: the block is going away, so the next observation of that
        // position must establish a fresh baseline instead of diffing against the removed contents
        HopperTracker hopperTracker = mod.hopperTracker();
        if (hopperTracker != null) hopperTracker.forget(world, canonical);
        DispenserTracker dispenserTracker = mod.dispenserTracker();
        if (dispenserTracker != null) dispenserTracker.forget(world, canonical);
    }

    /**
     * {@return the position a merged container is tracked and logged at - the lexicographically
     * smaller half of a double chest, the position itself for anything else}
     */
    static BlockPos canonicalContainerPos(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) return pos;
        BlockPos immutable = pos.toImmutable();
        BlockState state = world.getBlockState(immutable);
        if (!(state.getBlock() instanceof ChestBlock chest)) return immutable;
        Inventory merged = ChestBlock.getInventory(chest, state, world, immutable, true);
        if (!(merged instanceof DoubleInventory doubleChest)) return immutable;
        BlockPos canonical = immutable;
        for (Direction direction : HORIZONTALS) {
            BlockPos other = immutable.offset(direction);
            if (world.getBlockEntity(other) instanceof Inventory part
                    && doubleChest.isPart(part)
                    && precedes(other, canonical)) {
                canonical = other.toImmutable();
            }
        }
        return canonical;
    }

    /** Comparator giving every merged container one deterministic canonical half. */
    private static boolean precedes(BlockPos first, BlockPos second) {
        if (first.getY() != second.getY()) return first.getY() < second.getY();
        if (first.getX() != second.getX()) return first.getX() < second.getX();
        return first.getZ() < second.getZ();
    }

    /**
     * Cheap contents fingerprint: no allocation and no item encoding, so a container that did not
     * change can be skipped before {@link #snapshot} runs. It covers the container size, every
     * stack's item and data components ({@link ItemStack#hashCode()}) and the stack counts, which
     * is exactly what a snapshot aggregates by.
     */
    static long digest(Inventory inv) {
        long hash = 0x9E3779B97F4A7C15L;
        int size = inv.size();
        for (int i = 0; i < size; i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            hash = 31 * hash + stack.hashCode();
            hash = 31 * hash + stack.getCount();
        }
        return 31 * hash + size;
    }

    /**
     * {@return the container at {@code pos}, with both halves of a double chest
     * merged into a single 54 slot inventory}
     */
    public static Inventory containerAt(ServerWorld world, BlockPos pos) {
        BlockEntity be = world.getBlockEntity(pos);
        if (!(be instanceof Inventory inv)) return null;
        BlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock chest) {
            Inventory merged = ChestBlock.getInventory(chest, state, world, pos, true);
            if (merged != null) return merged;
        }
        return inv;
    }

    /**
     * Snapshots a container. Two stacks of the same item count as one entry only when they carry the
     * same data components - otherwise swapping an enchanted sword for a plain one would look like no
     * change at all.
     */
    static Map<String, Tally> snapshot(ServerWorld world, Inventory inv) {
        Map<String, Tally> map = new HashMap<>();
        RegistryWrapper.WrapperLookup registries = world.getRegistryManager();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            String key = ItemStackCodec.encodeIdentity(stack, registries);
            if (key == null) {
                // a stack whose components cannot be encoded is still tracked, just without them
                key = Registries.ITEM.getId(stack.getItem()).toString();
            }
            Tally tally = map.get(key);
            if (tally == null) {
                map.put(key, new Tally(stack.copyWithCount(1), stack.getCount()));
            } else {
                map.put(key, new Tally(tally.sample(), tally.count() + stack.getCount()));
            }
        }
        return map;
    }
}
