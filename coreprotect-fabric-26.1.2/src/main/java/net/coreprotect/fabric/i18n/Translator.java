package net.coreprotect.fabric.i18n;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.coreprotect.fabric.CoreProtectFabric;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server-side translation engine. Language files live in
 * {@code assets/coreprotect/lang/<locale>.json}. The language of a message is
 * resolved per player: /co language override, then the player's client locale,
 * then the configured default, then {@code en_us}.
 */
public final class Translator {
    private static final Gson GSON = new Gson();
    private static final TypeToken<Map<String, String>> MAP_TYPE = new TypeToken<>() {
    };

    private final Map<String, Map<String, String>> languages = new HashMap<>();
    private final Map<UUID, String> overrides = new ConcurrentHashMap<>();
    private final List<String> available = new ArrayList<>();
    private String defaultLanguage = "en_us";

    public void load(String defaultLanguage) {
        this.defaultLanguage = defaultLanguage == null || defaultLanguage.isBlank() ? "en_us" : defaultLanguage;
        languages.clear();
        available.clear();
        Path langDir = FabricLoader.getInstance().getModContainer(CoreProtectFabric.MOD_ID)
                .flatMap(c -> c.findPath("assets/" + CoreProtectFabric.MOD_ID + "/lang"))
                .orElse(null);
        if (langDir == null) {
            CoreProtectFabric.LOGGER.warn("[CoreProtect] Language directory not found; messages will show raw keys.");
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(langDir, "*.json")) {
            for (Path p : stream) {
                String code = p.getFileName().toString().replace(".json", "");
                try {
                    Map<String, String> map = GSON.fromJson(Files.readString(p), MAP_TYPE.getType());
                    if (map != null && !map.isEmpty()) {
                        languages.put(code, map);
                        available.add(code);
                    }
                } catch (IOException e) {
                    CoreProtectFabric.LOGGER.warn("[CoreProtect] Failed to read language file " + p, e);
                }
            }
        } catch (IOException e) {
            CoreProtectFabric.LOGGER.warn("[CoreProtect] Failed to list language files", e);
        }
        available.sort(String::compareTo);
        if (!languages.containsKey("en_us")) {
            CoreProtectFabric.LOGGER.warn("[CoreProtect] Missing en_us language file!");
        }
    }

    public List<String> available() {
        return new ArrayList<>(available);
    }

    public boolean isAvailable(String code) {
        return languages.containsKey(code);
    }

    public String defaultLanguage() {
        return defaultLanguage;
    }

    public void setOverride(UUID uuid, String code) {
        if (code == null) {
            overrides.remove(uuid);
        } else {
            overrides.put(uuid, code);
        }
    }

    public String localeOf(ServerPlayer player) {
        if (player != null) {
            String override = overrides.get(player.getUUID());
            if (override != null) return override;
            try {
                String client = player.clientInformation().language();
                if (client != null && !client.isBlank()) return client;
            } catch (Exception ignored) {
            }
        }
        return defaultLanguage;
    }

    public String get(ServerPlayer player, String key, Object... args) {
        return format(lookup(localeOf(player), key), args);
    }

    public String get(String key, Object... args) {
        return format(lookup(defaultLanguage, key), args);
    }

    private String lookup(String code, String key) {
        for (String candidate : chain(code)) {
            Map<String, String> map = languages.get(candidate);
            if (map != null && map.containsKey(key)) {
                return map.get(key);
            }
        }
        return key;
    }

    private List<String> chain(String code) {
        Set<String> chain = new LinkedHashSet<>();
        if (code != null) {
            chain.add(code);
            int underscore = code.indexOf('_');
            if (underscore > 0) {
                chain.add(code.substring(0, underscore));
            }
        }
        chain.add(defaultLanguage);
        chain.add("en_us");
        return new ArrayList<>(chain);
    }

    private static String format(String pattern, Object... args) {
        if (args == null || args.length == 0) return pattern;
        try {
            // MessageFormat treats ' as a quoting character: an apostrophe in a translation
            // (or a name/message inserted into one) would throw and lose the placeholders,
            // so every quote is escaped first
            return MessageFormat.format(pattern.replace("'", "''"), args);
        } catch (IllegalArgumentException e) {
            return pattern;
        }
    }
}
