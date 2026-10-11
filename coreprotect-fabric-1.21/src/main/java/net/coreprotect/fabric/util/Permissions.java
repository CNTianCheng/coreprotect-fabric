package net.coreprotect.fabric.util;

import java.util.Set;

import net.coreprotect.fabric.CoreProtectFabric;
import net.coreprotect.fabric.config.CoreProtectConfig;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;

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
    public static final String NODE_ROLLBACK = "rollback";
    public static final String NODE_RESTORE = "restore";
    public static final String NODE_UNDO = "undo";
    public static final String NODE_PURGE = "purge";
    public static final String NODE_RELOAD = "reload";
    public static final String NODE_DEBUG = "debug";

    private static final Set<String> ADMIN_NODES = Set.of(
            NODE_ROLLBACK, NODE_RESTORE, NODE_UNDO, NODE_PURGE, NODE_RELOAD, NODE_DEBUG);

    private Permissions() {
    }

    /**
     * True when the command source satisfies the given vanilla permission level. Console/RCON
     * hold level 4 and pass; a command block only holds the level of whoever placed it, so it
     * is checked like any other source.
     *
     * <p>1.21 has no {@code PermissionCheck} / {@code CommandManager.*_CHECK} (the 1.21.9+
     * permission API); the same test is {@code ServerCommandSource#hasPermissionLevel}.
     */
    public static boolean hasLevel(ServerCommandSource source, int level) {
        if (source == null) return false;
        return source.hasPermissionLevel(levelCheck(level));
    }

    /**
     * Maps a vanilla OP level (0-4) to the level to test. Anything outside 0..4 fails closed
     * (owners only) - {@code adminLevel: 5} used to mean "everybody", and a negative level
     * meant "everybody" as well.
     */
    public static int levelCheck(int level) {
        return (level < 0 || level > 4) ? 4 : level;
    }

    public static boolean has(ServerCommandSource source, String node) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null || source == null) return false;

        CoreProtectConfig.Permissions p = mod.config().permissions;
        int required = ADMIN_NODES.contains(node) ? p.adminLevel : p.lookupLevel;

        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            // Console and RCON hold level 4 and still pass, but a command block is only as
            // privileged as the player who placed it: a GAMEMASTER could otherwise place one
            // and run /co purge or /co rollback through it.
            return hasLevel(source, required);
        }
        if (hasLevel(source, required)) return true;

        CoreProtectConfig.PermissionGroups pg = mod.config().permissionGroups;
        if (pg == null || !pg.enabled || pg.groups == null) return false;
        String uuid = player.getUuidAsString();
        String name = player.getGameProfile().getName();
        // Player names are not identities on an offline-mode server: anybody can log in with a
        // name that is listed in the config and inherit that group (default admin = "all").
        boolean onlineMode = mod.server() != null && mod.server().isOnlineMode();
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
            for (String node2 : group.permissions) {
                if (node2.equalsIgnoreCase("all") || node2.equalsIgnoreCase(node)) {
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
