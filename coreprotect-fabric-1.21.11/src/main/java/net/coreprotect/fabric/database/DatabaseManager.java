package net.coreprotect.fabric.database;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import net.coreprotect.fabric.CoreProtectFabric;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

/**
 * SQLite storage, close to the CoreProtect schema.
 *
 * <p><b>Space</b>: block states and item/material strings are stored once in the
 * {@code co_state} dictionary and referenced by integer id from the log tables
 * (schema v2). Legacy v1 databases are migrated automatically (with a one-time
 * backup), the text columns are dropped, and the file is rebuilt with
 * {@code auto_vacuum=INCREMENTAL}; {@code /co purge} compacts the file afterwards.
 *
 * <p><b>Speed</b>: log rows are queued and written in batches (one transaction per
 * {@code database.batchMaxRows} rows or {@code database.batchIntervalMs} milliseconds,
 * whichever comes first), which replaces one fsync per row with one per batch. Writes run on
 * a single writer thread (WAL) and reads run on a parallel pool (up to 8 threads, one SQLite
 * connection each). Queries never run on the server thread: {@link #submitRead} returns the
 * result there when it is ready. Connections use a large page cache and memory-mapped I/O.
 */
public final class DatabaseManager {

    public record BlockLog(long id, long time, String user, String wid, int x, int y, int z,
                           int type, String oldData, String newData, String action, String meta) {
    }

    public record ContainerLog(long id, long time, String user, String wid, int x, int y, int z,
                               int type, String data, int amount, String nbt) {
        /** Backwards-compatible constructor for rows logged before {@code nbt} existed. */
        public ContainerLog(long id, long time, String user, String wid, int x, int y, int z,
                            int type, String data, int amount) {
            this(id, time, user, wid, x, y, z, type, data, amount, null);
        }
    }

    /** Item drops ({@code -}) and pickups ({@code +}). {@code nbt} carries the data components. */
    public record ItemLog(long id, long time, String user, String wid, int x, int y, int z,
                          String action, String data, int amount, String nbt) {
        /** Backwards-compatible constructor for rows logged before {@code nbt} existed. */
        public ItemLog(long id, long time, String user, String wid, int x, int y, int z,
                       String action, String data, int amount) {
            this(id, time, user, wid, x, y, z, action, data, amount, null);
        }
    }

    /** Sign text edits; {@code data} is the JSON of the new front lines. */
    public record SignLog(long id, long time, String user, String wid, int x, int y, int z,
                          String data) {
    }

    public record EntityLog(long id, long time, String user, String wid, int x, int y, int z,
                            String data, String action) {
    }

    /** Used for sessions, commands and chat. */
    public record MessageLog(long id, long time, String user, String message, String action) {
    }

    // block types
    public static final int TYPE_BREAK = 0;
    public static final int TYPE_PLACE = 1;
    public static final int TYPE_NATURAL = 2;
    // container types
    public static final int CONTAINER_DEPOSIT = 0;
    public static final int CONTAINER_WITHDRAW = 1;

    /** Schema v3: user/world/action strings live in dictionaries, indexed by integer id. */
    private static final String[] DDL_V3 = {
            "CREATE TABLE IF NOT EXISTS co_state (id INTEGER PRIMARY KEY, state TEXT NOT NULL UNIQUE)",
            "CREATE TABLE IF NOT EXISTS co_name (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE)",
            "CREATE TABLE IF NOT EXISTS co_world (id INTEGER PRIMARY KEY, wid TEXT NOT NULL UNIQUE)",
            "CREATE TABLE IF NOT EXISTS co_action (id INTEGER PRIMARY KEY, action TEXT NOT NULL UNIQUE)",
            "CREATE TABLE IF NOT EXISTS co_block (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, name_id INTEGER NOT NULL, wid_id INTEGER NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, type INTEGER NOT NULL, old_id INTEGER, new_id INTEGER, action_id INTEGER, meta TEXT)",
            "CREATE INDEX IF NOT EXISTS idx_block_pos ON co_block(wid_id, x, y, z)",
            "CREATE INDEX IF NOT EXISTS idx_block_time ON co_block(time, name_id)",
            "CREATE TABLE IF NOT EXISTS co_container (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, name_id INTEGER NOT NULL, wid_id INTEGER NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, type INTEGER NOT NULL, data_id INTEGER, amount INTEGER NOT NULL, nbt TEXT)",
            "CREATE INDEX IF NOT EXISTS idx_container_pos ON co_container(wid_id, x, y, z)",
            "CREATE INDEX IF NOT EXISTS idx_container_time ON co_container(time, name_id)",
            "CREATE TABLE IF NOT EXISTS co_item (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, name_id INTEGER NOT NULL, wid_id INTEGER NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, action_id INTEGER, data_id INTEGER, amount INTEGER NOT NULL, nbt TEXT)",
            "CREATE INDEX IF NOT EXISTS idx_item_pos ON co_item(wid_id, x, y, z)",
            "CREATE INDEX IF NOT EXISTS idx_item_time ON co_item(time, name_id)",
            "CREATE TABLE IF NOT EXISTS co_sign (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, name_id INTEGER NOT NULL, wid_id INTEGER NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, data TEXT NOT NULL)",
            "CREATE INDEX IF NOT EXISTS idx_sign_pos ON co_sign(wid_id, x, y, z)",
            "CREATE INDEX IF NOT EXISTS idx_sign_time ON co_sign(time, name_id)",
            "CREATE TABLE IF NOT EXISTS co_entity (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, name_id INTEGER NOT NULL, wid_id INTEGER NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, data_id INTEGER, action_id INTEGER)",
            // the other four position tables have this index; without it every r: lookup and
            // single-block entity history scanned the whole entity table
            "CREATE INDEX IF NOT EXISTS idx_entity_pos ON co_entity(wid_id, x, y, z)",
            "CREATE INDEX IF NOT EXISTS idx_entity_time ON co_entity(time, name_id)",
            "CREATE TABLE IF NOT EXISTS co_session (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, name_id INTEGER NOT NULL, wid_id INTEGER NOT NULL, action_id INTEGER)",
            "CREATE INDEX IF NOT EXISTS idx_session_time ON co_session(time, name_id)",
            // co_command/co_chat are the only log tables without a position, but they still need the
            // world so that a multi-world server can search chat and commands per dimension (L3-21).
            "CREATE TABLE IF NOT EXISTS co_command (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, name_id INTEGER NOT NULL, wid_id INTEGER, message TEXT NOT NULL)",
            "CREATE INDEX IF NOT EXISTS idx_command_time ON co_command(time, name_id)",
            "CREATE TABLE IF NOT EXISTS co_chat (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, name_id INTEGER NOT NULL, wid_id INTEGER, message TEXT NOT NULL)",
            "CREATE INDEX IF NOT EXISTS idx_chat_time ON co_chat(time, name_id)",
            // Language preferences are keyed by player name for backwards compatibility, but the uuid
            // is stored alongside it and mirrored into co_user_uuid so a rename keeps the setting (L4-15).
            "CREATE TABLE IF NOT EXISTS co_user (user TEXT PRIMARY KEY, language TEXT, uuid TEXT)",
            "CREATE TABLE IF NOT EXISTS co_user_uuid (uuid TEXT PRIMARY KEY, language TEXT)"
    };

    /** Old v2 indexes, dropped while the text columns are replaced by dictionary ids. */
    private static final String[] LEGACY_V2_INDEXES = {
            "idx_block_pos", "idx_block_user", "idx_block_time",
            "idx_container_pos", "idx_container_user", "idx_container_time",
            "idx_item_pos", "idx_item_user", "idx_item_time",
            "idx_sign_pos", "idx_sign_user", "idx_sign_time",
            "idx_entity_user", "idx_entity_time",
            "idx_session_time", "idx_session_user",
            "idx_command_time", "idx_command_user",
            "idx_chat_time", "idx_chat_user"
    };

    private static final String[] COUNT_TABLES = {
            "co_block", "co_container", "co_item", "co_sign", "co_entity", "co_session", "co_command", "co_chat"};

