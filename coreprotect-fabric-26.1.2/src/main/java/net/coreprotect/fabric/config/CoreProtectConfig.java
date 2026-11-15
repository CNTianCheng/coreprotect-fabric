package net.coreprotect.fabric.config;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
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
        /**
         * Commands whose arguments are never stored in the command log. Login and registration
         * commands carry plain-text passwords and the log is readable in-game. Matching ignores
         * case, surrounding whitespace and the leading slash ("/Login" == "login", F-9.1).
         */
        public java.util.List<String> commandBlacklist = new java.util.ArrayList<>(java.util.List.of(
                "/login", "/register", "/l", "/reg", "/changepassword", "/newpassword"));
    }

    public static class Lookup {
        /** Default time window (seconds) for lookups without a t: parameter. */
        public long defaultTimeSeconds = 604800;
        /** Rows shown per lookup page (1-1000). */
        public int maxLines = 10;
        /** Rows shown by inspection mode (1-1000). */
        public int inspectLines = 8;
    }

    public static class Rollback {
        /** Refuse rollbacks/restores that would touch more than this many block logs. */
        public int maxBlocks = 5000;
    }

    public static class Permissions {
        /**
         * Permission level required for inspect/lookup/status (help and language are always
         * available: they only print text or change the caller's own language).
         * 0 = every player, 1 = moderators (default), 4 = owners only.
         */
        public int lookupLevel = 1;
        /** Permission level required for rollback/restore/undo/purge/reload (4 = ops). */
        public int adminLevel = 4;
        /**
         * Permission level required to read chat logs ({@code a:#chat}). Separate from the block
         * lookup level because players type passwords into chat.
         */
        public int chatLevel = 3;
        /** Permission level required to read the server-command log ({@code a:#command}). */
        public int commandLevel = 4;
    }

    /**
     * CoreProtect-style permission groups: when enabled, players listed in a group
     * gain the nodes in that group's {@code permissions} list (or {@code "all"}).
     * Works together with the OP-level fallback above.
     *
     * <p>Players are matched by UUID (with or without dashes). A plain player name is still
     * accepted on an online-mode server; on an offline-mode server names are not identities,
     * so name entries are ignored and only UUIDs count.
     */
    public static class PermissionGroups {
        public boolean enabled = false;
        public java.util.LinkedHashMap<String, Group> groups = new java.util.LinkedHashMap<>();

        public static class Group {
            /** Player UUIDs (preferred) or names (online-mode servers only). */
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
    /**
     * Database tuning. These fields are volatile because /co reload rewrites them on the
     * server thread while the database worker reads them; without a happens-before edge the
     * worker could keep using the previous value for an unbounded time.
     */
    public static class Database {
        /** SQLite page cache per connection, in MB (larger = faster reads, more RAM). */
        public volatile int cacheSizeMB = 128;
        /** WAL durability: "full" fsyncs every commit (max crash safety, default); "normal" is faster. */
        public volatile String syncMode = "full";
        /**
         * How long a log row may wait before it is written, in milliseconds. Rows are
         * collected for this long and committed as one transaction, which turns dozens of
         * fsyncs per second into a handful. A crash can lose at most this window (250 ms
         * by default). 0 writes each row in its own transaction (slowest, most durable).
         */
        public volatile int batchIntervalMs = 250;
        /** Maximum rows written in one transaction (a burst is split into several batches). */
        public volatile int batchMaxRows = 1000;
        /** How often the WAL is checkpointed in the background, in minutes (smaller WAL = faster crash recovery). */
        public volatile int checkpointMinutes = 10;
        /** How often a hot backup (VACUUM INTO) is written to <db>.backup, in minutes (0 = disabled). */
        public volatile int backupMinutes = 360;
        /** When integrity checking finds a corrupt database, restore it from <db>.backup automatically. */
        public volatile boolean autoRestoreBackup = true;
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
    /** Largest page size accepted from the config file (rows). */
    private static final int MAX_ROWS = 1000;
    /** Longest default lookup window accepted from the config file (10 years, in seconds). */
    private static final long MAX_DEFAULT_TIME_SECONDS = 315_360_000L;
    /** Largest rollback/restore row budget accepted from the config file (per table). */
    private static final int MAX_ROLLBACK_BLOCKS = 1_000_000;
    private transient boolean endpointMigrated;
    /** Set when load() had to clamp or fill a value, so the corrected config is written back. */
    private transient boolean configCorrected;
    /** True when the last load() could not read/parse the file and kept the previous values. */
    private transient boolean lastLoadFailed;
    /** Message of the last load failure, or {@code null}. */
    private transient String lastLoadError;
    /** Settings changed by the last load() that only take effect after a server restart. */
    private transient java.util.Set<String> restartRequired = new java.util.LinkedHashSet<>();

    public Path path() {
        if (path == null) {
            path = FabricLoader.getInstance().getConfigDir().resolve("coreprotect-fabric.json");
        }
        return path;
    }

    public void load() {
        Path p = path();
        // Reset the load diagnostics even when the file is missing or unreadable, otherwise
        // /co reload keeps reporting the result of an earlier attempt.
        restartRequired = new java.util.LinkedHashSet<>();
        lastLoadFailed = false;
        lastLoadError = null;
        try {
            if (!Files.exists(p)) {
                save();
                return;
            }
            String json = Files.readString(p);
            // The parsed tree is used twice: to detect the old file format, and to name keys the
            // mod does not know (a typo used to be ignored without a single line of output).
            com.google.gson.JsonObject root = null;
            try {
                com.google.gson.JsonElement parsed = com.google.gson.JsonParser.parseString(json);
                if (parsed != null && parsed.isJsonObject()) root = parsed.getAsJsonObject();
            } catch (Exception ignored) {
            }
            // Older config files have no "dispenser" key in logging; keep it enabled for them.
            boolean hasDispenserKey = true;
            if (root != null && root.has("logging") && root.get("logging").isJsonObject()) {
                hasDispenserKey = root.getAsJsonObject("logging").has("dispenser");
            }
            if (root != null) warnUnknownKeys(root, CoreProtectConfig.class, "");
            CoreProtectConfig loaded = GSON.fromJson(json, CoreProtectConfig.class);
            if (loaded == null) return;
            boolean corrected = false;
            // merge top-level fields, keeping nested defaults for missing sections
            if (loaded.language != null && !loaded.language.isBlank()) {
                this.language = loaded.language;
            } else {
                corrected = true;
            }
            String previousDatabaseFile = this.databaseFile;
            this.databaseFile = loaded.databaseFile;
            // Nested sections are copied field by field instead of being replaced: a volatile
            // field only protects the reference, so swapping config.database dropped the
            // happens-before edge that field exists for and left the database worker reading
            // the old object (L4-10). Readers also re-read the nested objects on every call.
            if (loaded.logging != null) {
                Logging from = loaded.logging;
                Logging to = this.logging;
                to.block = from.block;
                to.container = from.container;
                to.entity = from.entity;
                to.chat = from.chat;
                to.command = from.command;
                to.session = from.session;
                to.natural = from.natural;
                to.hopper = from.hopper;
                to.dispenser = from.dispenser;
                to.item = from.item;
                to.signEdit = from.signEdit;
                if (from.commandBlacklist != null) {
                    to.commandBlacklist = new java.util.ArrayList<>(from.commandBlacklist);
                }
                if (!hasDispenserKey) to.dispenser = true;
            }
            if (loaded.lookup != null) {
                this.lookup.defaultTimeSeconds = loaded.lookup.defaultTimeSeconds;
                this.lookup.maxLines = loaded.lookup.maxLines;
                this.lookup.inspectLines = loaded.lookup.inspectLines;
            }
            if (loaded.rollback != null) {
                this.rollback.maxBlocks = loaded.rollback.maxBlocks;
            }
            if (loaded.permissions != null) {
                this.permissions.lookupLevel = loaded.permissions.lookupLevel;
                this.permissions.adminLevel = loaded.permissions.adminLevel;
                this.permissions.chatLevel = loaded.permissions.chatLevel;
                this.permissions.commandLevel = loaded.permissions.commandLevel;
            }
            if (loaded.permissionGroups != null) {
                this.permissionGroups.enabled = loaded.permissionGroups.enabled;
                if (loaded.permissionGroups.groups != null) {
                    this.permissionGroups.groups = new java.util.LinkedHashMap<>(loaded.permissionGroups.groups);
                }
            }
            boolean previousRetentionEnabled = this.dataRetention.enabled;
            int previousRetentionDays = this.dataRetention.maxDays;
            if (loaded.dataRetention != null) {
                this.dataRetention.enabled = loaded.dataRetention.enabled;
                this.dataRetention.maxDays = loaded.dataRetention.maxDays;
            }
            boolean previousMetricsEnabled = this.metrics.enabled;
            if (loaded.metrics != null) {
                this.metrics.enabled = loaded.metrics.enabled;
                this.metrics.serviceId = loaded.metrics.serviceId;
                this.metrics.endpoint = loaded.metrics.endpoint;
            }
            if (loaded.updateCheck != null) {
                this.updateCheck.enabled = loaded.updateCheck.enabled;
                this.updateCheck.slug = loaded.updateCheck.slug;
                this.updateCheck.url = loaded.updateCheck.url;
                this.updateCheck.intervalHours = loaded.updateCheck.intervalHours;
            }
            if (loaded.database != null) {
                Database from = loaded.database;
                Database to = this.database;
                int previousCacheSizeMB = to.cacheSizeMB;
                to.cacheSizeMB = from.cacheSizeMB;
                to.syncMode = from.syncMode;
                to.batchIntervalMs = from.batchIntervalMs;
                to.batchMaxRows = from.batchMaxRows;
                to.checkpointMinutes = from.checkpointMinutes;
                to.backupMinutes = from.backupMinutes;
                to.autoRestoreBackup = from.autoRestoreBackup;
                // The page cache is applied when the connections are opened, so a change only
                // takes effect after a restart; /co reload reports that instead of implying it
                // applied (L4-07). syncMode is re-applied live by DatabaseManager#applySyncMode.
                if (to.cacheSizeMB != previousCacheSizeMB) restartRequired.add("database.cacheSizeMB");
            }
            // every clamp below is remembered so the corrected values are written back to
            // disk; otherwise the file kept the bad value and it was re-applied on each start
            if (this.database.cacheSizeMB < 16 || this.database.cacheSizeMB > 8192) {
                this.database.cacheSizeMB = 128;
                corrected = true;
            }
            if (!"normal".equalsIgnoreCase(this.database.syncMode)) {
                this.database.syncMode = "full";
                corrected = true;
            }
            if (this.database.batchIntervalMs < 0 || this.database.batchIntervalMs > 5000) {
                this.database.batchIntervalMs = 250;
                corrected = true;
            }
            if (this.database.batchMaxRows < 1 || this.database.batchMaxRows > 100000) {
                this.database.batchMaxRows = 1000;
                corrected = true;
            }
            if (this.database.checkpointMinutes < 1) {
                this.database.checkpointMinutes = 10;
                corrected = true;
            }
            if (this.database.backupMinutes < 0) {
                this.database.backupMinutes = 360;
                corrected = true;
            }
            if (this.permissionGroups.groups == null) this.permissionGroups.groups = new java.util.LinkedHashMap<>();
            if (this.lookup.maxLines < 1 || this.lookup.maxLines > MAX_ROWS) {
                this.lookup.maxLines = 10;
                corrected = true;
            }
            if (this.lookup.inspectLines < 1 || this.lookup.inspectLines > MAX_ROWS) {
                this.lookup.inspectLines = 8;
                corrected = true;
            }
            if (this.lookup.defaultTimeSeconds < 0
                    || this.lookup.defaultTimeSeconds > MAX_DEFAULT_TIME_SECONDS) {
                this.lookup.defaultTimeSeconds = 604800;
                corrected = true;
            }
            // Permission levels are clamped as well: an out-of-range level used to fall through
            // to "always pass" (adminLevel: 5 handed /co purge to every player).
            if (this.permissions.adminLevel < 1 || this.permissions.adminLevel > 4) {
                this.permissions.adminLevel = 4;
                corrected = true;
            }
            if (this.permissions.lookupLevel < 0 || this.permissions.lookupLevel > 4) {
                this.permissions.lookupLevel = 4;
                corrected = true;
            }
            if (this.permissions.chatLevel < 0 || this.permissions.chatLevel > 4) {
                this.permissions.chatLevel = 3;
                corrected = true;
            }
            if (this.permissions.commandLevel < 0 || this.permissions.commandLevel > 4) {
                this.permissions.commandLevel = 4;
                corrected = true;
            }
            // Clamped from both sides: the value is a row budget per table, and an unbounded
            // one let a single /co rollback rewrite the whole database (L4-19).
            if (this.rollback.maxBlocks < 1 || this.rollback.maxBlocks > MAX_ROLLBACK_BLOCKS) {
                this.rollback.maxBlocks = 5000;
                corrected = true;
            }
            if (this.dataRetention.maxDays < 1) {
                this.dataRetention.maxDays = 30;
                corrected = true;
            }
            if (this.metrics.serviceId < 0) {
                this.metrics.serviceId = 0;
                corrected = true;
            }
            if (this.metrics.endpoint == null || this.metrics.endpoint.isBlank()
                    || this.metrics.endpoint.contains("/api/v2/data/fabric")) {
                // "fabric" is not a valid bStats platform; the service is registered under
                // "server-implementation" (the platform for server-side mods).
                this.metrics.endpoint = "https://bstats.org/api/v2/data/server-implementation";
                endpointMigrated = true;
                corrected = true;
            }
            if (this.updateCheck.slug == null || this.updateCheck.slug.isBlank()) {
                this.updateCheck.slug = "coreprotect-fabric";
                corrected = true;
            }
            if (this.updateCheck.intervalHours < 1) {
                this.updateCheck.intervalHours = 12;
                corrected = true;
            }
            if (this.databaseFile == null || this.databaseFile.isBlank()) {
                this.databaseFile = "coreprotect.db";
                corrected = true;
            }
            if (previousDatabaseFile != null && !previousDatabaseFile.isBlank()
                    && !previousDatabaseFile.equals(this.databaseFile)) {
                // The path is resolved once in DatabaseManager#open: saying nothing made
                // /co reload look like it had moved the database when nothing happened.
                restartRequired.add("database.file");
                CoreProtectFabric.LOGGER.warn("[CoreProtect] database.file changed to '{}' - restart"
                        + " the server for it to take effect (the database is opened at startup).",
                        this.databaseFile);
            }
            // Retention runs once at startup and bStats is built there as well, so a change to
            // either only takes effect after a restart (L4-07).
            if (previousRetentionEnabled != this.dataRetention.enabled
                    || previousRetentionDays != this.dataRetention.maxDays) {
                restartRequired.add("dataRetention");
            }
            if (previousMetricsEnabled != this.metrics.enabled) restartRequired.add("metrics.enabled");
            if (corrected) configCorrected = true;
        } catch (Exception e) {
            // The old message claimed "using defaults" while every assignment happens after
            // fromJson: on a read/parse error the PREVIOUS values stayed in place, so the log
            // described the opposite of what happened (L4-11).
            lastLoadFailed = true;
            lastLoadError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            CoreProtectFabric.LOGGER.error("[CoreProtect] Failed to read {} - keeping the previously"
                    + " loaded configuration: {}", p, lastLoadError, e);
        }
        if (endpointMigrated || configCorrected) {
            endpointMigrated = false;
            configCorrected = false;
            save(); // persist corrections (bStats endpoint fix, clamped values) back to disk
        }
    }

    /** True when the last {@link #load()} could not read the file; the previous values were kept. */
    public boolean lastLoadFailed() {
        return lastLoadFailed;
    }

    /** Message of the last load failure, or {@code null} when the last load succeeded. */
    public String lastLoadError() {
        return lastLoadError;
    }

    /** Settings changed by the last {@link #load()} that only take effect after a server restart. */
    public java.util.List<String> restartRequired() {
        return new java.util.ArrayList<>(restartRequired);
    }

    /**
     * True when a command may be written to the command log (F-9.1).
     *
     * <p>{@code logging.commandBlacklist} lists the commands whose ARGUMENTS are credentials
     * ({@code /login <password>}). Matching is case-insensitive and ignores surrounding
     * whitespace and the leading slash, so {@code "/Login hunter2"}, {@code "login hunter2"}
     * and {@code "  /LOGIN hunter2"} are all rejected. Only the command label is compared, so
     * one entry covers every argument form.
     */
    public boolean shouldLogCommand(String rawCommand) {
        if (rawCommand == null) return true;
        String command = normalizeCommand(rawCommand);
        if (command.isEmpty()) return true;
        Logging logs = this.logging;
        if (logs == null || logs.commandBlacklist == null) return true;
        String label = commandLabel(command);
        if (label.isEmpty()) return true;
        for (String entry : logs.commandBlacklist) {
            String candidate = normalizeCommand(entry);
            if (!candidate.isEmpty() && commandLabel(candidate).equals(label)) return false;
        }
        return true;
    }

    /** Strips surrounding whitespace and every leading slash ("/login x" and "login x" agree). */
    private static String normalizeCommand(String command) {
        String trimmed = command == null ? "" : command.trim();
        while (trimmed.startsWith("/")) trimmed = trimmed.substring(1).trim();
        return trimmed;
    }

    /** The command name: everything before the first whitespace ("co purge t:1d" -> "co"). */
    private static String commandLabel(String command) {
        for (int i = 0; i < command.length(); i++) {
            if (Character.isWhitespace(command.charAt(i))) {
                return command.substring(0, i).toLowerCase(java.util.Locale.ROOT);
            }
        }
        return command.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Logs config keys the mod does not know. Gson silently ignores unknown fields, so a typo
     * ("permission_groups", "lookupLevel" at the top level) used to disable a whole section
     * without a single hint (L4-11).
     */
    private static void warnUnknownKeys(com.google.gson.JsonElement element, Class<?> type, String prefix) {
        if (element == null || !element.isJsonObject() || type == null) return;
        java.util.Set<String> known = new java.util.HashSet<>();
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
            if (Modifier.isTransient(field.getModifiers())) continue;
            known.add(field.getName());
        }
        for (java.util.Map.Entry<String, com.google.gson.JsonElement> entry
                : element.getAsJsonObject().entrySet()) {
            String key = entry.getKey();
            if (!known.contains(key)) {
                CoreProtectFabric.LOGGER.warn("[CoreProtect] Unknown config key '{}{}' is ignored"
                        + " (check the spelling).", prefix, key);
                continue;
            }
            try {
                Field field = type.getDeclaredField(key);
                // Collections and maps hold user data, not schema: the permission group names
                // are arbitrary keys and must not be reported as unknown settings.
                if (java.util.Map.class.isAssignableFrom(field.getType())
                        || java.util.Collection.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                warnUnknownKeys(entry.getValue(), field.getType(), prefix + key + ".");
            } catch (NoSuchFieldException ignored) {
            }
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
