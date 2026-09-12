package net.coreprotect.fabric.util;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;

public final class BlockStateUtil {
    private static final Gson GSON = new Gson();
    private static final TypeToken<List<String>> STRING_LIST = new TypeToken<>() {
    };

    private BlockStateUtil() {
    }

    public static String stringify(BlockState state) {
        try {
            return BlockStateParser.serialize(state);
        } catch (Exception e) {
            return state.getBlock().toString();
        }
    }

    public static BlockState parse(MinecraftServer server, String str) {
        if (str == null || str.isEmpty()) return Blocks.AIR.defaultBlockState();
        try {
            BlockStateParser.BlockResult result = BlockStateParser.parseForBlock(
                    server.registryAccess().lookupOrThrow(Registries.BLOCK), str, true);
            return result.blockState();
        } catch (Exception e) {
            return Blocks.AIR.defaultBlockState();
        }
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
    public static void applySignText(ServerLevel world, BlockPos pos, String meta) {
        if (meta == null || meta.isBlank()) return;
        try {
            List<String> lines = signLines(meta);
            boolean front = !signIsBack(meta);
            BlockEntity be = world.getBlockEntity(pos);
            if (be instanceof SignBlockEntity sign && lines != null) {
                SignText text = front ? sign.getFrontText() : sign.getBackText();
                for (int i = 0; i < 4 && i < lines.size(); i++) {
                    text = text.setMessage(i, Component.literal(lines.get(i)));
                }
                sign.setText(text, front);
                sign.setChanged();
            }
        } catch (Exception ignored) {
        }
    }
}
