package net.coreprotect.fabric.event;

import net.coreprotect.fabric.CoreProtectFabric;
import net.coreprotect.fabric.util.TimeUtil;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;

/**
 * Logs player chat messages.
 *
 * <p>Vanilla sends at most one chat message per tick, but the server accepts whatever the client
 * puts on the wire (L3-08): a modified client could push messages as fast as its connection
 * allows and fill the bounded write queue, which then starts dropping audit rows. Chat therefore
 * has its own budget ({@code LogKind.CHAT}), the text is flattened and truncated
 * ({@code sanitizeText}), and every dropped message is counted so {@code /co status} shows it
 * (L3-09).</p>
 */
public final class MessageEventListener {

    private MessageEventListener() {
    }

    public static void register() {
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            CoreProtectFabric mod = CoreProtectFabric.instance();
            if (mod == null || !mod.config().logging.chat) return;
            if (!CoreProtectFabric.allowPacketLog(sender.getUuid(), CoreProtectFabric.LogKind.CHAT)) return;
            mod.database().insertChatAsync(
                    TimeUtil.now(),
                    sender.getGameProfile().name(),
                    sender.getEntityWorld().getRegistryKey().getValue().toString(),
                    CoreProtectFabric.sanitizeText(message.getContent().getString()));
        });
    }
}
