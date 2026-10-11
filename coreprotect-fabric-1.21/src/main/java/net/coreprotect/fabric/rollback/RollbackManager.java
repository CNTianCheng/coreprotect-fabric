package net.coreprotect.fabric.rollback;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.coreprotect.fabric.CoreProtectFabric;
import net.coreprotect.fabric.container.ContainerTracker;
import net.coreprotect.fabric.database.Criteria;
import net.coreprotect.fabric.database.DatabaseManager;
import net.coreprotect.fabric.util.BlockStateUtil;
import net.coreprotect.fabric.util.ItemStackCodec;
import net.coreprotect.fabric.util.Messages;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
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
import net.minecraft.util.math.Box;

/**
 * Rollback / restore / undo engine, mirroring CoreProtect's semantics:
 * container transactions are reversed first, then every block position replays all of the
 * logs that touched it (rollback walks them newest first, restore oldest first), and the
 * sign text and dropped item stacks are reverted afterwards.
 */
public final class RollbackManager {
    /** Time budget for one slice on the server thread (4 ms), so a big rollback never
     *  freezes a tick: the rest of the work is re-scheduled onto the next tick. */
    private static final long SLICE_NANOS = 4_000_000L;

    /** Where {@code /co undo} is stored, so the record survives a restart. */
    private static final String UNDO_FILE = "coreprotect-fabric-undo.json";
    private static final int UNDO_FILE_VERSION = 1;

    /** A running job rewrites its undo record at most this often; the record is always written
     *  when the job ends, so an interrupted rollback loses at most five seconds of undo data. */
    private static final long UNDO_SAVE_NANOS = 5_000_000_000L;

    /** How far back a sign's previous text is looked up. */
    private static final int SIGN_HISTORY_LIMIT = 256;

    private final Map<UUID, LastOperation> lastOperations = new ConcurrentHashMap<>();

    /** The jobs that are still applying changes; a job leaves this set when it ends. */
    private final Set<Job> runningJobs = ConcurrentHashMap.newKeySet();

