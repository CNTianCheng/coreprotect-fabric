package net.coreprotect.fabric.event;

import net.coreprotect.fabric.CoreProtectFabric;
import net.coreprotect.fabric.util.Messages;
import net.coreprotect.fabric.util.Permissions;
import net.coreprotect.fabric.util.TimeUtil;
import net.coreprotect.fabric.util.UpdateChecker;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;

/** Logs player sessions (join/leave), restores per-player language overrides, and notifies admins of updates. */
public final class SessionEventListener {

    private SessionEventListener() {
    }

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            CoreProtectFabric mod = CoreProtectFabric.instance();
            if (mod == null) return;
            ServerPlayer player = handler.getPlayer();
            String name = player.getGameProfile().name();
            // D6: the join path must not touch the database - the blocking read it used to do
            // could stall the server thread for as long as the read pool is busy (up to 30 s).
            // The cached value is applied immediately, the stored row is refreshed in the
            // background and picked up by the next join.
            String lang = mod.translator().cachedUserLanguage(name);
            if (lang != null && mod.translator().isAvailable(lang)) {
                mod.translator().setOverride(player.getUUID(), lang);
            }
            mod.database().submitRead(() -> mod.database().userLanguage(name), stored -> {
                mod.translator().cacheUserLanguage(name, stored);
                if (stored == null || !mod.translator().isAvailable(stored)) return;
                ServerPlayer online = server.getPlayerList().getPlayerByName(name);
                if (online == null) return; // disconnected again before the read finished
                mod.translator().setOverride(online.getUUID(), stored);
            });
            if (mod.config().logging.session) {
                mod.database().insertSessionAsync(TimeUtil.now(), name,
                        player.level().dimension().identifier().toString(), "+");
            }
            // New-version notice for admin players (level >= adminLevel).
            UpdateChecker checker = mod.updateChecker();
            if (checker != null && checker.available()
                    && Permissions.hasLevel(player.createCommandSourceStack(), mod.config().permissions.adminLevel)) {
                server.execute(() -> Messages.cmd(player.createCommandSourceStack(), "coreprotect.update.available",
                        checker.latestVersion(), CoreProtectFabric.MOD_VERSION, checker.latestUrl()));
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            CoreProtectFabric mod = CoreProtectFabric.instance();
            if (mod == null) return;
            ServerPlayer player = handler.getPlayer();
            mod.containerTracker().onDisconnect(player);
            // B7: inspecting and the language override are session state. Leaving them behind kept
            // the player inspecting after a relog (and out of reach of a permission change), and
            // made the two tracking sets grow forever.
            mod.inspector().disable(player);
            mod.translator().setOverride(player.getUUID(), null);
            if (mod.config().logging.session) {
                mod.database().insertSessionAsync(TimeUtil.now(), player.getGameProfile().name(),
                        player.level().dimension().identifier().toString(), "-");
            }
        });
    }
}
