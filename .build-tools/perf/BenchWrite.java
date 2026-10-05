import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Measures what "one autocommit INSERT per log row" costs compared with a
 * group-commit writer, on the real sqlite-jdbc driver and the real co_chat shape.
 *
 * Usage: java -cp "<sqlite-jdbc.jar>;<slf4j-api.jar>" BenchWrite.java <dir> [rows] [rowsPerSecond] [seconds]
 */
public final class BenchWrite {

    static int ROWS = 4000;
    static int RATE = 60;
    static int SECONDS = 15;

    public static void main(String[] args) throws Exception {
        Path dir = Paths.get(args[0]);
        Files.createDirectories(dir);
        if (args.length > 1) ROWS = Integer.parseInt(args[1]);
        if (args.length > 2) RATE = Integer.parseInt(args[2]);
        if (args.length > 3) SECONDS = Integer.parseInt(args[3]);

        System.out.println("sqlite-jdbc : " + DriverManager.getDriver("jdbc:sqlite:x").getClass().getName());
        System.out.println("rows/variant: " + ROWS + "   streaming: " + RATE + " rows/s for " + SECONDS + "s");
        System.out.println();
        System.out.printf("%-34s %10s %10s %10s %10s%n", "variant", "total ms", "us/row", "max ms", "commits");
        System.out.println("--------------------------------------------------------------------------------");

        for (String sync : new String[]{"FULL", "NORMAL"}) {
            perRow(dir, "autocommit per row, sync=" + sync, sync);
        }
        for (String sync : new String[]{"FULL", "NORMAL"}) {
            batchFixed(dir, "batch 500 rows/tx, sync=" + sync, sync, 500);
        }
        System.out.println();
        System.out.println("streaming, real arrival rate " + RATE + " rows/s for " + SECONDS + "s:");
        System.out.printf("%-34s %10s %10s %10s %10s %10s%n", "variant", "total ms", "us/row", "max ms", "commits", "max wait");
        System.out.println("--------------------------------------------------------------------------------");
        streaming(dir, "per-row commit, sync=FULL", "FULL", 1, 0);
        streaming(dir, "group commit 1000/250ms FULL", "FULL", 1000, 250);
        streaming(dir, "group commit 1000/250ms NORMAL", "NORMAL", 1000, 250);
        streaming(dir, "group commit 1000/500ms NORMAL", "NORMAL", 1000, 500);
    }

    // ------------------------------------------------------------------

