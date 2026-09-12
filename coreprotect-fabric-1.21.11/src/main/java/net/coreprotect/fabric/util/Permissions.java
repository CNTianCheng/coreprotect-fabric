package net.coreprotect.fabric.util;

import java.util.Set;

import net.coreprotect.fabric.CoreProtectFabric;
import net.coreprotect.fabric.config.CoreProtectConfig;
import net.minecraft.command.permission.PermissionCheck;
import net.minecraft.server.command.CommandManager;
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
     * True when the command source satisfies the given vanilla permission level
     * (1.21.9+ permission API); console and command blocks always pass.
     */
    public static boolean hasLevel(ServerCommandSource source, int level) {
        if (source == null) return false;
        if (source.getPlayer() == null) return true;
        return levelCheck(level).allows(source.getPermissions());
    }

    /** Maps a vanilla OP level (0-4) to the corresponding permission check. */
    public static PermissionCheck levelCheck(int level) {
        return switch (level) {
            case 4 -> CommandManager.OWNERS_CHECK;
            case 3 -> CommandManager.ADMINS_CHECK;
            case 2 -> CommandManager.GAMEMASTERS_CHECK;
            case 1 -> CommandManager.MODERATORS_CHECK;
            default -> CommandManager.ALWAYS_PASS_CHECK;
        };
    }

    public static boolean has(ServerCommandSource source, String node) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null) return false;
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) return true; // console / command blocks / server

        CoreProtectConfig.Permissions p = mod.config().permissions;
        int required = ADMIN_NODES.contains(node) ? p.adminLevel : p.lookupLevel;
        if (levelCheck(required).allows(source.getPermissions())) return true;

        CoreProtectConfig.PermissionGroups pg = mod.config().permissionGroups;
        if (pg == null || !pg.enabled || pg.groups == null) return false;
        String name = player.getGameProfile().name();
        for (CoreProtectConfig.PermissionGroups.Group group : pg.groups.values()) {
            if (group == null || group.players == null || group.permissions == null) continue;
            boolean member = false;
            for (String pName : group.players) {
                if (name.equalsIgnoreCase(pName)) {
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
