package net.coreprotect.fabric.rollback;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.coreprotect.fabric.CoreProtectFabric;
import net.coreprotect.fabric.database.Criteria;
import net.coreprotect.fabric.database.DatabaseManager;
import net.coreprotect.fabric.util.BlockStateUtil;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * Rollback / restore / undo engine, mirroring CoreProtect's semantics:
 * container transactions are reversed first, then for every block position the
 * latest matching log is applied (rollback restores the old state, restore
 * re-applies the new state).
 */
public final class RollbackManager {
    private final Map<UUID, LastOperation> lastOperations = new ConcurrentHashMap<>();

    public record Summary(int blocks, int containers, int itemOps, int skipped) {
    }

    private record PosKey(String wid, int x, int y, int z) {
        BlockPos pos() {
            return new BlockPos(x, y, z);
        }

        static PosKey of(String wid, int x, int y, int z) {
            return new PosKey(wid, x, y, z);
        }
    }

    private record BlockUndo(String wid, BlockPos pos, String expected, String apply) {
    }

    /** One container item change performed by a rollback/restore, invertible by /co undo. */
    private record ItemUndo(String wid, BlockPos pos, String item, int amount, boolean wasAdded) {
    }

    private record LastOperation(String kind, List<BlockUndo> blocks, List<ItemUndo> items) {
    }

    /** Returns {@code null} when the operation exceeds the configured safety limit. */
    public Summary rollback(ServerCommandSource source, Criteria c, boolean isRestore) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        MinecraftServer server = mod.server();
        ServerPlayerEntity operator = source.getPlayer();

        // Both queries are capped one row above the safety limit: SQLite treats a negative
        // LIMIT as "unlimited", which used to load the whole table into memory.
        long limit = Math.max(1, mod.config().rollback.maxBlocks) + 1L;

        // 1) Safety check BEFORE touching the world: the block set decides whether this
        //    operation is allowed at all, so a refused rollback must not mutate containers.
        List<DatabaseManager.BlockLog> logs = mod.database().queryBlocks(c, limit, 0);
        if (logs == null) {
            return new Summary(0, 0, 0, 0);
        }
        if (logs.size() > mod.config().rollback.maxBlocks) {
            return null;
        }

        List<DatabaseManager.ContainerLog> containerLogs = mod.database().queryContainers(c, limit, 0);
        Map<PosKey, List<DatabaseManager.ContainerLog>> groups = groupContainers(containerLogs);
        int itemOps = 0;
        List<ItemUndo> itemUndos = new ArrayList<>();
        for (Map.Entry<PosKey, List<DatabaseManager.ContainerLog>> entry : groups.entrySet()) {
            ServerWorld world = worldOf(server, entry.getKey().wid());
            if (world == null) continue;
            BlockEntity be = world.getBlockEntity(entry.getKey().pos());
            if (!(be instanceof Inventory inv)) continue;
            for (DatabaseManager.ContainerLog log : entry.getValue()) {
                int done = reverseContainer(log, inv, operator, world, isRestore);
                itemOps += done;
                if (done > 0) {
                    // deposit: rollback removes -> undo adds; withdraw: rollback adds -> undo removes
                    boolean deposit = log.type() == DatabaseManager.CONTAINER_DEPOSIT;
                    boolean added = isRestore == deposit;
                    itemUndos.add(new ItemUndo(log.wid(), entry.getKey().pos(), log.data(), log.amount(), added));
                }
            }
        }

        // 2) Blocks: newest log per position wins.
        Map<PosKey, DatabaseManager.BlockLog> latest = new LinkedHashMap<>();
        for (DatabaseManager.BlockLog l : logs) {
            if (Objects.equals(l.oldData(), l.newData())) continue;
            latest.putIfAbsent(PosKey.of(l.wid(), l.x(), l.y(), l.z()), l);
        }
        int blocks = 0;
        int skipped = 0;
        List<BlockUndo> undos = new ArrayList<>();
        for (DatabaseManager.BlockLog l : latest.values()) {
            ServerWorld world = worldOf(server, l.wid());
            if (world == null) {
                skipped++;
                continue;
            }
            BlockPos pos = new BlockPos(l.x(), l.y(), l.z());
            BlockState target = BlockStateUtil.parse(server, isRestore ? l.newData() : l.oldData());
            BlockState expected = BlockStateUtil.parse(server, isRestore ? l.oldData() : l.newData());
            BlockState current = world.getBlockState(pos);
            if (current.equals(target)) continue; // already in the target state
            if (!current.equals(expected)) {
                skipped++; // the world changed since this log entry
                continue;
            }
            if (target.isAir()) {
                dropInventoryContents(world, pos);
            }
            world.setBlockState(pos, target, Block.NOTIFY_ALL);
            BlockStateUtil.applySignText(world, pos, l.meta());
            blocks++;
            undos.add(new BlockUndo(l.wid(), pos, BlockStateUtil.stringify(target), BlockStateUtil.stringify(expected)));
        }

