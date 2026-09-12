package net.coreprotect.fabric.util;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import com.google.gson.Gson;
import net.coreprotect.fabric.CoreProtectFabric;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;

/**
 * Minimal bStats client for Fabric, following the official bStats v2
 * metrics protocol (https://bstats.org):
 * a gzipped JSON document is POSTed to {@code /api/v2/data/<platform>} every
 * 30 minutes with an initial randomized 3-6 minute delay. All data is anonymous.
 *
 * <p>The opt-out file {@code config/bstats/config.txt} (format {@code enabled: false})
 * is honored, as required by bStats policy. The service id is read from the mod
 * config ({@code metrics.serviceId}); 0 disables submission until the mod is
 * registered at bstats.org.
 */
public final class BStatsMetrics {
    private static final String METRICS_VERSION = "3.2.2";

    private final ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "bStats-Metrics");
        t.setDaemon(true);
        return t;
    });

    private final String serverUuid;
    private final boolean enabled;
    private final boolean logFailedRequests;
    private final boolean logSentData;
    private final boolean logResponseStatusText;
    private final int serviceId;
    private final String endpoint;

    public BStatsMetrics(CoreProtectFabric mod) {
        // bStats opt-out config: config/bstats/config.txt (official bStats file: config.yml)
        Path file = FabricLoader.getInstance().getConfigDir().resolve("bstats").resolve("config.txt");
        Path yml = FabricLoader.getInstance().getConfigDir().resolve("bstats").resolve("config.yml");
        String[] cfg = loadConfig(file, yml);
        this.serverUuid = cfg[0];
        boolean fileEnabled = Boolean.parseBoolean(cfg[1]);
        this.logFailedRequests = Boolean.parseBoolean(cfg[2]);
        this.logSentData = Boolean.parseBoolean(cfg[3]);
        this.logResponseStatusText = Boolean.parseBoolean(cfg[4]);

        this.serviceId = mod.config().metrics.serviceId;
        this.endpoint = mod.config().metrics.endpoint;
        this.enabled = fileEnabled && mod.config().metrics.enabled && serviceId > 0;
        if (!fileEnabled) {
            CoreProtectFabric.LOGGER.info("[CoreProtect] bStats disabled by config/bstats/config.txt (opt-out).");
        } else if (serviceId <= 0) {
            CoreProtectFabric.LOGGER.info("[CoreProtect] bStats metrics are disabled: set metrics.serviceId in the mod config "
                    + "(register the mod at https://bstats.org to receive an id).");
        }
    }

    public void start() {
        if (!enabled) {
            scheduler.shutdown();
            return;
        }
        Runnable task = this::submitData;
        boolean customEndpoint = !endpoint.startsWith("https://bstats.org");
        // Official bStats cadence: first submit after 3-6 minutes, then every 30 minutes.
        // Custom/self-hosted endpoints (not bStats) use a short delay instead.
        long initialDelay = customEndpoint
                ? TimeUnit.SECONDS.toMillis(10)
                : (long) (1000 * 60 * (3 + Math.random() * 3));
        long secondDelay = customEndpoint ? TimeUnit.SECONDS.toMillis(10) : (long) (1000 * 60 * (Math.random() * 30));
        scheduler.schedule(task, initialDelay, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(task, initialDelay + secondDelay, 1000 * 60 * 30, TimeUnit.MILLISECONDS);
        CoreProtectFabric.LOGGER.info("[CoreProtect] bStats metrics enabled (service id {}).", serviceId);
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }

    private void submitData() {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null || mod.server() == null) return;
        int playerAmount = mod.server().getPlayerCount();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("playerAmount", playerAmount);
        data.put("onlineMode", mod.server().usesAuthentication() ? 1 : 0);
        data.put("minecraftVersion", SharedConstants.getCurrentVersion().name());
        data.put("fabricVersion", FabricLoader.getInstance().getModContainer("fabricloader")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown"));
        data.put("javaVersion", System.getProperty("java.version"));
        data.put("osName", System.getProperty("os.name"));
        data.put("osArch", System.getProperty("os.arch"));
        data.put("osVersion", System.getProperty("os.version"));
        data.put("coreCount", Runtime.getRuntime().availableProcessors());
        data.put("serverSoftware", "fabric");

        Map<String, Object> service = new LinkedHashMap<>();
        service.put("id", serviceId);
        // Official bStats single-line-chart format; the backend stores the value into
        // the "players" chart of the service (the chart itself is created on bstats.org).
        service.put("customCharts", List.of(Map.of(
                "chartId", "players",
                "data", Map.of("value", playerAmount))));
        service.put("pluginVersion", CoreProtectFabric.MOD_VERSION);
        data.put("service", service);
        data.put("serverUUID", serverUuid);
        data.put("metricsVersion", METRICS_VERSION);

        String json = new Gson().toJson(data);
        try {
            sendData(json);
        } catch (Exception e) {
            if (logFailedRequests) {
                CoreProtectFabric.LOGGER.error("[CoreProtect] Could not submit bStats metrics data", e);
            }
        }
    }

    private void sendData(String json) throws Exception {
        if (logSentData) {
            CoreProtectFabric.LOGGER.info("[CoreProtect] Sent bStats metrics data: {}", json);
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        byte[] compressed = compress(json);
        connection.setRequestMethod("POST");
        connection.addRequestProperty("Accept", "application/json");
        connection.addRequestProperty("Connection", "close");
        connection.addRequestProperty("Content-Encoding", "gzip");
        connection.addRequestProperty("Content-Length", String.valueOf(compressed.length));
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("User-Agent", "Metrics-Service/1");
        connection.setDoOutput(true);
        try (DataOutputStream out = new DataOutputStream(connection.getOutputStream())) {
            out.write(compressed);
        }
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        } finally {
            connection.disconnect();
        }
        if (logResponseStatusText) {
            CoreProtectFabric.LOGGER.info("[CoreProtect] Sent data to bStats and received response: {}", builder);
        }
    }

    private static byte[] compress(String str) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(str.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    /**
     * Loads {@code config/bstats/config.txt} and honours the official bStats opt-out file
     * {@code config/bstats/config.yml}. Returns
     * {@code [serverUuid, enabled, logFailedRequests, logSentData, logResponseStatusText]}.
     * The file is only (re)written when it is missing, so a manual opt-out survives.
     */
    private static String[] loadConfig(Path file, Path yml) {
        String uuid = UUID.randomUUID().toString();
        String enabled = "true";
        String logFailed = "false";
        String logSent = "false";
        String logResponse = "false";
        boolean exists = false;
        try {
            if (Files.exists(file)) {
                exists = true;
                for (String line : Files.readAllLines(file)) {
                    String s = line.trim();
                    if (s.startsWith("#") || !s.contains(":")) continue;
                    int idx = s.indexOf(':');
                    String key = s.substring(0, idx).trim();
                    String value = s.substring(idx + 1).trim();
                    switch (key) {
                        case "serverUuid" -> uuid = value;
                        case "enabled" -> enabled = value;
                        case "logFailedRequests" -> logFailed = value;
                        case "logSentData" -> logSent = value;
                        case "logResponseStatusText" -> logResponse = value;
                        default -> {
                        }
                    }
                }
            }
            // the official bStats file (config.yml, written by the bStats library) must be
            // respected as well: "enabled: false" there means the admin opted out
            if (Files.exists(yml)) {
                for (String line : Files.readAllLines(yml)) {
                    String s = line.trim();
                    if (!s.startsWith("enabled")) continue;
                    int idx = s.indexOf(':');
                    if (idx < 0) continue;
                    enabled = s.substring(idx + 1).trim();
                    break;
                }
            }
            if (!exists) {
                String content = "# bStats collects some basic information for the mod author, like how\n"
                        + "# many people are using their mod and their total player count. It's\n"
                        + "# recommended to keep bStats enabled, but if you're not comfortable with\n"
                        + "# this, you can turn this setting off. There is no performance penalty\n"
                        + "# associated with having metrics enabled, and data sent to bStats is fully\n"
                        + "# anonymous.\n"
                        + "enabled: " + enabled + "\n"
                        + "serverUuid: " + uuid + "\n"
                        + "logFailedRequests: " + logFailed + "\n"
                        + "logSentData: " + logSent + "\n"
                        + "logResponseStatusText: " + logResponse + "\n";
                if (file.getParent() != null) {
                    Files.createDirectories(file.getParent());
                }
                Files.writeString(file, content, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            CoreProtectFabric.LOGGER.error("[CoreProtect] Failed to read bStats config", e);
        }
        return new String[] {uuid, enabled, logFailed, logSent, logResponse};
    }
}
