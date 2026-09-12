package net.coreprotect.fabric.util;

import net.coreprotect.fabric.CoreProtectFabric;
import net.coreprotect.fabric.i18n.Translator;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Chat message helpers, styled after the CoreProtect plugin (v22+):
 * generic messages use the {@code <dark aqua>CoreProtect <white>- </white>text} prefix,
 * headers are {@code ----- <dark aqua>Title</dark aqua> -----}, and status lines use
 * dark-aqua labels with white values.
 */
public final class Messages {
    private static final MutableText PREFIX =
            Text.literal("CoreProtect ").formatted(Formatting.DARK_AQUA)
                    .append(Text.literal("- ").formatted(Formatting.WHITE));

    private Messages() {
    }

    public static String translate(ServerCommandSource source, String key, Object... args) {
        Translator t = CoreProtectFabric.instance().translator();
        ServerPlayerEntity player = source.getPlayer();
        return player != null ? t.get(player, key, args) : t.get(key, args);
    }

    /** {@code <dark aqua>CoreProtect <white>- </white>text} — the plugin's generic message style. */
    public static void cmd(ServerCommandSource source, String key, Object... args) {
        send(source, PREFIX.copy().append(Text.literal(translate(source, key, args)).formatted(Formatting.WHITE)));
    }

    /** Errors use the same white prefix as the plugin. */
    public static void error(ServerCommandSource source, String key, Object... args) {
        cmd(source, key, args);
    }

    /** Completion messages (rollback/restore/purge/undo/reload) use the same white prefix as the plugin. */
    public static void success(ServerCommandSource source, String key, Object... args) {
        cmd(source, key, args);
    }

    /** {@code ----- <dark aqua title> -----}, as in the plugin's headers. */
    public static void title(ServerCommandSource source, String key, Object... args) {
        send(source, Text.literal("----- ").formatted(Formatting.WHITE)
                .append(Text.literal(translate(source, key, args)).formatted(Formatting.DARK_AQUA))
                .append(Text.literal(" -----").formatted(Formatting.WHITE)));
    }

    /**
     * Plugin-style header with coordinates: {@code ----- <dark aqua>CoreProtect</dark aqua> ----- <gray>coords</gray>}.
     */
    public static void coreHeader(ServerCommandSource source, String coordsKey, Object... args) {
        send(source, Text.literal("----- ").formatted(Formatting.WHITE)
                .append(Text.literal("CoreProtect").formatted(Formatting.DARK_AQUA))
                .append(Text.literal(" ----- ").formatted(Formatting.WHITE))
                .append(Text.literal(translate(source, coordsKey, args)).formatted(Formatting.GRAY)));
    }

    /**
     * Plugin-style status line: the label before the first colon (':', '：' or '-') is
     * dark aqua, the value after it is white.
     */
    public static void statusLine(ServerCommandSource source, String key, Object... args) {
        String msg = translate(source, key, args);
        int idx = firstColon(msg);
        if (idx < 0) {
            send(source, PREFIX.copy().append(Text.literal(msg).formatted(Formatting.WHITE)));
            return;
        }
        send(source, PREFIX.copy()
                .append(Text.literal(msg.substring(0, idx + 1)).formatted(Formatting.DARK_AQUA))
                .append(Text.literal(msg.substring(idx + 1)).formatted(Formatting.WHITE)));
    }

    /** Plugin-style help line: dark-aqua command, white description (split at the first " - "). */
    public static void helpLine(ServerCommandSource source, String key, Object... args) {
        String msg = translate(source, key, args);
        int idx = msg.indexOf(" - ");
        if (idx < 0) {
            send(source, PREFIX.copy().append(Text.literal(msg).formatted(Formatting.WHITE)));
            return;
        }
        send(source, PREFIX.copy()
                .append(Text.literal(msg.substring(0, idx)).formatted(Formatting.DARK_AQUA))
                .append(Text.literal(" - ").formatted(Formatting.WHITE))
                .append(Text.literal(msg.substring(idx + 3)).formatted(Formatting.WHITE)));
    }

    /** Plain white line, no prefix. */
    public static void plain(ServerCommandSource source, String key, Object... args) {
        send(source, Text.literal(translate(source, key, args)).formatted(Formatting.WHITE));
    }

    /**
     * Clickable footer line: clicking the text runs {@code clickCommand}
     * (e.g. the next lookup page) as a chat command.
     */
    public static void footer(ServerCommandSource source, String clickCommand, String key, Object... args) {
        MutableText text = Text.literal(translate(source, key, args)).formatted(Formatting.WHITE)
                .styled(style -> style
                        .withClickEvent(new ClickEvent.RunCommand(clickCommand))
                        .withHoverEvent(new HoverEvent.ShowText(Text.literal(clickCommand))));
        send(source, PREFIX.copy().append(text));
    }

    public static void send(ServerCommandSource source, Text text) {
        source.sendFeedback(() -> text, false);
    }

    private static int firstColon(String s) {
        int ascii = s.indexOf(':');
        int full = s.indexOf('：');
        if (ascii < 0) return full;
        if (full < 0) return ascii;
        return Math.min(ascii, full);
    }
}
