package net.coreprotect.fabric.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
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
    /** Set when the update source reports that the project/URL does not exist: stops the retries. */
    private volatile boolean sourceMissing;
    private ScheduledFuture<?> task;

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
        task = scheduler.scheduleAtFixedRate(this::check, TimeUnit.SECONDS.toMillis(30) + interval, interval, TimeUnit.MILLISECONDS);
    }

    public void shutdown() {
        if (task != null) {
            task.cancel(true); // scheduleAtFixedRate keeps running after a plain shutdown()
        }
        scheduler.shutdownNow();
    }

    private void check() {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null || mod.server() == null || sourceMissing) return;
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
                    "coreprotect-fabric/" + CoreProtectFabric.MOD_VERSION + " (Fabric)");
            int status = connection.getResponseCode();
            if (status == 404 || status == 410) {
                // the project simply does not exist (yet): report once instead of warning
                // on every interval for the rest of the server's life
                sourceMissing = true;
                CoreProtectFabric.LOGGER.info("[CoreProtect] Update check disabled: {} returned HTTP {}. "
                        + "Set updateCheck.slug/url in the config once the mod is published.", url, status);
                connection.disconnect();
                return;
            }
            if (status < 200 || status >= 300) {
                CoreProtectFabric.LOGGER.warn("[CoreProtect] Update check failed: HTTP {} from {}", status, url);
                connection.disconnect();
                return;
            }
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
            String newest = null;
            JsonObject newestEntry = null;
            // scan every matching entry instead of stopping at the first: Modrinth returns
            // newest-first, but a pre-release of a lower version could otherwise hide a
            // genuinely newer stable release
            for (JsonElement element : versions) {
                JsonObject v = element.getAsJsonObject();
                if (!contains(v.get("game_versions"), minecraft) || !contains(v.get("loaders"), "fabric")) {
                    continue;
                }
                JsonElement numberElement = v.get("version_number");
                if (numberElement == null || !numberElement.isJsonPrimitive()) continue;
                String number = numberElement.getAsString();
                if (isPreRelease(number) && !isPreRelease(CoreProtectFabric.MOD_VERSION)) continue;
                if (newest == null || compareVersions(number, newest) > 0) {
                    newest = number;
                    newestEntry = v;
                }
            }
            if (newest != null && compareVersions(newest, CoreProtectFabric.MOD_VERSION) > 0) {
                latestVersion = newest;
                latestUrl = fileUrl(newestEntry, slug, newest);
                CoreProtectFabric.LOGGER.info("[CoreProtect] A new version of CoreProtect Fabric is available: v{} "
                        + "(current: v{}). Download: {}", newest, CoreProtectFabric.MOD_VERSION, latestUrl);
            }
        } catch (Exception e) {
            CoreProtectFabric.LOGGER.warn("[CoreProtect] Update check failed: {}", e.toString());
        }
    }

    /** A version carrying a pre-release suffix such as {@code 1.9.0-beta.1}. */
    private static boolean isPreRelease(String version) {
        return version != null && version.matches(".*[A-Za-z].*");
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
