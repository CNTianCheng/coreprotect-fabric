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
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

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
    public Summary rollback(CommandSourceStack source, Criteria c, boolean isRestore) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        MinecraftServer server = mod.server();
        ServerPlayer operator = source.getPlayer();

        // 1) Container transactions first, so that chests about to be removed are emptied.
        List<DatabaseManager.ContainerLog> containerLogs = mod.database().queryContainers(c, -1, 0);
        Map<PosKey, List<DatabaseManager.ContainerLog>> groups = groupContainers(containerLogs);
        int itemOps = 0;
        for (Map.Entry<PosKey, List<DatabaseManager.ContainerLog>> entry : groups.entrySet()) {
            ServerLevel world = worldOf(server, entry.getKey().wid());
            if (world == null) continue;
            BlockEntity be = world.getBlockEntity(entry.getKey().pos());
            if (!(be instanceof Container inv)) continue;
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
            ServerLevel world = worldOf(server, l.wid());
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
            world.setBlock(pos, target, Block.UPDATE_ALL);
            BlockStateUtil.applySignText(world, pos, l.meta());
            blocks++;
            undos.add(new BlockUndo(l.wid(), pos, BlockStateUtil.stringify(target), BlockStateUtil.stringify(expected)));
        }

        if (operator != null && !undos.isEmpty()) {
            lastOperations.put(operator.getUUID(),
                    new LastOperation(isRestore ? "restore" : "rollback", undos));
        }
        return new Summary(blocks, groups.size(), itemOps, skipped);
    }

    /** Reverts the caller's last rollback/restore. Returns {@code null} when there is nothing to undo. */
    public Summary undo(CommandSourceStack source) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        ServerPlayer operator = source.getPlayer();
        if (operator == null) return null;
        LastOperation op = lastOperations.remove(operator.getUUID());
        if (op == null || op.blocks().isEmpty()) return null;
        MinecraftServer server = mod.server();
        int done = 0;
        for (BlockUndo u : op.blocks()) {
            ServerLevel world = worldOf(server, u.wid());
            if (world == null) continue;
            BlockState current = world.getBlockState(u.pos());
            if (!current.equals(BlockStateUtil.parse(server, u.expected()))) continue;
            world.setBlock(u.pos(), BlockStateUtil.parse(server, u.apply()), Block.UPDATE_ALL);
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
    private int reverseContainer(DatabaseManager.ContainerLog log, Container inv, ServerPlayer operator,
                                 ServerLevel world, boolean isRestore) {
        // deposit (+N): rollback removes N, restore adds N; withdraw (-N): rollback adds N, restore removes N.
        boolean deposit = log.type() == DatabaseManager.CONTAINER_DEPOSIT;
        boolean add = isRestore == deposit;
        Identifier id = Identifier.tryParse(log.data());
        if (id == null) return 0;
        Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        if (item == null || item == Items.AIR) return 0;
        BlockPos pos = new BlockPos(log.x(), log.y(), log.z());
        if (add) {
            addItems(inv, new ItemStack(item, log.amount()), world, pos);
        } else {
            removeItems(inv, item, log.amount(), operator, world, pos);
        }
        return 1;
    }

    private static void addItems(Container inv, ItemStack remaining, ServerLevel world, BlockPos pos) {
        for (int i = 0; i < inv.getContainerSize() && !remaining.isEmpty(); i++) {
            ItemStack slot = inv.getItem(i);
            if (slot.isEmpty()) {
                inv.setItem(i, remaining.copy());
                remaining.setCount(0);
            } else if (ItemStack.isSameItemSameComponents(slot, remaining) && slot.getCount() < slot.getMaxStackSize()) {
                int move = Math.min(remaining.getCount(), slot.getMaxStackSize() - slot.getCount());
                slot.grow(move);
                remaining.shrink(move);
            }
        }
        if (!remaining.isEmpty()) {
            world.addFreshEntity(new ItemEntity(world, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, remaining));
        }
    }

    private static void removeItems(Container inv, Item item, int amount, ServerPlayer operator,
                                    ServerLevel world, BlockPos pos) {
        int remaining = amount;
        for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
            ItemStack slot = inv.getItem(i);
            if (slot.is(item)) {
                int take = Math.min(slot.getCount(), remaining);
                ItemStack taken = slot.split(take);
                remaining -= take;
                giveStack(operator, taken, world, pos);
            }
        }
    }

    private static void giveStack(ServerPlayer operator, ItemStack stack, ServerLevel world, BlockPos pos) {
        if (operator != null) {
            boolean inserted = operator.getInventory().add(stack);
            if (!stack.isEmpty() || !inserted) {
                if (!stack.isEmpty()) {
                    world.addFreshEntity(new ItemEntity(world, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack));
                }
            }
        } else {
            world.addFreshEntity(new ItemEntity(world, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack));
        }
    }

    /** Drops a container's remaining contents before the container block is removed. */
    private static void dropInventoryContents(ServerLevel world, BlockPos pos) {
        BlockEntity be = world.getBlockEntity(pos);
        if (!(be instanceof Container inv)) return;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                world.addFreshEntity(new ItemEntity(world, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack.copy()));
                inv.setItem(i, ItemStack.EMPTY);
            }
        }
    }

    private static ServerLevel worldOf(MinecraftServer server, String wid) {
        if (wid == null) return null;
        Identifier id = Identifier.tryParse(wid);
        if (id == null) return null;
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }
}
