package net.coreprotect.fabric.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.coreprotect.fabric.CoreProtectFabric;
import net.minecraft.SharedConstants;

/**
 * Checks for newer mod releases (Modrinth API by default, or a custom URL) and
 * notifies the server console; admin players are notified when they join
 * (see {@link net.coreprotect.fabric.event.SessionEventListener}).
 */
public final class UpdateChecker {
    private final ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "CoreProtect-Update");
        t.setDaemon(true);
        return t;
    });

    private volatile String latestVersion;
    private volatile String latestUrl;

    public boolean available() {
        return latestVersion != null;
    }

    public String latestVersion() {
        return latestVersion;
    }

    public String latestUrl() {
        return latestUrl;
    }

    public void start() {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null || !mod.config().updateCheck.enabled) {
            CoreProtectFabric.LOGGER.info("[CoreProtect] Update check is disabled in the config.");
            scheduler.shutdown();
            return;
        }
        long interval = TimeUnit.HOURS.toMillis(mod.config().updateCheck.intervalHours);
        scheduler.schedule(this::check, TimeUnit.SECONDS.toMillis(30), TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(this::check, TimeUnit.SECONDS.toMillis(30) + interval, interval, TimeUnit.MILLISECONDS);
    }

    public void shutdown() {
        scheduler.shutdown();
    }

    private void check() {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null || mod.server() == null) return;
        String slug = mod.config().updateCheck.slug;
        String custom = mod.config().updateCheck.url;
        String url = (custom != null && !custom.isBlank())
                ? custom
                : "https://api.modrinth.com/v2/project/" + slug + "/version";
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("User-Agent",
                    "coreprotect-fabric/" + CoreProtectFabric.MOD_VERSION + " (Minecraft 1.21; Fabric)");
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                }
            } finally {
                connection.disconnect();
            }
            JsonArray versions = JsonParser.parseString(body.toString()).getAsJsonArray();
            String minecraft = SharedConstants.getGameVersion().getName();
            for (JsonElement element : versions) {
                JsonObject v = element.getAsJsonObject();
                if (!contains(v.get("game_versions"), minecraft) || !contains(v.get("loaders"), "fabric")) {
                    continue;
                }
                String number = v.get("version_number").getAsString();
                if (compareVersions(number, CoreProtectFabric.MOD_VERSION) > 0) {
                    latestVersion = number;
                    latestUrl = fileUrl(v, slug, number);
                    CoreProtectFabric.LOGGER.info("[CoreProtect] A new version of CoreProtect Fabric is available: v{} "
                            + "(current: v{}). Download: {}", number, CoreProtectFabric.MOD_VERSION, latestUrl);
                }
                break; // Modrinth returns newest first; the first matching entry decides.
            }
        } catch (Exception e) {
            CoreProtectFabric.LOGGER.warn("[CoreProtect] Update check failed: {}", e.toString());
        }
    }

    private static boolean contains(JsonElement element, String value) {
        if (element == null || !element.isJsonArray()) return false;
        for (JsonElement e : element.getAsJsonArray()) {
            if (e.isJsonPrimitive() && e.getAsString().equalsIgnoreCase(value)) return true;
        }
        return false;
    }

    private static String fileUrl(JsonObject version, String slug, String number) {
        try {
            JsonElement files = version.get("files");
            if (files != null && files.isJsonArray() && files.getAsJsonArray().size() > 0) {
                JsonElement url = files.getAsJsonArray().get(0).getAsJsonObject().get("url");
                if (url != null && url.isJsonPrimitive()) return url.getAsString();
            }
        } catch (Exception ignored) {
        }
        return "https://modrinth.com/mod/" + slug + "/version/" + number;
    }

    /** Numeric segment comparison, ignoring pre-release suffixes (e.g. "1.4.0+build.1" == "1.4.0"). */
    static int compareVersions(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int va = i < pa.length ? numPart(pa[i]) : 0;
            int vb = i < pb.length ? numPart(pb[i]) : 0;
            if (va != vb) return Integer.compare(va, vb);
        }
        return 0;
    }

    private static int numPart(String segment) {
        int i = 0;
        while (i < segment.length() && !Character.isDigit(segment.charAt(i))) {
            i++;
        }
        int value = 0;
        while (i < segment.length() && Character.isDigit(segment.charAt(i))) {
            value = value * 10 + (segment.charAt(i) - '0');
            i++;
        }
        return value;
    }
}
