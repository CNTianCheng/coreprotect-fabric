import java.sql.*;

/** Prints how much space each table/index occupies (SQLite dbstat virtual table). */
public class DbStat {
    public static void main(String[] a) throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + a[0]); Statement st = c.createStatement()) {
            System.out.println("== per-object page usage ==");
            try (ResultSet rs = st.executeQuery(
                    "SELECT name, SUM(pgsize) s, COUNT(*) n FROM dbstat GROUP BY name ORDER BY s DESC")) {
                while (rs.next()) {
                    System.out.printf("  %-30s %10.2f MB  (%d pages)%n",
                            rs.getString(1), rs.getLong(2) / 1048576.0, rs.getLong(3));
                }
            }
            long total = 0;
            try (ResultSet rs = st.executeQuery("SELECT SUM(pgsize) FROM dbstat")) {
                if (rs.next()) total = rs.getLong(1);
            }
            System.out.printf("  %-30s %10.2f MB%n", "TOTAL", total / 1048576.0);
        } catch (SQLException e) {
            System.out.println("dbstat unavailable: " + e.getMessage());
        }
    }
}
