import java.io.File;
import java.sql.*;
import java.util.Random;

/**
 * Measures how much the schema-v3 dictionary encoding saves compared to schema v2.
 * Generates the same synthetic dataset (a realistic survival server) into two databases.
 */
public class Bench {
    static final int BLOCKS = 300000;
    static final int CONTAINERS = 30000;
    static final int ITEMS = 20000;
    static final int SIGNS = 2000;
    static final int ENTITIES = 5000;
    static final int SESSIONS = 20000;
    static final int MESSAGES = 20000;

    public static void main(String[] args) throws Exception {
        Class.forName("org.sqlite.JDBC");
        String dir = args.length > 0 ? args[0] : ".";
        String[][] variants = {
                {"v2", "schema v2 (current: text user/wid/action)"},
                {"v3", "schema v3 (dictionary user/wid/action)"},
                {"v4", "schema v4 (+ composite time+user index, no user index)"},
                {"v4p16", "schema v4 + 16 KiB pages"}
        };
        long v2size = 0;
        for (String[] v : variants) {
            File f = new File(dir, "bench-" + v[0] + ".db");
            f.delete();
            long t0 = System.currentTimeMillis();
            build(f.getPath(), v[0]);
            long ms = System.currentTimeMillis() - t0;
            if (v[0].equals("v2")) v2size = f.length();
            long rows = BLOCKS + CONTAINERS + ITEMS + SIGNS + ENTITIES + SESSIONS + MESSAGES * 2;
            System.out.printf("%-58s %10d bytes (%6.2f MB)  %.1f B/row  build %5d ms  vs v2: %5.1f%%%n",
                    v[1], f.length(), f.length() / 1048576.0, (double) f.length() / rows, ms,
                    (1 - (double) f.length() / v2size) * 100);
        }
    }

