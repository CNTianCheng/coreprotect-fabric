import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

public class TestDump {
    public static void main(String[] args) throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + args[0]);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id,time,user,wid,x,y,z,type,old_data,new_data,action FROM co_block ORDER BY id DESC LIMIT 20")) {
            int n = 0;
            while (rs.next()) {
                n++;
                System.out.println("id=" + rs.getLong(1) + " t=" + rs.getLong(2) + " user=" + rs.getString(3)
                        + " pos=" + rs.getInt(5) + "," + rs.getInt(6) + "," + rs.getInt(7)
                        + " type=" + rs.getInt(8) + " old=" + rs.getString(9)
                        + " new=" + rs.getString(10) + " action=" + rs.getString(11));
            }
            System.out.println("total rows shown: " + n);
        }
    }
}
