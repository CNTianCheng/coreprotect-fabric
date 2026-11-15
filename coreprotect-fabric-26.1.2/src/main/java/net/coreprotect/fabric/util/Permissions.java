package net.coreprotect.fabric.util;

import java.util.Set;

import net.coreprotect.fabric.CoreProtectFabric;
import net.coreprotect.fabric.config.CoreProtectConfig;
import net.minecraft.server.permissions.PermissionCheck;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

/**
 * Permission checks: console/server is always allowed; players are checked
 * against their OP level (lookup/admin tier) first, then against the
 * CoreProtect-style permission groups from the config.
 */
public final class Permissions {
    public static final String NODE_HELP = "help";
    public static final String NODE_STATUS = "status";
    public static final String NODE_INSPECT = "inspect";
    public static final String NODE_LOOKUP = "lookup";
    public static final String NODE_ONLINE = "online";
    public static final String NODE_LANGUAGE = "language";
    /** Reading {@code a:#chat} / {@code a:chat} rows (they contain raw commands, incl. passwords). */
    public static final String NODE_CHAT = "chat";
    /** Reading {@code a:#command} / {@code a:command} rows (server console commands). */
    public static final String NODE_COMMAND = "command";
    public static final String NODE_ROLLBACK = "rollback";
    public static final String NODE_RESTORE = "restore";
    public static final String NODE_UNDO = "undo";
    public static final String NODE_PURGE = "purge";
    public static final String NODE_RELOAD = "reload";
    public static final String NODE_DEBUG = "debug";
    /**
     * Not a command node: gates the operational detail of {@code /co status} (database path,
     * logging switches, write statistics). Granted by the admin level.
     */
    public static final String NODE_ADMIN = "admin";

    /** Node prefix documented in the README; both spellings are accepted. */
    private static final String NODE_PREFIX = "coreprotect.";

    /** Nodes that every player may use: they only print help text / change your own language. */
    private static final Set<String> PUBLIC_NODES = Set.of(NODE_HELP, NODE_LANGUAGE);

    private static final Set<String> ADMIN_NODES = Set.of(
            NODE_ROLLBACK, NODE_RESTORE, NODE_UNDO, NODE_PURGE, NODE_RELOAD, NODE_DEBUG);

    private Permissions() {
    }

    /**
     * Strips the optional {@code coreprotect.} prefix and lower-cases a permission node, so
     * that {@code coreprotect.help}, {@code CoreProtect.help} and {@code help} all name the
     * same node. The README documents the prefixed spelling while the code passes bare names.
     */
    public static String normalize(String node) {
        if (node == null) return "";
        String trimmed = node.trim();
        if (trimmed.regionMatches(true, 0, NODE_PREFIX, 0, NODE_PREFIX.length())) {
            trimmed = trimmed.substring(NODE_PREFIX.length());
        }
        return trimmed.toLowerCase();
    }

    /** Lookup node required to read the given lookup action ({@code a:#chat} needs more than blocks). */
    public static String lookupNodeFor(String action) {
        if (action == null) return NODE_LOOKUP;
        String normalized = action.trim().toLowerCase();
        if (normalized.startsWith("#") || normalized.startsWith("+") || normalized.startsWith("-")) {
            normalized = normalized.substring(1);
        }
        return switch (normalized) {
            case "chat" -> NODE_CHAT;
            case "command" -> NODE_COMMAND;
            default -> NODE_LOOKUP;
        };
    }

    /** OP level the config requires for a node. */
    private static int requiredLevel(String node, CoreProtectConfig.Permissions p) {
        String normalized = normalize(node);
        if (PUBLIC_NODES.contains(normalized)) return 0;
        if (ADMIN_NODES.contains(normalized)) return p.adminLevel;
        return switch (normalized) {
            case NODE_CHAT -> p.chatLevel;
            case NODE_COMMAND -> p.commandLevel;
            case NODE_ADMIN -> p.adminLevel;
            default -> p.lookupLevel;
        };
    }

    /**
     * True when the command source satisfies the given vanilla permission level
     * (1.21.9+ permission API). Console/RCON hold level 4 and pass; a command block only
     * holds the level of whoever placed it, so it is checked like any other source.
     */
    public static boolean hasLevel(CommandSourceStack source, int level) {
        if (source == null) return false;
        return levelCheck(level).check(source.permissions());
    }

    /**
     * Maps a vanilla OP level (0-4) to the corresponding permission check. Anything outside
     * 0..4 fails closed (owners only) - {@code adminLevel: 5} used to mean "everybody".
     */
    public static PermissionCheck levelCheck(int level) {
        return switch (level) {
            case 4 -> Commands.LEVEL_OWNERS;
            case 3 -> Commands.LEVEL_ADMINS;
            case 2 -> Commands.LEVEL_GAMEMASTERS;
            case 1 -> Commands.LEVEL_MODERATORS;
            case 0 -> Commands.LEVEL_ALL;
            default -> Commands.LEVEL_OWNERS;
        };
    }

    public static boolean has(CommandSourceStack source, String node) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null || source == null || node == null) return false;
        String wanted = normalize(node);

        CoreProtectConfig.Permissions p = mod.config().permissions;
        int required = requiredLevel(wanted, p);

        ServerPlayer player = source.getPlayer();
        if (player == null) {
            // Console and RCON hold level 4 and still pass, but a command block is only as
            // privileged as the player who placed it: a GAMEMASTER could otherwise place one
            // and run /co purge or /co rollback through it.
            return levelCheck(required).check(source.permissions());
        }
        if (levelCheck(required).check(source.permissions())) return true;

        CoreProtectConfig.PermissionGroups pg = mod.config().permissionGroups;
        if (pg == null || !pg.enabled || pg.groups == null) return false;
        String uuid = player.getStringUUID();
        String name = player.getGameProfile().name();
        // Player names are not identities on an offline-mode server: anybody can log in with a
        // name that is listed in the config and inherit that group (default admin = "all").
        boolean onlineMode = mod.server() != null && mod.server().usesAuthentication();
        for (CoreProtectConfig.PermissionGroups.Group group : pg.groups.values()) {
            if (group == null || group.players == null || group.permissions == null) continue;
            boolean member = false;
            for (String entry : group.players) {
                if (entry == null || entry.isBlank()) continue;
                if (sameUuid(entry, uuid) || (onlineMode && name != null && name.equalsIgnoreCase(entry))) {
                    member = true;
                    break;
                }
            }
            if (!member) continue;
            for (String entry : group.permissions) {
                // Both spellings are accepted: the README documents "coreprotect.help",
                // earlier configs used the bare "help", and "all" grants everything.
                String granted = normalize(entry);
                if (granted.isEmpty()) continue;
                if (granted.equals("all") || granted.equals(wanted)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** UUID comparison that tolerates the dashed and undashed spellings of the same id. */
    private static boolean sameUuid(String configured, String uuid) {
        if (configured == null || uuid == null) return false;
        String a = configured.trim().replace("-", "");
        String b = uuid.replace("-", "");
        return a.length() == 32 && a.equalsIgnoreCase(b);
    }

    /** Number of configured permission groups (for /co status). */
    public static int groupCount() {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null || mod.config().permissionGroups == null
                || mod.config().permissionGroups.groups == null) {
            return 0;
        }
        return mod.config().permissionGroups.groups.size();
    }
}
