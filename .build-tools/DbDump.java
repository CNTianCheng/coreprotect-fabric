import java.sql.*;

public class DbDump {
    public static void main(String[] args) throws Exception {
        String db = args[0];
        Class.forName("org.sqlite.JDBC");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            System.out.println("== counts ==");
            for (String t : new String[]{"co_block", "co_container", "co_item", "co_sign", "co_entity", "co_session", "co_command", "co_chat"}) {
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + t)) {
                    System.out.println(t + " = " + (rs.next() ? rs.getLong(1) : -1));
                }
            }
            System.out.println("== co_block grouped by action/user ==");
            try (ResultSet rs = st.executeQuery(
                    "SELECT action, user, COUNT(*) n FROM co_block GROUP BY action, user ORDER BY n DESC")) {
                while (rs.next()) {
                    System.out.println("  action=" + rs.getString(1) + " user=" + rs.getString(2) + " n=" + rs.getInt(3));
                }
            }
            System.out.println("== co_container grouped by user ==");
            try (ResultSet rs = st.executeQuery(
                    "SELECT user, COUNT(*) n FROM co_container GROUP BY user ORDER BY n DESC")) {
                while (rs.next()) {
                    System.out.println("  user=" + rs.getString(1) + " n=" + rs.getInt(2));
                }
            }
            System.out.println("== table columns ==");
            for (String t : new String[]{"co_block", "co_container"}) {
                try (ResultSet rs = st.executeQuery("PRAGMA table_info(" + t + ")")) {
                    StringBuilder sb = new StringBuilder("  " + t + ": ");
                    while (rs.next()) sb.append(rs.getString("name")).append(" ");
                    System.out.println(sb);
                }
            }
            if (args.length > 1 && args[1].equals("blocks")) {
                System.out.println("== co_block rows (id,time,user,x,y,z,type,action,old,new) ==");
                try (ResultSet rs = st.executeQuery(
                        "SELECT id,user,x,y,z,type,action,old_id,new_id FROM co_block ORDER BY id")) {
                    while (rs.next()) {
                        System.out.println("  " + rs.getLong(1) + " " + rs.getString(2)
                                + " (" + rs.getInt(3) + "," + rs.getInt(4) + "," + rs.getInt(5) + ")"
                                + " t=" + rs.getInt(6) + " a=" + rs.getString(7)
                                + " old=" + rs.getObject(8) + " new=" + rs.getObject(9));
                    }
                }
            }
            if (args.length > 1 && args[1].equals("states")) {
                System.out.println("== co_state ==");
                try (ResultSet rs = st.executeQuery("SELECT id,state FROM co_state ORDER BY id")) {
                    while (rs.next()) System.out.println("  " + rs.getLong(1) + " " + rs.getString(2));
                }
            }
        }
    }
}
