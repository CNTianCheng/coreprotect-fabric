import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

public class TestDump2 {
    public static void main(String[] args) throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + args[0]);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id,time,user,wid,x,y,z,type,data,amount FROM co_container ORDER BY id DESC LIMIT 12")) {
            while (rs.next()) {
                System.out.println("id=" + rs.getLong(1) + " user=[" + rs.getString(3) + "] pos=" + rs.getInt(5) + "," + rs.getInt(6) + "," + rs.getInt(7)
                        + " type=" + rs.getInt(8) + " data=" + rs.getString(9) + " amount=" + rs.getInt(10));
            }
        }
    }
}
