import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

public class TestQuery2 {
    public static void main(String[] args) throws Exception {
        String db = args[0];
        Class.forName("org.sqlite.JDBC");
        long now = System.currentTimeMillis() / 1000L;
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            // Replicate queryBlocks(criteria u:TestUser t:15m r:100 action=block, limit=-1, offset=0)
            StringBuilder sql = new StringBuilder(
                    "SELECT id,time,user,wid,x,y,z,type,old_data,new_data,action,meta FROM co_block WHERE 1=1");
            List<Object> params = new ArrayList<>();
            sql.append(" AND time >= ?");
            params.add(now - 900L);
            sql.append(" AND user = ?");
            params.add("TestUser");
            sql.append(" AND (x-?)*(x-?)+(y-?)*(y-?)+(z-?)*(z-?) <= ?");
            int cx = 0, cy = 0, cz = 0;
            params.add(cx);
            params.add(cx);
            params.add(cy);
            params.add(cy);
            params.add(cz);
            params.add(cz);
            params.add((long) 100 * 100);
            sql.append(" AND type IN (0,1,2)");
            sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
            params.add(-1L);
            params.add(0L);
            System.out.println("SQL: " + sql);
            try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    Object p = params.get(i);
                    if (p == null) ps.setObject(i + 1, null);
                    else if (p instanceof Long l) ps.setLong(i + 1, l);
                    else if (p instanceof Integer in) ps.setInt(i + 1, in);
                    else ps.setString(i + 1, p.toString());
                }
                try (ResultSet rs = ps.executeQuery()) {
                    int n = 0;
                    while (rs.next()) {
                        n++;
                        System.out.println("row: time=" + rs.getLong(2) + " user=" + rs.getString(3) + " x=" + rs.getInt(5) + " y=" + rs.getInt(6) + " z=" + rs.getInt(7) + " type=" + rs.getInt(8));
                    }
                    System.out.println("matched: " + n);
                }
            }
        }
    }
}
