package net.coreprotect.fabric.database;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import net.coreprotect.fabric.CoreProtectFabric;
import net.fabricmc.loader.api.FabricLoader;

/**
 * SQLite storage, close to the CoreProtect schema. All reads and writes run on a
 * single dedicated worker thread; command handlers wait on reads, while writes are
 * fire-and-forget so gameplay never blocks on disk I/O.
 */
public final class DatabaseManager {

    public record BlockLog(long id, long time, String user, String wid, int x, int y, int z,
                           int type, String oldData, String newData, String action, String meta) {
    }

    public record ContainerLog(long id, long time, String user, String wid, int x, int y, int z,
                               int type, String data, int amount) {
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

    private static final String[] DDL = {
            "CREATE TABLE IF NOT EXISTS co_block (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, user TEXT NOT NULL, wid TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, type INTEGER NOT NULL, old_data TEXT, new_data TEXT, action TEXT NOT NULL, meta TEXT)",
            "CREATE INDEX IF NOT EXISTS idx_block_pos ON co_block(wid, x, y, z)",
            "CREATE INDEX IF NOT EXISTS idx_block_user ON co_block(user)",
            "CREATE INDEX IF NOT EXISTS idx_block_time ON co_block(time)",
            "CREATE TABLE IF NOT EXISTS co_container (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, user TEXT NOT NULL, wid TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, type INTEGER NOT NULL, data TEXT NOT NULL, amount INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS idx_container_pos ON co_container(wid, x, y, z)",
            "CREATE INDEX IF NOT EXISTS idx_container_user ON co_container(user)",
            "CREATE INDEX IF NOT EXISTS idx_container_time ON co_container(time)",
            "CREATE TABLE IF NOT EXISTS co_entity (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, user TEXT NOT NULL, wid TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, data TEXT NOT NULL, action TEXT NOT NULL)",
            "CREATE INDEX IF NOT EXISTS idx_entity_user ON co_entity(user)",
            "CREATE INDEX IF NOT EXISTS idx_entity_time ON co_entity(time)",
            "CREATE TABLE IF NOT EXISTS co_session (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, user TEXT NOT NULL, wid TEXT NOT NULL, action TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS co_command (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, user TEXT NOT NULL, message TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS co_chat (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, user TEXT NOT NULL, message TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS co_user (user TEXT PRIMARY KEY, language TEXT)"
    };

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CoreProtect-DB");
        t.setDaemon(true);
        return t;
    });
    private Connection conn;
    private volatile boolean open;

    public boolean isOpen() {
        return open;
    }

    public void open() {
        Path dbPath = FabricLoader.getInstance().getGameDir()
                .resolve(CoreProtectFabric.instance().config().databaseFile);
        execute(() -> {
            Class.forName("org.sqlite.JDBC");
            conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("PRAGMA busy_timeout=10000");
                for (String ddl : DDL) {
                    st.executeUpdate(ddl);
                }
            }
            open = true;
            return null;
        });
    }

    public void close() {
        if (!open) return;
        execute(() -> {
            open = false;
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException ignored) {
                }
                conn = null;
            }
            return null;
        });
        worker.shutdown();
        try {
            worker.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------
    // Async writes
    // ------------------------------------------------------------------

    public void insertBlockAsync(BlockLog l) {
        enqueue("INSERT INTO co_block(time,user,wid,x,y,z,type,old_data,new_data,action,meta) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                Arrays.asList(l.time(), l.user(), l.wid(), l.x(), l.y(), l.z(), l.type(),
                        l.oldData(), l.newData(), l.action(), l.meta()));
    }

    public void insertContainerAsync(ContainerLog l) {
        enqueue("INSERT INTO co_container(time,user,wid,x,y,z,type,data,amount) VALUES(?,?,?,?,?,?,?,?,?)",
                Arrays.asList(l.time(), l.user(), l.wid(), l.x(), l.y(), l.z(), l.type(), l.data(), l.amount()));
    }

    public void insertEntityAsync(EntityLog l) {
        enqueue("INSERT INTO co_entity(time,user,wid,x,y,z,data,action) VALUES(?,?,?,?,?,?,?,?)",
                Arrays.asList(l.time(), l.user(), l.wid(), l.x(), l.y(), l.z(), l.data(), l.action()));
    }

    public void insertSessionAsync(long time, String user, String wid, String action) {
        enqueue("INSERT INTO co_session(time,user,wid,action) VALUES(?,?,?,?)",
                Arrays.asList(time, user, wid, action));
    }

    public void insertCommandAsync(long time, String user, String message) {
        enqueue("INSERT INTO co_command(time,user,message) VALUES(?,?,?)",
                Arrays.asList(time, user, message));
    }

    public void insertChatAsync(long time, String user, String message) {
        enqueue("INSERT INTO co_chat(time,user,message) VALUES(?,?,?)",
                Arrays.asList(time, user, message));
    }

    public void saveUserLanguageAsync(String user, String language) {
        enqueue("INSERT INTO co_user(user,language) VALUES(?,?) ON CONFLICT(user) DO UPDATE SET language=excluded.language",
                Arrays.asList(user, language));
    }

    // ------------------------------------------------------------------
    // Reads (synchronous on the caller's thread, executed on the worker)
    // ------------------------------------------------------------------

    public List<BlockLog> queryBlocks(Criteria c, long limit, long offset) {
        return execute(() -> {
            StringBuilder sql = new StringBuilder(
                    "SELECT id,time,user,wid,x,y,z,type,old_data,new_data,action,meta FROM co_block WHERE 1=1");
            List<Object> params = new ArrayList<>();
            appendBlockFilters(sql, params, c);
            appendBlockAction(sql, params, c.action);
            sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
            params.add(limit);
            params.add(offset);
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
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
        return execute(() -> {
            String sql = "SELECT id,time,user,wid,x,y,z,type,old_data,new_data,action,meta FROM co_block "
                    + "WHERE wid=? AND x=? AND y=? AND z=? ORDER BY id DESC LIMIT ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
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
        return execute(() -> {
            StringBuilder sql = new StringBuilder(
                    "SELECT id,time,user,wid,x,y,z,type,data,amount FROM co_container WHERE 1=1");
            List<Object> params = new ArrayList<>();
            appendCommonFilters(sql, params, c);
            sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
            params.add(limit);
            params.add(offset);
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
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
        return execute(() -> {
            String sql = "SELECT id,time,user,wid,x,y,z,type,data,amount FROM co_container "
                    + "WHERE wid=? AND x=? AND y=? AND z=? ORDER BY id DESC LIMIT ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
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

    public List<EntityLog> queryEntities(Criteria c, long limit, long offset) {
        return execute(() -> {
            StringBuilder sql = new StringBuilder(
                    "SELECT id,time,user,wid,x,y,z,data,action FROM co_entity WHERE 1=1");
            List<Object> params = new ArrayList<>();
            if (c.time > 0) {
                sql.append(" AND time >= ?");
                params.add(c.time);
            }
            if (c.user != null && !c.user.isEmpty()) {
                sql.append(" AND user = ?");
                params.add(c.user);
            }
            sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
            params.add(limit);
            params.add(offset);
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
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
        return execute(() -> {
            StringBuilder sql = new StringBuilder("SELECT id,time,user,message");
            if (hasAction) {
                sql.append(",action");
            }
            sql.append(" FROM ").append(table).append(" WHERE 1=1");
            List<Object> params = new ArrayList<>();
            if (c.time > 0) {
                sql.append(" AND time >= ?");
                params.add(c.time);
            }
            if (c.user != null && !c.user.isEmpty()) {
                sql.append(" AND user = ?");
                params.add(c.user);
            }
            if (hasAction && "+session".equals(c.action)) {
                sql.append(" AND action = '+'");
            } else if (hasAction && "-session".equals(c.action)) {
                sql.append(" AND action = '-'");
            }
            sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
            params.add(limit);
            params.add(offset);
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
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
        Long result = execute(() -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM " + table);
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        });
        return result == null ? 0L : result;
    }

    public long purge(long before) {
        Long result = execute(() -> {
            long total = 0;
            String[] tables = {"co_block", "co_container", "co_entity", "co_session", "co_command", "co_chat"};
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM co_block WHERE time < ?")) {
                ps.setLong(1, before);
                total += ps.executeUpdate();
            }
            for (String t : tables) {
                if ("co_block".equals(t)) continue;
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + t + " WHERE time < ?")) {
                    ps.setLong(1, before);
                    total += ps.executeUpdate();
                }
            }
            return total;
        });
        return result == null ? 0L : result;
    }

    public String userLanguage(String user) {
        return execute(() -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT language FROM co_user WHERE user=?")) {
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
            sql.append(" AND user = ?");
            params.add(c.user);
        }
        if (c.exclude != null && !c.exclude.isEmpty()) {
            sql.append(" AND user <> ?");
            params.add(c.exclude);
        }
        if (c.radius > 0 && c.center != null) {
            sql.append(" AND (x-?)*(x-?)+(y-?)*(y-?)+(z-?)*(z-?) <= ?");
            int cx = c.center.getX();
            int cy = c.center.getY();
            int cz = c.center.getZ();
            params.add(cx);
            params.add(cx);
            params.add(cy);
            params.add(cy);
            params.add(cz);
            params.add(cz);
            params.add((long) c.radius * c.radius);
        }
    }

    private void appendBlockFilters(StringBuilder sql, List<Object> params, Criteria c) {
        appendCommonFilters(sql, params, c);
        if (c.block != null && !c.block.isEmpty()) {
            sql.append(" AND (old_data LIKE ? OR new_data LIKE ?)");
            String like = "%" + c.block.toLowerCase() + "%";
            params.add(like);
            params.add(like);
        }
    }

    private void appendBlockAction(StringBuilder sql, List<Object> params, String action) {
        if (action == null) return;
        switch (action) {
            case "block" -> sql.append(" AND type IN (0,1,2)");
            case "+block" -> sql.append(" AND type = 1");
            case "-block" -> sql.append(" AND type = 0");
            default -> {
                if (action.startsWith("#")) {
                    sql.append(" AND type = 2 AND action = ?");
                    params.add(action);
                }
            }
        }
    }

    private <T> T execute(Callable<T> task) {
        try {
            return worker.submit(task).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            CoreProtectFabric.LOGGER.error("[CoreProtect] Database operation failed", e);
            return null;
        }
    }

    private void enqueue(String sql, List<Object> params) {
        worker.submit(() -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                bind(ps, params);
                ps.executeUpdate();
            } catch (Exception e) {
                CoreProtectFabric.LOGGER.error("[CoreProtect] Database write failed: " + sql, e);
            }
        });
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