    static Connection open(Path db, String sync) throws Exception {
        Files.deleteIfExists(db);
        Files.deleteIfExists(Paths.get(db.toString() + "-wal"));
        Files.deleteIfExists(Paths.get(db.toString() + "-shm"));
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
        try (Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("PRAGMA journal_mode=WAL")) {
                rs.next();
            }
            st.execute("PRAGMA synchronous=" + sync);
            st.execute("PRAGMA busy_timeout=10000");
            st.execute("CREATE TABLE co_chat (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL,"
                    + " name_id INTEGER NOT NULL, message TEXT NOT NULL)");
            st.execute("CREATE INDEX idx_chat_time ON co_chat(time, name_id)");
        }
        return c;
    }

    static void insert(PreparedStatement ps, long i) throws Exception {
        ps.setLong(1, 1_700_000_000_000L + i);
        ps.setLong(2, 1 + (i % 12));
        ps.setString(3, "hello world message number " + i);
        ps.executeUpdate();
    }

    static void report(String label, long startNanos, int rows, long maxNanos, int commits) {
        long totalNanos = System.nanoTime() - startNanos;
        double perRowUs = totalNanos / 1000.0 / rows;
        System.out.printf("%-34s %10.1f %10.1f %10.2f %10d%n", label, totalNanos / 1e6, perRowUs,
                maxNanos / 1e6, commits);
    }

    static void perRow(Path dir, String label, String sync) throws Exception {
        try (Connection c = open(dir.resolve("per-row-" + sync + ".db"), sync)) {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO co_chat(time,name_id,message) VALUES(?,?,?)")) {
                long start = System.nanoTime();
                long max = 0;
                for (int i = 0; i < ROWS; i++) {
                    long t0 = System.nanoTime();
                    insert(ps, i);
                    long d = System.nanoTime() - t0;
                    if (d > max) max = d;
                }
                report(label, start, ROWS, max, ROWS);
            }
        }
    }

    static void batchFixed(Path dir, String label, String sync, int batchRows) throws Exception {
        try (Connection c = open(dir.resolve("batch-" + sync + ".db"), sync)) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO co_chat(time,name_id,message) VALUES(?,?,?)")) {
                long start = System.nanoTime();
                long max = 0;
                int commits = 0;
                for (int i = 0; i < ROWS; i++) {
                    insert(ps, i);
                    if ((i + 1) % batchRows == 0) {
                        long t0 = System.nanoTime();
                        c.commit();
                        long d = System.nanoTime() - t0;
                        if (d > max) max = d;
                        commits++;
                    }
                }
                c.commit();
                commits++;
                report(label, start, ROWS, max, commits);
            }
        }
    }

    static void streaming(Path dir, String label, String sync, int maxRows, long intervalMs) throws Exception {
        Path db = dir.resolve("stream-" + sync + "-" + maxRows + "-" + intervalMs + ".db");
        ArrayBlockingQueue<long[]> queue = new ArrayBlockingQueue<>(200_000);
        AtomicBoolean done = new AtomicBoolean(false);
        AtomicLong maxWait = new AtomicLong();
        AtomicLong maxCommit = new AtomicLong();
        AtomicLong commits = new AtomicLong();

        Thread producer = new Thread(() -> {
            long periodNanos = 1_000_000_000L / RATE;
            long next = System.nanoTime();
            long i = 0;
            long end = System.nanoTime() + SECONDS * 1_000_000_000L;
            while (System.nanoTime() < end) {
                queue.add(new long[]{i++, System.nanoTime()});
                next += periodNanos;
                long sleep = next - System.nanoTime();
                if (sleep > 0) {
                    try {
                        TimeUnit.NANOSECONDS.sleep(sleep);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
            done.set(true);
        }, "producer");

        try (Connection c = open(db, sync)) {
            producer.start();
            boolean batched = maxRows > 1;
            c.setAutoCommit(!batched);
            long start = System.nanoTime();
            int rows = 0;
            int pending = 0;
            long oldestEnqueue = 0;
            long deadline = System.nanoTime() + intervalMs * 1_000_000L;
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO co_chat(time,name_id,message) VALUES(?,?,?)")) {
                while (true) {
                    long pollMs = batched ? Math.max(1, (deadline - System.nanoTime()) / 1_000_000L) : 50;
                    long[] item = queue.poll(pollMs, TimeUnit.MILLISECONDS);
                    if (item != null) {
                        long t0 = System.nanoTime();
                        if (batched && pending == 0) oldestEnqueue = item[1];
                        insert(ps, item[0]);
                        if (!batched) {
                            long d = System.nanoTime() - t0;
                            if (d > maxCommit.get()) maxCommit.set(d);
                            if (d > maxWait.get()) maxWait.set(d);
                            commits.incrementAndGet();
                        } else {
                            pending++;
                        }
                        rows++;
                    }
                    if (!batched) {
                        if (item == null && done.get() && queue.isEmpty()) break;
                        continue;
                    }
                    boolean timeUp = System.nanoTime() >= deadline;
                    boolean flush = pending > 0 && (pending >= maxRows || timeUp
                            || (item == null && done.get() && queue.isEmpty()));
                    if (flush) {
                        long t0 = System.nanoTime();
                        c.commit();
                        long d = System.nanoTime() - t0;
                        if (d > maxCommit.get()) maxCommit.set(d);
                        commits.incrementAndGet();
                        long wait = System.nanoTime() - oldestEnqueue;
                        if (wait > maxWait.get()) maxWait.set(wait);
                        pending = 0;
                        deadline = System.nanoTime() + intervalMs * 1_000_000L;
                    } else if (item == null && done.get() && queue.isEmpty() && pending == 0) {
                        break;
                    }
                }
            }
            producer.join();
            long totalNanos = System.nanoTime() - start;
            System.out.printf("%-34s %10.1f %10.1f %10.2f %10d %8.0f ms%n", label, totalNanos / 1e6,
                    totalNanos / 1000.0 / Math.max(1, rows), maxCommit.get() / 1e6, commits.get(),
                    maxWait.get() / 1e6);
        }
    }
}
