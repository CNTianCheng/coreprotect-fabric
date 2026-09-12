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
            String name = player.getGameProfile().name();
            String lang = mod.database().userLanguage(name);
            if (lang != null && mod.translator().isAvailable(lang)) {
                mod.translator().setOverride(player.getUuid(), lang);
            }
            if (mod.config().logging.session) {
                mod.database().insertSessionAsync(TimeUtil.now(), name,
                        player.getEntityWorld().getRegistryKey().getValue().toString(), "+");
            }
            // New-version notice for admin players (level >= adminLevel).
            UpdateChecker checker = mod.updateChecker();
            if (checker != null && checker.available()
                    && Permissions.hasLevel(player.getCommandSource(), mod.config().permissions.adminLevel)) {
                server.execute(() -> Messages.cmd(player.getCommandSource(), "coreprotect.update.available",
                        checker.latestVersion(), CoreProtectFabric.MOD_VERSION, checker.latestUrl()));
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            CoreProtectFabric mod = CoreProtectFabric.instance();
            if (mod == null) return;
            ServerPlayerEntity player = handler.getPlayer();
            mod.containerTracker().onDisconnect(player);
            if (mod.config().logging.session) {
                mod.database().insertSessionAsync(TimeUtil.now(), player.getGameProfile().name(),
                        player.getEntityWorld().getRegistryKey().getValue().toString(), "-");
            }
        });
    }
}
