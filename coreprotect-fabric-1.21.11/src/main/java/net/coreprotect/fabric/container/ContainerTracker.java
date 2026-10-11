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

/**
 * Tracks container interactions CoreProtect-style: when a player opens a
 * container, its contents are snapshotted; when the screen closes (or the
 * player leaves/dies/opens another container), the diff is logged as one row
 * per changed item type.
 *
 * <p>A double chest is tracked as one 54 slot container: the snapshot merges
 * both halves, so items moved into the half that was not clicked are recorded
 * as well.
 */
public final class ContainerTracker {
    private final Map<UUID, Watch> watches = new ConcurrentHashMap<>();

    private record Watch(String userName, ServerWorld world, BlockPos pos, Map<String, Tally> before) {
    }

    /**
     * One snapshot entry: a representative stack (its data components decide whether two stacks of
     * the same item are interchangeable) and the summed count of everything matching it.
     */
    private record Tally(ItemStack sample, int count) {
    }

    public void onPlayerOpened(ServerPlayerEntity player, ServerWorld world, BlockPos pos) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null || !mod.config().logging.container) return;
        finalizeWatch(player);
        Inventory inv = containerAt(world, pos);
        if (inv != null) {
            watches.put(player.getUuid(),
                    new Watch(player.getGameProfile().name(), world, pos.toImmutable(), snapshot(world, inv)));
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

    /** {@return whether a player currently has this exact container open} */
    boolean watchedAt(ServerWorld world, BlockPos pos) {
        if (watches.isEmpty()) return false;
        BlockPos target = pos.toImmutable();
        for (Watch w : watches.values()) {
            if (w.pos().equals(target) && w.world().getRegistryKey().equals(world.getRegistryKey())) {
                return true;
            }
        }
        return false;
    }

    void rebaselineAt(ServerWorld world, BlockPos pos) {
        if (watches.isEmpty()) return;
        Inventory inv = containerAt(world, pos);
        if (inv == null) return;
        Map<String, Tally> current = snapshot(world, inv);
        BlockPos target = pos.toImmutable();
        for (UUID uuid : watches.keySet()) {
            Watch w = watches.get(uuid);
            if (w == null || !w.world().getRegistryKey().equals(world.getRegistryKey())) continue;
            // a double chest is one container: a watch on either half must be refreshed too
            if (!w.pos().equals(target) && !(inv instanceof DoubleInventory merged
                    && w.world().getBlockEntity(w.pos()) instanceof Inventory half && merged.isPart(half))) {
                continue;
            }
            watches.replace(uuid, w, new Watch(w.userName(), w.world(), w.pos(), current));
        }
    }

    private void finalizeWatch(ServerPlayerEntity player) {
        Watch w = watches.remove(player.getUuid());
        if (w == null) return;
        diffAndLog(w);
        try {
            // another player may have the same container (or the other half of a double
            // chest) open: refresh their baseline so this change is not logged twice
            rebaselineAt(w.world(), w.pos());
        } catch (Exception e) {
            CoreProtectFabric.LOGGER.warn("[CoreProtect] Failed to refresh container watch at {}", w.pos(), e);
        }
    }

    private void diffAndLog(Watch w) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null) return;
        try {
            // A container that cannot be read is not an emptied one. Before, every remembered stack
            // was logged as a full withdrawal blamed on the player who had the screen open whenever
            // the block entity was unavailable - an unloaded chunk, or a block that a rollback had
            // just set to air. Only a container that is really gone is logged, and then as an
            // anonymous "#container" change instead of as that player's doing.
            Inventory inv = containerAt(w.world(), w.pos());
            String user = w.userName();
            if (inv == null) {
                if (!w.world().isChunkLoaded(w.pos().getX() >> 4, w.pos().getZ() >> 4)) {
                    return; // chunk not loaded: the contents are unknown, not gone
                }
                if (w.world().getBlockEntity(w.pos()) != null
                        || w.world().getBlockState(w.pos()).hasBlockEntity()) {
                    return; // still a container block, only its inventory is unavailable
                }
                user = "#container";
            }
            Map<String, Tally> after = inv != null ? snapshot(w.world(), inv) : new HashMap<>();
            Set<String> keys = new LinkedHashSet<>();
            keys.addAll(w.before().keySet());
            keys.addAll(after.keySet());
            long time = TimeUtil.now();
            String wid = w.world().getRegistryKey().getValue().toString();
            for (String key : keys) {
                Tally beforeTally = w.before().get(key);
                Tally afterTally = after.get(key);
                int delta = (afterTally == null ? 0 : afterTally.count())
                        - (beforeTally == null ? 0 : beforeTally.count());
                if (delta == 0) continue;
                ItemStack sample = afterTally != null ? afterTally.sample() : beforeTally.sample();
                mod.database().insertContainerAsync(new DatabaseManager.ContainerLog(
                        0, time, w.userName(), wid,
                        w.pos().getX(), w.pos().getY(), w.pos().getZ(),
                        delta > 0 ? DatabaseManager.CONTAINER_DEPOSIT : DatabaseManager.CONTAINER_WITHDRAW,
                        Registries.ITEM.getId(sample.getItem()).toString(), Math.abs(delta),
                        ItemStackCodec.encodeIdentity(sample, w.world().getRegistryManager())));
            }
        } catch (Exception e) {
            CoreProtectFabric.LOGGER.warn("[CoreProtect] Failed to log container changes at {}", w.pos(), e);
        }
    }

    /**
     * {@return the container at {@code pos}, with both halves of a double chest
     * merged into a single 54 slot inventory}
     */
    private static Inventory containerAt(ServerWorld world, BlockPos pos) {
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
    private static Map<String, Tally> snapshot(ServerWorld world, Inventory inv) {
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