    static void build(String path, String variant) throws Exception {
        boolean v3 = !variant.equals("v2");
        boolean v4 = variant.startsWith("v4");
        int pageSize = variant.equals("v4p16") ? 16384 : 0;
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + path)) {
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA journal_mode=OFF");
                st.execute("PRAGMA synchronous=OFF");
                st.execute("PRAGMA auto_vacuum=INCREMENTAL");
                st.execute("PRAGMA cache_size=-131072");
                st.execute("PRAGMA temp_store=MEMORY");
                if (pageSize > 0) st.execute("PRAGMA page_size=" + pageSize);
                for (String ddl : (v4 ? ddlV4() : v3 ? ddlV3() : ddlV2())) {
                    st.execute(ddl);
                }
            }
            Random rnd = new Random(20260101L);
            String[] worlds = {"minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"};
            int[] worldWeight = {60, 25, 15};
            String[] players = {"Steve", "Alex", "Notch", "Herobrine", "Dinnerbone", "Grumm", "CNTianCheng",
                    "xiao_ming", "Player_01", "Player_02", "Miner_77", "Builder_9"};
            String[] causes = {"", "#tnt", "#creeper", "#fire", "#water", "#explosion", "#piston", "#enderman", "#decay"};
            int[] causeWeight = {70, 5, 4, 6, 8, 3, 2, 1, 1};
            String[] users = new String[players.length + 8];
            System.arraycopy(players, 0, users, 0, players.length);
            String[] causeUsers = {"#tnt", "#creeper", "#fire", "#water", "#piston", "#enderman", "#decay", "#hopper"};
            System.arraycopy(causeUsers, 0, users, players.length, causeUsers.length);
            int[] userWeight = new int[users.length];
            for (int i = 0; i < users.length; i++) userWeight[i] = i < players.length ? 7 : 2;

            String[] blocks = new String[3000];
            String[] shapes = {"", "[axis=y]", "[facing=north]", "[facing=south]", "[waterlogged=true]",
                    "[half=top]", "[powered=true]", "[age=7]", "[stage=0]", "[snowy=true]"};
            String[] types = {"stone", "dirt", "grass_block", "oak_log", "oak_planks", "cobblestone", "sand",
                    "gravel", "coal_ore", "iron_ore", "diamond_ore", "oak_leaves", "glass", "chest", "furnace",
                    "torch", "crafting_table", "wheat", "carrots", "water", "lava", "obsidian", "netherrack",
                    "white_wool", "redstone_block", "hopper", "dispenser", "piston", "spruce_log", "deepslate"};
            for (int i = 0; i < blocks.length; i++) {
                blocks[i] = "minecraft:" + types[rnd.nextInt(types.length)] + shapes[rnd.nextInt(shapes.length)];
            }

            long base = System.currentTimeMillis() / 1000L - (long) BLOCKS * 3L; // ends at "now"
            c.setAutoCommit(false);
            try (PreparedStatement ins = c.prepareStatement(v3
                    ? "INSERT INTO co_block(time,name_id,wid_id,x,y,z,type,old_id,new_id,action_id,meta) VALUES(?,?,?,?,?,?,?,?,?,?,?)"
                    : "INSERT INTO co_block(time,user,wid,x,y,z,type,old_id,new_id,action,meta) VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
                int[] nameIds = v3 ? nameIds(c, users) : null;
                int[] worldIds = v3 ? worldIds(c, worlds) : null;
                int[] stateIds = stateIds(c, blocks, 200);
                int[] actionIds = v3 ? actionIds(c, causes) : null;
                for (int i = 0; i < BLOCKS; i++) {
                    long time = base + i * 3L + rnd.nextInt(3);
                    int u = pick(rnd, userWeight);
                    int w = pick(rnd, worldWeight);
                    int a = pick(rnd, causeWeight);
                    int ox = rnd.nextInt(4000) - 2000;
                    int oz = rnd.nextInt(4000) - 2000;
                    int y = 40 + rnd.nextInt(80);
                    int oldState = rnd.nextInt(blocks.length);
                    int newState = rnd.nextInt(blocks.length);
                    int type = rnd.nextInt(3);
                    String meta = rnd.nextInt(1000) == 0 ? "[\"line1\",\"line2\",\"\",\"\"]" : null;
                    if (v3) {
                        ins.setLong(1, time);
                        ins.setInt(2, nameIds[u]);
                        ins.setInt(3, worldIds[w]);
                        ins.setInt(4, ox);
                        ins.setInt(5, y);
                        ins.setInt(6, oz);
                        ins.setInt(7, type);
                        ins.setInt(8, stateIds[oldState]);
                        ins.setInt(9, stateIds[newState]);
                        ins.setInt(10, actionIds[a]);
                        if (meta == null) ins.setNull(11, Types.VARCHAR); else ins.setString(11, meta);
                    } else {
                        ins.setLong(1, time);
                        ins.setString(2, users[u]);
                        ins.setString(3, worlds[w]);
                        ins.setInt(4, ox);
                        ins.setInt(5, y);
                        ins.setInt(6, oz);
                        ins.setInt(7, type);
                        ins.setInt(8, oldState + 1);
                        ins.setInt(9, newState + 1);
                        ins.setString(10, causes[a]);
                        if (meta == null) ins.setNull(11, Types.VARCHAR); else ins.setString(11, meta);
                    }
                    ins.addBatch();
                    if (i % 20000 == 0) ins.executeBatch();
                }
                ins.executeBatch();
            }
            fillContainers(c, v3, rnd, users, worlds, blocks, base);
            fillSimple(c, v3 ? "co_item" : "co_item", v3, rnd, users, worlds, blocks, base, ITEMS,
                    v3 ? "INSERT INTO co_item(time,name_id,wid_id,x,y,z,action_id,data_id,amount) VALUES(?,?,?,?,?,?,?,?,?)"
                       : "INSERT INTO co_item(time,user,wid,x,y,z,action,data_id,amount) VALUES(?,?,?,?,?,?,?,?,?)");
            fillSimple(c, "co_entity", v3, rnd, users, worlds, blocks, base, ENTITIES,
                    v3 ? "INSERT INTO co_entity(time,name_id,wid_id,x,y,z,data_id,action_id) VALUES(?,?,?,?,?,?,?,?)"
                       : "INSERT INTO co_entity(time,user,wid,x,y,z,data_id,action) VALUES(?,?,?,?,?,?,?,?)");
            fillSimple(c, "co_sign", v3, rnd, users, worlds, blocks, base, SIGNS,
                    v3 ? "INSERT INTO co_sign(time,name_id,wid_id,x,y,z,data) VALUES(?,?,?,?,?,?,?)"
                       : "INSERT INTO co_sign(time,user,wid,x,y,z,data) VALUES(?,?,?,?,?,?,?)");
            fillSimple(c, "co_session", v3, rnd, users, worlds, blocks, base, SESSIONS,
                    v3 ? "INSERT INTO co_session(time,name_id,wid_id,action_id) VALUES(?,?,?,?)"
                       : "INSERT INTO co_session(time,user,wid,action) VALUES(?,?,?,?)");
            fillMessages(c, v3, rnd, users, base, MESSAGES);
            c.commit();
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA user_version=" + (v3 ? 3 : 2));
            }
        }
        // compact exactly like the mod does after a migration (also applies page_size)
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
             Statement st = c.createStatement()) {
            st.execute("PRAGMA auto_vacuum=INCREMENTAL");
            st.execute("VACUUM");
            st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        }
    }

    static void fillContainers(Connection c, boolean v3, Random rnd, String[] users, String[] worlds,
                               String[] blocks, long base) throws SQLException {
        try (PreparedStatement ins = c.prepareStatement(v3
                ? "INSERT INTO co_container(time,name_id,wid_id,x,y,z,type,data_id,amount) VALUES(?,?,?,?,?,?,?,?,?)"
                : "INSERT INTO co_container(time,user,wid,x,y,z,type,data_id,amount) VALUES(?,?,?,?,?,?,?,?,?)")) {
            int[] nameIds = v3 ? nameIds(c, users) : null;
            int[] worldIds = v3 ? worldIds(c, worlds) : null;
            int[] stateIds = stateIds(c, blocks, 200);
            for (int i = 0; i < CONTAINERS; i++) {
                long time = base + i * 9L;
                int u = rnd.nextInt(users.length);
                int w = rnd.nextInt(3);
                int d = rnd.nextInt(blocks.length);
                int type = rnd.nextInt(2);
                int amount = 1 + rnd.nextInt(64);
                if (v3) {
                    ins.setLong(1, time); ins.setInt(2, nameIds[u]); ins.setInt(3, worldIds[w]);
                    ins.setInt(4, rnd.nextInt(4000) - 2000); ins.setInt(5, 40 + rnd.nextInt(80));
                    ins.setInt(6, rnd.nextInt(4000) - 2000); ins.setInt(7, type);
                    ins.setInt(8, stateIds[d]); ins.setInt(9, amount);
                } else {
                    ins.setLong(1, time); ins.setString(2, users[u]); ins.setString(3, worlds[w]);
                    ins.setInt(4, rnd.nextInt(4000) - 2000); ins.setInt(5, 40 + rnd.nextInt(80));
                    ins.setInt(6, rnd.nextInt(4000) - 2000); ins.setInt(7, type);
                    ins.setInt(8, d + 1); ins.setInt(9, amount);
                }
                ins.addBatch();
                if (i % 20000 == 0) ins.executeBatch();
            }
            ins.executeBatch();
        }
    }

    static void fillSimple(Connection c, String table, boolean v3, Random rnd, String[] users, String[] worlds,
                           String[] blocks, long base, int count, String sql) throws SQLException {
        int[] nameIds = v3 ? nameIds(c, users) : null;
        int[] worldIds = v3 ? worldIds(c, worlds) : null;
        int[] stateIds = stateIds(c, blocks, 200);
        try (PreparedStatement ins = c.prepareStatement(sql)) {
            for (int i = 0; i < count; i++) {
                long time = base + i * 7L;
                int u = rnd.nextInt(users.length);
                int w = rnd.nextInt(3);
                int d = rnd.nextInt(blocks.length);
                int x = rnd.nextInt(4000) - 2000;
                int y = 40 + rnd.nextInt(80);
                int z = rnd.nextInt(4000) - 2000;
                if (v3) {
                    if (table.equals("co_item")) {
                        ins.setLong(1, time); ins.setInt(2, nameIds[u]); ins.setInt(3, worldIds[w]);
                        ins.setInt(4, x); ins.setInt(5, y); ins.setInt(6, z);
                        ins.setInt(7, stateIds[rnd.nextInt(20)]); ins.setInt(8, stateIds[d]); ins.setInt(9, 1 + rnd.nextInt(64));
                    } else if (table.equals("co_entity")) {
                        ins.setLong(1, time); ins.setInt(2, nameIds[u]); ins.setInt(3, worldIds[w]);
                        ins.setInt(4, x); ins.setInt(5, y); ins.setInt(6, z);
                        ins.setInt(7, stateIds[d]); ins.setInt(8, rnd.nextInt(2) + 1);
                    } else if (table.equals("co_sign")) {
                        ins.setLong(1, time); ins.setInt(2, nameIds[u]); ins.setInt(3, worldIds[w]);
                        ins.setInt(4, x); ins.setInt(5, y); ins.setInt(6, z); ins.setString(7, "[\"line1\",\"line2\",\"\",\"\"]");
                    } else {
                        ins.setLong(1, time); ins.setInt(2, nameIds[u]); ins.setInt(3, worldIds[w]); ins.setInt(4, rnd.nextInt(2) + 1);
                    }
                } else {
                    if (table.equals("co_item")) {
                        ins.setLong(1, time); ins.setString(2, users[u]); ins.setString(3, worlds[w]);
                        ins.setInt(4, x); ins.setInt(5, y); ins.setInt(6, z);
                        ins.setString(7, rnd.nextBoolean() ? "+" : "-"); ins.setInt(8, d + 1); ins.setInt(9, 1 + rnd.nextInt(64));
                    } else if (table.equals("co_entity")) {
                        ins.setLong(1, time); ins.setString(2, users[u]); ins.setString(3, worlds[w]);
                        ins.setInt(4, x); ins.setInt(5, y); ins.setInt(6, z);
                        ins.setInt(7, d + 1); ins.setString(8, rnd.nextBoolean() ? "kill" : "death");
                    } else if (table.equals("co_sign")) {
                        ins.setLong(1, time); ins.setString(2, users[u]); ins.setString(3, worlds[w]);
                        ins.setInt(4, x); ins.setInt(5, y); ins.setInt(6, z);
                        ins.setString(7, "[\"line1\",\"line2\",\"\",\"\"]");
                    } else {
                        ins.setLong(1, time); ins.setString(2, users[u]); ins.setString(3, worlds[w]);
                        ins.setString(4, rnd.nextBoolean() ? "+" : "-");
                    }
                }
                ins.addBatch();
                if (i % 20000 == 0) ins.executeBatch();
            }
            ins.executeBatch();
        }
    }

    static void fillMessages(Connection c, boolean v3, Random rnd, String[] users, long base, int count) throws SQLException {
        int[] nameIds = v3 ? nameIds(c, users) : null;
        try (PreparedStatement ins = c.prepareStatement(v3
                ? "INSERT INTO co_command(time,name_id,message) VALUES(?,?,?)"
                : "INSERT INTO co_command(time,user,message) VALUES(?,?,?)");
             PreparedStatement ins2 = c.prepareStatement(v3
                ? "INSERT INTO co_chat(time,name_id,message) VALUES(?,?,?)"
                : "INSERT INTO co_chat(time,user,message) VALUES(?,?,?)")) {
            String[] words = {"/co lookup", "/co rollback", "/home", "/spawn", "/tpa Steve", "hello", "hi there",
                    "where are you", "tp me", "anyone online?", "nice build", "gg", "need help", "thanks"};
            for (int i = 0; i < count; i++) {
                long time = base + i * 11L;
                int u = rnd.nextInt(users.length);
                String msg = words[rnd.nextInt(words.length)] + " " + rnd.nextInt(1000);
                if (v3) {
                    ins.setLong(1, time); ins.setInt(2, nameIds[u]); ins.setString(3, msg);
                    ins2.setLong(1, time); ins2.setInt(2, nameIds[u]); ins2.setString(3, msg);
                } else {
                    ins.setLong(1, time); ins.setString(2, users[u]); ins.setString(3, msg);
                    ins2.setLong(1, time); ins2.setString(2, users[u]); ins2.setString(3, msg);
                }
                ins.addBatch();
                ins2.addBatch();
                if (i % 5000 == 0) { ins.executeBatch(); ins2.executeBatch(); }
            }
            ins.executeBatch();
            ins2.executeBatch();
        }
    }

    static int pick(Random rnd, int[] weights) {
        int total = 0;
        for (int w : weights) total += w;
        int r = rnd.nextInt(total);
        for (int i = 0; i < weights.length; i++) {
            r -= weights[i];
            if (r < 0) return i;
        }
        return weights.length - 1;
    }

    static int[] nameIds(Connection c, String[] names) throws SQLException {
        int[] out = new int[names.length];
        try (PreparedStatement ins = c.prepareStatement("INSERT INTO co_name(name) VALUES(?) ON CONFLICT(name) DO NOTHING");
             PreparedStatement sel = c.prepareStatement("SELECT id FROM co_name WHERE name=?")) {
            for (int i = 0; i < names.length; i++) {
                ins.setString(1, names[i]);
                ins.executeUpdate();
                sel.setString(1, names[i]);
                try (ResultSet rs = sel.executeQuery()) {
                    rs.next();
                    out[i] = rs.getInt(1);
                }
            }
        }
        return out;
    }

    static int[] worldIds(Connection c, String[] worlds) throws SQLException {
        int[] out = new int[worlds.length];
        try (PreparedStatement ins = c.prepareStatement("INSERT INTO co_world(wid) VALUES(?) ON CONFLICT(wid) DO NOTHING");
             PreparedStatement sel = c.prepareStatement("SELECT id FROM co_world WHERE wid=?")) {
            for (int i = 0; i < worlds.length; i++) {
                ins.setString(1, worlds[i]);
                ins.executeUpdate();
                sel.setString(1, worlds[i]);
                try (ResultSet rs = sel.executeQuery()) {
                    rs.next();
                    out[i] = rs.getInt(1);
                }
            }
        }
        return out;
    }

    static int[] actionIds(Connection c, String[] actions) throws SQLException {
        int[] out = new int[actions.length];
        try (PreparedStatement ins = c.prepareStatement("INSERT INTO co_action(action) VALUES(?) ON CONFLICT(action) DO NOTHING");
             PreparedStatement sel = c.prepareStatement("SELECT id FROM co_action WHERE action=?")) {
            for (int i = 0; i < actions.length; i++) {
                ins.setString(1, actions[i]);
                ins.executeUpdate();
                sel.setString(1, actions[i]);
                try (ResultSet rs = sel.executeQuery()) {
                    rs.next();
                    out[i] = rs.getInt(1);
                }
            }
        }
        return out;
    }

    static int[] stateIds(Connection c, String[] states, int used) throws SQLException {
        int[] out = new int[states.length];
        try (PreparedStatement ins = c.prepareStatement("INSERT INTO co_state(state) VALUES(?) ON CONFLICT(state) DO NOTHING");
             PreparedStatement sel = c.prepareStatement("SELECT id FROM co_state WHERE state=?")) {
            for (int i = 0; i < states.length; i++) {
                ins.setString(1, states[i]);
                ins.executeUpdate();
                sel.setString(1, states[i]);
                try (ResultSet rs = sel.executeQuery()) {
                    rs.next();
                    out[i] = rs.getInt(1);
                }
            }
        }
        return out;
    }

    static String[] ddlV2() {
        return new String[]{
                "CREATE TABLE IF NOT EXISTS co_state (id INTEGER PRIMARY KEY, state TEXT NOT NULL UNIQUE)",
                "CREATE TABLE IF NOT EXISTS co_block (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, user TEXT NOT NULL, wid TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, type INTEGER NOT NULL, old_id INTEGER, new_id INTEGER, action TEXT NOT NULL, meta TEXT)",
                "CREATE INDEX IF NOT EXISTS idx_block_pos ON co_block(wid, x, y, z)",
                "CREATE INDEX IF NOT EXISTS idx_block_user ON co_block(user)",
                "CREATE INDEX IF NOT EXISTS idx_block_time ON co_block(time)",
                "CREATE TABLE IF NOT EXISTS co_container (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, user TEXT NOT NULL, wid TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, type INTEGER NOT NULL, data_id INTEGER, amount INTEGER NOT NULL)",
                "CREATE INDEX IF NOT EXISTS idx_container_pos ON co_container(wid, x, y, z)",
                "CREATE INDEX IF NOT EXISTS idx_container_user ON co_container(user)",
                "CREATE INDEX IF NOT EXISTS idx_container_time ON co_container(time)",
                "CREATE TABLE IF NOT EXISTS co_item (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, user TEXT NOT NULL, wid TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, action TEXT NOT NULL, data_id INTEGER, amount INTEGER NOT NULL)",
                "CREATE INDEX IF NOT EXISTS idx_item_pos ON co_item(wid, x, y, z)",
                "CREATE INDEX IF NOT EXISTS idx_item_user ON co_item(user)",
                "CREATE INDEX IF NOT EXISTS idx_item_time ON co_item(time)",
                "CREATE TABLE IF NOT EXISTS co_sign (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, user TEXT NOT NULL, wid TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, data TEXT NOT NULL)",
                "CREATE INDEX IF NOT EXISTS idx_sign_pos ON co_sign(wid, x, y, z)",
                "CREATE INDEX IF NOT EXISTS idx_sign_user ON co_sign(user)",
                "CREATE INDEX IF NOT EXISTS idx_sign_time ON co_sign(time)",
                "CREATE TABLE IF NOT EXISTS co_entity (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, user TEXT NOT NULL, wid TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, data_id INTEGER, action TEXT NOT NULL)",
                "CREATE INDEX IF NOT EXISTS idx_entity_user ON co_entity(user)",
                "CREATE INDEX IF NOT EXISTS idx_entity_time ON co_entity(time)",
                "CREATE TABLE IF NOT EXISTS co_session (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, user TEXT NOT NULL, wid TEXT NOT NULL, action TEXT NOT NULL)",
                "CREATE INDEX IF NOT EXISTS idx_session_time ON co_session(time)",
                "CREATE INDEX IF NOT EXISTS idx_session_user ON co_session(user)",
                "CREATE TABLE IF NOT EXISTS co_command (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, user TEXT NOT NULL, message TEXT NOT NULL)",
                "CREATE INDEX IF NOT EXISTS idx_command_time ON co_command(time)",
                "CREATE INDEX IF NOT EXISTS idx_command_user ON co_command(user)",
                "CREATE TABLE IF NOT EXISTS co_chat (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, user TEXT NOT NULL, message TEXT NOT NULL)",
                "CREATE INDEX IF NOT EXISTS idx_chat_time ON co_chat(time)",
                "CREATE INDEX IF NOT EXISTS idx_chat_user ON co_chat(user)",
                "CREATE TABLE IF NOT EXISTS co_user (user TEXT PRIMARY KEY, language TEXT)"
        };
    }

    static String[] ddlV4() {
        String[] base = ddlV3();
        String[] out = new String[base.length];
        for (int i = 0; i < base.length; i++) {
            String ddl = base[i];
            // the user index is folded into the time index: (time, name_id) serves both
            // "recent rows" queries and user+time lookups as a covering index
            if (ddl.contains("idx_") && ddl.contains("_user ON")) continue;
            if (ddl.contains("_time ON")) {
                ddl = ddl.replace("(time)", "(time, name_id)");
            }
            out[i] = ddl;
        }
        return java.util.Arrays.stream(out).filter(java.util.Objects::nonNull).toArray(String[]::new);
    }

    static String[] ddlV3() {
        return new String[]{
                "CREATE TABLE IF NOT EXISTS co_state (id INTEGER PRIMARY KEY, state TEXT NOT NULL UNIQUE)",
                "CREATE TABLE IF NOT EXISTS co_name (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE)",
                "CREATE TABLE IF NOT EXISTS co_world (id INTEGER PRIMARY KEY, wid TEXT NOT NULL UNIQUE)",
                "CREATE TABLE IF NOT EXISTS co_action (id INTEGER PRIMARY KEY, action TEXT NOT NULL UNIQUE)",
                "CREATE TABLE IF NOT EXISTS co_block (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, name_id INTEGER NOT NULL, wid_id INTEGER NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, type INTEGER NOT NULL, old_id INTEGER, new_id INTEGER, action_id INTEGER, meta TEXT)",
                "CREATE INDEX IF NOT EXISTS idx_block_pos ON co_block(wid_id, x, y, z)",
                "CREATE INDEX IF NOT EXISTS idx_block_user ON co_block(name_id)",
                "CREATE INDEX IF NOT EXISTS idx_block_time ON co_block(time)",
                "CREATE TABLE IF NOT EXISTS co_container (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, name_id INTEGER NOT NULL, wid_id INTEGER NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, type INTEGER NOT NULL, data_id INTEGER, amount INTEGER NOT NULL)",
                "CREATE INDEX IF NOT EXISTS idx_container_pos ON co_container(wid_id, x, y, z)",
                "CREATE INDEX IF NOT EXISTS idx_container_user ON co_container(name_id)",
                "CREATE INDEX IF NOT EXISTS idx_container_time ON co_container(time)",
                "CREATE TABLE IF NOT EXISTS co_item (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, name_id INTEGER NOT NULL, wid_id INTEGER NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, action_id INTEGER, data_id INTEGER, amount INTEGER NOT NULL)",
                "CREATE INDEX IF NOT EXISTS idx_item_pos ON co_item(wid_id, x, y, z)",
                "CREATE INDEX IF NOT EXISTS idx_item_user ON co_item(name_id)",
                "CREATE INDEX IF NOT EXISTS idx_item_time ON co_item(time)",
                "CREATE TABLE IF NOT EXISTS co_sign (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, name_id INTEGER NOT NULL, wid_id INTEGER NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, data TEXT NOT NULL)",
                "CREATE INDEX IF NOT EXISTS idx_sign_pos ON co_sign(wid_id, x, y, z)",
                "CREATE INDEX IF NOT EXISTS idx_sign_user ON co_sign(name_id)",
                "CREATE INDEX IF NOT EXISTS idx_sign_time ON co_sign(time)",
                "CREATE TABLE IF NOT EXISTS co_entity (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, name_id INTEGER NOT NULL, wid_id INTEGER NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, data_id INTEGER, action_id INTEGER)",
                "CREATE INDEX IF NOT EXISTS idx_entity_user ON co_entity(name_id)",
                "CREATE INDEX IF NOT EXISTS idx_entity_time ON co_entity(time)",
                "CREATE TABLE IF NOT EXISTS co_session (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, name_id INTEGER NOT NULL, wid_id INTEGER NOT NULL, action_id INTEGER)",
                "CREATE INDEX IF NOT EXISTS idx_session_time ON co_session(time)",
                "CREATE INDEX IF NOT EXISTS idx_session_user ON co_session(name_id)",
                "CREATE TABLE IF NOT EXISTS co_command (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, name_id INTEGER NOT NULL, message TEXT NOT NULL)",
                "CREATE INDEX IF NOT EXISTS idx_command_time ON co_command(time)",
                "CREATE INDEX IF NOT EXISTS idx_command_user ON co_command(name_id)",
                "CREATE TABLE IF NOT EXISTS co_chat (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, name_id INTEGER NOT NULL, message TEXT NOT NULL)",
                "CREATE INDEX IF NOT EXISTS idx_chat_time ON co_chat(time)",
                "CREATE INDEX IF NOT EXISTS idx_chat_user ON co_chat(name_id)",
                "CREATE TABLE IF NOT EXISTS co_user (user TEXT PRIMARY KEY, language TEXT)"
        };
    }

}
