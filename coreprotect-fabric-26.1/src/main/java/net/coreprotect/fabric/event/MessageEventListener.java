package net.coreprotect.fabric.event;

import net.coreprotect.fabric.CoreProtectFabric;
import net.coreprotect.fabric.util.TimeUtil;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;

/** Logs player chat messages. */
public final class MessageEventListener {

    private MessageEventListener() {
    }

    public static void register() {
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            CoreProtectFabric mod = CoreProtectFabric.instance();
            if (mod == null || !mod.config().logging.chat) return;
            mod.database().insertChatAsync(
                    TimeUtil.now(),
                    sender.getGameProfile().name(),
                    message.signedContent());
        });
    }
}
