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
    /** SQLite file name, resolved relative to the game directory. */
    public String databaseFile = "coreprotect.db";

    public Logging logging = new Logging();
    public Lookup lookup = new Lookup();
    public Rollback rollback = new Rollback();
    public Permissions permissions = new Permissions();

    public static class Logging {
        public boolean block = true;
        public boolean container = true;
        public boolean entity = true;
        public boolean chat = true;
        public boolean command = true;
        public boolean session = true;
        public boolean natural = true;
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

    private transient Path path;

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
            CoreProtectConfig loaded = GSON.fromJson(json, CoreProtectConfig.class);
            if (loaded == null) return;
            // merge top-level fields, keeping nested defaults for missing sections
            this.language = loaded.language;
            this.databaseFile = loaded.databaseFile;
            if (loaded.logging != null) this.logging = loaded.logging;
            if (loaded.lookup != null) this.lookup = loaded.lookup;
            if (loaded.rollback != null) this.rollback = loaded.rollback;
            if (loaded.permissions != null) this.permissions = loaded.permissions;
            if (this.lookup.maxLines < 1) this.lookup.maxLines = 10;
            if (this.lookup.inspectLines < 1) this.lookup.inspectLines = 8;
            if (this.lookup.defaultTimeSeconds < 0) this.lookup.defaultTimeSeconds = 604800;
            if (this.rollback.maxBlocks < 1) this.rollback.maxBlocks = 5000;
        } catch (Exception e) {
            CoreProtectFabric.LOGGER.error("[CoreProtect] Failed to load config, using defaults", e);
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
