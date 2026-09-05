import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

public class TestQuery {
    public static void main(String[] args) throws Exception {
        String db = args[0];
        Class.forName("org.sqlite.JDBC");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            // test 1: time + user only
            run(c, "T1 time+user", "SELECT COUNT(*) FROM co_container WHERE 1=1 AND time >= ? AND user = ?", List.of(1750000000L, "TestUser"));
            // test 2: radius math
            run(c, "T2 radius", "SELECT COUNT(*) FROM co_container WHERE 1=1 AND (x-?)*(x-?)+(y-?)*(y-?)+(z-?)*(z-?) <= ?", List.of(0, 0, 0, 10000L));
            // test 3: limit/offset
            run(c, "T3 limit", "SELECT COUNT(*) FROM co_container WHERE 1=1 ORDER BY id DESC LIMIT ? OFFSET ?", List.of(-1L, 0L));
            // test 4: everything
            run(c, "T4 all", "SELECT id,time,user,wid,x,y,z,type,data,amount FROM co_container WHERE 1=1 AND time >= ? AND user = ? AND (x-?)*(x-?)+(y-?)*(y-?)+(z-?)*(z-?) <= ? ORDER BY id DESC LIMIT ? OFFSET ?",
                    List.of(1750000000L, "TestUser", 0, 0, 0, 10000L, -1L, 0L));
            // test 5: blocks query equivalent
            run(c, "T5 blocks", "SELECT id,time,user,wid,x,y,z,type,old_data,new_data,action,meta FROM co_block WHERE 1=1 AND time >= ? AND user = ? AND (x-?)*(x-?)+(y-?)*(y-?)+(z-?)*(z-?) <= ? ORDER BY id DESC LIMIT ? OFFSET ?",
                    List.of(1750000000L, "TestUser", 0, 0, 0, 10000L, -1L, 0L));
        }
    }

    static void run(Connection c, String name, String sql, List<Object> params) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                Object p = params.get(i);
                if (p == null) ps.setObject(i + 1, null);
                else if (p instanceof Long l) ps.setLong(i + 1, l);
                else if (p instanceof Integer in) ps.setInt(i + 1, in);
                else ps.setString(i + 1, p.toString());
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                System.out.println(name + " OK -> " + rs.getLong(1));
            }
        } catch (Exception e) {
            System.out.println(name + " FAILED: " + e.getMessage());
        }
    }
}
