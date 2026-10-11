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

    /** Serializes a sign's front lines to JSON, or returns {@code null} if not a sign. */
    public static String signTextToJson(BlockEntity be) {
        if (!(be instanceof SignBlockEntity sign)) return null;
        List<String> lines = new ArrayList<>();
        boolean any = false;
        for (int i = 0; i < 4; i++) {
            String line = sign.getFrontText().getMessage(i, false).getString();
            lines.add(line);
            if (!line.isBlank()) any = true;
        }
        return any ? GSON.toJson(lines) : null;
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
     * Restores the sign face stored in a JSON meta string and returns the text that stood on that
     * face before, in the same JSON form, so the change can be undone. Returns {@code null} when
     * the stored value is unusable or the position does not hold a sign.
     */
    public static String replaceSignText(ServerWorld world, BlockPos pos, String meta) {
        if (meta == null || meta.isBlank()) return null;
        try {
            List<String> lines = signLines(meta);
            if (lines == null) return null;
            BlockEntity be = world.getBlockEntity(pos);
            if (!(be instanceof SignBlockEntity sign)) return null;
            boolean front = !signIsBack(meta);
            SignText text = front ? sign.getFrontText() : sign.getBackText();
            String previous = signTextToJson(text, front);
            for (int i = 0; i < 4 && i < lines.size(); i++) {
                String line = lines.get(i);
                text = text.withMessage(i, Text.literal(line == null ? "" : line));
            }
            sign.setText(text, front);
            sign.markDirty();
            return previous;
        } catch (Exception e) {
            return null;
        }
    }

    /** Serializes one face of a sign text, with the side marker used by the sign logs. */
    private static String signTextToJson(SignText text, boolean front) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            lines.add(text.getMessage(i, false).getString());
        }
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("side", front ? "front" : "back");
        payload.put("lines", lines);
        return GSON.toJson(payload);
    }
}
