package net.coreprotect.fabric.config;

import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.coreprotect.fabric.CoreProtectFabric;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Mod configuration, stored as JSON in {@code config/coreprotect-fabric.json}.
 * Missing fields keep their defaults, so the file can be edited freely.
 */
public final class CoreProtectConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Fallback language when a player has no override and their client locale is unknown. */
    public String language = "en_us";
    /** SQLite file name/path: relative paths resolve against the game directory, absolute paths are used as-is. */
    public String databaseFile = "coreprotect.db";

    public Logging logging = new Logging();
    public Lookup lookup = new Lookup();
    public Rollback rollback = new Rollback();
    public Permissions permissions = new Permissions();
    public PermissionGroups permissionGroups = new PermissionGroups();
    public DataRetention dataRetention = new DataRetention();
    public Metrics metrics = new Metrics();
    public UpdateCheck updateCheck = new UpdateCheck();
    public Database database = new Database();

    public static class Logging {
        public boolean block = true;
        public boolean container = true;
        public boolean entity = true;
        public boolean chat = true;
        public boolean command = true;
        public boolean session = true;
        public boolean natural = true;
        public boolean hopper = true;
        public boolean dispenser = true;
        public boolean item = true;
        public boolean signEdit = true;
    }

    public static class Lookup {
        /** Default time window (seconds) for lookups without a t: parameter. */
        public long defaultTimeSeconds = 604800;
        /** Rows shown per lookup page. */
        public int maxLines = 10;
        /** Rows shown by inspection mode. */
        public int inspectLines = 8;
    }

    public static class Rollback {
        /** Refuse rollbacks/restores that would touch more than this many block logs. */
        public int maxBlocks = 5000;
    }

    public static class Permissions {
        /** Permission level required for inspect/lookup/status/help/language (0 = everyone). */
        public int lookupLevel = 0;
        /** Permission level required for rollback/restore/undo/purge/reload (4 = ops). */
        public int adminLevel = 4;
    }

    /**
     * CoreProtect-style permission groups: when enabled, players listed in a group
     * gain the nodes in that group's {@code permissions} list (or {@code "all"}).
     * Works together with the OP-level fallback above.
     */
    public static class PermissionGroups {
        public boolean enabled = false;
        public java.util.LinkedHashMap<String, Group> groups = new java.util.LinkedHashMap<>();

        public static class Group {
            public java.util.List<String> players = new java.util.ArrayList<>();
            public java.util.List<String> permissions = new java.util.ArrayList<>();
        }

        public PermissionGroups() {
            Group admin = new Group();
            admin.permissions.add("all");
            groups.put("admin", admin);
        }
    }

    /**
     * Automatic data-retention limit: when enabled, rows older than {@code maxDays}
     * days are deleted once at server startup (after the database is opened).
     */
    public static class DataRetention {
        public boolean enabled = false;
        /** Maximum data age in days; rows older than this are pruned at startup. */
        public int maxDays = 30;
    }

    /**
     * bStats usage statistics (https://bstats.org). Anonymous data is submitted
     * every 30 minutes; players can opt out via {@code config/bstats/config.txt}.
     */
    public static class Metrics {
        public boolean enabled = true;
        /** bStats service id, assigned when the mod was registered at bstats.org. 0 disables submission. */
        public int serviceId = 33739;
        /** bStats API endpoint. Custom endpoints use a short submit delay (for self-hosting/testing). */
        public String endpoint = "https://bstats.org/api/v2/data/server-implementation";
    }

    /**
     * Update notifications: checks Modrinth (or a custom URL) for newer releases and
     * notifies the server console and admin players.
     */
    /** Database tuning. */
    public static class Database {
        /** SQLite page cache per connection, in MB (larger = faster reads, more RAM). */
        public int cacheSizeMB = 128;
    }

    public static class UpdateCheck {
        public boolean enabled = true;
        /** Modrinth project slug. */
        public String slug = "coreprotect-fabric";
        /** Optional override: a custom JSON API URL returning a Modrinth-style version list. */
        public String url = "";
        /** How often to re-check for updates, in hours. */
        public int intervalHours = 12;
    }

    private transient Path path;
    private transient boolean endpointMigrated;

    public Path path() {
        if (path == null) {
            path = FabricLoader.getInstance().getConfigDir().resolve("coreprotect-fabric.json");
        }
        return path;
    }

    public void load() {
        Path p = path();
        try {
            if (!Files.exists(p)) {
                save();
                return;
            }
            String json = Files.readString(p);
            // Older config files have no "dispenser" key in logging; keep it enabled for them.
            boolean hasDispenserKey = true;
            try {
                com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
                if (root.has("logging") && root.get("logging").isJsonObject()) {
                    hasDispenserKey = root.getAsJsonObject("logging").has("dispenser");
                }
            } catch (Exception ignored) {
            }
            CoreProtectConfig loaded = GSON.fromJson(json, CoreProtectConfig.class);
            if (loaded == null) return;
            // merge top-level fields, keeping nested defaults for missing sections
            this.language = loaded.language;
            this.databaseFile = loaded.databaseFile;
            if (loaded.logging != null) {
                this.logging = loaded.logging;
                if (!hasDispenserKey) this.logging.dispenser = true;
            }
            if (loaded.lookup != null) this.lookup = loaded.lookup;
            if (loaded.rollback != null) this.rollback = loaded.rollback;
            if (loaded.permissions != null) this.permissions = loaded.permissions;
            if (loaded.permissionGroups != null) this.permissionGroups = loaded.permissionGroups;
            if (loaded.dataRetention != null) this.dataRetention = loaded.dataRetention;
            if (loaded.metrics != null) this.metrics = loaded.metrics;
            if (loaded.updateCheck != null) this.updateCheck = loaded.updateCheck;
            if (loaded.database != null) this.database = loaded.database;
            if (this.database.cacheSizeMB < 16) this.database.cacheSizeMB = 128;
            if (this.permissionGroups.groups == null) this.permissionGroups.groups = new java.util.LinkedHashMap<>();
            if (this.lookup.maxLines < 1) this.lookup.maxLines = 10;
            if (this.lookup.inspectLines < 1) this.lookup.inspectLines = 8;
            if (this.lookup.defaultTimeSeconds < 0) this.lookup.defaultTimeSeconds = 604800;
            if (this.rollback.maxBlocks < 1) this.rollback.maxBlocks = 5000;
            if (this.dataRetention.maxDays < 1) this.dataRetention.maxDays = 30;
            if (this.metrics.serviceId < 0) this.metrics.serviceId = 0;
            if (this.metrics.endpoint == null || this.metrics.endpoint.isBlank()
                    || this.metrics.endpoint.contains("/api/v2/data/fabric")) {
                // "fabric" is not a valid bStats platform; the service is registered under
                // "server-implementation" (the platform for server-side mods).
                this.metrics.endpoint = "https://bstats.org/api/v2/data/server-implementation";
                endpointMigrated = true;
            }
            if (this.updateCheck.slug == null || this.updateCheck.slug.isBlank()) {
                this.updateCheck.slug = "coreprotect-fabric";
            }
            if (this.updateCheck.intervalHours < 1) this.updateCheck.intervalHours = 12;
            if (this.databaseFile == null || this.databaseFile.isBlank()) this.databaseFile = "coreprotect.db";
        } catch (Exception e) {
            CoreProtectFabric.LOGGER.error("[CoreProtect] Failed to load config, using defaults", e);
        }
        if (endpointMigrated) {
            endpointMigrated = false;
            save(); // persist corrections (e.g. the bStats endpoint fix) back to disk
        }
    }

    public void save() {
        try {
            Files.writeString(path(), GSON.toJson(this));
        } catch (Exception e) {
            CoreProtectFabric.LOGGER.error("[CoreProtect] Failed to save config", e);
        }
    }
}