    private static final int READ_THREADS = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));

    /** How long {@link #close()} waits for the writer thread before it gives up and interrupts it. */
    private static final int CLOSE_TIMEOUT_SECONDS = 30;

    /** Bounds the final drain: a batch that keeps failing must not keep the shutdown spinning. */
    private static final long DRAIN_TIMEOUT_NANOS = 30_000_000_000L;
    private static final int DRAIN_MAX_FAILURES = 3;

    /** Overall budget for the per-table counts, so {@code /co status} cannot stall for minutes. */
    private static final long COUNTS_BUDGET_NANOS = 20_000_000_000L;
    private static final int COUNT_QUERY_TIMEOUT_SECONDS = 5;

    /**
     * Consecutive failed batches before the queued rows are dropped. A batch that always fails
     * (a poisoned row, a read-only or full disk) used to be retried every second forever, which
     * filled the queue and lost every new row instead of the batch that actually failed.
     */
    private static final int MAX_FLUSH_FAILURES = 6;
    /** Upper bound of the per-batch retry delay (1 s, 2 s, 4 s, ... capped here). */
    private static final long MAX_FLUSH_BACKOFF_MS = 30_000L;

    /** How often a failed {@link #open()} is retried in the background (the listeners stay registered). */
    private static final int OPEN_RETRY_ATTEMPTS = 3;
    private static final int OPEN_RETRY_SECONDS = 30;

    /** A purge deletes in one transaction; on a large database that may legitimately take minutes. */
    private static final long PURGE_TIMEOUT_SECONDS = 600;

    /** Pages freed per {@code incremental_vacuum} call: bounds how long the writer thread is blocked. */
    private static final int INCREMENTAL_VACUUM_PAGES = 2000;

    /**
     * The single writer thread. Not final: after a {@link #close()} (a {@code /co reload} that
     * reloads the config, or a failed open) the executors are shut down, and reopening the
     * database has to start fresh ones instead of silently rejecting every task.
     */
    private ScheduledExecutorService worker = newWriter();
    /** Parallel read pool, replaced together with the writer thread. */
    private ExecutorService readPool = newReadPool();
    /** True while the current thread is a read-pool worker (same-pool nesting would deadlock). */
    private final ThreadLocal<Boolean> poolReader = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private final ThreadLocal<Object[]> readHolder = new ThreadLocal<>();
    /** Every open read connection, so they can all be closed before the database file is moved. */
    private final Set<Connection> readConnections = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Set on the calling thread when its last read failed, so callers can report it. */
    private final ThreadLocal<Boolean> lastReadFailed = ThreadLocal.withInitial(() -> Boolean.FALSE);
    /** Bumped after an automatic restore so reader threads reopen their connections. */
    private final java.util.concurrent.atomic.AtomicInteger generation = new java.util.concurrent.atomic.AtomicInteger();

    /** Recently seen player names (for /co lookup u: suggestions). */
    private final Set<String> recentUsers = Collections.synchronizedSet(new LinkedHashSet<>());

    /** String -> dictionary id cache, used by the writer thread only (LRU by access order). */
    private final Map<String, Long> stateCache = Collections.synchronizedMap(new LinkedHashMap<>(2048, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > 8192;
        }
    });

    /** Player names, world ids and action/cause strings, same caching scheme as {@link #stateCache}. */
    private final Map<String, Long> nameIds = dictionaryCache();
    private final Map<String, Long> worldIds = dictionaryCache();
    private final Map<String, Long> actionIds = dictionaryCache();

    private Connection conn;
    private volatile boolean open;
    /**
     * Set by {@link #close()}: a background open retry must not resurrect a database whose owner
     * already shut it down (that would leave a writer thread and a connection running forever).
     */
    private volatile boolean closed;
    private Path dbPath;

    // ------------------------------------------------------------------
    // Batched writes
    //
    // Every log row used to be its own autocommit statement, so it was also its own WAL
    // transaction and - with the default synchronous=FULL - its own fsync. A busy server
    // produces hundreds of rows per second, and that many fsyncs saturate the disk queue,
    // which is what shows up as MSPT spikes. Rows are now queued and written in one
    // transaction per batch (measured ~100x cheaper per row, see
    // .build-tools/perf/PERF-NOTES.md).
    // ------------------------------------------------------------------

    /** Queued writes, drained by {@link #flushWrites()} inside a single transaction. */
    private final ArrayBlockingQueue<Runnable> writeQueue = new ArrayBlockingQueue<>(65536);
    /** True while a flush is already scheduled, so a burst does not queue thousands of them. */
    private final AtomicBoolean flushScheduled = new AtomicBoolean();
    /** Rows dropped because the queue was full (only possible during a sustained disk stall). */
    private final AtomicLong droppedWrites = new AtomicLong();
    /** Transactions committed since startup, reported by /co status. */
    private final AtomicLong committedBatches = new AtomicLong();
    /** Reused prepared statements (writer thread only). */
    private final Map<String, PreparedStatement> statements = new HashMap<>();
    /** Non-zero while the last batch failed: wait this long before retrying instead of spinning. */
    private volatile long flushRetryMs;
    /** Consecutive failed batches, drives both the retry delay and the give-up decision. */
    private int flushFailures;
    /** One-shot flag: b: cannot be applied to sign lookups, so warn once instead of per query. */
    private volatile boolean signBlockFilterWarned;
    /** One-shot flag: chat/command/session rows have no position, so r: cannot be applied. */
    private volatile boolean messageRadiusFilterWarned;

    private static ScheduledExecutorService newWriter() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "CoreProtect-DB");
            t.setDaemon(true);
            return t;
        });
    }

    private static ExecutorService newReadPool() {
        return Executors.newFixedThreadPool(READ_THREADS, r -> {
            Thread t = new Thread(r, "CoreProtect-Read");
            t.setDaemon(true);
            return t;
        });
    }

    /** Restarts the executors when a previous {@link #close()} shut them down (used by open()). */
    private void ensureExecutors() {
        boolean restarted = false;
        if (worker.isShutdown()) {
            worker = newWriter();
            restarted = true;
        }
        if (readPool.isShutdown()) {
            readPool = newReadPool();
            restarted = true;
        }
        if (restarted) {
            // a flush scheduled on the old executor is gone; allow a new one to be queued
            flushScheduled.set(false);
            flushRetryMs = 0;
        }
    }

    public boolean isOpen() {
        return open;
    }

    /** Resolved SQLite file location (absolute path). */
    public Path dbPath() {
        return dbPath;
    }

    public void open() {
        Path configured = Path.of(CoreProtectFabric.instance().config().databaseFile);
        Path resolved = configured.isAbsolute()
                ? configured
                : FabricLoader.getInstance().getGameDir().resolve(configured);
        dbPath = resolved.toAbsolutePath().normalize();
        try {
            Path parent = dbPath.getParent();
            if (parent != null) {
                java.nio.file.Files.createDirectories(parent);
            }
        } catch (Exception e) {
            CoreProtectFabric.LOGGER.error("[CoreProtect] Failed to create database directory", e);
        }
        CoreProtectFabric.LOGGER.info("[CoreProtect] Using database file: {}", dbPath);
        ensureExecutors();
        closed = false;
        try {
            // The one-time v1->v2 migration may take a while on large databases; wait patiently.
            // The cast keeps the compiler from having to choose between submit(Callable) and submit(Runnable).
            worker.submit((Callable<Void>) this::openWriter).get(30, TimeUnit.MINUTES);
        } catch (java.util.concurrent.TimeoutException e) {
            // the writer thread is still inside the task (a migration of a huge file): retrying
            // would run a second open on top of it
            CoreProtectFabric.LOGGER.error("[CoreProtect] Opening the database took longer than 30 minutes; "
                    + "logging stays disabled until the open finishes.", e);
            return;
        } catch (Exception e) {
            // The listeners and commands are registered regardless of this failure, so a single log
            // line used to hide that every log row was being dropped until the next restart.
            CoreProtectFabric.LOGGER.error("[CoreProtect] Failed to open the database {} - block, container, item, "
                    + "sign, entity, session, chat and command logging are DISABLED until an open succeeds. Check the "
                    + "path, the file permissions and the free disk space. Retrying {} time(s) in the background.",
                    dbPath, OPEN_RETRY_ATTEMPTS, e);
            scheduleOpenRetry(OPEN_RETRY_ATTEMPTS);
            return;
        }
        afterOpen();
    }

    /** Opens the writer connection and initializes the schema. Runs on the writer thread. */
    private Void openWriter() throws Exception {
        Class.forName("org.sqlite.JDBC");
        Connection fresh = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try {
            applyPragmas(fresh, false);
            initSchema(fresh);
        } catch (Exception e) {
            try {
                fresh.close();
            } catch (SQLException ignored) {
            }
            throw e;
        }
        conn = fresh;
        open = true;
        return null;
    }

    /** Post-open work: retention/compaction, the queue left over from a previous run, maintenance. */
    private void afterOpen() {
        if (legacyMigrated) {
            // rebuild the file in the background so the dropped text columns actually free disk space
            vacuumAsync();
        }
        if (!writeQueue.isEmpty()) {
            scheduleFlush(0); // rows queued before a close/reopen are still waiting
        }
        scheduleCheckpoints();
        scheduleBackups();
    }

    /** Retries a failed {@link #open()} on the writer thread instead of staying dead until a restart. */
    private void scheduleOpenRetry(int attemptsLeft) {
        if (attemptsLeft <= 0 || closed) return;
        try {
            worker.schedule(() -> {
                if (open || closed) return;
                try {
                    openWriter();
                    CoreProtectFabric.LOGGER.info("[CoreProtect] Database opened on retry ({}).", dbPath);
                    afterOpen();
                } catch (Exception e) {
                    CoreProtectFabric.LOGGER.warn("[CoreProtect] Database open retry failed ({} attempt(s) left): {}",
                            attemptsLeft - 1, e.toString());
                    scheduleOpenRetry(attemptsLeft - 1);
                }
            }, OPEN_RETRY_SECONDS, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            // the writer thread is shutting down: nothing left to retry on
        }
    }

    /** Schema setup (version check, one-time v1->v2 and v2->v3 migrations, DDL). Runs on the writer connection. */
    private void initSchema(Connection c) throws SQLException {
        int version = userVersion(c);
        boolean hasBlockTable = tableExists(c, "co_block");
        if (hasBlockTable && version < 2 && hasColumn(c, "co_block", "old_data")) {
            migrateV1ToV2(c);
            version = 2;
        }
        if (hasBlockTable && version < 3) {
            migrateV2ToV3(c);
        }
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA auto_vacuum=INCREMENTAL");
            for (String ddl : DDL_V3) {
                st.executeUpdate(ddl);
            }
            // Databases created before container rows carried item data components are widened here;
            // without the column a rollback cannot restore an enchanted or filled stack.
            if (!hasColumn(c, "co_container", "nbt")) {
                st.executeUpdate("ALTER TABLE co_container ADD COLUMN nbt TEXT");
            }
            // Same for dropped items: without the components, undoing a rollback of an enchanted or
            // filled drop could only rebuild a plain stack of the item.
            if (!hasColumn(c, "co_item", "nbt")) {
                st.executeUpdate("ALTER TABLE co_item ADD COLUMN nbt TEXT");
            }
            // Chat and commands logged before the world column existed cannot be attributed to a
            // dimension, so the column is nullable and old rows simply do not match a world filter.
            if (!hasColumn(c, "co_command", "wid_id")) {
                st.executeUpdate("ALTER TABLE co_command ADD COLUMN wid_id INTEGER");
            }
            if (!hasColumn(c, "co_chat", "wid_id")) {
                st.executeUpdate("ALTER TABLE co_chat ADD COLUMN wid_id INTEGER");
            }
            // co_user predates uuid keying; the column lets a rename keep its language setting.
            if (!hasColumn(c, "co_user", "uuid")) {
                st.executeUpdate("ALTER TABLE co_user ADD COLUMN uuid TEXT");
            }
            // The world indexes are created here rather than in DDL_V3: an existing database only
            // gets the wid_id column in the ALTER statements above, so CREATE INDEX would fail there.
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_command_world ON co_command(wid_id, time)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_chat_world ON co_chat(wid_id, time)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_user_uuid ON co_user(uuid)");
            if (version < 3) {
                st.executeUpdate("PRAGMA user_version=3");
            }
        }
    }

    private boolean tableExists(Connection c, String table) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private volatile boolean legacyMigrated;

    private int userVersion(Connection c) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private boolean hasColumn(Connection c, String table, String column) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equals(rs.getString(2))) return true;
            }
        }
        return false;
    }

    /**
     * Writes the pre-migration copy next to the database file (the safety net of both migrations).
     *
     * <p>Two problems with the plain {@code Files.copy(dbPath, backup)} this replaces: in WAL mode
     * the main file alone does not contain the transactions committed after the last checkpoint, and
     * the copy was only written when no backup existed yet, so an old file from an earlier attempt
     * was kept forever and looked like a fresh safety net. The copy is now replaced on every attempt
     * and is written as a consistent snapshot ({@code VACUUM INTO}).
     */
    private void copyMigrationBackup(Connection c, Path backup) throws SQLException {
        try {
            Files.deleteIfExists(backup);
        } catch (java.io.IOException e) {
            throw new SQLException("Failed to replace the migration backup", e);
        }
        String target = backup.toAbsolutePath().toString().replace("'", "''");
        try (Statement st = c.createStatement()) {
            st.execute("VACUUM INTO '" + target + "'");
            return;
        } catch (SQLException e) {
            // never let a failing snapshot block the open: fall back to the old file copy below
            CoreProtectFabric.LOGGER.warn("[CoreProtect] Could not write the migration backup with VACUUM INTO ({}); "
                    + "falling back to a plain file copy.", e.toString());
        }
        try (Statement st = c.createStatement()) {
            // fold the WAL into the main file, otherwise the copy misses recent transactions
            st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        } catch (SQLException e) {
            CoreProtectFabric.LOGGER.warn("[CoreProtect] WAL checkpoint before the migration backup failed: {}",
                    e.toString());
        }
        try {
            Files.copy(dbPath, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException e) {
            throw new SQLException("Failed to create migration backup", e);
        }
    }

    /**
     * One-time v1 -> v2 migration: dictionary-encode the state/material text columns,
     * drop them, and mark the schema as v2. A backup copy is kept next to the file.
     */
    private void migrateV1ToV2(Connection c) throws SQLException {
        try {
            Path backup = dbPath.resolveSibling(dbPath.getFileName() + ".bak-v1");
            CoreProtectFabric.LOGGER.info("[CoreProtect] Migrating database to compressed schema; creating backup {} ...",
                    backup.getFileName());
            copyMigrationBackup(c, backup);
            // one transaction for the whole migration: a failure in the middle used to leave a
            // half-migrated file behind, and the next attempt then copied that broken file over
            // the good backup.
            boolean autoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS co_state (id INTEGER PRIMARY KEY, state TEXT NOT NULL UNIQUE)");
                migrateColumn(st, "co_block", "old_data", "old_id");
                migrateColumn(st, "co_block", "new_data", "new_id");
                migrateColumn(st, "co_container", "data", "data_id");
                migrateColumn(st, "co_item", "data", "data_id");
                migrateColumn(st, "co_entity", "data", "data_id");
                // temporary indexes make the orphan-GC subselects fast; they are dropped afterwards
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_block_old ON co_block(old_id)");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_block_new ON co_block(new_id)");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_container_data ON co_container(data_id)");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_item_data ON co_item(data_id)");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_entity_data ON co_entity(data_id)");
                // drop the text columns so the old copies are not stored twice
                st.executeUpdate("ALTER TABLE co_block DROP COLUMN old_data");
                st.executeUpdate("ALTER TABLE co_block DROP COLUMN new_data");
                st.executeUpdate("ALTER TABLE co_container DROP COLUMN data");
                st.executeUpdate("ALTER TABLE co_item DROP COLUMN data");
                st.executeUpdate("ALTER TABLE co_entity DROP COLUMN data");
                st.executeUpdate("DELETE FROM co_state WHERE id NOT IN ("
                        + "SELECT old_id FROM co_block WHERE old_id IS NOT NULL "
                        + "UNION SELECT new_id FROM co_block WHERE new_id IS NOT NULL "
                        + "UNION SELECT data_id FROM co_container WHERE data_id IS NOT NULL "
                        + "UNION SELECT data_id FROM co_item WHERE data_id IS NOT NULL "
                        + "UNION SELECT data_id FROM co_entity WHERE data_id IS NOT NULL)");
                st.executeUpdate("DROP INDEX IF EXISTS idx_block_old");
                st.executeUpdate("DROP INDEX IF EXISTS idx_block_new");
                st.executeUpdate("DROP INDEX IF EXISTS idx_container_data");
                st.executeUpdate("DROP INDEX IF EXISTS idx_item_data");
                st.executeUpdate("DROP INDEX IF EXISTS idx_entity_data");
                st.executeUpdate("PRAGMA user_version=2");
            }
            c.commit();
            c.setAutoCommit(autoCommit);
            CoreProtectFabric.LOGGER.info("[CoreProtect] Database schema migrated to v2 (dictionary-compressed).");
            legacyMigrated = true;
        } catch (SQLException e) {
            try {
                c.rollback();
                c.setAutoCommit(true);
            } catch (SQLException ignored) {
            }
            CoreProtectFabric.LOGGER.error("[CoreProtect] Database migration failed; restore {} to keep using the old schema.",
                    dbPath.getFileName() + ".bak-v1", e);
            throw e;
        }
    }

    private void migrateColumn(Statement st, String table, String textColumn, String idColumn) throws SQLException {
        if (!hasColumn(st.getConnection(), table, textColumn)) return;
        CoreProtectFabric.LOGGER.info("[CoreProtect] Migrating {}.{} ...", table, textColumn);
        st.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + idColumn + " INTEGER");
        st.executeUpdate("INSERT OR IGNORE INTO co_state(state) SELECT DISTINCT " + textColumn
                + " FROM " + table + " WHERE " + textColumn + " IS NOT NULL");
        st.executeUpdate("UPDATE " + table + " SET " + idColumn
                + " = (SELECT id FROM co_state WHERE co_state.state = " + table + "." + textColumn + ")"
                + " WHERE " + idColumn + " IS NULL AND " + textColumn + " IS NOT NULL");
    }

    /**
     * One-time v2 -> v3 migration: the {@code user}, {@code wid} and {@code action} strings move
     * into dictionary tables ({@code co_name}, {@code co_world}, {@code co_action}) and every log
     * row keeps an integer id instead. Together with the narrower indexes this removes roughly
     * half of the file size. A backup copy of the v2 database is kept next to the file.
     */
    private void migrateV2ToV3(Connection c) throws SQLException {
        if (hasColumn(c, "co_block", "name_id")) return; // already converted
        try {
            Path backup = dbPath.resolveSibling(dbPath.getFileName() + ".bak-v2");
            CoreProtectFabric.LOGGER.info("[CoreProtect] Compressing the database (schema v3); creating backup {} ...",
                    backup.getFileName());
            copyMigrationBackup(c, backup);
            boolean autoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS co_name (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE)");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS co_world (id INTEGER PRIMARY KEY, wid TEXT NOT NULL UNIQUE)");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS co_action (id INTEGER PRIMARY KEY, action TEXT NOT NULL UNIQUE)");
                for (String t : LOG_TABLES) {
                    if (!tableExists(c, t)) continue;
                    st.executeUpdate("INSERT OR IGNORE INTO co_name(name) SELECT DISTINCT " + quote("user")
                            + " FROM " + t + " WHERE " + quote("user") + " IS NOT NULL");
                    if (hasColumn(c, t, "wid")) {
                        st.executeUpdate("INSERT OR IGNORE INTO co_world(wid) SELECT DISTINCT wid FROM " + t);
                    }
                    if (hasColumn(c, t, "action")) {
                        st.executeUpdate("INSERT OR IGNORE INTO co_action(action) SELECT DISTINCT action FROM " + t);
                    }
                }
                for (String t : LOG_TABLES) {
                    if (!tableExists(c, t)) continue;
                    migrateTextToId(st, t, "user", "name_id", "co_name", "name");
                    migrateTextToId(st, t, "wid", "wid_id", "co_world", "wid");
                    migrateTextToId(st, t, "action", "action_id", "co_action", "action");
                }
                // a column can only be dropped once its indexes are gone
                for (String idx : LEGACY_V2_INDEXES) {
                    st.executeUpdate("DROP INDEX IF EXISTS " + idx);
                }
                for (String t : LOG_TABLES) {
                    if (!tableExists(c, t)) continue;
                    dropColumnIfExists(st, t, "user");
                    dropColumnIfExists(st, t, "wid");
                    dropColumnIfExists(st, t, "action");
                }
                gcDictionaries(st);
                st.executeUpdate("PRAGMA user_version=3");
            }
            c.commit();
            c.setAutoCommit(autoCommit);
            CoreProtectFabric.LOGGER.info("[CoreProtect] Database schema migrated to v3 "
                    + "(user/world/action dictionaries); compacting in the background.");
            CoreProtectFabric.LOGGER.info("[CoreProtect] The pre-migration copy {} is kept as a safety net; "
                    + "you can delete it once the new database looks correct.",
                    dbPath.getFileName() + ".bak-v2");
            legacyMigrated = true;
        } catch (SQLException e) {
            try {
                c.rollback();
                c.setAutoCommit(true);
            } catch (SQLException ignored) {
            }
            CoreProtectFabric.LOGGER.error("[CoreProtect] Database migration to v3 failed; restore {} to keep using the old schema.",
                    dbPath.getFileName() + ".bak-v2", e);
            throw e;
        }
    }

    private static final String[] LOG_TABLES = {
            "co_block", "co_container", "co_item", "co_sign", "co_entity", "co_session", "co_command", "co_chat"};

    private static String quote(String identifier) {
        return "\"" + identifier + "\"";
    }

    /** Adds {@code idColumn} and fills it from the dictionary row matching the text column. */
    private void migrateTextToId(Statement st, String table, String textColumn, String idColumn,
                                 String dictTable, String dictColumn) throws SQLException {
        if (!hasColumn(st.getConnection(), table, textColumn)) return;
        st.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + idColumn + " INTEGER");
        st.executeUpdate("UPDATE " + table + " SET " + idColumn + " = (SELECT id FROM " + dictTable
                + " WHERE " + dictTable + "." + dictColumn + " = " + table + "." + textColumn + ")");
        st.executeUpdate("DROP INDEX IF EXISTS idx_" + table.substring(3) + "_user");
    }

    private void dropColumnIfExists(Statement st, String table, String column) throws SQLException {
        if (!hasColumn(st.getConnection(), table, column)) return;
        st.executeUpdate("ALTER TABLE " + table + " DROP COLUMN " + quote(column));
    }

    /**
     * Removes dictionary rows that no log row references any more (used after a purge/migration).
     *
     * <p>The reference lists used to be hard-coded, so the v2 -> v3 migration of an old database
     * that has no {@code co_session}/{@code co_command}/{@code co_chat} table yet failed with
     * "no such table" - the v3 DDL only runs after the migration. The lists are now built from the
     * tables and columns that actually exist.
     */
    private void gcDictionaries(Statement st) throws SQLException {
        // co_state: every column that stores a state id (old/new block state, container/item/entity data)
        gcDictionary(st, "co_state", "id", new String[][] {
                {"co_block", "old_id"}, {"co_block", "new_id"},
                {"co_container", "data_id"}, {"co_item", "data_id"}, {"co_entity", "data_id"}});
        // co_name: the user of every log table
        gcDictionary(st, "co_name", "id", new String[][] {
                {"co_block", "name_id"}, {"co_container", "name_id"}, {"co_item", "name_id"},
                {"co_sign", "name_id"}, {"co_entity", "name_id"}, {"co_session", "name_id"},
                {"co_command", "name_id"}, {"co_chat", "name_id"}});
        // co_world: only the tables that have a world column at all (chat/command rows carry one
        // since L3-21, and the rows of a world that is only used there must survive this cleanup)
        gcDictionary(st, "co_world", "id", new String[][] {
                {"co_block", "wid_id"}, {"co_container", "wid_id"}, {"co_item", "wid_id"},
                {"co_sign", "wid_id"}, {"co_entity", "wid_id"}, {"co_session", "wid_id"},
                {"co_command", "wid_id"}, {"co_chat", "wid_id"}});
        // co_action: blocks, items, entities and sessions carry one, but it may be NULL
        gcDictionary(st, "co_action", "id", new String[][] {
                {"co_block", "action_id"}, {"co_item", "action_id"}, {"co_entity", "action_id"},
                {"co_session", "action_id"}});
    }

    /** Deletes the {@code dictTable} rows whose id is not referenced by any of the given columns. */
    private void gcDictionary(Statement st, String dictTable, String dictColumn, String[][] references)
            throws SQLException {
        Connection c = st.getConnection();
        if (!tableExists(c, dictTable)) return;
        StringBuilder union = new StringBuilder();
        for (String[] ref : references) {
            if (!tableExists(c, ref[0]) || !hasColumn(c, ref[0], ref[1])) continue;
            if (union.length() > 0) {
                union.append(" UNION ");
            }
            // the table name qualifies the column: the union mixes tables and column names, and a
            // NULL id is not a reference
            union.append("SELECT ").append(ref[0]).append('.').append(ref[1])
                    .append(" FROM ").append(ref[0])
                    .append(" WHERE ").append(ref[0]).append('.').append(ref[1]).append(" IS NOT NULL");
        }
        if (union.length() == 0) {
            // nothing references this dictionary yet, so "NOT IN ()" (a syntax error) must not be
            // attempted; keeping the rows is safe, the next log row reuses them
            return;
        }
        st.executeUpdate("DELETE FROM " + dictTable + " WHERE " + dictColumn + " NOT IN (" + union + ")");
    }

    private void applyPragmas(Connection c, boolean readOnly) {
        try (Statement st = c.createStatement()) {
            // negative cache_size = KiB of page cache; long maths so a large configured
            // value cannot overflow into a nonsense pragma value
            long cacheKb = -(long) CoreProtectFabric.instance().config().database.cacheSizeMB * 1024L;
            st.execute("PRAGMA busy_timeout=10000");
            st.execute("PRAGMA cache_size=" + cacheKb);
            st.execute("PRAGMA mmap_size=1073741824");
            st.execute("PRAGMA temp_store=MEMORY");
            if (readOnly) {
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("PRAGMA query_only=ON");
            } else {
                st.execute("PRAGMA journal_mode=WAL");
                // full = WAL fsync'd on every commit (max crash safety, default); normal = faster
                st.execute("PRAGMA synchronous=" + syncModePragma());
            }
        } catch (Exception e) {
            CoreProtectFabric.LOGGER.error("[CoreProtect] Failed to apply SQLite pragmas", e);
        }
    }

    /**
     * The configured {@code database.syncMode} as a pragma value. The value is concatenated into a
     * PRAGMA, so an unknown word made SQLite reject that one statement and silently keep the
     * previous (default FULL) mode; it has to be validated instead of trusted.
     */
    private static String syncModePragma() {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        String raw = mod == null ? null : mod.config().database.syncMode;
        String mode = raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT);
        switch (mode) {
            case "OFF", "NORMAL", "FULL", "EXTRA":
                return mode;
            default:
                CoreProtectFabric.LOGGER.warn("[CoreProtect] Unknown database.syncMode \"{}\"; using FULL.", raw);
                return "FULL";
        }
    }

    /**
     * Applies the configured {@code database.syncMode} to the writer connection without reopening it
     * (SQLite allows {@code synchronous} to change between transactions). Called after
     * {@code /co reload}: the reload reloaded the config file, but the connection kept the mode it
     * was opened with until the next server restart, so changing the setting needed a restart.
     */
    public void applySyncMode() {
        if (!open) {
            CoreProtectFabric.LOGGER.warn("[CoreProtect] The database is not open; database.syncMode is applied "
                    + "when it opens.");
            return;
        }
        try {
            worker.submit(() -> {
                if (conn == null) return;
                try {
                    if (!conn.getAutoCommit()) {
                        // the mode cannot change inside a transaction, and the rows of that batch would
                        // be committed with the old one anyway
                        CoreProtectFabric.LOGGER.warn("[CoreProtect] Cannot apply database.syncMode while a write "
                                + "batch is running; it stays unchanged until the next /co reload.");
                        return;
                    }
                    String mode = syncModePragma();
                    try (Statement st = conn.createStatement()) {
                        st.execute("PRAGMA synchronous=" + mode);
                    }
                    CoreProtectFabric.LOGGER.info("[CoreProtect] Applied database.syncMode={} to the open database.",
                            mode);
                } catch (SQLException e) {
                    CoreProtectFabric.LOGGER.warn("[CoreProtect] Failed to apply database.syncMode: {}", e.toString());
                }
            }).get(5, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            // the writer is busy (purge, VACUUM); the pragma still runs when it gets there
            CoreProtectFabric.LOGGER.info("[CoreProtect] database.syncMode is applied as soon as the writer is free.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            CoreProtectFabric.LOGGER.warn("[CoreProtect] Could not apply database.syncMode: {}", e.toString());
        }
    }

    /**
     * Runs {@code PRAGMA quick_check} after an unclean shutdown was detected. If the
     * database reports errors, a recovery copy of the db/wal/shm files is kept next
     * to the original so no data is lost while investigating.
     */
    public void quickCheck() {
        execute(() -> {
            if (conn == null) {
                CoreProtectFabric.LOGGER.warn("[CoreProtect] Skipping the integrity check: no database connection.");
                return null;
            }
            String result = quickCheckResult(conn);
            if ("ok".equalsIgnoreCase(result)) {
                CoreProtectFabric.LOGGER.info("[CoreProtect] Database integrity check passed (quick_check: ok).");
                return null;
            }
            CoreProtectFabric.LOGGER.error("[CoreProtect] Database integrity check reported: {}", result);
            try {
                String ts = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
                Path db = dbPath;
                Path wal = db.resolveSibling(db.getFileName() + "-wal");
                Path shm = db.resolveSibling(db.getFileName() + "-shm");
                Path corruptDir = db.resolveSibling(db.getFileName() + ".corrupt-" + ts);
                Files.createDirectories(corruptDir);
                // Release every file handle first: on Windows the reader connections (and the
                // 1 GB mmap) keep the database locked, so moving it would fail and abort recovery.
                open = false; // writes are dropped while the files are swapped
                closeReadConnections();
                closeStatements();
                try {
                    conn.close();
                } catch (SQLException ignored) {
                }
                conn = null;
                if (Files.exists(db)) Files.move(db, corruptDir.resolve(db.getFileName()));
                if (Files.exists(wal)) Files.move(wal, corruptDir.resolve(wal.getFileName()));
                if (Files.exists(shm)) Files.move(shm, corruptDir.resolve(shm.getFileName()));
                CoreProtectFabric.LOGGER.info("[CoreProtect] Corrupted files moved to {}", corruptDir);

                Path backup = db.resolveSibling(db.getFileName() + ".backup");
                boolean restored = false;
                if (CoreProtectFabric.instance().config().database.autoRestoreBackup && Files.exists(backup)) {
                    try (Connection bc = DriverManager.getConnection("jdbc:sqlite:" + backup)) {
                        if ("ok".equalsIgnoreCase(quickCheckResult(bc))) {
                            Files.copy(backup, db);
                            restored = true;
                        }
                    }
                }
                // reopen the writer connection against the (restored or fresh) file
                Class.forName("org.sqlite.JDBC");
                conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                applyPragmas(conn, false);
                initSchema(conn);
                open = true;
                generation.incrementAndGet(); // reader threads reopen their connections on next use
                if (restored) {
                    CoreProtectFabric.LOGGER.info("[CoreProtect] Database restored from the latest backup ({}).", backup.getFileName());
                } else {
                    CoreProtectFabric.LOGGER.warn("[CoreProtect] No valid backup found; the database was re-initialized empty. "
                            + "The corrupted copy is kept in {} for manual recovery.", corruptDir);
                }
            } catch (Exception recoveryError) {
                // never leave open == true with conn == null: writes would NPE forever
                open = false;
                CoreProtectFabric.LOGGER.error("[CoreProtect] Automatic recovery failed; logging is suspended until the next restart", recoveryError);
            }
            return null;
        });
    }

    private static String quickCheckResult(Connection c) {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA quick_check")) {
            return rs.next() ? rs.getString(1) : "unknown";
        } catch (SQLException e) {
            return "error: " + e.getMessage();
        }
    }

    /** Periodically checkpoints the WAL so crash recovery stays fast. */
    private void scheduleCheckpoints() {
        int minutes = CoreProtectFabric.instance().config().database.checkpointMinutes;
        worker.scheduleWithFixedDelay(() -> {
            if (!open) return;
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA wal_checkpoint(PASSIVE)");
            } catch (SQLException e) {
                CoreProtectFabric.LOGGER.warn("[CoreProtect] WAL checkpoint failed: {}", e.toString());
            }
        }, minutes, minutes, TimeUnit.MINUTES);
    }

    /** Periodically writes a hot backup of the database to <db>.backup (VACUUM INTO = consistent snapshot). */
    private void scheduleBackups() {
        int minutes = CoreProtectFabric.instance().config().database.backupMinutes;
        if (minutes <= 0) return;
        worker.scheduleWithFixedDelay(() -> {
            if (!open) return;
            Path target = dbPath.resolveSibling(dbPath.getFileName() + ".backup");
            Path tmp = dbPath.resolveSibling(dbPath.getFileName() + ".backup.tmp");
            try {
                // VACUUM INTO blocks the single writer thread for as long as the copy takes and needs
                // room for a second full copy next to the live file, so refuse to start without it
                // instead of filling the disk (L1-04).
                long needed = Files.size(dbPath) * 2;
                long usable = Files.getFileStore(dbPath).getUsableSpace();
                if (usable < needed) {
                    CoreProtectFabric.LOGGER.warn("[CoreProtect] Skipping the database backup: {} MB free, {} MB "
                            + "needed for a full copy next to the database.",
                            usable / (1024 * 1024), needed / (1024 * 1024));
                    return;
                }
                Files.deleteIfExists(tmp);
                String sql = "VACUUM INTO '" + tmp.toAbsolutePath().toString().replace("'", "''") + "'";
                long started = System.nanoTime();
                try (Statement st = conn.createStatement()) {
                    st.execute(sql);
                }
                long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                CoreProtectFabric.LOGGER.info("[CoreProtect] Database backup written: {} ({} MB, took {} ms).",
                        target.getFileName(), Files.size(target) / (1024 * 1024), tookMs);
            } catch (Exception e) {
                CoreProtectFabric.LOGGER.warn("[CoreProtect] Database backup failed: {}", e.toString());
                try {
                    // a half written tmp file would keep a full copy of the database on disk
                    Files.deleteIfExists(tmp);
                } catch (java.io.IOException ignored) {
                }
            }
        }, minutes, minutes, TimeUnit.MINUTES);
    }

    public void close() {
        closed = true; // a queued open retry must not reopen what the owner just closed
        if (!open) return;
        open = false; // drop any write that arrives from now on
        // Flush on the writer thread, but never lose the close task to a timed get(): a
        // backup/purge queued ahead of it used to push it past the 60 s timeout. The queued batch
        // is drained first, so rows logged just before the shutdown are still written (they are no
        // longer sitting in the executor queue).
        try {
            worker.submit(this::drainWrites).get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            CoreProtectFabric.LOGGER.error("[CoreProtect] Database close task did not finish within {} s; "
                    + "rows may still be queued: {}", CLOSE_TIMEOUT_SECONDS, e.toString());
            // A writer that is stuck inside a failing batch has to be interrupted: shutdown() only
            // stops accepting tasks, so the awaitTermination below used to run into its own timeout.
            worker.shutdownNow();
        }
        worker.shutdown();
        boolean writerStopped = false;
        try {
            writerStopped = worker.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // The read pool is stopped before its connections are closed: closing a connection that
        // a reader thread is still using made that (harmless) query fail with a SQL error.
        readPool.shutdown();
        try {
            readPool.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        closeReadConnections();
        // The WAL is folded into the main file only now, after every reader is gone: a read
        // connection holding a transaction made the checkpoint requested on the writer thread
        // return SQLITE_BUSY, the result was ignored, and the .db-wal stayed on disk (it was
        // replayed on the next start, so nothing was lost, but the file never shrank).
        // Nothing else can touch the connection here: the writer thread either finished its close
        // task or was interrupted and has terminated.
        if (writerStopped && conn != null) {
            closeStatements(); // (the checkpoint needs no open statements either)
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            } catch (SQLException e) {
                CoreProtectFabric.LOGGER.warn("[CoreProtect] Final WAL checkpoint failed: {}", e.toString());
            }
            try {
                conn.close();
            } catch (SQLException ignored) {
            }
            conn = null;
        }
    }

    /**
     * Closes every pooled read connection. The connections are tracked in a set
     * (instead of relying on one cleanup task per pool thread) so none of them can
     * keep the database file locked on Windows.
     */
    private void closeReadConnections() {
        generation.incrementAndGet(); // makes every reader discard its (closed) connection
        for (Connection c : readConnections) {
            try {
                c.close();
            } catch (SQLException ignored) {
            }
        }
        readConnections.clear();
    }

    // ------------------------------------------------------------------
    // Async writes (single writer thread)
    // ------------------------------------------------------------------

    public void insertBlockAsync(BlockLog l) {
        trackUser(l.user());
        submitWrite(() -> {
            long oldId = stateId(l.oldData());
            long newId = stateId(l.newData());
            try {
                PreparedStatement ps = prepare("INSERT INTO co_block(time,name_id,wid_id,x,y,z,type,old_id,new_id,action_id,meta) VALUES(?,?,?,?,?,?,?,?,?,?,?)");
                bind(ps, Arrays.asList(l.time(), nameId(l.user()), worldId(l.wid()), l.x(), l.y(), l.z(), l.type(),
                        oldId, newId, actionId(l.action()), l.meta()));
                ps.executeUpdate();
            } catch (Exception e) {
                CoreProtectFabric.LOGGER.error("[CoreProtect] Database write failed: co_block", e);
                // A row whose own INSERT throws must be counted: the batch around it still commits,
                // so without this the row disappeared while /co status reported "0 dropped" (L1-02).
                countDroppedRow();
            }
        });
    }

    public void insertContainerAsync(ContainerLog l) {
        trackUser(l.user());
        submitWrite(() -> {
            long dataId = stateId(l.data());
            try {
                PreparedStatement ps = prepare("INSERT INTO co_container(time,name_id,wid_id,x,y,z,type,data_id,amount,nbt) VALUES(?,?,?,?,?,?,?,?,?,?)");
                bind(ps, Arrays.asList(l.time(), nameId(l.user()), worldId(l.wid()), l.x(), l.y(), l.z(),
                        l.type(), dataId, l.amount(), l.nbt()));
                ps.executeUpdate();
            } catch (Exception e) {
                CoreProtectFabric.LOGGER.error("[CoreProtect] Database write failed: co_container", e);
                countDroppedRow();
            }
        });
    }

    public void insertItemAsync(ItemLog l) {
        trackUser(l.user());
        submitWrite(() -> {
            long dataId = stateId(l.data());
            try {
                PreparedStatement ps = prepare("INSERT INTO co_item(time,name_id,wid_id,x,y,z,action_id,data_id,amount,nbt) VALUES(?,?,?,?,?,?,?,?,?,?)");
                bind(ps, Arrays.asList(l.time(), nameId(l.user()), worldId(l.wid()), l.x(), l.y(), l.z(),
                        actionId(l.action()), dataId, l.amount(), l.nbt()));
                ps.executeUpdate();
            } catch (Exception e) {
                CoreProtectFabric.LOGGER.error("[CoreProtect] Database write failed: co_item", e);
                countDroppedRow();
            }
        });
    }

    public void insertSignAsync(SignLog l) {
        trackUser(l.user());
        submitWrite(() -> {
            try {
                PreparedStatement ps = prepare("INSERT INTO co_sign(time,name_id,wid_id,x,y,z,data) VALUES(?,?,?,?,?,?,?)");
                bind(ps, Arrays.asList(l.time(), nameId(l.user()), worldId(l.wid()),
                        l.x(), l.y(), l.z(), l.data()));
                ps.executeUpdate();
            } catch (Exception e) {
                CoreProtectFabric.LOGGER.error("[CoreProtect] Database write failed: co_sign", e);
                countDroppedRow();
            }
        });
    }

    public void insertEntityAsync(EntityLog l) {
        trackUser(l.user());
        submitWrite(() -> {
            long dataId = stateId(l.data());
            try {
                PreparedStatement ps = prepare("INSERT INTO co_entity(time,name_id,wid_id,x,y,z,data_id,action_id) VALUES(?,?,?,?,?,?,?,?)");
                bind(ps, Arrays.asList(l.time(), nameId(l.user()), worldId(l.wid()), l.x(), l.y(), l.z(),
                        dataId, actionId(l.action())));
                ps.executeUpdate();
            } catch (Exception e) {
                CoreProtectFabric.LOGGER.error("[CoreProtect] Database write failed: co_entity", e);
                countDroppedRow();
            }
        });
    }

    public void insertSessionAsync(long time, String user, String wid, String action) {
        trackUser(user);
        submitWrite(() -> {
            try {
                PreparedStatement ps = prepare("INSERT INTO co_session(time,name_id,wid_id,action_id) VALUES(?,?,?,?)");
                bind(ps, Arrays.asList(time, nameId(user), worldId(wid), actionId(action)));
                ps.executeUpdate();
            } catch (Exception e) {
                CoreProtectFabric.LOGGER.error("[CoreProtect] Database write failed: co_session", e);
                countDroppedRow();
            }
        });
    }

    public void insertCommandAsync(long time, String user, String message) {
        // older call sites do not know the dimension; the row then has no world attribution
        insertCommandAsync(time, user, null, message);
    }

    /**
     * Logs a command together with the dimension it was run in.
     *
     * @param wid the world folder name, or {@code null} when unknown
     */
    public void insertCommandAsync(long time, String user, String wid, String message) {
        trackUser(user);
        submitWrite(() -> {
            try {
                PreparedStatement ps = prepare("INSERT INTO co_command(time,name_id,wid_id,message) VALUES(?,?,?,?)");
                bind(ps, Arrays.asList(time, nameId(user), worldId(wid), message));
                ps.executeUpdate();
            } catch (Exception e) {
                CoreProtectFabric.LOGGER.error("[CoreProtect] Database write failed: co_command", e);
                countDroppedRow();
            }
        });
    }

    public void insertChatAsync(long time, String user, String message) {
        // older call sites do not know the dimension; the row then has no world attribution
        insertChatAsync(time, user, null, message);
    }

    /**
     * Logs a chat line together with the dimension it was sent in.
     *
     * @param wid the world folder name, or {@code null} when unknown
     */
    public void insertChatAsync(long time, String user, String wid, String message) {
        trackUser(user);
        submitWrite(() -> {
            try {
                PreparedStatement ps = prepare("INSERT INTO co_chat(time,name_id,wid_id,message) VALUES(?,?,?,?)");
                bind(ps, Arrays.asList(time, nameId(user), worldId(wid), message));
                ps.executeUpdate();
            } catch (Exception e) {
                CoreProtectFabric.LOGGER.error("[CoreProtect] Database write failed: co_chat", e);
                countDroppedRow();
            }
        });
    }

    // ------------------------------------------------------------------
    // String dictionaries (user name, world id, action/cause)
    // ------------------------------------------------------------------

    /** Resolves a dictionary id through a bounded LRU cache (writer thread only). */
    private long dictId(String table, String column, String value, Map<String, Long> cache) {
        if (value == null || value.isEmpty()) return 0L;
        Long cached = cache.get(value);
        if (cached != null) return cached;
        try {
            PreparedStatement ins = prepare("INSERT INTO " + table + "(" + column
                    + ") VALUES(?) ON CONFLICT(" + column + ") DO NOTHING");
            ins.setString(1, value);
            ins.executeUpdate();
            PreparedStatement sel = prepare("SELECT id FROM " + table + " WHERE " + column + "=?");
            sel.setString(1, value);
            try (ResultSet rs = sel.executeQuery()) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    cache.put(value, id);
                    return id;
                }
            }
        } catch (SQLException e) {
            CoreProtectFabric.LOGGER.error("[CoreProtect] Failed to store a " + table + " entry", e);
        }
        return 0L;
    }

    private long nameId(String name) {
        return dictId("co_name", "name", name, nameIds);
    }

    private long worldId(String wid) {
        return dictId("co_world", "wid", wid, worldIds);
    }

    private long actionId(String action) {
        return dictId("co_action", "action", action, actionIds);
    }

    private static Map<String, Long> dictionaryCache() {
        return Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > 8192;
            }
        });
    }

    public void saveUserLanguageAsync(String user, String language) {
        // legacy call site: without a uuid only the name-keyed row can be written
        saveUserLanguageAsync(user, null, language);
    }

    /**
     * Stores a language preference keyed by name and, when known, by uuid. The uuid row survives a
     * rename and is not inherited by a different player who happens to use the same name.
     *
     * @param user     the player name
     * @param uuid     the player uuid as a string, or {@code null} when unknown
     * @param language the language code to store
     */
    public void saveUserLanguageAsync(String user, String uuid, String language) {
        enqueue("INSERT INTO co_user(user,language,uuid) VALUES(?,?,?) "
                        + "ON CONFLICT(user) DO UPDATE SET language=excluded.language, "
                        + "uuid=COALESCE(excluded.uuid, co_user.uuid)",
                Arrays.asList(user, language, uuid));
        if (uuid != null && !uuid.isEmpty()) {
            enqueue("INSERT INTO co_user_uuid(uuid,language) VALUES(?,?) "
                            + "ON CONFLICT(uuid) DO UPDATE SET language=excluded.language",
                    Arrays.asList(uuid, language));
        }
    }

    // ------------------------------------------------------------------
    // Reads (parallel read pool, one connection per reader thread)
    // ------------------------------------------------------------------

    private static final String BLOCK_SELECT =
            "SELECT b.id,b.time,n.name,w.wid,b.x,b.y,b.z,b.type,so.state,sn.state,a.action,b.meta "
                    + "FROM co_block b LEFT JOIN co_state so ON so.id=b.old_id LEFT JOIN co_state sn ON sn.id=b.new_id "
                    + "LEFT JOIN co_name n ON n.id=b.name_id LEFT JOIN co_world w ON w.id=b.wid_id "
                    + "LEFT JOIN co_action a ON a.id=b.action_id ";

    public List<BlockLog> queryBlocks(Criteria c, long limit, long offset) {
        // a:container / a:item / a:sign / a:kill must not return block rows either: appendBlockAction()
        // only filters the block type, so without this gate every block of the window was listed next
        // to the rows the action actually asked for (L2-10 / L4-03).
        if (!blockActionSelects(c.action)) {
            return new ArrayList<>();
        }
        return executeRead(() -> {
            StringBuilder sql = new StringBuilder(BLOCK_SELECT + "WHERE 1=1");
            List<Object> params = new ArrayList<>();
            appendBlockFilters(sql, params, c);
            appendBlockAction(sql, params, c.action);
            sql.append(" ORDER BY b.id DESC LIMIT ? OFFSET ?");
            params.add(limit);
            params.add(offset);
            try (PreparedStatement ps = readConnection().prepareStatement(sql.toString())) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    List<BlockLog> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(new BlockLog(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                                rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getInt(8),
                                rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12)));
                    }
                    return out;
                }
            }
        });
    }

    public List<BlockLog> queryBlockHistory(String wid, int x, int y, int z, int limit) {
        return executeRead(() -> {
            String sql = BLOCK_SELECT
                    + "WHERE b.wid_id=(SELECT id FROM co_world WHERE wid=?) AND b.x=? AND b.y=? AND b.z=? "
                    + "ORDER BY b.id DESC LIMIT ?";
            try (PreparedStatement ps = readConnection().prepareStatement(sql)) {
                bind(ps, Arrays.asList(wid, x, y, z, limit));
                try (ResultSet rs = ps.executeQuery()) {
                    List<BlockLog> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(new BlockLog(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                                rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getInt(8),
                                rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12)));
                    }
                    return out;
                }
            }
        });
    }

    public List<ContainerLog> queryContainers(Criteria c, long limit, long offset) {
        // a:<action> has to be honoured here too: this query used to return container rows for every
        // action, so /co lookup a:#tnt also listed every chest access of the window (L2-10 / L4-03).
        if (actionNamesOtherTable(c.action, "container")) {
            return new ArrayList<>();
        }
        return executeRead(() -> {
            StringBuilder sql = new StringBuilder(
                    "SELECT c.id,c.time,n.name,w.wid,c.x,c.y,c.z,c.type,s.state,c.amount,c.nbt "
                            + "FROM co_container c LEFT JOIN co_state s ON s.id=c.data_id "
                            + "LEFT JOIN co_name n ON n.id=c.name_id LEFT JOIN co_world w ON w.id=c.wid_id WHERE 1=1");
            List<Object> params = new ArrayList<>();
            appendCommonFilters(sql, params, c);
            // b:<item> was ignored here (L4-14): the accessed item is the joined co_state row
            appendDataFilter(sql, params, c.block, "s.state");
            appendDataExcludeFilter(sql, params, c.exclude, "s.state");
            sql.append(" ORDER BY c.id DESC LIMIT ? OFFSET ?");
            params.add(limit);
            params.add(offset);
            try (PreparedStatement ps = readConnection().prepareStatement(sql.toString())) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    List<ContainerLog> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(new ContainerLog(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                                rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getInt(8),
                                rs.getString(9), rs.getInt(10), rs.getString(11)));
                    }
                    return out;
                }
            }
        });
    }

    public List<ContainerLog> queryContainerHistory(String wid, int x, int y, int z, int limit) {
        return executeRead(() -> {
            String sql = "SELECT c.id,c.time,n.name,w.wid,c.x,c.y,c.z,c.type,s.state,c.amount,c.nbt FROM co_container c "
                    + "LEFT JOIN co_state s ON s.id=c.data_id "
                    + "LEFT JOIN co_name n ON n.id=c.name_id LEFT JOIN co_world w ON w.id=c.wid_id "
                    + "WHERE c.wid_id=(SELECT id FROM co_world WHERE wid=?) AND c.x=? AND c.y=? AND c.z=? "
                    + "ORDER BY c.id DESC LIMIT ?";
            try (PreparedStatement ps = readConnection().prepareStatement(sql)) {
                bind(ps, Arrays.asList(wid, x, y, z, limit));
                try (ResultSet rs = ps.executeQuery()) {
                    List<ContainerLog> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(new ContainerLog(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                                rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getInt(8),
                                rs.getString(9), rs.getInt(10), rs.getString(11)));
                    }
                    return out;
                }
            }
        });
    }

    public List<ItemLog> queryItems(Criteria c, long limit, long offset) {
        if (actionNamesOtherTable(c.action, "item")) {
            return new ArrayList<>();
        }
        return executeRead(() -> {
            StringBuilder sql = new StringBuilder(
                    "SELECT i.id,i.time,n.name,w.wid,i.x,i.y,i.z,ac.action,s.state,i.amount,i.nbt "
                            + "FROM co_item i LEFT JOIN co_state s ON s.id=i.data_id "
                            + "LEFT JOIN co_name n ON n.id=i.name_id LEFT JOIN co_world w ON w.id=i.wid_id "
                            + "LEFT JOIN co_action ac ON ac.id=i.action_id WHERE 1=1");
            List<Object> params = new ArrayList<>();
            appendCommonFilters(sql, params, c);
            // b:<item> was ignored here (L4-14): the dropped/picked up item is the joined co_state row
            appendDataFilter(sql, params, c.block, "s.state");
            appendDataExcludeFilter(sql, params, c.exclude, "s.state");
            // '+item' = pickups, '-item' = drops; plain 'item' shows both
            if ("+item".equals(c.action)) {
                sql.append(" AND i.action_id = (SELECT id FROM co_action WHERE action = '+')");
            } else if ("-item".equals(c.action)) {
                sql.append(" AND i.action_id = (SELECT id FROM co_action WHERE action = '-')");
            }
            sql.append(" ORDER BY i.id DESC LIMIT ? OFFSET ?");
            params.add(limit);
            params.add(offset);
            try (PreparedStatement ps = readConnection().prepareStatement(sql.toString())) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    List<ItemLog> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(new ItemLog(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                                rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getString(8), rs.getString(9),
                                rs.getInt(10), rs.getString(11)));
                    }
                    return out;
                }
            }
        });
    }

    public List<SignLog> querySigns(Criteria c, long limit, long offset) {
        if (actionNamesOtherTable(c.action, "sign")) {
            return new ArrayList<>();
        }
        if (c.block != null && !c.block.isEmpty()) {
            // co_sign only stores the sign text: there is no material to match b:<block> against, so
            // say so once instead of quietly returning every sign in the window (L4-14).
            if (!signBlockFilterWarned) {
                signBlockFilterWarned = true;
                CoreProtectFabric.LOGGER.warn("[CoreProtect] b:<block> cannot be applied to sign lookups "
                        + "(a sign has no material) and is ignored.");
            }
        }
        return executeRead(() -> {
            StringBuilder sql = new StringBuilder(
                    "SELECT g.id,g.time,n.name,w.wid,g.x,g.y,g.z,g.data FROM co_sign g "
                            + "LEFT JOIN co_name n ON n.id=g.name_id LEFT JOIN co_world w ON w.id=g.wid_id WHERE 1=1");
            List<Object> params = new ArrayList<>();
            appendCommonFilters(sql, params, c);
            // 'id' alone is ambiguous here: co_name and co_world are joined in as well and
            // both have an id column, so the sign table's alias has to be spelled out.
            sql.append(" ORDER BY g.id DESC LIMIT ? OFFSET ?");
            params.add(limit);
            params.add(offset);
            try (PreparedStatement ps = readConnection().prepareStatement(sql.toString())) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    List<SignLog> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(new SignLog(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                                rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getString(8)));
                    }
                    return out;
                }
            }
        });
    }

    public List<SignLog> querySignHistory(String wid, int x, int y, int z, int limit) {
        return executeRead(() -> {
            String sql = "SELECT g.id,g.time,n.name,w.wid,g.x,g.y,g.z,g.data FROM co_sign g "
                    + "LEFT JOIN co_name n ON n.id=g.name_id LEFT JOIN co_world w ON w.id=g.wid_id "
                    + "WHERE g.wid_id=(SELECT id FROM co_world WHERE wid=?) AND g.x=? AND g.y=? AND g.z=? "
                    + "ORDER BY g.id DESC LIMIT ?";
            try (PreparedStatement ps = readConnection().prepareStatement(sql)) {
                bind(ps, Arrays.asList(wid, x, y, z, limit));
                try (ResultSet rs = ps.executeQuery()) {
                    List<SignLog> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(new SignLog(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                                rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getString(8)));
                    }
                    return out;
                }
            }
        });
    }

    public List<EntityLog> queryEntities(Criteria c, long limit, long offset) {
        if (actionNamesOtherTable(c.action, "kill", "death")) {
            return new ArrayList<>();
        }
        return executeRead(() -> {
            StringBuilder sql = new StringBuilder(
                    "SELECT e.id,e.time,n.name,w.wid,e.x,e.y,e.z,s.state,ac.action "
                            + "FROM co_entity e LEFT JOIN co_state s ON s.id=e.data_id "
                            + "LEFT JOIN co_name n ON n.id=e.name_id LEFT JOIN co_world w ON w.id=e.wid_id "
                            + "LEFT JOIN co_action ac ON ac.id=e.action_id WHERE 1=1");
            List<Object> params = new ArrayList<>();
            // reuse the common filters so a:#kill also honours r:/t:/u:/e:
            appendCommonFilters(sql, params, c);
            // b:<entity> was ignored here (L4-14): the entity type is the joined co_state row
            appendDataFilter(sql, params, c.block, "s.state");
            appendDataExcludeFilter(sql, params, c.exclude, "s.state");
            // '#kill' only shows kills, '#death' only deaths; the plain '#kill' alias used to mix both
            // (co_entity has no "action" column - the action name lives in the joined co_action)
            if ("#kill".equals(c.action) || "kill".equals(c.action) || "#kills".equals(c.action)) {
                sql.append(" AND ac.action = ?");
                params.add("kill");
            } else if ("#death".equals(c.action) || "death".equals(c.action)) {
                sql.append(" AND ac.action = ?");
                params.add("death");
            }
            sql.append(" ORDER BY e.id DESC LIMIT ? OFFSET ?");
            params.add(limit);
            params.add(offset);
            try (PreparedStatement ps = readConnection().prepareStatement(sql.toString())) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    List<EntityLog> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(new EntityLog(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                                rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getString(8), rs.getString(9)));
                    }
                    return out;
                }
            }
        });
    }

    public List<MessageLog> querySessions(Criteria c, long limit, long offset) {
        return queryMessages("co_session", c, limit, offset, true, true);
    }

    public List<MessageLog> queryCommands(Criteria c, long limit, long offset) {
        return queryMessages("co_command", c, limit, offset, false, true);
    }

    public List<MessageLog> queryChat(Criteria c, long limit, long offset) {
        return queryMessages("co_chat", c, limit, offset, false, true);
    }

    private List<MessageLog> queryMessages(String table, Criteria c, long limit, long offset, boolean hasAction,
                                           boolean hasWorld) {
        if (c.radius > 0 && !messageRadiusFilterWarned) {
            // chat, command and session rows have no position: r: cannot be honored, so say it once
            // instead of silently returning the whole history (L4-14)
            messageRadiusFilterWarned = true;
            CoreProtectFabric.LOGGER.warn("[CoreProtect] Radius lookups are not supported for chat, command and "
                    + "session rows; the radius of this query is ignored.");
        }
        return executeRead(() -> {
            // co_session stores wid instead of a message column; alias it so both shapes map to MessageLog.message
            StringBuilder sql = new StringBuilder("SELECT t.id,t.time,n.name,");
            sql.append(hasAction ? "w.wid AS message" : "t.message");
            if (hasAction) {
                sql.append(",ac.action");
            }
            sql.append(" FROM ").append(table).append(" t LEFT JOIN co_name n ON n.id=t.name_id");
            if (hasAction) {
                sql.append(" LEFT JOIN co_world w ON w.id=t.wid_id LEFT JOIN co_action ac ON ac.id=t.action_id");
            }
            sql.append(" WHERE 1=1");
            List<Object> params = new ArrayList<>();
            if (c.time > 0) {
                sql.append(" AND t.time >= ?");
                params.add(c.time);
            }
            if (c.user != null && !c.user.isEmpty()) {
                sql.append(" AND t.name_id IN (SELECT id FROM co_name WHERE name = ? COLLATE NOCASE)");
                params.add(c.user);
            }
            if (hasAction && "+session".equals(c.action)) {
                sql.append(" AND ac.action = '+'");
            } else if (hasAction && "-session".equals(c.action)) {
                sql.append(" AND ac.action = '-'");
            }
            if (hasWorld && c.world != null && !c.world.isEmpty()) {
                // co_command/co_chat only gained wid_id in this schema revision; old rows do not match
                sql.append(" AND t.wid_id = (SELECT id FROM co_world WHERE wid = ?)");
                params.add(c.world);
            }
            if (c.exclude != null && !c.exclude.isEmpty()) {
                // messages carry no block name, so e: keeps its user meaning here (L4-14)
                sql.append(" AND t.name_id NOT IN (SELECT id FROM co_name WHERE name = ? COLLATE NOCASE)");
                params.add(c.exclude);
            }
            sql.append(" ORDER BY t.id DESC LIMIT ? OFFSET ?");
            params.add(limit);
            params.add(offset);
            try (PreparedStatement ps = readConnection().prepareStatement(sql.toString())) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    List<MessageLog> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(new MessageLog(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                                hasAction ? rs.getString(5) : null));
                    }
                    return out;
                }
            }
        });
    }

    public long count(String table) {
        long[] all = counts();
        for (int i = 0; i < COUNT_TABLES.length; i++) {
            if (COUNT_TABLES[i].equals(table)) return all[i];
        }
        return 0;
    }

    /** Runs all eight table counts in parallel on the read pool. A failed table reports {@code -1}. */
    public long[] counts() {
        if (Boolean.TRUE.equals(poolReader.get()) || readPool.isShutdown()) {
            // Already on a read worker (submitting to our own pool can exhaust it), or the pool is
            // gone: count on this thread instead of throwing RejectedExecutionException (L1-09).
            return countsSequential();
        }
        List<Future<Long>> futures = new ArrayList<>();
        for (String table : COUNT_TABLES) {
            try {
                futures.add(readPool.submit(withPoolMarker(() -> {
                    try (PreparedStatement ps = readConnection().prepareStatement("SELECT COUNT(*) FROM " + table);
                         ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getLong(1) : 0L;
                    }
                })));
            } catch (RejectedExecutionException e) {
                // the pool was shut down between the check above and the submit
                futures.add(CompletableFuture.completedFuture(-1L));
            }
        }
        long[] out = new long[COUNT_TABLES.length];
        // One shared budget instead of 30 s per table: eight hanging queries used to keep the
        // caller - /co status runs on the command thread - waiting for up to four minutes.
        long deadline = System.nanoTime() + COUNTS_BUDGET_NANOS;
        for (int i = 0; i < futures.size(); i++) {
            try {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    out[i] = -1L;
                    futures.get(i).cancel(true);
                    continue;
                }
                long waitMs = Math.min(TimeUnit.NANOSECONDS.toMillis(left),
                        COUNT_QUERY_TIMEOUT_SECONDS * 1000L);
                Long v = futures.get(i).get(waitMs, TimeUnit.MILLISECONDS);
                out[i] = v == null ? -1L : v;
            } catch (Exception e) {
                out[i] = -1L; // unknown, not zero: /co status prints "n/a" for this table
                futures.get(i).cancel(true);
            }
        }
        return out;
    }

    /**
     * Same result as {@link #counts()} but the eight queries run one after another on the
     * calling thread's own read connection. Safe from a read-pool thread: the parallel
     * version submits to the very pool it would be running on and can exhaust it.
     * A failed table reports {@code -1} so the caller can tell "0 rows" from "query failed".
     */
    public long[] countsSequential() {
        long[] out = new long[COUNT_TABLES.length];
        // The same shared budget the parallel version uses: eight COUNT(*) over a large co_block can
        // take minutes on a cold cache, and /co status calls this on the command thread (L1-08/4-09).
        long deadline = System.nanoTime() + COUNTS_BUDGET_NANOS;
        boolean budgetWarned = false;
        for (int i = 0; i < COUNT_TABLES.length; i++) {
            if (System.nanoTime() - deadline > 0) {
                if (!budgetWarned) {
                    budgetWarned = true;
                    CoreProtectFabric.LOGGER.warn("[CoreProtect] Counting the log tables took longer than {} s; "
                            + "the remaining tables are reported as unknown.",
                            COUNTS_BUDGET_NANOS / 1_000_000_000L);
                }
                for (int j = i; j < COUNT_TABLES.length; j++) {
                    out[j] = -1L;
                }
                break;
            }
            try (PreparedStatement ps = readConnection().prepareStatement("SELECT COUNT(*) FROM " + COUNT_TABLES[i])) {
                // A single query needs its own bound: there is no cancel(true) on this path, so a
                // timeout is the only way out of a statement that hangs on a locked file.
                try {
                    ps.setQueryTimeout(COUNT_QUERY_TIMEOUT_SECONDS);
                } catch (SQLException ignored) {
                    // driver without timeout support: the budget check above still applies
                }
                try (ResultSet rs = ps.executeQuery()) {
                    out[i] = rs.next() ? rs.getLong(1) : 0L;
                }
            } catch (Exception e) {
                CoreProtectFabric.LOGGER.warn("[CoreProtect] Count of {} failed: {}", COUNT_TABLES[i], e.toString());
                out[i] = -1L;
            }
        }
        return out;
    }

    /** Recently seen player names (newest first), for u: command suggestions. */
    public List<String> recentUsers(int limit) {        synchronized (recentUsers) {
            List<String> out = new ArrayList<>(recentUsers);
            Collections.reverse(out);
            return out.size() > limit ? out.subList(0, limit) : out;
        }
    }

    private void trackUser(String user) {
        if (user == null || user.startsWith("#")) return;
        synchronized (recentUsers) {
            recentUsers.remove(user);
            recentUsers.add(user);
            while (recentUsers.size() > 100) {
                Iterator<String> it = recentUsers.iterator();
                it.next();
                it.remove();
            }
        }
    }

    // ------------------------------------------------------------------
    // Purge / compaction
    // ------------------------------------------------------------------

    /**
     * Deletes every log row older than {@code before}.
     *
     * @return the number of deleted rows, or {@code null} when the purge failed - the caller
     *         must report the failure instead of announcing "0 rows deleted".
     */
    public Long purge(long before) {
        return purge(criteriaWithTimeBefore(before));
    }

    /**
     * Deletes the rows matching a whole criteria: everything older than {@code c.time} (exclusive)
     * plus the u:/e:/r:/world filters and, when the action selects the block table, the b: filter.
     * Runs in one transaction on the writer thread, so a failed or interrupted purge leaves the
     * database unchanged instead of half deleted, and a query cannot observe the eight tables
     * disagreeing with each other.
     *
     * @return the number of deleted rows, or {@code null} when the criteria carry no cutoff or the
     *         purge failed
     */
    public Long purge(Criteria c) {
        if (c == null || c.time <= 0) {
            // no cutoff means "delete everything older than 0" = wipe the database; the command
            // rejects /co purge without t:, and a silent wipe must never be the fallback
            CoreProtectFabric.LOGGER.warn("[CoreProtect] Refusing to purge without a time cutoff.");
            return null;
        }
        return execute(() -> doPurge(c), PURGE_TIMEOUT_SECONDS);
    }

    /** The criteria shape used by the "older than X" purge: a cutoff and nothing else. */
    private static Criteria criteriaWithTimeBefore(long before) {
        Criteria c = new Criteria();
        c.time = before;
        return c;
    }

    /** Fire-and-forget purge used by the data-retention startup limit; logs the row count. */
    public void purgeAsync(long before, int maxDays) {
        worker.submit(() -> {
            try {
                long rows = doPurge(criteriaWithTimeBefore(before));
                CoreProtectFabric.LOGGER.info("[CoreProtect] Data retention: deleted {} row(s) older than {} day(s).",
                        rows, maxDays);
                incrementalVacuum();
            } catch (Exception e) {
                CoreProtectFabric.LOGGER.error("[CoreProtect] Data retention purge failed", e);
            }
        });
    }

    private long doPurge(Criteria criteria) throws SQLException {
        boolean autoCommit = conn.getAutoCommit();
        // One transaction for all eight tables: the rows were auto-committed one table at a time, so
        // a failure halfway through left a database where block rows were gone but container rows
        // were not (L1-03).
        conn.setAutoCommit(false);
        try {
            long total = 0;
            for (String table : COUNT_TABLES) {
                total += deleteTable(conn, table, criteria);
            }
            conn.commit();
            // drop dictionary rows that the deleted log rows were the last users of
            try (Statement st = conn.createStatement()) {
                gcDictionaries(st);
            }
            // ... and drop the id caches too: they still map strings to ids that were just deleted,
            // so the next log row would reuse a dictionary id that no longer exists.
            invalidateDictionaries();
            return total;
        } catch (SQLException e) {
            try {
                conn.rollback();
            } catch (SQLException rollbackError) {
                CoreProtectFabric.LOGGER.error("[CoreProtect] Purge rollback failed", rollbackError);
            }
            throw e;
        } finally {
            try {
                conn.setAutoCommit(true);
            } catch (SQLException e) {
                CoreProtectFabric.LOGGER.warn("[CoreProtect] Could not re-enable auto-commit after the purge: {}",
                        e.toString());
            }
        }
    }

    /**
     * Deletes the rows of one table. co_block is the only table with the two state columns and the
     * action id, so its filters are applied through a sub-select on its own id; the other tables
     * only know time, player and position.
     */
    private long deleteTable(Connection c, String table, Criteria criteria) throws SQLException {
        Criteria filters = purgeFilters(criteria, table);
        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<>();
        if ("co_block".equals(table) && isBlockAction(criteria.action)) {
            sql.append("DELETE FROM co_block WHERE id IN (SELECT b.id FROM co_block b ")
                    .append("LEFT JOIN co_state so ON so.id=b.old_id LEFT JOIN co_state sn ON sn.id=b.new_id ")
                    .append("LEFT JOIN co_action a ON a.id=b.action_id WHERE b.time < ?");
            params.add(criteria.time);
            // appendBlockFilters() also adds the u:/e:/r:/world filters of the common set
            appendBlockFilters(sql, params, filters);
            appendBlockAction(sql, params, criteria.action);
            sql.append(")");
        } else {
            sql.append("DELETE FROM ").append(table).append(" WHERE time < ?");
            params.add(criteria.time);
            appendCommonFilters(sql, params, filters);
        }
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            bind(ps, params);
            return ps.executeUpdate();
        }
    }

    /** The filters a purge applies to one table; the cutoff itself is passed as {@code time < ?}. */
    private static Criteria purgeFilters(Criteria c, String table) {
        Criteria filters = new Criteria();
        filters.user = c.user;
        filters.exclude = c.exclude;
        filters.block = c.block;
        filters.action = c.action;
        filters.time = 0; // keep appendCommonFilters() from adding a second, lower bound
        if (hasPositionColumns(table)) {
            filters.radius = c.radius;
            filters.center = c.center;
            filters.world = c.world;
        }
        return filters;
    }

    /** True for the tables that store x/y/z, without which r:/world filters cannot be applied. */
    private static boolean hasPositionColumns(String table) {
        return switch (table) {
            case "co_block", "co_container", "co_item", "co_sign", "co_entity" -> true;
            default -> false;
        };
    }

    /** True when a: selects block rows ({@code block}, {@code +block}, {@code -block}, {@code #tnt}). */
    private static boolean isBlockAction(String action) {
        if (action == null || action.isEmpty()) return true;
        String lower = action.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("block") || lower.startsWith("#");
    }

    /** Drops every dictionary id cache (after a purge, or after a batch was rolled back). */
    private void invalidateDictionaries() {
        nameIds.clear();
        worldIds.clear();
        actionIds.clear();
        stateCache.clear();
    }

    /** Lightweight: returns freed pages to the file system (requires auto_vacuum=INCREMENTAL). */
    private void incrementalVacuum() {
        // VACUUM-style maintenance refuses to run while prepared statements are still open,
        // so the writer's statement cache is dropped first (it fills up again on demand).
        closeStatements();
        try (Statement st = conn.createStatement()) {
            // Bounded on purpose: an unbounded incremental_vacuum frees every page in one go and holds
            // the only writer thread while all logged rows pile up in the queue (L1-04).
            st.execute("PRAGMA incremental_vacuum(" + INCREMENTAL_VACUUM_PAGES + ")");
        } catch (SQLException e) {
            CoreProtectFabric.LOGGER.warn("[CoreProtect] Incremental vacuum failed: {}", e.toString());
        }
    }

    /** Full compaction: rebuilds the database file (also applies auto_vacuum=INCREMENTAL) and logs freed space. */
    public void vacuumAsync() {
        worker.submit(() -> {
            try {
                long before = Files.size(dbPath);
                // VACUUM rewrites the file and needs room for a second copy while the old one still
                // exists, so check the disk before starting a procedure that cannot be cancelled (L1-04).
                long usable = Files.getFileStore(dbPath).getUsableSpace();
                if (usable < before * 2) {
                    CoreProtectFabric.LOGGER.warn("[CoreProtect] Skipping the database compaction: {} MB free, "
                            + "{} MB needed to rebuild the file.", usable / (1024 * 1024), before * 2 / (1024 * 1024));
                    return;
                }
                long started = System.nanoTime();
                closeStatements();
                try (Statement st = conn.createStatement()) {
                    st.execute("PRAGMA auto_vacuum=INCREMENTAL");
                    st.execute("VACUUM");
                    // in WAL mode the rebuilt pages live in the WAL; checkpoint first so the
                    // main file can actually be truncated by incremental_vacuum below
                    st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                    st.execute("PRAGMA incremental_vacuum(" + INCREMENTAL_VACUUM_PAGES + ")");
                }
                long after = Files.size(dbPath);
                CoreProtectFabric.LOGGER.info("[CoreProtect] Database compacted: {} MB -> {} MB (freed {} MB, took {} ms).",
                        before / (1024 * 1024), after / (1024 * 1024), (before - after) / (1024 * 1024),
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            } catch (Exception e) {
                CoreProtectFabric.LOGGER.warn("[CoreProtect] Database compaction failed: {}", e.toString());
            }
        });
    }

    public String userLanguage(String user) {
        return executeRead(() -> {
            try (PreparedStatement ps = readConnection().prepareStatement("SELECT language FROM co_user WHERE user=?")) {
                ps.setString(1, user);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    /**
     * Reads the language preference by uuid, which keeps working after a player renames.
     *
     * @param uuid the player uuid as stored by {@link #saveUserLanguageAsync(String, String, String)}
     * @return the stored language code, or {@code null} when this uuid never saved one
     */
    public String userLanguageByUuid(String uuid) {
        if (uuid == null || uuid.isEmpty()) return null;
        return executeRead(() -> {
            try (PreparedStatement ps = readConnection().prepareStatement(
                    "SELECT language FROM co_user_uuid WHERE uuid=?")) {
                ps.setString(1, uuid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getString(1);
                }
            }
            // fall back to the name-keyed table for databases written before the uuid table existed
            try (PreparedStatement ps = readConnection().prepareStatement(
                    "SELECT language FROM co_user WHERE uuid=?")) {
                ps.setString(1, uuid);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private void appendCommonFilters(StringBuilder sql, List<Object> params, Criteria c) {
        if (c.time > 0) {
            sql.append(" AND time >= ?");
            params.add(c.time);
        }
        if (c.user != null && !c.user.isEmpty()) {
            // player names are matched case-insensitively, like /co online does
            sql.append(" AND name_id IN (SELECT id FROM co_name WHERE name = ? COLLATE NOCASE)");
            params.add(c.user);
        }
        if (c.exclude != null && !c.exclude.isEmpty()) {
            // e:<value>: never match this user (kept for compatibility)
            sql.append(" AND name_id NOT IN (SELECT id FROM co_name WHERE name = ? COLLATE NOCASE)");
            params.add(c.exclude);
        }
        if (c.radius > 0 && c.center != null) {
            int cx = c.center.getX();
            int cy = c.center.getY();
            int cz = c.center.getZ();
            long r = c.radius;
            // A radius without a world is not a place: the same x/y/z exists in every dimension,
            // so "/co lookup r:10" used to list - and "/co rollback r:10" used to revert - blocks
            // of the nether and the end that happened to share those coordinates.
            if (c.world != null && !c.world.isEmpty()) {
                sql.append(" AND wid_id = (SELECT id FROM co_world WHERE wid = ?)");
                params.add(c.world);
            }
            // bounding box first so SQLite can use the (wid,x,y,z) index, then the exact distance check
            sql.append(" AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ?"
                    + " AND (x-?)*(x-?)+(y-?)*(y-?)+(z-?)*(z-?) <= ?");
            params.add(cx - r);
            params.add(cx + r);
            params.add(cy - r);
            params.add(cy + r);
            params.add(cz - r);
            params.add(cz + r);
            params.add(cx);
            params.add(cx);
            params.add(cy);
            params.add(cy);
            params.add(cz);
            params.add(cz);
            params.add(r * r);
        }
    }

    private void appendBlockFilters(StringBuilder sql, List<Object> params, Criteria c) {
        appendCommonFilters(sql, params, c);
        if (c.block != null && !c.block.isEmpty()) {
            // resolved state strings come from the joined dictionary columns
            // ESCAPE: the pattern is user input, and an unescaped '_' matched any character
            // ("b:_" matched every block) while '%' matched everything (L1-12)
            sql.append(" AND (so.state LIKE ? ESCAPE '\\' OR sn.state LIKE ? ESCAPE '\\')");
            String like = "%" + likeEscape(c.block.toLowerCase()) + "%";
            params.add(like);
            params.add(like);
        }
        if (c.exclude != null && !c.exclude.isEmpty()) {
            // e: also excludes a matching action/cause (#tnt, #fire, ...) or block type
            // (e:stone); previously it was compared against the user column only, so a
            // block exclusion silently filtered nothing
            String like = "%" + likeEscape(c.exclude.toLowerCase()) + "%";
            sql.append(" AND (a.action IS NULL OR a.action NOT LIKE ? ESCAPE '\\')")
                    .append(" AND (so.state IS NULL OR so.state NOT LIKE ? ESCAPE '\\')")
                    .append(" AND (sn.state IS NULL OR sn.state NOT LIKE ? ESCAPE '\\')");
            params.add(like); // was the raw value: "e:fire" never matched the action "#fire"
            params.add(like);
            params.add(like);
        }
    }

    /**
     * Escapes the LIKE wildcards of a user-supplied value so it is matched literally. Callers must
     * append {@code ESCAPE '\'} to the pattern, otherwise SQLite keeps treating the backslash as an
     * ordinary character (L1-12: {@code b:_} matched every block and {@code e:%} every row).
     */
    private static String likeEscape(String raw) {
        if (raw == null) return "";
        StringBuilder out = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == '\\' || ch == '%' || ch == '_') {
                out.append('\\');
            }
            out.append(ch);
        }
        return out.toString();
    }

    /**
     * True when {@code a:<action>} can be answered by this table. The lookup queries used to ignore
     * the action completely, so {@code a:container} also returned blocks and {@code a:#tnt} also
     * returned every chest access (L2-10 / L4-03). A null/empty action selects everything.
     */
    private static boolean actionSelects(String action, String... aliases) {
        if (action == null || action.isEmpty()) return true;
        String lower = action.toLowerCase(java.util.Locale.ROOT);
        for (String alias : aliases) {
            if (lower.contains(alias)) return true;
        }
        return false;
    }

    /**
     * True when {@code a:} selects block rows: no action, {@code block}/{@code +block}/{@code -block}
     * or a {@code #<cause>} query ({@code #tnt}, {@code #kill}, ...).
     */
    private static boolean blockActionSelects(String action) {
        if (action == null || action.isEmpty()) return true;
        if (action.startsWith("#")) return true; // #tnt / #fire are block causes, #kill/#death entities
        return actionSelects(action, "block");
    }

    /**
     * True when {@code a:} explicitly names a different table, so this one must stay empty.
     * {@code block}/#cause and no action at all do not narrow the other tables: {@code /co rollback}
     * queries block, container, sign, item and entity rows with the parser's default action "block"
     * and has to keep rolling all of them back.
     */
    private static boolean actionNamesOtherTable(String action, String... aliases) {
        if (action == null || action.isEmpty()) return false;
        if (actionSelects(action, aliases)) return false;
        return !blockActionSelects(action);
    }

    /** {@code b:<value>} for the tables whose item/entity type is the joined co_state row (L4-14). */
    private void appendDataFilter(StringBuilder sql, List<Object> params, String value, String column) {
        if (value == null || value.isEmpty()) return;
        sql.append(" AND ").append(column).append(" LIKE ? ESCAPE '\\'");
        params.add("%" + likeEscape(value.toLowerCase()) + "%");
    }

    /**
     * {@code e:<value>} for the same tables: co_block excludes a matching item/entity name as well as
     * a user, so the joined data row has to be excluded here too (L4-14). A null data id stays.
     */
    private void appendDataExcludeFilter(StringBuilder sql, List<Object> params, String value, String column) {
        if (value == null || value.isEmpty()) return;
        sql.append(" AND (").append(column).append(" IS NULL OR ").append(column).append(" NOT LIKE ? ESCAPE '\\')");
        params.add("%" + likeEscape(value.toLowerCase()) + "%");
    }

    private void appendBlockAction(StringBuilder sql, List<Object> params, String action) {
        if (action == null) return;
        switch (action) {
            case "block" -> sql.append(" AND b.type IN (0,1,2)");
            case "+block" -> sql.append(" AND b.type = 1");
            case "-block" -> sql.append(" AND b.type = 0");
            default -> {
                if (action.startsWith("#")) {
                    sql.append(" AND b.type = 2 AND b.action_id = (SELECT id FROM co_action WHERE action = ?)");
                    params.add(action);
                }
            }
        }
    }

    /** Synchronous task on the writer thread (init, purge). */
    private <T> T execute(Callable<T> task) {
        return execute(task, 60);
    }

    /**
     * Synchronous task on the writer thread with an explicit timeout. A timed-out task keeps
     * running on the writer, so the caller gets {@code null} and must not report "nothing was
     * deleted" as if the work had been aborted.
     */
    private <T> T execute(Callable<T> task, long timeoutSeconds) {
        try {
            return worker.submit(task).get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            CoreProtectFabric.LOGGER.error("[CoreProtect] Database operation still running after {} s; "
                    + "the result is unknown.", timeoutSeconds);
            return null;
        } catch (Exception e) {
            CoreProtectFabric.LOGGER.error("[CoreProtect] Database operation failed", e);
            return null;
        }
    }

    /** Synchronous read on the parallel read pool (one connection per reader thread). */
    private <T> T executeRead(Callable<T> task) {
        if (Boolean.TRUE.equals(poolReader.get())) {
            // A read-pool worker asking the pool for another read waits for a thread that may
            // never come free (the pool is small), which stalls every other query too. Run the
            // nested read on the thread that is already there.
            return callDirect(task);
        }
        try {
            T result = readPool.submit(withPoolMarker(task)).get(30, TimeUnit.SECONDS);
            lastReadFailed.set(Boolean.FALSE);
            return result;
        } catch (Exception e) {
            lastReadFailed.set(Boolean.TRUE);
            CoreProtectFabric.LOGGER.error("[CoreProtect] Database read failed", e);
            return null;
        }
    }

    /** Marks the executing thread as a read-pool worker for the duration of the task. */
    private <T> Callable<T> withPoolMarker(Callable<T> task) {
        return () -> {
            poolReader.set(Boolean.TRUE);
            try {
                return task.call();
            } finally {
                poolReader.remove();
                // A timed-out query is cancelled with cancel(true), whose interrupt may still be
                // pending when the worker thread picks up the next query; sqlite-jdbc checks the
                // interrupt flag and would abort that unrelated read (L1-16). Clear it here.
                Thread.interrupted();
            }
        };
    }

    /** Runs a read task on the calling thread (callers that are already read-pool workers). */
    private <T> T callDirect(Callable<T> task) {
        try {
            T result = task.call();
            lastReadFailed.set(Boolean.FALSE);
            return result;
        } catch (Exception e) {
            lastReadFailed.set(Boolean.TRUE);
            CoreProtectFabric.LOGGER.error("[CoreProtect] Database read failed", e);
            return null;
        }
    }

    /** True when the last read issued by this thread failed (timeout or SQL error). */
    public boolean readFailed() {
        return Boolean.TRUE.equals(lastReadFailed.get());
    }

    /**
     * The caller-thread read connection (lazily opened, query-only). Throws instead of
     * returning {@code null} so a failure surfaces as a readable SQL error and never as
     * a bare NPE on {@code readConnection().prepareStatement(...)}.
     */
    private Connection readConnection() throws SQLException {
        Object[] holder = readHolder.get();
        if (holder != null && (Integer) holder[0] == generation.get() && holder[1] instanceof Connection c) {
            return c;
        }
        // generation changed (restore/close): drop the outdated connection instead of leaking it
        if (holder != null && holder[1] instanceof Connection old) {
            readHolder.remove();
            readConnections.remove(old);
            try {
                old.close();
            } catch (SQLException ignored) {
            }
        }
        try {
            Class.forName("org.sqlite.JDBC");
            Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            applyPragmas(c, true);
            readConnections.add(c);
            readHolder.set(new Object[] { generation.get(), c });
            return c;
        } catch (Exception e) {
            throw new SQLException("Failed to open a read connection", e);
        }
    }

    /** Resolves a state string to its dictionary id (writer thread only, cached). */
    private long stateId(String state) {
        if (state == null) return 0L;
        Long cached = stateCache.get(state);
        if (cached != null) return cached;
        try {
            PreparedStatement ins = prepare("INSERT INTO co_state(state) VALUES(?) ON CONFLICT(state) DO NOTHING");
            ins.setString(1, state);
            ins.executeUpdate();
            PreparedStatement sel = prepare("SELECT id FROM co_state WHERE state=?");
            sel.setString(1, state);
            try (ResultSet rs = sel.executeQuery()) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    stateCache.put(state, id);
                    return id;
                }
            }
        } catch (SQLException e) {
            CoreProtectFabric.LOGGER.error("[CoreProtect] Failed to resolve state id", e);
        }
        return 0L;
    }

    private void enqueue(String sql, List<Object> params) {
        submitWrite(() -> {
            try {
                PreparedStatement ps = prepare(sql);
                bind(ps, params);
                ps.executeUpdate();
            } catch (Exception e) {
                CoreProtectFabric.LOGGER.error("[CoreProtect] Database write failed: " + sql, e);
                countDroppedRow();
            }
        });
    }

    /**
     * Queues a log row for the next batch. Writes that arrive after the database was
     * closed (server shutdown, listener callbacks still firing) are dropped instead of
     * throwing {@link RejectedExecutionException} on the server thread.
     */
    private void submitWrite(Runnable task) {
        if (!open) {
            // Rows that arrive after close() (listener callbacks during shutdown) are counted:
            // /co status used to report "0 dropped" while they were silently disappearing.
            countDropped();
            return;
        }
        if (!writeQueue.offer(task)) {
            countDropped();
            return;
        }
        scheduleFlush(batchIntervalMs());
    }

    /** Counts one dropped log row and warns about it (rate limited to once per 1000 rows). */
    private void countDropped() {
        countDropped(1L);
    }

    /**
     * Counts dropped log rows. The warning is rate limited to the first row and to every full 1000,
     * so a burst of drops cannot flood the log.
     */
    private void countDropped(long rows) {
        if (rows <= 0) return;
        long dropped = droppedWrites.addAndGet(rows);
        long before = dropped - rows;
        if (dropped == rows || dropped / 1000 != before / 1000) {
            CoreProtectFabric.LOGGER.warn("[CoreProtect] {} log row(s) dropped so far - the database could not "
                    + "take them. Consider database.syncMode=normal.", dropped);
        }
    }

    /**
     * Counts one log row that will never reach the database (its own INSERT failed). Uses the same
     * counter that {@code /co status} prints, so a row-level failure is no longer invisible (L1-02).
     */
    public void countDroppedRow() {
        countDropped(1L);
    }

    /** Total number of log rows dropped since startup - the value {@code /co status} reports. */
    public long droppedRows() {
        return droppedWrites.get();
    }

    private int batchIntervalMs() {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        return mod == null ? 250 : Math.max(0, mod.config().database.batchIntervalMs);
    }

    private int batchMaxRows() {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        return mod == null ? 1000 : Math.max(1, mod.config().database.batchMaxRows);
    }

    /** Schedules one flush on the writer thread unless a flush is already pending. */
    private void scheduleFlush(long delayMs) {
        if (!flushScheduled.compareAndSet(false, true)) return;
        try {
            worker.schedule(this::runScheduledFlush, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            flushScheduled.set(false); // the writer is shutting down
        }
    }

    private void runScheduledFlush() {
        flushScheduled.set(false);
        flushWrites();
    }

    /**
     * Writes the queued rows (at most {@code database.batchMaxRows} per call) inside one
     * transaction and commits once, so a burst of log rows costs a single fsync instead
     * of one per row. Runs on the writer thread only.
     */
    private void flushWrites() {
        if (conn == null) {
            // No writer connection (open failed or is being reopened): keep the rows and retry
            // instead of polling the queue forever without a connection.
            if (open && !writeQueue.isEmpty()) {
                flushRetryMs = Math.max(flushRetryMs, 1000L);
                scheduleFlush(flushRetryMs);
            }
            return;
        }
        int rows = 0;
        boolean transaction = false;
        boolean restartWriter = false;
        List<Runnable> pending = null;
        try {
            int max = batchMaxRows();
            while (rows < max) {
                Runnable task = writeQueue.poll();
                if (task == null) break;
                if (pending == null) {
                    pending = new ArrayList<>();
                }
                pending.add(task);
                if (!transaction) {
                    conn.setAutoCommit(false);
                    transaction = true;
                }
                task.run();
                rows++;
            }
            if (transaction) {
                conn.commit();
                committedBatches.incrementAndGet();
                // a batch that went through resets both the delay and the give-up counter
                flushFailures = 0;
                flushRetryMs = 0;
            }
        } catch (Exception e) {
            CoreProtectFabric.LOGGER.error("[CoreProtect] Batch of {} row(s) failed; they are queued again", rows, e);
            try {
                conn.rollback();
            } catch (SQLException rollbackError) {
                // The transaction is still open. Re-enabling auto-commit in the finally block would
                // then COMMIT the rows that are about to be queued again, so the same rows would be
                // written twice (L1-06). Reopening the connection throws the transaction away.
                restartWriter = true;
                CoreProtectFabric.LOGGER.error("[CoreProtect] Rollback of the failed batch failed; the writer "
                        + "connection is reopened so the open transaction cannot be committed.", rollbackError);
            }
            // dictionary ids created inside the rolled-back transaction do not exist any more
            invalidateDictionaries();
            flushFailures++;
            if (restartWriter) {
                reopenWriterConnection();
                // the failed rows were never committed: queue them so they are written again
                requeueFailedBatch(pending, false);
                flushRetryMs = 1000L;
            } else if (flushFailures >= MAX_FLUSH_FAILURES) {
                // A batch that fails because of ONE bad row used to be retried every second forever:
                // the queue filled up and every new row was dropped (L1-07). Give up on this batch,
                // count it, and let the rows behind it through - a broken disk still fails, but the
                // rows are then counted instead of being squeezed out silently.
                int lost = pending == null ? 0 : pending.size();
                CoreProtectFabric.LOGGER.error("[CoreProtect] The database has been unwritable for {} batch(es) in "
                        + "a row; {} row(s) of the last batch are dropped so that new rows can be written.",
                        flushFailures, lost);
                countDropped(lost);
                flushFailures = 0;
                flushRetryMs = 1000L;
            } else {
                // The rows were already taken out of the queue, so a rolled-back batch used to be
                // lost without a single log line. Put them back (in their original order, behind
                // whatever arrived meanwhile) so the next flush writes them.
                requeueFailedBatch(pending, true);
                flushRetryMs = nextFlushBackoff();
            }
        } finally {
            if (transaction && !restartWriter) {
                try {
                    conn.setAutoCommit(true);
                } catch (SQLException e) {
                    // the batch itself is committed; only the flag is lost and the next batch sets it
                    CoreProtectFabric.LOGGER.warn("[CoreProtect] Could not re-enable auto-commit on the writer "
                            + "connection: {}", e.toString());
                }
            }
            if (!writeQueue.isEmpty() && open) {
                scheduleFlush(flushRetryMs); // 0 = keep draining, >0 = back off after a failed batch
            }
        }
    }

    /**
     * Puts the rows of a failed batch back into the queue, in their original order. With
     * {@code includeQueue} the rows that arrived meanwhile are moved behind them, so a retry never
     * reorders the log; rows that no longer fit are counted as dropped.
     */
    private void requeueFailedBatch(List<Runnable> pending, boolean includeQueue) {
        if (pending == null || pending.isEmpty()) return;
        List<Runnable> retry = new ArrayList<>(pending);
        if (includeQueue) {
            writeQueue.drainTo(retry);
        }
        for (Runnable task : retry) {
            if (!writeQueue.offer(task)) {
                countDropped();
            }
        }
    }

    /** 1 s, 2 s, 4 s, ... capped, so a disk that refuses every batch is not hammered once per second. */
    private long nextFlushBackoff() {
        int shift = Math.min(Math.max(flushFailures - 1, 0), 5);
        return Math.min(MAX_FLUSH_BACKOFF_MS, 1000L << shift);
    }

    /** Reopens the writer connection after a batch left a transaction that could not be rolled back. */
    private void reopenWriterConnection() {
        closeStatements();
        Connection broken = conn;
        conn = null;
        if (broken != null) {
            try {
                // closing drops the transaction that could not be rolled back
                broken.close();
            } catch (SQLException e) {
                CoreProtectFabric.LOGGER.warn("[CoreProtect] Could not close the broken writer connection: {}",
                        e.toString());
            }
        }
        if (!open || closed) return;
        try {
            openWriter();
            // ids cached before the failed transaction may belong to rolled-back dictionary rows
            invalidateDictionaries();
            CoreProtectFabric.LOGGER.warn("[CoreProtect] The writer connection was reopened; the queued rows are "
                    + "written again.");
        } catch (Exception e) {
            CoreProtectFabric.LOGGER.error("[CoreProtect] Failed to reopen the writer connection; the queued rows "
                    + "stay queued until it succeeds: {}", e.toString());
        }
    }

    /** Writes everything still queued. Shutdown path, runs on the writer thread. */
    private void drainWrites() {
        // A batch that keeps failing is put back into the queue (see flushWrites), so this loop used
        // to spin forever and the close task could only end through its timeout. Stop after a
        // deadline or a few unsuccessful rounds and report what could not be written.
        long deadline = System.nanoTime() + DRAIN_TIMEOUT_NANOS;
        int failures = 0;
        while (!writeQueue.isEmpty() && conn != null && System.nanoTime() < deadline) {
            int before = writeQueue.size();
            flushWrites();
            if (writeQueue.isEmpty()) break;
            if (writeQueue.size() >= before) {
                if (++failures >= DRAIN_MAX_FAILURES) break;
                try {
                    Thread.sleep(250L); // give a disk that just refused a batch a moment
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } else {
                failures = 0;
            }
        }
        if (!writeQueue.isEmpty()) {
            int lost = writeQueue.size();
            writeQueue.clear();
            // counted, not just logged: /co status used to report "0 dropped" for the rows that the
            // shutdown threw away (L1-10)
            countDropped(lost);
            CoreProtectFabric.LOGGER.warn("[CoreProtect] {} row(s) could not be written before the database closed and were dropped.",
                    lost);
        }
    }

    /** Reuses one prepared statement per SQL string (writer thread only). */
    private PreparedStatement prepare(String sql) throws SQLException {
        PreparedStatement ps = statements.get(sql);
        if (ps == null) {
            ps = conn.prepareStatement(sql);
            statements.put(sql, ps);
        }
        ps.clearParameters();
        return ps;
    }

    private void closeStatements() {
        for (PreparedStatement ps : statements.values()) {
            try {
                ps.close();
            } catch (SQLException ignored) {
            }
        }
        statements.clear();
    }

    /** {committed batches, rows still queued, rows dropped} - shown by /co status. */
    public long[] writeStats() {
        return new long[]{committedBatches.get(), writeQueue.size(), droppedWrites.get()};
    }

    /**
     * Runs a query on the read pool and hands the result back ON THE SERVER THREAD, so
     * commands never park the tick thread waiting for SQLite. The old path used
     * {@code executeRead(...)} with {@code get(30, SECONDS)} per query, which stalled the
     * server for the duration of the query (up to 8 x 30 s for a single {@code /co status}).
     * A failed query yields {@code null} for the callback.
     */
    public <T> void submitRead(Callable<T> task, Consumer<T> callback) {
        CompletableFuture<T> future = submitReadFuture(task);
        if (future == null) return;
        future.thenAccept(value -> {
            MinecraftServer server = CoreProtectFabric.instance() == null ? null : CoreProtectFabric.instance().server();
            if (server == null) {
                // no server left to deliver to (shutdown): say so instead of staying silent
                CoreProtectFabric.LOGGER.warn("[CoreProtect] Query result discarded - no server to deliver it to.");
                return;
            }
            server.execute(() -> {
                try {
                    callback.accept(value);
                } catch (Throwable t) {
                    // a broken callback used to kill the server thread
                    CoreProtectFabric.LOGGER.error("[CoreProtect] Query callback failed", t);
                }
            });
        });
    }

    /**
     * Runs a query on the read pool and returns its future, so a caller that has to answer
     * before the server tick ends (console/RCON output is flushed when the command returns)
     * can bound how long it waits without running the task twice. Returns {@code null} when
     * the pool is shutting down; a failed query completes with {@code null}.
     */
    public <T> CompletableFuture<T> submitReadFuture(Callable<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            readPool.submit(withPoolMarker(() -> {
                T value;
                try {
                    value = task.call();
                } catch (Throwable t) {
                    CoreProtectFabric.LOGGER.warn("[CoreProtect] Query failed: {}", t.toString());
                    value = null;
                }
                future.complete(value);
                return value;
            }));
        } catch (RejectedExecutionException e) {
            // the read pool is shutting down; nothing left to answer
            CoreProtectFabric.LOGGER.warn("[CoreProtect] Query rejected - the database is shutting down.");
            return null;
        }
        return future;
    }

    private static void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object p = params.get(i);
            if (p == null) {
                ps.setObject(i + 1, null);
            } else if (p instanceof Long l) {
                ps.setLong(i + 1, l);
            } else if (p instanceof Integer in) {
                ps.setInt(i + 1, in);
            } else {
                ps.setString(i + 1, p.toString());
            }
        }
    }
}
