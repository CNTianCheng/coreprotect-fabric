package net.coreprotect.fabric.event;

import net.coreprotect.fabric.CoreProtectFabric;
import net.coreprotect.fabric.util.Messages;
import net.coreprotect.fabric.util.Permissions;
import net.coreprotect.fabric.util.TimeUtil;
import net.coreprotect.fabric.util.UpdateChecker;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.network.ServerPlayerEntity;

/** Logs player sessions (join/leave), restores per-player language overrides, and notifies admins of updates. */
public final class SessionEventListener {

    private SessionEventListener() {
    }

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            CoreProtectFabric mod = CoreProtectFabric.instance();
            if (mod == null) return;
            ServerPlayerEntity player = handler.getPlayer();
            String name = player.getGameProfile().getName();
            // D6: the join path must not touch the database - the blocking read it used to do
            // could stall the server thread for as long as the read pool is busy (up to 30 s).
            // The cached value is applied immediately, the stored row is refreshed in the
            // background and picked up by the next join.
            String lang = mod.translator().cachedUserLanguage(name);
            if (lang != null && mod.translator().isAvailable(lang)) {
                mod.translator().setOverride(player.getUuid(), lang);
            }
            mod.database().submitRead(() -> mod.database().userLanguage(name), stored -> {
                mod.translator().cacheUserLanguage(name, stored);
                if (stored == null || !mod.translator().isAvailable(stored)) return;
                ServerPlayerEntity online = server.getPlayerManager().getPlayer(name);
                if (online == null) return; // disconnected again before the read finished
                mod.translator().setOverride(online.getUuid(), stored);
            });
            if (mod.config().logging.session) {
                mod.database().insertSessionAsync(TimeUtil.now(), name,
                        player.getWorld().getRegistryKey().getValue().toString(), "+");
            }
            // New-version notice for admin players (level >= adminLevel).
            // 1.21 test: Entity#hasPermissionLevel, the equivalent of 1.21.11's
            // Permissions.hasLevel(source, level) -- same result for an exact 0..4 level.
            UpdateChecker checker = mod.updateChecker();
            if (checker != null && checker.available()
                    && player.hasPermissionLevel(mod.config().permissions.adminLevel)) {
                server.execute(() -> Messages.cmd(player.getCommandSource(), "coreprotect.update.available",
                        checker.latestVersion(), CoreProtectFabric.MOD_VERSION, checker.latestUrl()));
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            CoreProtectFabric mod = CoreProtectFabric.instance();
            if (mod == null) return;
            ServerPlayerEntity player = handler.getPlayer();
            mod.containerTracker().onDisconnect(player);
            // B7: inspecting and the language override are session state. Leaving them behind kept
            // the player inspecting after a relog (and out of reach of a permission change), and
            // made the two tracking sets grow forever.
            mod.inspector().disable(player);
            mod.translator().setOverride(player.getUuid(), null);
            if (mod.config().logging.session) {
                mod.database().insertSessionAsync(TimeUtil.now(), player.getGameProfile().getName(),
                        player.getWorld().getRegistryKey().getValue().toString(), "-");
            }
        });
    }
}