        // The undo record is always replaced: a container-only or fully-skipped rollback must
        // not leave an older operation armed for the next /co undo.
        if (operator != null) {
            lastOperations.put(operator.getUuid(),
                    new LastOperation(isRestore ? "restore" : "rollback", undos, itemUndos));
        }
        return new Summary(blocks, groups.size(), itemOps, skipped);
    }

    /** Reverts the caller's last rollback/restore. Returns {@code null} when there is nothing to undo. */
    public Summary undo(ServerCommandSource source) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        ServerPlayerEntity operator = source.getPlayer();
        if (operator == null) return null;
        LastOperation op = lastOperations.remove(operator.getUuid());
        if (op == null || (op.blocks().isEmpty() && op.items().isEmpty())) return null;
        MinecraftServer server = mod.server();
        int done = 0;
        for (BlockUndo u : op.blocks()) {
            ServerWorld world = worldOf(server, u.wid());
            if (world == null) continue;
            BlockState current = world.getBlockState(u.pos());
            if (!current.equals(BlockStateUtil.parse(server, u.expected()))) continue;
            world.setBlockState(u.pos(), BlockStateUtil.parse(server, u.apply()), Block.NOTIFY_ALL);
            done++;
        }
        int items = 0;
        for (ItemUndo u : op.items()) {
            ServerWorld world = worldOf(server, u.wid());
            if (world == null) continue;
            BlockEntity be = world.getBlockEntity(u.pos());
            if (!(be instanceof Inventory inv)) continue;
            Identifier id = Identifier.tryParse(u.item());
            if (id == null) continue;
            Item item = Registries.ITEM.get(id);
            if (item == null || item == Items.AIR) continue;
            if (u.wasAdded()) {
                removeItems(inv, item, u.amount(), operator, world, u.pos());
            } else {
                addItems(inv, new ItemStack(item, u.amount()), world, u.pos());
            }
            items++;
        }
        return new Summary(done, 0, items, 0);
    }

    // ------------------------------------------------------------------

    private Map<PosKey, List<DatabaseManager.ContainerLog>> groupContainers(List<DatabaseManager.ContainerLog> logs) {
        Map<PosKey, List<DatabaseManager.ContainerLog>> groups = new LinkedHashMap<>();
        if (logs == null) return groups;
        for (DatabaseManager.ContainerLog log : logs) {
            groups.computeIfAbsent(PosKey.of(log.wid(), log.x(), log.y(), log.z()), k -> new ArrayList<>()).add(log);
        }
        return groups;
    }

    /** Returns 1 when an item operation was performed, 0 otherwise. */
    private int reverseContainer(DatabaseManager.ContainerLog log, Inventory inv, ServerPlayerEntity operator,
                                 ServerWorld world, boolean isRestore) {
        // deposit (+N): rollback removes N, restore adds N; withdraw (-N): rollback adds N, restore removes N.
        boolean deposit = log.type() == DatabaseManager.CONTAINER_DEPOSIT;
        boolean add = isRestore == deposit;
        Identifier id = Identifier.tryParse(log.data());
        if (id == null) return 0;
        Item item = Registries.ITEM.get(id);
        if (item == null || item == Items.AIR) return 0;
        BlockPos pos = new BlockPos(log.x(), log.y(), log.z());
        if (add) {
            addItems(inv, new ItemStack(item, log.amount()), world, pos);
        } else {
            removeItems(inv, item, log.amount(), operator, world, pos);
        }
        return 1;
    }

    private static void addItems(Inventory inv, ItemStack remaining, ServerWorld world, BlockPos pos) {
        for (int i = 0; i < inv.size() && !remaining.isEmpty(); i++) {
            ItemStack slot = inv.getStack(i);
            if (slot.isEmpty()) {
                // never place more than one stack's worth in a single slot
                int move = Math.min(remaining.getCount(), remaining.getMaxCount());
                inv.setStack(i, remaining.copyWithCount(move));
                remaining.decrement(move);
            } else if (ItemStack.areItemsEqual(slot, remaining) && slot.getCount() < slot.getMaxCount()) {
                int move = Math.min(remaining.getCount(), slot.getMaxCount() - slot.getCount());
                slot.increment(move);
                remaining.decrement(move);
            }
        }
        if (!remaining.isEmpty()) {
            world.spawnEntity(new ItemEntity(world, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, remaining));
        }
    }

    private static void removeItems(Inventory inv, Item item, int amount, ServerPlayerEntity operator,
                                    ServerWorld world, BlockPos pos) {
        int remaining = amount;
        for (int i = 0; i < inv.size() && remaining > 0; i++) {
            ItemStack slot = inv.getStack(i);
            if (slot.isOf(item)) {
                int take = Math.min(slot.getCount(), remaining);
                ItemStack taken = slot.split(take);
                remaining -= take;
                giveStack(operator, taken, world, pos);
            }
        }
        // the container held less than the logged amount: hand the missing items out so no
        // data is silently lost
        if (remaining > 0) {
            giveStack(operator, new ItemStack(item, remaining), world, pos);
        }
    }

    private static void giveStack(ServerPlayerEntity operator, ItemStack stack, ServerWorld world, BlockPos pos) {
        if (operator != null) {
            boolean inserted = operator.getInventory().insertStack(stack);
            if (!stack.isEmpty() || !inserted) {
                if (!stack.isEmpty()) {
                    world.spawnEntity(new ItemEntity(world, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack));
                }
            }
        } else {
            world.spawnEntity(new ItemEntity(world, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack));
        }
    }

    /** Drops a container's remaining contents before the container block is removed. */
    private static void dropInventoryContents(ServerWorld world, BlockPos pos) {
        BlockEntity be = world.getBlockEntity(pos);
        if (!(be instanceof Inventory inv)) return;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty()) {
                world.spawnEntity(new ItemEntity(world, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack.copy()));
                inv.setStack(i, ItemStack.EMPTY);
            }
        }
    }

    private static ServerWorld worldOf(MinecraftServer server, String wid) {
        if (wid == null) return null;
        Identifier id = Identifier.tryParse(wid);
        if (id == null) return null;
        return server.getWorld(RegistryKey.of(RegistryKeys.WORLD, id));
    }
}
