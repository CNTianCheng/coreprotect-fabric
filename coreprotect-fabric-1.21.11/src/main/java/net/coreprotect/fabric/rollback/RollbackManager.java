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
import net.coreprotect.fabric.util.Messages;
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

    /** Time budget for one slice on the server thread (4 ms), so a big rollback never
     *  freezes a tick: the rest of the work is re-scheduled onto the next tick. */
    private static final long SLICE_NANOS = 4_000_000L;

    /** What the read pool prepared for a rollback: nothing here touches the world. */
    private record Plan(boolean tooLarge,
                        List<DatabaseManager.BlockLog> blocks,
                        List<Map.Entry<PosKey, List<DatabaseManager.ContainerLog>>> containers,
                        int containerGroups) {
    }

    /** One rollback/restore/undo in flight, advanced one unit of work at a time. */
    private static final class Job {
        private final MinecraftServer server;
        private final ServerCommandSource source;
        private final ServerPlayerEntity operator;
        private final boolean isRestore;
        private final boolean undo;

        private List<Map.Entry<PosKey, List<DatabaseManager.ContainerLog>>> containers = List.of();
        private int containerGroups;
        private int groupIndex;
        private int entryIndex;
        private List<DatabaseManager.BlockLog> blocks = List.of();
        private int blockIndex;
        private LastOperation operation;
        private int undoIndex;

        private final List<BlockUndo> undos = new ArrayList<>();
        private final List<ItemUndo> itemUndos = new ArrayList<>();
        private int blocksApplied;
        private int itemOps;
        private int itemsApplied;
        private int skipped;

        private Job(MinecraftServer server, ServerCommandSource source, ServerPlayerEntity operator,
                    boolean isRestore, boolean undo) {
            this.server = server;
            this.source = source;
            this.operator = operator;
            this.isRestore = isRestore;
            this.undo = undo;
        }
    }

    /**
     * Starts a rollback/restore. The two queries run on the read pool and the world is
     * edited in 4 ms slices on the server thread, so the command returns immediately and
     * the server keeps ticking while a large area is restored. All user-visible messages
     * are sent from here.
     */
    public void startRollback(ServerCommandSource source, Criteria c, boolean isRestore) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        long limit = Math.max(1, mod.config().rollback.maxBlocks) + 1L;
        int maxBlocks = mod.config().rollback.maxBlocks;

        // Both queries are capped one row above the safety limit: SQLite treats a negative
        // LIMIT as "unlimited", which used to load the whole table into memory.
        mod.database().submitRead(() -> {
            List<DatabaseManager.BlockLog> logs = mod.database().queryBlocks(c, limit, 0);
            if (logs == null) return null;
            if (logs.size() > maxBlocks) return new Plan(true, List.of(), List.of(), 0);
            List<DatabaseManager.ContainerLog> containerLogs = mod.database().queryContainers(c, limit, 0);
            // newest log per position wins
            Map<PosKey, DatabaseManager.BlockLog> latest = new LinkedHashMap<>();
            for (DatabaseManager.BlockLog l : logs) {
                if (Objects.equals(l.oldData(), l.newData())) continue;
                latest.putIfAbsent(PosKey.of(l.wid(), l.x(), l.y(), l.z()), l);
            }
            Map<PosKey, List<DatabaseManager.ContainerLog>> groups = groupContainers(containerLogs);
            return new Plan(false, new ArrayList<>(latest.values()),
                    new ArrayList<>(groups.entrySet()), groups.size());
        }, plan -> {
            if (plan == null) {
                Messages.error(source, "coreprotect.error.db", "database read failed");
                return;
            }
            if (plan.tooLarge()) {
                Messages.error(source, "coreprotect.rollback.too_many", maxBlocks);
                return;
            }
            Job job = new Job(mod.server(), source, source.getPlayer(), isRestore, false);
            job.blocks = plan.blocks();
            job.containers = plan.containers();
            job.containerGroups = plan.containerGroups();
            slice(job);
        });
    }

    /** Reverts the caller's last rollback/restore. Reports "nothing to undo" itself. */
    public void startUndo(ServerCommandSource source) {
        ServerPlayerEntity operator = source.getPlayer();
        LastOperation op = operator == null ? null : lastOperations.remove(operator.getUuid());
        if (op == null || (op.blocks().isEmpty() && op.items().isEmpty())) {
            Messages.cmd(source, "coreprotect.undo.empty");
            return;
        }
        Job job = new Job(CoreProtectFabric.instance().server(), source, operator, false, true);
        job.operation = op;
        slice(job);
    }

    /** Applies work until the slice budget runs out, then continues on a later tick. */
    private void slice(Job job) {
        long deadline = System.nanoTime() + SLICE_NANOS;
        do {
            boolean more;
            try {
                more = job.undo ? stepUndo(job) : stepRollback(job);
            } catch (Exception e) {
                CoreProtectFabric.LOGGER.error("[CoreProtect] Rollback failed", e);
                Messages.error(job.source, "coreprotect.error.db", e.toString());
                return;
            }
            if (!more) {
                finish(job);
                return;
            }
        } while (System.nanoTime() < deadline);
        MinecraftServer server = job.server;
        if (server == null) {
            finish(job);
            return;
        }
        server.execute(() -> slice(job)); // continue on the next tick
    }

    private void finish(Job job) {
        if (job.undo) {
            Messages.success(job.source, "coreprotect.undo.complete", job.blocksApplied);
            return;
        }
        // The undo record is always replaced: a container-only or fully-skipped rollback must
        // not leave an older operation armed for the next /co undo.
        if (job.operator != null) {
            lastOperations.put(job.operator.getUuid(),
                    new LastOperation(job.isRestore ? "restore" : "rollback", job.undos, job.itemUndos));
        }
        if (job.blocksApplied == 0 && job.itemOps == 0) {
            Messages.cmd(job.source, "coreprotect.rollback.empty");
            return;
        }
        Messages.success(job.source, job.isRestore ? "coreprotect.restore.complete" : "coreprotect.rollback.complete",
                job.blocksApplied, job.containerGroups, job.itemOps, job.skipped);
        if (job.source.getPlayer() == null) {
            // console and RCON only print their output while the command runs, so the result
            // of an asynchronous rollback is mirrored into the server log
            CoreProtectFabric.LOGGER.info("[CoreProtect] {} finished: {} block(s), {} container(s), {} item(s), {} skipped.",
                    job.isRestore ? "Restore" : "Rollback", job.blocksApplied, job.containerGroups, job.itemOps, job.skipped);
        }
    }

    /** One unit of rollback work. Returns false when there is nothing left to do. */
    private boolean stepRollback(Job job) {
        if (job.groupIndex < job.containers.size()) {
            Map.Entry<PosKey, List<DatabaseManager.ContainerLog>> entry = job.containers.get(job.groupIndex);
            List<DatabaseManager.ContainerLog> list = entry.getValue();
            ServerWorld world = worldOf(job.server, entry.getKey().wid());
            if (world == null) {
                job.groupIndex++;
                job.entryIndex = 0;
                return true;
            }
            BlockEntity be = world.getBlockEntity(entry.getKey().pos());
            if (!(be instanceof Inventory inv)) {
                job.groupIndex++;
                job.entryIndex = 0;
                return true;
            }
            DatabaseManager.ContainerLog log = list.get(job.entryIndex++);
            int done = reverseContainer(log, inv, job.operator, world, job.isRestore);
            job.itemOps += done;
            if (done > 0) {
                // deposit: rollback removes -> undo adds; withdraw: rollback adds -> undo removes
                boolean deposit = log.type() == DatabaseManager.CONTAINER_DEPOSIT;
                boolean added = job.isRestore == deposit;
                job.itemUndos.add(new ItemUndo(log.wid(), entry.getKey().pos(), log.data(), log.amount(), added));
            }
            if (job.entryIndex >= list.size()) {
                job.groupIndex++;
                job.entryIndex = 0;
            }
            return true;
        }
        if (job.blockIndex < job.blocks.size()) {
            DatabaseManager.BlockLog l = job.blocks.get(job.blockIndex++);
            ServerWorld world = worldOf(job.server, l.wid());
            if (world == null) {
                job.skipped++;
                return true;
            }
            BlockPos pos = new BlockPos(l.x(), l.y(), l.z());
            BlockState target = BlockStateUtil.parse(job.server, job.isRestore ? l.newData() : l.oldData());
            BlockState expected = BlockStateUtil.parse(job.server, job.isRestore ? l.oldData() : l.newData());
            BlockState current = world.getBlockState(pos);
            if (current.equals(target)) return true; // already in the target state
            if (!current.equals(expected)) {
                job.skipped++; // the world changed since this log entry
                return true;
            }
            if (target.isAir()) {
                dropInventoryContents(world, pos);
            }
            world.setBlockState(pos, target, Block.NOTIFY_ALL);
            BlockStateUtil.applySignText(world, pos, l.meta());
            job.blocksApplied++;
            job.undos.add(new BlockUndo(l.wid(), pos, BlockStateUtil.stringify(target), BlockStateUtil.stringify(expected)));
            return true;
        }
        return false;
    }

    /** One unit of undo work. Returns false when there is nothing left to do. */
    private boolean stepUndo(Job job) {
        LastOperation op = job.operation;
        if (job.blockIndex < op.blocks().size()) {
            BlockUndo u = op.blocks().get(job.blockIndex++);
            ServerWorld world = worldOf(job.server, u.wid());
            if (world == null) return true;
            BlockState current = world.getBlockState(u.pos());
            if (!current.equals(BlockStateUtil.parse(job.server, u.expected()))) return true;
            world.setBlockState(u.pos(), BlockStateUtil.parse(job.server, u.apply()), Block.NOTIFY_ALL);
            job.blocksApplied++;
            return true;
        }
        if (job.undoIndex < op.items().size()) {
            ItemUndo u = op.items().get(job.undoIndex++);
            ServerWorld world = worldOf(job.server, u.wid());
            if (world == null) return true;
            BlockEntity be = world.getBlockEntity(u.pos());
            if (!(be instanceof Inventory inv)) return true;
            Identifier id = Identifier.tryParse(u.item());
            if (id == null) return true;
            Item item = Registries.ITEM.get(id);
            if (item == null || item == Items.AIR) return true;
            if (u.wasAdded()) {
                removeItems(inv, item, u.amount(), job.operator, world, u.pos());
            } else {
                addItems(inv, new ItemStack(item, u.amount()), world, u.pos());
            }
            job.itemsApplied++;
            return true;
        }
        return false;
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
