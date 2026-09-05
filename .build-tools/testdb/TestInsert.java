import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

public class TestInsert {
    public static void main(String[] args) throws Exception {
        String db = args[0];
        Class.forName("org.sqlite.JDBC");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            long now = System.currentTimeMillis() / 1000L;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO co_block(time,user,wid,x,y,z,type,old_data,new_data,action,meta) VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
                ps.setLong(1, now);
                ps.setString(2, "TestUser");
                ps.setString(3, "minecraft:overworld");
                ps.setInt(4, 0);
                ps.setInt(5, -61);
                ps.setInt(6, 0);
                ps.setInt(7, 1);
                ps.setString(8, "minecraft:air");
                ps.setString(9, "minecraft:grass_block");
                ps.setString(10, "+");
                ps.setString(11, null);
                ps.executeUpdate();
            }
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM co_block")) {
                rs.next();
                System.out.println("co_block rows: " + rs.getLong(1));
            }
        }
    }
}
