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

    private record LastOperation(String kind, List<BlockUndo> blocks) {
    }

    /** Returns {@code null} when the operation exceeds the configured safety limit. */
    public Summary rollback(ServerCommandSource source, Criteria c, boolean isRestore) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        MinecraftServer server = mod.server();
        ServerPlayerEntity operator = source.getPlayer();

        // 1) Container transactions first, so that chests about to be removed are emptied.
        List<DatabaseManager.ContainerLog> containerLogs = mod.database().queryContainers(c, -1, 0);
        Map<PosKey, List<DatabaseManager.ContainerLog>> groups = groupContainers(containerLogs);
        int itemOps = 0;
        for (Map.Entry<PosKey, List<DatabaseManager.ContainerLog>> entry : groups.entrySet()) {
            ServerWorld world = worldOf(server, entry.getKey().wid());
            if (world == null) continue;
            BlockEntity be = world.getBlockEntity(entry.getKey().pos());
            if (!(be instanceof Inventory inv)) continue;
            for (DatabaseManager.ContainerLog log : entry.getValue()) {
                itemOps += reverseContainer(log, inv, operator, world, isRestore);
            }
        }

        // 2) Blocks: newest log per position wins.
        List<DatabaseManager.BlockLog> logs = mod.database().queryBlocks(c, -1, 0);
        if (logs == null) {
            return new Summary(0, groups.size(), itemOps, 0);
        }
        if (logs.size() > mod.config().rollback.maxBlocks) {
            return null;
        }
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

        if (operator != null && !undos.isEmpty()) {
            lastOperations.put(operator.getUuid(),
                    new LastOperation(isRestore ? "restore" : "rollback", undos));
        }
        return new Summary(blocks, groups.size(), itemOps, skipped);
    }

    /** Reverts the caller's last rollback/restore. Returns {@code null} when there is nothing to undo. */
    public Summary undo(ServerCommandSource source) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        ServerPlayerEntity operator = source.getPlayer();
        if (operator == null) return null;
        LastOperation op = lastOperations.remove(operator.getUuid());
        if (op == null || op.blocks().isEmpty()) return null;
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
        return new Summary(done, 0, 0, 0);
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
                inv.setStack(i, remaining.copy());
                remaining.setCount(0);
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