    /**
     * Writes the undo file off the tick thread. A multi-megabyte record (maxBlocks plus the
     * container, item and sign lists) used to be written from the slice loop, which blocked the
     * server thread every five seconds for the duration of the write.
     */
    private final ExecutorService undoWriter = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "CoreProtect undo writer");
        thread.setDaemon(true);
        return thread;
    });

    /** True while a background write is queued, so a slice cannot pile up several of them. */
    private final AtomicBoolean undoSaveQueued = new AtomicBoolean();

    private volatile boolean undoLoaded;

    public RollbackManager() {
        // Registered here rather than in CoreProtectFabric (whose stopping handler only shuts
        // down the metrics/updater and finalizes the containers): a rollback that is still in
        // flight has to be finished and persisted before the server goes away, otherwise the
        // world keeps the part it already changed with no way to undo it.
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> shutdown());
    }

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

    /**
     * One block change performed by a rollback/restore. {@code expected} is the state the
     * position holds afterwards (and the undo only fires while it still does), {@code apply}
     * is the state the undo writes back.
     */
    private record BlockUndo(String wid, BlockPos pos, String expected, String apply) {
    }

    /** One container item change performed by a rollback/restore, invertible by /co undo. */
    private record ItemUndo(String wid, BlockPos pos, String item, int amount, boolean wasAdded, String nbt) {
    }

    /**
     * A stack that was dropped on the ground by the rollback ({@code added}) or taken off the ground
     * by it; /co undo puts it back or removes it again. {@code nbt} carries the data components.
     */
    private record WorldItemUndo(String wid, BlockPos pos, String item, int amount, boolean added, String nbt) {
    }

    /** Sign text a rollback/restore replaced; /co undo writes {@code text} back. */
    private record SignUndo(String wid, BlockPos pos, String text) {
    }

    private record LastOperation(String kind, List<BlockUndo> blocks, List<ItemUndo> items,
                                 List<WorldItemUndo> worldItems, List<SignUndo> signs) {
        boolean isEmpty() {
            return blocks.isEmpty() && items.isEmpty() && worldItems.isEmpty() && signs.isEmpty();
        }
    }

    /** A sign position matched by a rollback and the text that has to be written back
     *  ({@code text == null}: the text from before the oldest matched edit is unknown). */
    private record SignTarget(PosKey pos, String text) {
    }

    /** What the read pool prepared for a rollback: nothing here touches the world. */
    private record Plan(boolean tooLarge,
                        List<Map.Entry<PosKey, List<DatabaseManager.BlockLog>>> blocks,
                        List<Map.Entry<PosKey, List<DatabaseManager.ContainerLog>>> containers,
                        int containerGroups,
                        List<SignTarget> signs,
                        List<DatabaseManager.ItemLog> items,
                        List<DatabaseManager.EntityLog> entities) {
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
        /**
         * The per-position log chains of the matched blocks, newest row first. Every row is
         * replayed (newest to oldest for a rollback, oldest to newest for a restore) instead of
         * only the newest one, so a position that was changed several times ends up in the state
         * it had before the first replayed change.
         */
        private List<Map.Entry<PosKey, List<DatabaseManager.BlockLog>>> blocks = List.of();
        private int blockGroupIndex;
        private int blockRowIndex;
        private List<SignTarget> signs = List.of();
        private int signIndex;
        private List<DatabaseManager.ItemLog> items = List.of();
        private int itemIndex;
        private List<DatabaseManager.EntityLog> entities = List.of();
        private int entityIndex;

        private LastOperation operation;
        private int blockUndoIndex;
        private int undoIndex;
        private int signUndoIndex;
        private int worldItemIndex;

        private final List<BlockUndo> undos = new ArrayList<>();
        private final List<ItemUndo> itemUndos = new ArrayList<>();
        private final List<WorldItemUndo> worldItemUndos = new ArrayList<>();
        private final List<SignUndo> signUndos = new ArrayList<>();
        private int blocksApplied;
        private int itemOps;
        private int itemsApplied;
        private int skipped;
        private long lastUndoSave;

        private Job(MinecraftServer server, ServerCommandSource source, ServerPlayerEntity operator,
                    boolean isRestore, boolean undo) {
            this.server = server;
            this.source = source;
            this.operator = operator;
            this.isRestore = isRestore;
            this.undo = undo;
            this.lastUndoSave = System.nanoTime();
        }
    }

    /**
     * Starts a rollback/restore. The queries run on the read pool and the world is
     * edited in 4 ms slices on the server thread, so the command returns immediately and
     * the server keeps ticking while a large area is restored. All user-visible messages
     * are sent from here.
     */
    public void startRollback(ServerCommandSource source, Criteria c, boolean isRestore) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        long limit = Math.max(1, mod.config().rollback.maxBlocks) + 1L;
        int maxBlocks = mod.config().rollback.maxBlocks;
        ServerPlayerEntity operator = source.getPlayer();
        if (isRunning(operator)) {
            // Only one operation per player: a second rollback started while the first one is
            // still running would replace the undo record it is still building, and /co undo
            // would then revert a mixture of both. A console/RCON rollback counts as its own
            // operator, so a second one cannot interleave with it either.
            Messages.cmd(source, "coreprotect.error.busy");
            return;
        }
        Messages.cmd(source, isRestore ? "coreprotect.restore.start" : "coreprotect.rollback.start");
        ensureUndoLoaded();

        mod.database().submitRead(() -> {
            List<DatabaseManager.BlockLog> logs = mod.database().queryBlocks(c, limit, 0);
            if (logs == null) return null;
            if (logs.size() > maxBlocks) return new Plan(true, List.of(), List.of(), 0, List.of(), List.of(), List.of());
            List<DatabaseManager.ContainerLog> containerLogs = mod.database().queryContainers(c, limit, 0);
            if (containerLogs == null) return null;
            // Container rows are capped by the same limit as block rows: without this check a
            // search whose newest rows are container transactions silently rolled back only the
            // newest ones and still reported success.
            if (containerLogs.size() > maxBlocks) return new Plan(true, List.of(), List.of(), 0, List.of(), List.of(), List.of());
            // One chain per position, newest row first, so every row can be replayed in turn.
            Map<PosKey, List<DatabaseManager.BlockLog>> chains = new LinkedHashMap<>();
            for (DatabaseManager.BlockLog l : logs) {
                if (Objects.equals(l.oldData(), l.newData())) continue;
                chains.computeIfAbsent(PosKey.of(l.wid(), l.x(), l.y(), l.z()), k -> new ArrayList<>()).add(l);
            }
            Map<PosKey, List<DatabaseManager.ContainerLog>> groups = groupContainers(containerLogs);
            List<DatabaseManager.SignLog> signLogs = mod.database().querySigns(c, limit, 0);
            if (signLogs == null) return null;
            List<DatabaseManager.ItemLog> itemLogs = mod.database().queryItems(c, limit, 0);
            if (itemLogs == null) return null;
            List<DatabaseManager.EntityLog> entityLogs = mod.database().queryEntities(c, limit, 0);
            if (entityLogs == null) return null;
            // Sign, item and entity rows are capped like the block and container rows: a silently
            // truncated list would only revert the newest matches and still report success.
            if (signLogs.size() > maxBlocks || itemLogs.size() > maxBlocks || entityLogs.size() > maxBlocks) {
                return new Plan(true, List.of(), List.of(), 0, List.of(), List.of(), List.of());
            }
            return new Plan(false,
                    new ArrayList<>(chains.entrySet()),
                    new ArrayList<>(groups.entrySet()),
                    groups.size(),
                    resolveSignTargets(mod, signLogs, isRestore),
                    itemLogs,
                    entityLogs);
        }, plan -> {
            if (plan == null) {
                Messages.error(source, "coreprotect.error.db", "database read failed");
                return;
            }
            if (plan.tooLarge()) {
                Messages.error(source, "coreprotect.rollback.too_many", maxBlocks);
                return;
            }
            Job job = new Job(mod.server(), source, operator, isRestore, false);
            job.blocks = plan.blocks();
            job.containers = plan.containers();
            job.containerGroups = plan.containerGroups();
            job.signs = plan.signs();
            job.items = plan.items();
            job.entities = plan.entities();
            runningJobs.add(job);
            slice(job);
        });
    }

    /** Reverts the caller's last rollback/restore. Reports "nothing to undo" itself. */
    public void startUndo(ServerCommandSource source) {
        ServerPlayerEntity operator = source.getPlayer();
        ensureUndoLoaded();
        if (isRunning(operator)) {
            // The rollback of this player is still slicing, so its undo record is not finished yet:
            // undoing now would revert a partial record while the job keeps writing a new one.
            Messages.cmd(source, "coreprotect.error.busy");
            return;
        }
        LastOperation op = operator == null ? null : lastOperations.remove(operator.getUuid());
        if (op == null || op.isEmpty()) {
            Messages.cmd(source, "coreprotect.undo.empty");
            return;
        }
        if (operator != null) {
            saveLastOperations(); // the record is consumed, so it must not stay on disk either
        }
        Job job = new Job(CoreProtectFabric.instance().server(), source, operator, false, true);
        job.operation = op;
        runningJobs.add(job);
        slice(job);
    }

    /**
     * Ends every job that is still in flight, keeping the part of the world it already changed
     * undoable: the slices that were scheduled for a later tick never run once the server stops.
     * Wired to SERVER_STOPPING by the constructor.
     */
    public void shutdown() {
        for (Job job : List.copyOf(runningJobs)) {
            runningJobs.remove(job);
            try {
                commitUndo(job);
                CoreProtectFabric.LOGGER.info(
                        "[CoreProtect] {} stopped by server shutdown: {} block(s), {} container(s), {} item(s) applied, {} skipped.",
                        job.isRestore ? "Restore" : "Rollback", job.blocksApplied, job.containerGroups,
                        job.itemOps, job.skipped);
            } catch (Exception e) {
                CoreProtectFabric.LOGGER.warn("[CoreProtect] Failed to store the undo record of an interrupted rollback", e);
            }
        }
        saveLastOperations();
    }

    private boolean isRunning(ServerPlayerEntity operator) {
        UUID id = operator == null ? null : operator.getUuid();
        for (Job job : runningJobs) {
            ServerPlayerEntity other = job.operator;
            if (id == null) {
                // console, RCON and command blocks share one "operator": refuse rather than let two
                // of their jobs interleave on the server thread
                if (other == null) return true;
            } else if (other != null && id.equals(other.getUuid())) {
                return true;
            }
        }
        return false;
    }

    /** Applies work until the slice budget runs out, then continues on a later tick. */
    private void slice(Job job) {
        long deadline = System.nanoTime() + SLICE_NANOS;
        do {
            boolean more;
            try {
                more = job.undo ? stepUndo(job) : stepRollback(job);
            } catch (Exception e) {
                // The raw exception (JDBC paths, SQL state, ...) is for the log only: the command
                // source - and with it a command block's log - gets the generic database error.
                CoreProtectFabric.LOGGER.error("[CoreProtect] Rollback failed", e);
                Messages.error(job.source, "coreprotect.error.db");
                // Half of the world has already been changed, so the undo record has to be
                // written even though the job aborted.
                finish(job, true);
                return;
            }
            if (!more) {
                finish(job, false);
                return;
            }
        } while (System.nanoTime() < deadline);
        if (job.server == null) {
            // The server is already gone: nothing more can be applied. The rest of the plan stays
            // unapplied and the caller is told instead of getting a silent partial rollback.
            CoreProtectFabric.LOGGER.warn("[CoreProtect] {} stopped: no server available ({} block(s), {} container(s), {} item(s) applied, {} skipped).",
                    job.isRestore ? "Restore" : "Rollback", job.blocksApplied, job.containerGroups, job.itemOps, job.skipped);
            Messages.error(job.source, "coreprotect.error.db", "server unavailable");
            finish(job, true);
            return;
        }
        // Keep the undo record on disk up to date while the job runs: a crash (or a kill -9)
        // halfway through a long rollback must not lose what was already applied. The record is
        // rewritten at most every five seconds and on a background thread, so neither the disk
        // nor the tick thread is hammered by every slice.
        long now = System.nanoTime();
        if (!job.undo && now - job.lastUndoSave >= UNDO_SAVE_NANOS) {
            job.lastUndoSave = now;
            commitUndo(job); // publishes the record in memory
            saveLastOperationsAsync();
        }
        job.server.execute(() -> slice(job)); // continue on the next tick
    }

    private void finish(Job job, boolean failed) {
        runningJobs.remove(job);
        if (job.undo) {
            if (!failed) {
                Messages.success(job.source, "coreprotect.undo.complete", job.blocksApplied);
            }
            return;
        }
        // The undo record is always replaced: a container-only or fully-skipped rollback must
        // not leave an older operation armed for the next /co undo.
        commitUndo(job);
        if (failed) {
            return; // the error was already reported
        }
        if (job.blocksApplied == 0 && job.itemOps == 0 && job.skipped == 0) {
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

    /** Publishes the job's undo record in memory (for {@code /co undo}), without touching the disk. */
    private void publishUndo(Job job) {
        if (job.undo || job.operator == null) return;
        lastOperations.put(job.operator.getUuid(), new LastOperation(
                job.isRestore ? "restore" : "rollback",
                new ArrayList<>(job.undos), new ArrayList<>(job.itemUndos),
                new ArrayList<>(job.worldItemUndos), new ArrayList<>(job.signUndos)));
    }

    /** Publishes the job's undo record: in memory for {@code /co undo}, on disk for the next start. */
    private void commitUndo(Job job) {
        publishUndo(job);
        saveLastOperations();
    }

    /**
     * Writes the undo file on the background thread. The file is serialized from the live map when
     * the write actually runs, so a queued write can only ever store the newest state - never an
     * older snapshot that a later {@code commitUndo} already replaced.
     */
    private void saveLastOperationsAsync() {
        if (!undoSaveQueued.compareAndSet(false, true)) return;
        undoWriter.execute(() -> {
            try {
                saveLastOperations();
            } finally {
                undoSaveQueued.set(false);
            }
        });
    }

    /** One unit of rollback work. Returns false when there is nothing left to do. */
    private boolean stepRollback(Job job) {
        if (job.groupIndex < job.containers.size()) {
            Map.Entry<PosKey, List<DatabaseManager.ContainerLog>> entry = job.containers.get(job.groupIndex);
            List<DatabaseManager.ContainerLog> list = entry.getValue();
            ServerWorld world = worldOf(job.server, entry.getKey().wid());
            if (world == null) {
                job.skipped++;
                job.groupIndex++;
                job.entryIndex = 0;
                return true;
            }
            BlockEntity be = world.getBlockEntity(entry.getKey().pos());
            if (!(be instanceof Inventory inv)) {
                // The position does not hold a container (any more), so its item transactions
                // cannot be reversed; without the counter the result line claimed
                // "containers: 0, skipped: 0" even though nothing was restored.
                job.skipped++;
                job.groupIndex++;
                job.entryIndex = 0;
                return true;
            }
            // A rollback replays the group from the newest row down, a restore from the oldest up:
            // replaying a restore newest-first ended in a different state whenever the same item had
            // been deposited and withdrawn more than once.
            int slot = job.isRestore ? list.size() - 1 - job.entryIndex : job.entryIndex;
            DatabaseManager.ContainerLog log = list.get(slot);
            job.entryIndex++;
            int done = reverseContainer(job, log, inv, world, job.isRestore);
            job.itemOps += done;
            if (done > 0) {
                // deposit: rollback removes -> undo adds; withdraw: rollback adds -> undo removes
                boolean deposit = log.type() == DatabaseManager.CONTAINER_DEPOSIT;
                boolean added = job.isRestore == deposit;
                // Only what really moved is recorded: a row can describe 64 items while the container
                // still held 32, and an undo that puts all 64 back would invent the difference.
                job.itemUndos.add(new ItemUndo(log.wid(), entry.getKey().pos(), log.data(), done, added, log.nbt()));
            } else {
                job.skipped++;
            }
            if (job.entryIndex >= list.size()) {
                // The container is final now: refresh the baseline of every player watching it,
                // so the change this rollback just made is not attributed to them later.
                ContainerTracker.rebaseline(world, entry.getKey().pos());
                job.groupIndex++;
                job.entryIndex = 0;
            }
            return true;
        }
        if (job.blockGroupIndex < job.blocks.size()) {
            Map.Entry<PosKey, List<DatabaseManager.BlockLog>> entry = job.blocks.get(job.blockGroupIndex);
            List<DatabaseManager.BlockLog> chain = entry.getValue();
            // A rollback replays the chain from the newest row down, a restore from the oldest up.
            int row = job.isRestore ? chain.size() - 1 - job.blockRowIndex : job.blockRowIndex;
            DatabaseManager.BlockLog l = chain.get(row);
            job.blockRowIndex++;
            if (job.blockRowIndex >= chain.size()) {
                job.blockGroupIndex++;
                job.blockRowIndex = 0;
            }
            ServerWorld world = worldOf(job.server, l.wid());
            if (world == null) {
                job.skipped++;
                return true;
            }
            BlockState target = BlockStateUtil.parseOrNull(job.server, job.isRestore ? l.newData() : l.oldData());
            BlockState expected = BlockStateUtil.parseOrNull(job.server, job.isRestore ? l.oldData() : l.newData());
            if (target == null || expected == null) {
                // A state that cannot be parsed (block mod removed, ...) must never be treated as
                // air: that would delete whatever stands there instead of skipping this row.
                job.skipped++;
                return true;
            }
            BlockPos pos = new BlockPos(l.x(), l.y(), l.z());
            BlockState current = world.getBlockState(pos);
            if (!current.equals(expected)) {
                // The world moved on since this row was written, or a newer row of the chain
                // already produced this state (then the position simply needs no change). Either
                // way this row cannot be replayed; the next, older one still can.
                if (!current.equals(target)) {
                    job.skipped++;
                }
                return true;
            }
            if (!target.isOf(current.getBlock()) && world.getBlockEntity(pos) instanceof Inventory inv) {
                // Replacing a block that carries an inventory removes its block entity before
                // vanilla's onStateReplaced() could scatter the contents, so they have to be
                // taken out first - otherwise the container is emptied into nothing. They are
                // dropped next to the container and recorded, so the undo takes them away again.
                ejectInventory(inv, world, pos, job, l.wid());
            }
            world.setBlockState(pos, target, Block.NOTIFY_ALL);
            BlockStateUtil.applySignText(world, pos, l.meta());
            job.blocksApplied++;
            job.undos.add(new BlockUndo(l.wid(), pos,
                    BlockStateUtil.stringify(target), BlockStateUtil.stringify(expected)));
            return true;
        }
        if (job.signIndex < job.signs.size()) {
            SignTarget target = job.signs.get(job.signIndex++);
            ServerWorld world = worldOf(job.server, target.pos().wid());
            String meta = target.text();
            if (world == null || meta == null) {
                // co_sign only stores the text a row wrote, so the text from before the oldest
                // matched edit has to come from the position's own history; when that cannot be
                // determined the position is reported as skipped instead of guessed.
                job.skipped++;
                return true;
            }
            BlockPos pos = target.pos().pos();
            String previous = BlockStateUtil.replaceSignText(world, pos, meta);
            if (previous == null) {
                job.skipped++; // no sign at that position (any more)
                return true;
            }
            job.signUndos.add(new SignUndo(target.pos().wid(), pos, previous));
            job.blocksApplied++; // a restored sign text is reported with the block counter
            return true;
        }
        if (job.itemIndex < job.items.size()) {
            DatabaseManager.ItemLog l = job.items.get(job.itemIndex++);
            ServerWorld world = worldOf(job.server, l.wid());
            if (world == null || job.isRestore || !"-".equals(l.action())) {
                // Only a dropped stack can be reverted without inventing items: re-applying a
                // pickup (restore) or undoing one (rollback of a '+') would hand out a second copy
                // of what the player already carries, so those rows are reported as skipped.
                job.skipped++;
                return true;
            }
            Identifier id = Identifier.tryParse(l.data());
            Item item = id == null ? null : Registries.ITEM.get(id);
            if (item == null || item == Items.AIR) {
                job.skipped++;
                return true;
            }
            BlockPos pos = new BlockPos(l.x(), l.y(), l.z());
            // The row's data components describe the stack that was dropped; a row written before
            // they were stored falls back to a plain stack of that item.
            ItemStack template = ItemStackCodec.decode(l.nbt(), world.getRegistryManager());
            if (template == null) {
                template = new ItemStack(item);
            }
            int removed = removeDroppedItems(world, pos, template, l.amount());
            if (removed <= 0) {
                job.skipped++; // the stack is gone (picked up, burnt, despawned, moved)
                return true;
            }
            job.worldItemUndos.add(new WorldItemUndo(l.wid(), pos, l.data(), removed, false, l.nbt()));
            job.itemOps++;
            return true;
        }
        if (job.entityIndex < job.entities.size()) {
            // A kill or a death is logged with the entity type and no NBT, so respawning it would
            // invent a blank mob (or a second boss) rather than restore what was killed. Those
            // rows are reported as skipped instead of being silently covered by "Rollback
            // complete".
            job.entityIndex++;
            job.skipped++;
            return true;
        }
        return false;
    }

    /** One unit of undo work. Returns false when there is nothing left to do. */
    private boolean stepUndo(Job job) {
        LastOperation op = job.operation;
        if (job.blockUndoIndex < op.blocks().size()) {
            // Undo runs backwards: the change a rollback made last has to be reverted first,
            // which also makes the replayed per-position chains come out right.
            BlockUndo u = op.blocks().get(op.blocks().size() - 1 - job.blockUndoIndex);
            job.blockUndoIndex++;
            ServerWorld world = worldOf(job.server, u.wid());
            if (world == null) return true;
            BlockState expected = BlockStateUtil.parseOrNull(job.server, u.expected());
            BlockState apply = BlockStateUtil.parseOrNull(job.server, u.apply());
            if (expected == null || apply == null) return true; // nothing to restore from
            if (!world.getBlockState(u.pos()).equals(expected)) return true;
            world.setBlockState(u.pos(), apply, Block.NOTIFY_ALL);
            job.blocksApplied++;
            return true;
        }
        if (job.undoIndex < op.items().size()) {
            ItemUndo u = op.items().get(job.undoIndex);
            job.undoIndex++;
            ServerWorld world = worldOf(job.server, u.wid());
            if (world == null) return true;
            BlockEntity be = world.getBlockEntity(u.pos());
            if (!(be instanceof Inventory inv)) return true;
            Identifier id = Identifier.tryParse(u.item());
            if (id == null) return true;
            Item item = Registries.ITEM.get(id);
            if (item == null || item == Items.AIR) return true;
            // The stored data components decide what the stack actually was; without them an enchanted
            // item would be restored as a plain one and a filled shulker box as an empty one.
            ItemStack template = ItemStackCodec.decode(u.nbt(), world.getRegistryManager());
            if (template == null) {
                template = new ItemStack(item);
            }
            if (u.wasAdded()) {
                // the rollback put it into the container; the undo takes it out again, and what no
                // longer fits is dropped (it is on the ground either way, so nothing is created)
                removeItems(inv, template, u.amount());
            } else {
                addItems(inv, template.copyWithCount(u.amount()), world, u.pos(), null, null);
            }
            // the undo changed the container as well, so the watchers' baseline has to follow
            ContainerTracker.rebaseline(world, u.pos());
            job.itemsApplied++;
            return true;
        }
        if (job.signUndoIndex < op.signs().size()) {
            SignUndo u = op.signs().get(job.signUndoIndex++);
            ServerWorld world = worldOf(job.server, u.wid());
            if (world == null) return true;
            if (BlockStateUtil.replaceSignText(world, u.pos(), u.text()) != null) {
                job.blocksApplied++;
            }
            return true;
        }
        if (job.worldItemIndex < op.worldItems().size()) {
            WorldItemUndo u = op.worldItems().get(job.worldItemIndex++);
            ServerWorld world = worldOf(job.server, u.wid());
            if (world == null) return true;
            Identifier id = Identifier.tryParse(u.item());
            Item item = id == null ? null : Registries.ITEM.get(id);
            if (item == null || item == Items.AIR) return true;
            // The stored data components decide what the stack actually was; without them an enchanted
            // drop would come back as a plain one.
            ItemStack template = ItemStackCodec.decode(u.nbt(), world.getRegistryManager());
            if (template == null) {
                template = new ItemStack(item);
            }
            if (u.added()) {
                // the rollback dropped these (it emptied a container that was replaced); the undo
                // picks them off the ground again, and what was already taken stays taken
                removeDroppedItems(world, u.pos(), template, u.amount());
            } else {
                dropItems(world, u.pos(), template, u.amount());
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

    /**
     * Resolves the text a sign has to be set to. co_sign stores the text a row wrote and nothing
     * else, so for a rollback the text of the position's next older row is the one the players
     * lost when the oldest matched row was written; a restore re-applies the newest matched row.
     * Positions whose "before" text is unknown stay {@code null} and are reported as skipped.
     */
    private static List<SignTarget> resolveSignTargets(CoreProtectFabric mod, List<DatabaseManager.SignLog> signLogs,
                                                       boolean isRestore) {
        List<SignTarget> targets = new ArrayList<>();
        if (signLogs == null || signLogs.isEmpty()) return targets;
        Map<PosKey, List<DatabaseManager.SignLog>> chains = new LinkedHashMap<>();
        for (DatabaseManager.SignLog log : signLogs) {
            chains.computeIfAbsent(PosKey.of(log.wid(), log.x(), log.y(), log.z()), k -> new ArrayList<>()).add(log);
        }
        for (Map.Entry<PosKey, List<DatabaseManager.SignLog>> entry : chains.entrySet()) {
            List<DatabaseManager.SignLog> chain = entry.getValue();
            PosKey key = entry.getKey();
            String target = null;
            if (isRestore) {
                target = chain.get(0).data();
            } else {
                DatabaseManager.SignLog oldest = chain.get(chain.size() - 1);
                List<DatabaseManager.SignLog> history = mod.database()
                        .querySignHistory(key.wid(), key.x(), key.y(), key.z(), SIGN_HISTORY_LIMIT);
                if (history != null) {
                    for (DatabaseManager.SignLog log : history) { // newest first
                        if (log.id() >= oldest.id()) continue;
                        // only the same face: the other side's text is no replacement
                        if (BlockStateUtil.signIsBack(log.data()) == BlockStateUtil.signIsBack(oldest.data())) {
                            target = log.data();
                        }
                        break;
                    }
                }
            }
            targets.add(new SignTarget(key, target));
        }
        return targets;
    }

    /**
     * Replays one container row and returns how many items really moved: 0 when nothing did, which
     * is what keeps {@code /co undo} from inventing them (a row may describe 64 items while the
     * container only still held 32).
     */
    private int reverseContainer(Job job, DatabaseManager.ContainerLog log, Inventory inv, ServerWorld world, boolean isRestore) {
        // deposit (+N): rollback removes N, restore adds N; withdraw (-N): rollback adds N, restore removes N.
        boolean deposit = log.type() == DatabaseManager.CONTAINER_DEPOSIT;
        boolean add = isRestore == deposit;
        Identifier id = Identifier.tryParse(log.data());
        if (id == null) return 0;
        Item item = Registries.ITEM.get(id);
        if (item == null || item == Items.AIR) return 0;
        BlockPos pos = new BlockPos(log.x(), log.y(), log.z());
        // The row's data components describe the stack that was moved; a row written before they were
        // stored falls back to a plain stack of that item.
        ItemStack template = ItemStackCodec.decode(log.nbt(), world.getRegistryManager());
        if (template == null) {
            template = new ItemStack(item);
        }
        if (add) {
            return addItems(inv, template.copyWithCount(log.amount()), world, pos, job, log.wid());
        }
        return removeItems(inv, template, log.amount());
    }

    /**
     * Puts {@code remaining} into the container and returns how much of it fit. What does not fit is
     * dropped next to the container and recorded, so the undo takes it away again instead of leaving
     * a second copy of it in the world.
     */
    private static int addItems(Inventory inv, ItemStack remaining, ServerWorld world, BlockPos pos,
                                Job job, String wid) {
        int stored = 0;
        for (int i = 0; i < inv.size() && !remaining.isEmpty(); i++) {
            ItemStack slot = inv.getStack(i);
            if (slot.isEmpty()) {
                if (!inv.isValid(i, remaining)) continue;
                // never place more than one stack's worth in a single slot
                int move = Math.min(remaining.getCount(), remaining.getMaxCount());
                inv.setStack(i, remaining.copyWithCount(move));
                remaining.decrement(move);
                stored += move;
            } else if (ItemStack.areItemsAndComponentsEqual(slot, remaining) && slot.getCount() < slot.getMaxCount()) {
                int move = Math.min(remaining.getCount(), slot.getMaxCount() - slot.getCount());
                slot.increment(move);
                // write the mutated stack back: setStack is what marks the inventory dirty, so a
                // save right after a rollback keeps the merged stack and viewers see it
                inv.setStack(i, slot);
                remaining.decrement(move);
                stored += move;
            }
        }
        if (!remaining.isEmpty()) {
            if (job != null) {
                job.worldItemUndos.add(new WorldItemUndo(wid, pos, Registries.ITEM.getId(remaining.getItem()).toString(),
                        remaining.getCount(), true, ItemStackCodec.encodeIdentity(remaining, world.getRegistryManager())));
            }
            dropStack(world, pos, remaining);
        }
        return stored;
    }

    /**
     * Takes {@code amount} of the stack described by {@code template} out of the container and
     * returns how much was really taken. Only what the container holds is taken: the missing rest
     * used to be handed out as well, which invented items out of nothing (a rollback of a deposit
     * that was already partly withdrawn paid them out again). The data components are compared as
     * well, so taking an enchanted item cannot consume a plain one.
     */
    private static int removeItems(Inventory inv, ItemStack template, int amount) {
        int remaining = amount;
        for (int i = 0; i < inv.size() && remaining > 0; i++) {
            ItemStack slot = inv.getStack(i);
            if (ItemStack.areItemsAndComponentsEqual(slot, template)) {
                int take = Math.min(slot.getCount(), remaining);
                remaining -= take;
                if (take >= slot.getCount()) {
                    inv.setStack(i, ItemStack.EMPTY);
                } else {
                    slot.decrement(take);
                    // setStack, not a bare decrement: it marks the inventory dirty so the change
                    // survives a save and reaches the players who have the container open
                    inv.setStack(i, slot);
                }
            }
        }
        return amount - remaining;
    }

    /**
     * Empties a container that is about to be replaced. The block entity is removed before
     * vanilla's {@code onStateReplaced()} runs, so the contents would vanish; they are dropped next
     * to the container - where a player can pick them up - and recorded, so {@code /co undo} takes
     * them off the ground again instead of restoring an empty container and leaving a copy behind.
     * (Handing them to the command's operator was worse: a console rollback dropped them into the
     * void, and the items were never part of the undo record.)
     */
    private static void ejectInventory(Inventory inv, ServerWorld world, BlockPos pos, Job job, String wid) {
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            inv.setStack(i, ItemStack.EMPTY);
            if (job != null) {
                job.worldItemUndos.add(new WorldItemUndo(wid, pos,
                        Registries.ITEM.getId(stack.getItem()).toString(), stack.getCount(), true,
                        ItemStackCodec.encodeIdentity(stack, world.getRegistryManager())));
            }
            dropStack(world, pos, stack);
        }
    }

    /**
     * Removes up to {@code amount} of the stack described by {@code template} from the dropped stacks
     * inside one block. Only drops are reversible this way: the log stores the position and the item,
     * but not which entity it was, so stacks that were picked up or moved away are left alone and
     * reported. The data components are compared as well, so an enchanted drop cannot be satisfied
     * by a plain one lying next to it.
     */
    private static int removeDroppedItems(ServerWorld world, BlockPos pos, ItemStack template, int amount) {
        int remaining = amount;
        for (Entity entity : world.getOtherEntities(null, new Box(pos), e -> e instanceof ItemEntity)) {
            if (remaining <= 0) break;
            ItemEntity itemEntity = (ItemEntity) entity;
            ItemStack stack = itemEntity.getStack();
            if (!ItemStack.areItemsAndComponentsEqual(stack, template)) continue;
            int take = Math.min(stack.getCount(), remaining);
            remaining -= take;
            if (take >= stack.getCount()) {
                itemEntity.discard();
            } else {
                // the stack is a tracked value, so it has to be replaced instead of reduced
                itemEntity.setStack(stack.copyWithCount(stack.getCount() - take));
            }
        }
        return amount - remaining;
    }

    /** Drops {@code amount} of the stack described by {@code template}, split into legal stacks. */
    private static void dropItems(ServerWorld world, BlockPos pos, ItemStack template, int amount) {
        int max = Math.max(1, template.getMaxCount());
        int remaining = amount;
        while (remaining > 0) {
            int move = Math.min(remaining, max);
            dropItem(world, pos, template.copyWithCount(move));
            remaining -= move;
        }
    }

    /** Drops a whole stack, split into stacks the item can actually hold. */
    private static void dropStack(ServerWorld world, BlockPos pos, ItemStack stack) {
        dropItems(world, pos, stack, stack.getCount());
    }

    private static void dropItem(ServerWorld world, BlockPos pos, ItemStack stack) {
        world.spawnEntity(new ItemEntity(world, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack));
    }

    private static ServerWorld worldOf(MinecraftServer server, String wid) {
        if (server == null || wid == null) return null;
        Identifier id = Identifier.tryParse(wid);
        if (id == null) return null;
        return server.getWorld(RegistryKey.of(RegistryKeys.WORLD, id));
    }

    // ------------------------------------------------------------------
    // Undo store (finding C12: the record has to outlive the process)
    // ------------------------------------------------------------------

    private Path undoFilePath() {
        return FabricLoader.getInstance().getConfigDir().resolve(UNDO_FILE);
    }

    private synchronized void ensureUndoLoaded() {
        if (undoLoaded) return;
        undoLoaded = true;
        Path path = undoFilePath();
        if (!Files.exists(path)) return;
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(path));
            if (!parsed.isJsonObject()) return;
            JsonObject root = parsed.getAsJsonObject();
            JsonElement version = root.get("version");
            if (version != null && version.getAsInt() > UNDO_FILE_VERSION) {
                CoreProtectFabric.LOGGER.warn("[CoreProtect] {} was written by a newer version; /co undo starts empty", UNDO_FILE);
                return;
            }
            JsonElement players = root.get("players");
            if (players == null || !players.isJsonObject()) return;
            for (Map.Entry<String, JsonElement> entry : players.getAsJsonObject().entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                UUID id;
                try {
                    id = UUID.fromString(entry.getKey());
                } catch (IllegalArgumentException e) {
                    continue;
                }
                lastOperations.put(id, readOperation(entry.getValue().getAsJsonObject()));
            }
        } catch (Exception e) {
            CoreProtectFabric.LOGGER.warn("[CoreProtect] Could not read " + UNDO_FILE + "; /co undo starts empty", e);
        }
    }

    private synchronized void saveLastOperations() {
        ensureUndoLoaded(); // never drop a record an earlier session stored
        JsonObject root = new JsonObject();
        root.addProperty("version", UNDO_FILE_VERSION);
        JsonObject players = new JsonObject();
        for (Map.Entry<UUID, LastOperation> entry : lastOperations.entrySet()) {
            players.add(entry.getKey().toString(), writeOperation(entry.getValue()));
        }
        root.add("players", players);
        try {
            Files.writeString(undoFilePath(), root.toString());
        } catch (Exception e) {
            CoreProtectFabric.LOGGER.warn("[CoreProtect] Failed to store the undo record", e);
        }
    }

    private static JsonObject writeOperation(LastOperation op) {
        JsonObject out = new JsonObject();
        out.addProperty("kind", op.kind());
        JsonArray blocks = new JsonArray();
        for (BlockUndo u : op.blocks()) {
            JsonObject o = position(u.wid(), u.pos());
            o.addProperty("at", u.expected());
            o.addProperty("to", u.apply());
            blocks.add(o);
        }
        out.add("blocks", blocks);
        JsonArray items = new JsonArray();
        for (ItemUndo u : op.items()) {
            JsonObject o = position(u.wid(), u.pos());
            o.addProperty("item", u.item());
            o.addProperty("amount", u.amount());
            o.addProperty("added", u.wasAdded());
            if (u.nbt() != null) {
                // stored so an undo restores the same enchanted/filled stack the rollback moved
                o.addProperty("nbt", u.nbt());
            }
            items.add(o);
        }
        out.add("items", items);
        JsonArray worldItems = new JsonArray();
        for (WorldItemUndo u : op.worldItems()) {
            JsonObject o = position(u.wid(), u.pos());
            o.addProperty("item", u.item());
            o.addProperty("amount", u.amount());
            o.addProperty("added", u.added());
            if (u.nbt() != null) {
                o.addProperty("nbt", u.nbt());
            }
            worldItems.add(o);
        }
        out.add("world_items", worldItems);
        JsonArray signs = new JsonArray();
        for (SignUndo u : op.signs()) {
            JsonObject o = position(u.wid(), u.pos());
            o.addProperty("text", u.text());
            signs.add(o);
        }
        out.add("signs", signs);
        return out;
    }

    private static LastOperation readOperation(JsonObject o) {
        String kind = string(o, "kind");
        List<BlockUndo> blocks = new ArrayList<>();
        for (JsonElement e : array(o, "blocks")) {
            JsonObject b = e.getAsJsonObject();
            blocks.add(new BlockUndo(string(b, "w"), position(b), string(b, "at"), string(b, "to")));
        }
        List<ItemUndo> items = new ArrayList<>();
        for (JsonElement e : array(o, "items")) {
            JsonObject b = e.getAsJsonObject();
            items.add(new ItemUndo(string(b, "w"), position(b), string(b, "item"), integer(b, "amount"), bool(b, "added"), string(b, "nbt")));
        }
        List<WorldItemUndo> worldItems = new ArrayList<>();
        for (JsonElement e : array(o, "world_items")) {
            JsonObject b = e.getAsJsonObject();
            worldItems.add(new WorldItemUndo(string(b, "w"), position(b), string(b, "item"), integer(b, "amount"),
                    bool(b, "added"), string(b, "nbt")));
        }
        List<SignUndo> signs = new ArrayList<>();
        for (JsonElement e : array(o, "signs")) {
            JsonObject b = e.getAsJsonObject();
            signs.add(new SignUndo(string(b, "w"), position(b), string(b, "text")));
        }
        return new LastOperation(kind == null ? "rollback" : kind, blocks, items, worldItems, signs);
    }

    private static JsonObject position(String wid, BlockPos pos) {
        JsonObject o = new JsonObject();
        o.addProperty("w", wid);
        o.addProperty("x", pos.getX());
        o.addProperty("y", pos.getY());
        o.addProperty("z", pos.getZ());
        return o;
    }

    private static BlockPos position(JsonObject o) {
        return new BlockPos(integer(o, "x"), integer(o, "y"), integer(o, "z"));
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || !e.isJsonPrimitive() ? null : e.getAsString();
    }

    private static int integer(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || !e.isJsonPrimitive() ? 0 : e.getAsInt();
    }

    private static boolean bool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsBoolean();
    }

    private static JsonArray array(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }
}
