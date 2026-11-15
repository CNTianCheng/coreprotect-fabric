package net.coreprotect.fabric.util;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

public final class BlockStateUtil {
    private static final Gson GSON = new Gson();
    private static final TypeToken<List<String>> STRING_LIST = new TypeToken<>() {
    };

    private BlockStateUtil() {
    }

    public static String stringify(BlockState state) {
        if (state == null) return null;
        try {
            return BlockArgumentParser.stringifyBlockState(state);
        } catch (Exception e) {
            // The block's Java toString() is not a state string: storing it would later parse as
            // air and delete whatever stands there, so a state that cannot be written is unset.
            return null;
        }
    }

    /**
     * Parses a stored block state and returns {@code null} when the stored value cannot be used
     * (empty, or written by a block that is no longer installed). Callers have to skip such an
     * entry - treating the {@code null} as air makes a rollback delete the block standing there.
     */
    public static BlockState parseOrNull(MinecraftServer server, String str) {
        if (str == null || str.isEmpty()) return null;
        try {
            BlockArgumentParser.BlockResult result = BlockArgumentParser.block(
                    server.getRegistryManager().getOrThrow(RegistryKeys.BLOCK), str, true);
            return result.blockState();
        } catch (Exception e) {
            return null;
        }
    }

    /** Lenient variant for callers that need a usable state: an unusable value falls back to air. */
    public static BlockState parse(MinecraftServer server, String str) {
        BlockState state = parseOrNull(server, str);
        return state == null ? Blocks.AIR.getDefaultState() : state;
    }

    /** Strips the {@code minecraft:} namespace for display. */
    public static String displayName(String id) {
        if (id == null) return "?";
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    /**
     * Serializes a sign, or returns {@code null} when it carries no text. Both faces are stored: the
     * front as the primary face and the back as {@code other} when it has text. Storing only the front
     * lost the back side of every sign that was placed, edited or broken, so a rollback restored an
     * empty front and silently dropped the back text.
     */
    public static String signTextToJson(BlockEntity be) {
        if (!(be instanceof SignBlockEntity sign)) return null;
        List<String> front = faceLines(sign.getFrontText());
        List<String> back = faceLines(sign.getBackText());
        boolean frontEmpty = isBlank(front);
        boolean backEmpty = isBlank(back);
        if (frontEmpty && backEmpty) return null;
        if (backEmpty) {
            // legacy plain array: the front face, still readable by older code and rows
            return GSON.toJson(front);
        }
        if (frontEmpty) {
            // only the back carries text, so it becomes the primary face and the side marker matches
            return signPayload(false, back, front);
        }
        return signPayload(true, front, back);
    }

    /**
     * Serializes the edited sign face to JSON. The side is part of the payload so a
     * back-face edit is not restored onto the front; the legacy plain-array form is
     * still accepted when reading.
     */
    public static String signLinesToJson(String[] rawLines, boolean front) {
        if (rawLines == null) return null;
        List<String> lines = new ArrayList<>();
        boolean any = false;
        for (String line : rawLines) {
            lines.add(line == null ? "" : line);
            if (line != null && !line.isBlank()) any = true;
        }
        if (!any) return null;
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("side", front ? "front" : "back");
        payload.put("lines", lines);
        return GSON.toJson(payload);
    }

    /** The four stored lines of a sign log (legacy plain arrays count as the front face). */
    public static List<String> signLines(String meta) {
        if (meta == null || meta.isBlank()) return null;
        try {
            com.google.gson.JsonElement parsed = com.google.gson.JsonParser.parseString(meta);
            if (parsed.isJsonArray()) {
                return GSON.fromJson(parsed, STRING_LIST.getType());
            }
            if (parsed.isJsonObject()) {
                com.google.gson.JsonElement lines = parsed.getAsJsonObject().get("lines");
                if (lines != null) return GSON.fromJson(lines, STRING_LIST.getType());
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** True when a sign log stores the back face (legacy logs are the front face). */
    public static boolean signIsBack(String meta) {
        if (meta == null) return false;
        try {
            com.google.gson.JsonElement parsed = com.google.gson.JsonParser.parseString(meta);
            if (parsed.isJsonObject()) {
                com.google.gson.JsonElement side = parsed.getAsJsonObject().get("side");
                return side != null && "back".equals(side.getAsString());
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** Restores the sign face stored in a JSON meta string. */
    public static void applySignText(ServerWorld world, BlockPos pos, String meta) {
        replaceSignText(world, pos, meta);
    }

    /**
     * Restores the sign text stored in a JSON meta string and returns the text of both faces as it was
     * before, in the same JSON form, so the change can be undone. A payload that carries an
     * {@code other} array also restores the opposite face - that is how a sign's back text survives a
     * rollback. Returns {@code null} when the stored value is unusable or the position does not hold a
     * sign.
     */
    public static String replaceSignText(ServerWorld world, BlockPos pos, String meta) {
        if (meta == null || meta.isBlank()) return null;
        try {
            List<String> lines = signLines(meta);
            if (lines == null) return null;
            List<String> other = signLinesOf(meta, "other");
            BlockEntity be = world.getBlockEntity(pos);
            if (!(be instanceof SignBlockEntity sign)) return null;
            boolean front = !signIsBack(meta);
            SignText text = front ? sign.getFrontText() : sign.getBackText();
            SignText otherText = front ? sign.getBackText() : sign.getFrontText();
            // a snapshot of both faces: undoing the change has to restore exactly what stood there
            String previous = signPayload(front, faceLines(text), faceLines(otherText));
            sign.setText(withLines(text, lines), front);
            if (other != null) {
                sign.setText(withLines(otherText, other), !front);
            }
            sign.markDirty();
            return previous;
        } catch (Exception e) {
            return null;
        }
    }

    /** The four lines of one sign face, in the form the log stores them. */
    private static List<String> faceLines(SignText text) {
        List<String> lines = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            lines.add(text.getMessage(i, false).getString());
        }
        return lines;
    }

    /** Applies up to four stored lines to a copy of {@code text}. */
    private static SignText withLines(SignText text, List<String> lines) {
        for (int i = 0; i < 4 && i < lines.size(); i++) {
            String line = lines.get(i);
            text = text.withMessage(i, Text.literal(line == null ? "" : line));
        }
        return text;
    }

    /** Whether every line of a face is empty. */
    private static boolean isBlank(List<String> lines) {
        for (String line : lines) {
            if (line != null && !line.isBlank()) return false;
        }
        return true;
    }

    /** Serializes one face as the primary face, together with the lines of the opposite face. */
    private static String signPayload(boolean primaryIsFront, List<String> primary, List<String> other) {
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("side", primaryIsFront ? "front" : "back");
        payload.put("lines", primary);
        payload.put("other", other);
        return GSON.toJson(payload);
    }

    /** The lines stored under {@code key} of an object payload, or {@code null} when absent. */
    private static List<String> signLinesOf(String meta, String key) {
        if (meta == null || meta.isBlank()) return null;
        try {
            com.google.gson.JsonElement parsed = com.google.gson.JsonParser.parseString(meta);
            if (parsed.isJsonObject()) {
                com.google.gson.JsonElement lines = parsed.getAsJsonObject().get(key);
                if (lines != null) return GSON.fromJson(lines, STRING_LIST.getType());
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
