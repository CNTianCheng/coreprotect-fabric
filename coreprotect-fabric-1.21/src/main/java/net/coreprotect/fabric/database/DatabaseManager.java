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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
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
                               int type, String data, int amount) {
    }

    /** Item drops ({@code -}) and pickups ({@code +}). */
    public record ItemLog(long id, long time, String user, String wid, int x, int y, int z,
                          String action, String data, int amount) {
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
            "CREATE TABLE IF NOT EXISTS co_container (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, name_id INTEGER NOT NULL, wid_id INTEGER NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, type INTEGER NOT NULL, data_id INTEGER, amount INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS idx_container_pos ON co_container(wid_id, x, y, z)",
            "CREATE INDEX IF NOT EXISTS idx_container_time ON co_container(time, name_id)",
            "CREATE TABLE IF NOT EXISTS co_item (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, name_id INTEGER NOT NULL, wid_id INTEGER NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, action_id INTEGER, data_id INTEGER, amount INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS idx_item_pos ON co_item(wid_id, x, y, z)",
            "CREATE INDEX IF NOT EXISTS idx_item_time ON co_item(time, name_id)",
            "CREATE TABLE IF NOT EXISTS co_sign (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, name_id INTEGER NOT NULL, wid_id INTEGER NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, data TEXT NOT NULL)",
            "CREATE INDEX IF NOT EXISTS idx_sign_pos ON co_sign(wid_id, x, y, z)",
            "CREATE INDEX IF NOT EXISTS idx_sign_time ON co_sign(time, name_id)",
            "CREATE TABLE IF NOT EXISTS co_entity (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, name_id INTEGER NOT NULL, wid_id INTEGER NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, data_id INTEGER, action_id INTEGER)",
            "CREATE INDEX IF NOT EXISTS idx_entity_time ON co_entity(time, name_id)",
            "CREATE TABLE IF NOT EXISTS co_session (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, name_id INTEGER NOT NULL, wid_id INTEGER NOT NULL, action_id INTEGER)",
            "CREATE INDEX IF NOT EXISTS idx_session_time ON co_session(time, name_id)",
            "CREATE TABLE IF NOT EXISTS co_command (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, name_id INTEGER NOT NULL, message TEXT NOT NULL)",
            "CREATE INDEX IF NOT EXISTS idx_command_time ON co_command(time, name_id)",
            "CREATE TABLE IF NOT EXISTS co_chat (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, name_id INTEGER NOT NULL, message TEXT NOT NULL)",
            "CREATE INDEX IF NOT EXISTS idx_chat_time ON co_chat(time, name_id)",
            "CREATE TABLE IF NOT EXISTS co_user (user TEXT PRIMARY KEY, language TEXT)"
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

    private final java.util.concurrent.ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "CoreProtect-DB");
        t.setDaemon(true);
        return t;
    });
    private final int readThreads = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
    private final ExecutorService readPool = Executors.newFixedThreadPool(readThreads, r -> {
        Thread t = new Thread(r, "CoreProtect-Read");
        t.setDaemon(true);
        return t;
    });
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
        try {
            // The one-time v1->v2 migration may take a while on large databases; wait patiently.
            worker.submit(() -> {
                Class.forName("org.sqlite.JDBC");
                conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                applyPragmas(conn, false);
                initSchema(conn);
                open = true;
                return null;
            }).get(30, TimeUnit.MINUTES);
        } catch (Exception e) {
            CoreProtectFabric.LOGGER.error("[CoreProtect] Failed to open the database", e);
            return;
        }
        if (legacyMigrated) {
            // rebuild the file in the background so the dropped text columns actually free disk space
            vacuumAsync();
        }
        scheduleCheckpoints();
        scheduleBackups();
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
     * One-time v1 -> v2 migration: dictionary-encode the state/material text columns,
     * drop them, and mark the schema as v2. A backup copy is kept next to the file.
     */
    private void migrateV1ToV2(Connection c) throws SQLException {
        try {
            Path backup = dbPath.resolveSibling(dbPath.getFileName() + ".bak-v1");
            if (!Files.exists(backup)) {
                CoreProtectFabric.LOGGER.info("[CoreProtect] Migrating database to compressed schema; creating backup {} ...",
                        backup.getFileName());
                try {
                    Files.copy(dbPath, backup);
                } catch (java.io.IOException e) {
                    throw new SQLException("Failed to create migration backup", e);
                }
            }
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
            CoreProtectFabric.LOGGER.info("[CoreProtect] Database schema migrated to v2 (dictionary-compressed).");
            legacyMigrated = true;
        } catch (SQLException e) {
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
            if (!Files.exists(backup)) {
                CoreProtectFabric.LOGGER.info("[CoreProtect] Compressing the database (schema v3); creating backup {} ...",
                        backup.getFileName());
                try (Statement st = c.createStatement()) {
                    // fold the WAL into the main file first, otherwise the copy would miss the
                    // transactions that were committed after the last checkpoint
                    st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                } catch (SQLException e) {
                    CoreProtectFabric.LOGGER.warn("[CoreProtect] WAL checkpoint before the migration backup failed: {}",
                            e.toString());
                }
                try {
                    Files.copy(dbPath, backup);
                } catch (java.io.IOException e) {
                    throw new SQLException("Failed to create migration backup", e);
                }
            }
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

    /** Removes dictionary rows that no log row references any more (used after a purge/migration). */
    private void gcDictionaries(Statement st) throws SQLException {
        st.executeUpdate("DELETE FROM co_state WHERE id NOT IN ("
                + "SELECT old_id FROM co_block WHERE old_id IS NOT NULL "
                + "UNION SELECT new_id FROM co_block WHERE new_id IS NOT NULL "
                + "UNION SELECT data_id FROM co_container WHERE data_id IS NOT NULL "
                + "UNION SELECT data_id FROM co_item WHERE data_id IS NOT NULL "
                + "UNION SELECT data_id FROM co_entity WHERE data_id IS NOT NULL)");
        st.executeUpdate("DELETE FROM co_name WHERE id NOT IN ("
                + "SELECT name_id FROM co_block UNION SELECT name_id FROM co_container "
                + "UNION SELECT name_id FROM co_item UNION SELECT name_id FROM co_sign "
                + "UNION SELECT name_id FROM co_entity UNION SELECT name_id FROM co_session "
                + "UNION SELECT name_id FROM co_command UNION SELECT name_id FROM co_chat)");
        st.executeUpdate("DELETE FROM co_world WHERE id NOT IN ("
                + "SELECT wid_id FROM co_block UNION SELECT wid_id FROM co_container "
                + "UNION SELECT wid_id FROM co_item UNION SELECT wid_id FROM co_sign "
                + "UNION SELECT wid_id FROM co_entity UNION SELECT wid_id FROM co_session)");
        st.executeUpdate("DELETE FROM co_action WHERE id NOT IN ("
                + "SELECT action_id FROM co_block WHERE action_id IS NOT NULL "
                + "UNION SELECT action_id FROM co_item WHERE action_id IS NOT NULL "
                + "UNION SELECT action_id FROM co_entity WHERE action_id IS NOT NULL "
                + "UNION SELECT action_id FROM co_session WHERE action_id IS NOT NULL)");
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
                st.execute("PRAGMA synchronous=" + CoreProtectFabric.instance().config().database.syncMode.toUpperCase());
            }
        } catch (Exception e) {
            CoreProtectFabric.LOGGER.error("[CoreProtect] Failed to apply SQLite pragmas", e);
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
            try {
                Path target = dbPath.resolveSibling(dbPath.getFileName() + ".backup");
                Path tmp = dbPath.resolveSibling(dbPath.getFileName() + ".backup.tmp");
                Files.deleteIfExists(tmp);
                String sql = "VACUUM INTO '" + tmp.toAbsolutePath().toString().replace("'", "''") + "'";
                try (Statement st = conn.createStatement()) {
                    st.execute(sql);
                }
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                CoreProtectFabric.LOGGER.info("[CoreProtect] Database backup written: {} ({} MB).",
                        target.getFileName(), Files.size(target) / (1024 * 1024));
            } catch (Exception e) {
                CoreProtectFabric.LOGGER.warn("[CoreProtect] Database backup failed: {}", e.toString());
            }
        }, minutes, minutes, TimeUnit.MINUTES);
    }

    public void close() {
        if (!open) return;
        open = false; // drop any write that arrives from now on
        // Flush and close on the writer thread, but never lose the close task to a timed
        // get(): a backup/purge queued ahead of it used to push it past the 60 s timeout.
        // The queued batch is drained first, so rows logged just before the shutdown are
        // still written (they are no longer sitting in the executor queue).
        try {
            worker.submit(() -> {
                drainWrites();
                if (conn != null) {
                    try (Statement st = conn.createStatement()) {
                        st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                    } catch (SQLException ignored) {
                    }
                    closeStatements();
                    try {
                        conn.close();
                    } catch (SQLException ignored) {
                    }
                    conn = null;
                }
            }).get(5, TimeUnit.MINUTES);
        } catch (Exception e) {
            CoreProtectFabric.LOGGER.warn("[CoreProtect] Database close task did not finish cleanly: {}", e.toString());
            closeStatements();
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException ignored) {
                }
                conn = null;
            }
        }
        worker.shutdown();
        try {
            worker.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        closeReadConnections();
        readPool.shutdown();
        try {
            readPool.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
            }
        });
    }

    public void insertContainerAsync(ContainerLog l) {
        trackUser(l.user());
        submitWrite(() -> {
            long dataId = stateId(l.data());
            try {
                PreparedStatement ps = prepare("INSERT INTO co_container(time,name_id,wid_id,x,y,z,type,data_id,amount) VALUES(?,?,?,?,?,?,?,?,?)");
                bind(ps, Arrays.asList(l.time(), nameId(l.user()), worldId(l.wid()), l.x(), l.y(), l.z(),
                        l.type(), dataId, l.amount()));
                ps.executeUpdate();
            } catch (Exception e) {
                CoreProtectFabric.LOGGER.error("[CoreProtect] Database write failed: co_container", e);
            }
        });
    }

    public void insertItemAsync(ItemLog l) {
        trackUser(l.user());
        submitWrite(() -> {
            long dataId = stateId(l.data());
            try {
                PreparedStatement ps = prepare("INSERT INTO co_item(time,name_id,wid_id,x,y,z,action_id,data_id,amount) VALUES(?,?,?,?,?,?,?,?,?)");
                bind(ps, Arrays.asList(l.time(), nameId(l.user()), worldId(l.wid()), l.x(), l.y(), l.z(),
                        actionId(l.action()), dataId, l.amount()));
                ps.executeUpdate();
            } catch (Exception e) {
                CoreProtectFabric.LOGGER.error("[CoreProtect] Database write failed: co_item", e);
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
            }
        });
    }

    public void insertCommandAsync(long time, String user, String message) {
        trackUser(user);
        submitWrite(() -> {
            try {
                PreparedStatement ps = prepare("INSERT INTO co_command(time,name_id,message) VALUES(?,?,?)");
                bind(ps, Arrays.asList(time, nameId(user), message));
                ps.executeUpdate();
            } catch (Exception e) {
                CoreProtectFabric.LOGGER.error("[CoreProtect] Database write failed: co_command", e);
            }
        });
    }

    public void insertChatAsync(long time, String user, String message) {
        trackUser(user);
        submitWrite(() -> {
            try {
                PreparedStatement ps = prepare("INSERT INTO co_chat(time,name_id,message) VALUES(?,?,?)");
                bind(ps, Arrays.asList(time, nameId(user), message));
                ps.executeUpdate();
            } catch (Exception e) {
                CoreProtectFabric.LOGGER.error("[CoreProtect] Database write failed: co_chat", e);
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
        enqueue("INSERT INTO co_user(user,language) VALUES(?,?) ON CONFLICT(user) DO UPDATE SET language=excluded.language",
                Arrays.asList(user, language));
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
        return executeRead(() -> {
            StringBuilder sql = new StringBuilder(
                    "SELECT c.id,c.time,n.name,w.wid,c.x,c.y,c.z,c.type,s.state,c.amount "
                            + "FROM co_container c LEFT JOIN co_state s ON s.id=c.data_id "
                            + "LEFT JOIN co_name n ON n.id=c.name_id LEFT JOIN co_world w ON w.id=c.wid_id WHERE 1=1");
            List<Object> params = new ArrayList<>();
            appendCommonFilters(sql, params, c);
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
                                rs.getString(9), rs.getInt(10)));
                    }
                    return out;
                }
            }
        });
    }

    public List<ContainerLog> queryContainerHistory(String wid, int x, int y, int z, int limit) {
        return executeRead(() -> {
            String sql = "SELECT c.id,c.time,n.name,w.wid,c.x,c.y,c.z,c.type,s.state,c.amount FROM co_container c "
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
                                rs.getString(9), rs.getInt(10)));
                    }
                    return out;
                }
            }
        });
    }

    public List<ItemLog> queryItems(Criteria c, long limit, long offset) {
        return executeRead(() -> {
            StringBuilder sql = new StringBuilder(
                    "SELECT i.id,i.time,n.name,w.wid,i.x,i.y,i.z,ac.action,s.state,i.amount "
                            + "FROM co_item i LEFT JOIN co_state s ON s.id=i.data_id "
                            + "LEFT JOIN co_name n ON n.id=i.name_id LEFT JOIN co_world w ON w.id=i.wid_id "
                            + "LEFT JOIN co_action ac ON ac.id=i.action_id WHERE 1=1");
            List<Object> params = new ArrayList<>();
            appendCommonFilters(sql, params, c);
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
                                rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getString(8), rs.getString(9), rs.getInt(10)));
                    }
                    return out;
                }
            }
        });
    }

    public List<SignLog> querySigns(Criteria c, long limit, long offset) {
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
        return executeRead(() -> {
            StringBuilder sql = new StringBuilder(
                    "SELECT e.id,e.time,n.name,w.wid,e.x,e.y,e.z,s.state,ac.action "
                            + "FROM co_entity e LEFT JOIN co_state s ON s.id=e.data_id "
                            + "LEFT JOIN co_name n ON n.id=e.name_id LEFT JOIN co_world w ON w.id=e.wid_id "
                            + "LEFT JOIN co_action ac ON ac.id=e.action_id WHERE 1=1");
            List<Object> params = new ArrayList<>();
            // reuse the common filters so a:#kill also honours r:/t:/u:/e:
            appendCommonFilters(sql, params, c);
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
        return queryMessages("co_session", c, limit, offset, true);
    }

    public List<MessageLog> queryCommands(Criteria c, long limit, long offset) {
        return queryMessages("co_command", c, limit, offset, false);
    }

    public List<MessageLog> queryChat(Criteria c, long limit, long offset) {
        return queryMessages("co_chat", c, limit, offset, false);
    }

    private List<MessageLog> queryMessages(String table, Criteria c, long limit, long offset, boolean hasAction) {
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

    /** Runs all eight table counts in parallel on the read pool. */
    public long[] counts() {
        List<Future<Long>> futures = new ArrayList<>();
        for (String table : COUNT_TABLES) {
            futures.add(readPool.submit(() -> {
                try (PreparedStatement ps = readConnection().prepareStatement("SELECT COUNT(*) FROM " + table);
                     ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }));
        }
        long[] out = new long[COUNT_TABLES.length];
        for (int i = 0; i < futures.size(); i++) {
            try {
                Long v = futures.get(i).get(30, TimeUnit.SECONDS);
                out[i] = v == null ? 0L : v;
            } catch (Exception e) {
                out[i] = 0L;
            }
        }
        return out;
    }

    /**
     * Same result as {@link #counts()} but the eight queries run one after another on the
     * calling thread's own read connection. Safe from a read-pool thread: the parallel
     * version submits to the very pool it would be running on and can exhaust it.
     */
    public long[] countsSequential() {
        long[] out = new long[COUNT_TABLES.length];
        for (int i = 0; i < COUNT_TABLES.length; i++) {
            try (PreparedStatement ps = readConnection().prepareStatement("SELECT COUNT(*) FROM " + COUNT_TABLES[i]);
                 ResultSet rs = ps.executeQuery()) {
                out[i] = rs.next() ? rs.getLong(1) : 0L;
            } catch (Exception e) {
                out[i] = 0L;
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

    public long purge(long before) {
        Long result = execute(() -> doPurge(before));
        return result == null ? 0L : result;
    }

    /** Fire-and-forget purge used by the data-retention startup limit; logs the row count. */
    public void purgeAsync(long before, int maxDays) {
        worker.submit(() -> {
            try {
                long rows = doPurge(before);
                CoreProtectFabric.LOGGER.info("[CoreProtect] Data retention: deleted {} row(s) older than {} day(s).",
                        rows, maxDays);
                incrementalVacuum();
            } catch (Exception e) {
                CoreProtectFabric.LOGGER.error("[CoreProtect] Data retention purge failed", e);
            }
        });
    }

    private long doPurge(long before) throws SQLException {
        long total = 0;
        for (String t : COUNT_TABLES) {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + t + " WHERE time < ?")) {
                ps.setLong(1, before);
                total += ps.executeUpdate();
            }
        }
        // drop dictionary rows that the deleted log rows were the last users of
        try (Statement st = conn.createStatement()) {
            gcDictionaries(st);
        }
        return total;
    }

    /** Lightweight: returns freed pages to the file system (requires auto_vacuum=INCREMENTAL). */
    private void incrementalVacuum() {
        // VACUUM-style maintenance refuses to run while prepared statements are still open,\n        // so the writer's statement cache is dropped first (it fills up again on demand).\n        closeStatements();
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA incremental_vacuum");
        } catch (SQLException e) {
            CoreProtectFabric.LOGGER.warn("[CoreProtect] Incremental vacuum failed: {}", e.toString());
        }
    }

    /** Full compaction: rebuilds the database file (also applies auto_vacuum=INCREMENTAL) and logs freed space. */
    public void vacuumAsync() {
        worker.submit(() -> {
            try {
                long before = Files.size(dbPath);
                closeStatements();
                try (Statement st = conn.createStatement()) {
                    st.execute("PRAGMA auto_vacuum=INCREMENTAL");
                    st.execute("VACUUM");
                    // in WAL mode the rebuilt pages live in the WAL; checkpoint first so the
                    // main file can actually be truncated by incremental_vacuum below
                    st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                    st.execute("PRAGMA incremental_vacuum");
                }
                long after = Files.size(dbPath);
                CoreProtectFabric.LOGGER.info("[CoreProtect] Database compacted: {} MB -> {} MB (freed {} MB).",
                        before / (1024 * 1024), after / (1024 * 1024), (before - after) / (1024 * 1024));
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
            sql.append(" AND (so.state LIKE ? OR sn.state LIKE ?)");
            String like = "%" + c.block.toLowerCase() + "%";
            params.add(like);
            params.add(like);
        }
        if (c.exclude != null && !c.exclude.isEmpty()) {
            // e: also excludes a matching action/cause (#tnt, #fire, ...) or block type
            // (e:stone); previously it was compared against the user column only, so a
            // block exclusion silently filtered nothing
            String like = "%" + c.exclude.toLowerCase() + "%";
            sql.append(" AND (a.action IS NULL OR a.action NOT LIKE ?)")
                    .append(" AND (so.state IS NULL OR so.state NOT LIKE ?)")
                    .append(" AND (sn.state IS NULL OR sn.state NOT LIKE ?)");
            params.add(c.exclude);
            params.add(like);
            params.add(like);
        }
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
        try {
            return worker.submit(task).get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            CoreProtectFabric.LOGGER.error("[CoreProtect] Database operation failed", e);
            return null;
        }
    }

    /** Synchronous read on the parallel read pool (one connection per reader thread). */
    private <T> T executeRead(Callable<T> task) {
        try {
            T result = readPool.submit(task).get(30, TimeUnit.SECONDS);
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
            }
        });
    }

    /**
     * Queues a log row for the next batch. Writes that arrive after the database was
     * closed (server shutdown, listener callbacks still firing) are dropped instead of
     * throwing {@link RejectedExecutionException} on the server thread.
     */
    private void submitWrite(Runnable task) {
        if (!open) return;
        if (!writeQueue.offer(task)) {
            long dropped = droppedWrites.incrementAndGet();
            if (dropped == 1 || dropped % 1000 == 0) {
                CoreProtectFabric.LOGGER.warn("[CoreProtect] Write queue is full ({} rows dropped so far) - "
                        + "the disk cannot keep up. Consider database.syncMode=normal.", dropped);
            }
            return;
        }
        scheduleFlush(batchIntervalMs());
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
        if (conn == null) return;
        int rows = 0;
        boolean transaction = false;
        try {
            int max = batchMaxRows();
            while (rows < max) {
                Runnable task = writeQueue.poll();
                if (task == null) break;
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
            }
        } catch (Exception e) {
            CoreProtectFabric.LOGGER.error("[CoreProtect] Batch commit failed after {} rows", rows, e);
            try {
                conn.rollback();
            } catch (SQLException ignored) {
            }
        } finally {
            if (transaction) {
                try {
                    conn.setAutoCommit(true);
                } catch (SQLException ignored) {
                }
            }
            if (!writeQueue.isEmpty() && open) {
                scheduleFlush(0); // more rows are waiting, keep draining
            }
        }
    }

    /** Writes everything still queued. Shutdown path, runs on the writer thread. */
    private void drainWrites() {
        while (!writeQueue.isEmpty() && conn != null) {
            flushWrites();
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
        try {
            readPool.submit(() -> {
                T value;
                try {
                    value = task.call();
                } catch (Throwable t) {
                    CoreProtectFabric.LOGGER.warn("[CoreProtect] Query failed: {}", t.toString());
                    value = null;
                }
                final T result = value;
                MinecraftServer server = CoreProtectFabric.instance() == null ? null : CoreProtectFabric.instance().server();
                if (server == null) return;
                server.execute(() -> callback.accept(result));
            });
        } catch (RejectedExecutionException e) {
            // the read pool is shutting down; nothing left to answer
        }
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
