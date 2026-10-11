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
    /**
     * Language cached per player NAME - the key the database uses - so that the join handler
     * can apply an override without reading the database (D6). Refreshed in the background.
     */
    private final Map<String, String> storedLanguages = new ConcurrentHashMap<>();
    /** Keys already reported as missing, so a broken translation file is logged once (E19). */
    private final Set<String> warnedKeys = ConcurrentHashMap.newKeySet();
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

    /** The language last read for this player name, or {@code null} when there is none (D6). */
    public String cachedUserLanguage(String name) {
        return name == null ? null : storedLanguages.get(name);
    }

    /** Caches the language stored for a player name; {@code null} means "no override" (D6). */
    public void cacheUserLanguage(String name, String code) {
        if (name == null) return;
        if (code == null) {
            storedLanguages.remove(name);
        } else {
            storedLanguages.put(name, code);
        }
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
        // E19: a missing key used to be printed as the key itself with no hint about the cause.
        if (warnedKeys.add(key)) {
            CoreProtectFabric.LOGGER.warn("[CoreProtect] Missing translation key: {}", key);
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
            return MessageFormat.format(escapeQuotes(pattern), args);
        } catch (IllegalArgumentException e) {
            return pattern;
        }
    }

    /**
     * MessageFormat reads a single quote as the start of a quoted section, so an apostrophe in
     * a translation ("player's {0}") would swallow everything up to the next quote - placeholder
     * included - and the message would be printed unchanged. Escaping every quote is what makes
     * such translations work; the refinement here is that a pair the translator already wrote as
     * {@code ''} stays ONE literal quote instead of becoming two.
     *
     * <p>Escaping "only unbalanced quotes" is not sufficient: two apostrophes that are not meant
     * as a pair (e.g. "player's name's {0}") would still open and close a section, and both
     * characters would disappear from the output.</p>
     */
    private static String escapeQuotes(String pattern) {
        if (pattern.indexOf('\'') < 0) return pattern;
        StringBuilder escaped = new StringBuilder(pattern.length() + 8);
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c != '\'') {
                escaped.append(c);
                continue;
            }
            escaped.append("''");
            if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '\'') {
                i++; // an already doubled quote is one literal quote, not two
            }
        }
        return escaped.toString();
    }
}
