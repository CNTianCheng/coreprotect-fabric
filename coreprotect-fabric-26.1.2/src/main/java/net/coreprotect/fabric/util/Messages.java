package net.coreprotect.fabric.util;

import net.coreprotect.fabric.CoreProtectFabric;
import net.coreprotect.fabric.i18n.Translator;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

/**
 * Chat message helpers, styled after the CoreProtect plugin (v22+):
 * generic messages use the {@code <dark aqua>CoreProtect <white>- </white>text} prefix,
 * headers are {@code ----- <dark aqua>Title</dark aqua> -----}, and status lines use
 * dark-aqua labels with white values.
 */
public final class Messages {
    private static final MutableComponent PREFIX =
            Component.literal("CoreProtect ").withStyle(ChatFormatting.DARK_AQUA)
                    .append(Component.literal("- ").withStyle(ChatFormatting.WHITE));

    private Messages() {
    }

    public static String translate(CommandSourceStack source, String key, Object... args) {
        Translator t = CoreProtectFabric.instance().translator();
        ServerPlayer player = source.getPlayer();
        return player != null ? t.get(player, key, args) : t.get(key, args);
    }

    /** {@code <dark aqua>CoreProtect <white>- </white>text} — the plugin's generic message style. */
    public static void cmd(CommandSourceStack source, String key, Object... args) {
        send(source, PREFIX.copy().append(Component.literal(translate(source, key, args)).withStyle(ChatFormatting.WHITE)));
    }

    /** Errors use the same white prefix as the plugin. */
    public static void error(CommandSourceStack source, String key, Object... args) {
        cmd(source, key, args);
    }

    /** Completion messages (rollback/restore/purge/undo/reload) use the same white prefix as the plugin. */
    public static void success(CommandSourceStack source, String key, Object... args) {
        cmd(source, key, args);
    }

    /** {@code ----- <dark aqua title> -----}, as in the plugin's headers. */
    public static void title(CommandSourceStack source, String key, Object... args) {
        send(source, Component.literal("----- ").withStyle(ChatFormatting.WHITE)
                .append(Component.literal(translate(source, key, args)).withStyle(ChatFormatting.DARK_AQUA))
                .append(Component.literal(" -----").withStyle(ChatFormatting.WHITE)));
    }

    /**
     * Plugin-style header with coordinates: {@code ----- <dark aqua>CoreProtect</dark aqua> ----- <gray>coords</gray>}.
     */
    public static void coreHeader(CommandSourceStack source, String coordsKey, Object... args) {
        send(source, Component.literal("----- ").withStyle(ChatFormatting.WHITE)
                .append(Component.literal("CoreProtect").withStyle(ChatFormatting.DARK_AQUA))
                .append(Component.literal(" ----- ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(translate(source, coordsKey, args)).withStyle(ChatFormatting.GRAY)));
    }

    /**
     * Plugin-style status line: the label before the first colon (':', '：' or '-') is
     * dark aqua, the value after it is white.
     */
    public static void statusLine(CommandSourceStack source, String key, Object... args) {
        String msg = translate(source, key, args);
        int idx = firstColon(msg);
        if (idx < 0) {
            send(source, PREFIX.copy().append(Component.literal(msg).withStyle(ChatFormatting.WHITE)));
            return;
        }
        send(source, PREFIX.copy()
                .append(Component.literal(msg.substring(0, idx + 1)).withStyle(ChatFormatting.DARK_AQUA))
                .append(Component.literal(msg.substring(idx + 1)).withStyle(ChatFormatting.WHITE)));
    }

    /** Plugin-style help line: dark-aqua command, white description (split at the first " - "). */
    public static void helpLine(CommandSourceStack source, String key, Object... args) {
        String msg = translate(source, key, args);
        int idx = msg.indexOf(" - ");
        if (idx < 0) {
            send(source, PREFIX.copy().append(Component.literal(msg).withStyle(ChatFormatting.WHITE)));
            return;
        }
        send(source, PREFIX.copy()
                .append(Component.literal(msg.substring(0, idx)).withStyle(ChatFormatting.DARK_AQUA))
                .append(Component.literal(" - ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(msg.substring(idx + 3)).withStyle(ChatFormatting.WHITE)));
    }

    /** Plain white line, no prefix. */
    public static void plain(CommandSourceStack source, String key, Object... args) {
        send(source, Component.literal(translate(source, key, args)).withStyle(ChatFormatting.WHITE));
    }

    /**
     * Clickable footer line: clicking the text runs {@code clickCommand}
     * (e.g. the next lookup page) as a chat command.
     */
    public static void footer(CommandSourceStack source, String clickCommand, String key, Object... args) {
        MutableComponent text = Component.literal(translate(source, key, args)).withStyle(ChatFormatting.WHITE)
                .withStyle(style -> style
                        .withClickEvent(new ClickEvent.RunCommand(clickCommand))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal(clickCommand))));
        send(source, PREFIX.copy().append(text));
    }

    public static void send(CommandSourceStack source, Component text) {
        source.sendSuccess(() -> text, false);
    }

    private static int firstColon(String s) {
        int ascii = s.indexOf(':');
        int full = s.indexOf('：');
        if (ascii < 0) return full;
        if (full < 0) return ascii;
        return Math.min(ascii, full);
    }
}
