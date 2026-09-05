package net.coreprotect.fabric.util;

import net.coreprotect.fabric.CoreProtectFabric;
import net.coreprotect.fabric.i18n.Translator;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Chat message helpers. All command feedback goes through here so it can be
 * translated with the receiving player's language.
 */
public final class Messages {
    private static final MutableText PREFIX =
            Text.literal("[CoreProtect] ").formatted(Formatting.GOLD, Formatting.BOLD);

    private Messages() {
    }

    public static String translate(ServerCommandSource source, String key, Object... args) {
        Translator t = CoreProtectFabric.instance().translator();
        ServerPlayerEntity player = source.getPlayer();
        return player != null ? t.get(player, key, args) : t.get(key, args);
    }

    /** Prefix + body, default color. */
    public static void cmd(ServerCommandSource source, String key, Object... args) {
        send(source, PREFIX.copy().append(Text.literal(translate(source, key, args)).formatted(Formatting.GRAY)));
    }

    /** Prefix + red body. */
    public static void error(ServerCommandSource source, String key, Object... args) {
        send(source, PREFIX.copy().append(Text.literal(translate(source, key, args)).formatted(Formatting.RED)));
    }

    /** Prefix + aqua body. */
    public static void header(ServerCommandSource source, String key, Object... args) {
        send(source, PREFIX.copy().append(Text.literal(translate(source, key, args)).formatted(Formatting.AQUA)));
    }

    /** Plain translated line, no prefix. */
    public static void plain(ServerCommandSource source, String key, Object... args) {
        send(source, Text.literal(translate(source, key, args)).formatted(Formatting.GRAY));
    }

    public static void send(ServerCommandSource source, Text text) {
        source.sendFeedback(() -> text, false);
    }
}
