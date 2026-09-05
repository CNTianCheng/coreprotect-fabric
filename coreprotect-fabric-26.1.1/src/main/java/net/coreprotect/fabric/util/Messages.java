package net.coreprotect.fabric.util;

import net.coreprotect.fabric.CoreProtectFabric;
import net.coreprotect.fabric.i18n.Translator;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

/**
 * Chat message helpers. All command feedback goes through here so it can be
 * translated with the receiving player's language.
 */
public final class Messages {
    private static final MutableComponent PREFIX =
            Component.literal("[CoreProtect] ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);

    private Messages() {
    }

    public static String translate(CommandSourceStack source, String key, Object... args) {
        Translator t = CoreProtectFabric.instance().translator();
        ServerPlayer player = source.getPlayer();
        return player != null ? t.get(player, key, args) : t.get(key, args);
    }

    /** Prefix + body, default color. */
    public static void cmd(CommandSourceStack source, String key, Object... args) {
        send(source, PREFIX.copy().append(Component.literal(translate(source, key, args)).withStyle(ChatFormatting.GRAY)));
    }

    /** Prefix + red body. */
    public static void error(CommandSourceStack source, String key, Object... args) {
        send(source, PREFIX.copy().append(Component.literal(translate(source, key, args)).withStyle(ChatFormatting.RED)));
    }

    /** Prefix + aqua body. */
    public static void header(CommandSourceStack source, String key, Object... args) {
        send(source, PREFIX.copy().append(Component.literal(translate(source, key, args)).withStyle(ChatFormatting.AQUA)));
    }

    /** Plain translated line, no prefix. */
    public static void plain(CommandSourceStack source, String key, Object... args) {
        send(source, Component.literal(translate(source, key, args)).withStyle(ChatFormatting.GRAY));
    }

    public static void send(CommandSourceStack source, Component text) {
        source.sendSuccess(() -> text, false);
    }
}
