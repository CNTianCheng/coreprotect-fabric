package net.coreprotect.fabric.util;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;

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

    /** Restores sign front text from a JSON meta string. */
    public static void applySignText(ServerLevel world, BlockPos pos, String meta) {
        if (meta == null || meta.isBlank()) return;
        try {
            List<String> lines = GSON.fromJson(meta, STRING_LIST.getType());
            BlockEntity be = world.getBlockEntity(pos);
            if (be instanceof SignBlockEntity sign && lines != null) {
                SignText text = sign.getFrontText();
                for (int i = 0; i < 4 && i < lines.size(); i++) {
                    text = text.setMessage(i, Component.literal(lines.get(i)));
                }
                sign.setText(text, true);
                sign.setChanged();
            }
        } catch (Exception ignored) {
        }
    }
}
