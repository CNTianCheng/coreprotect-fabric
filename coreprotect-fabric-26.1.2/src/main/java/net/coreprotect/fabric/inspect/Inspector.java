package net.coreprotect.fabric.inspect;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.coreprotect.fabric.CoreProtectFabric;
import net.coreprotect.fabric.command.LookupService;
import net.coreprotect.fabric.database.Criteria;
import net.coreprotect.fabric.database.DatabaseManager;
import net.coreprotect.fabric.util.BlockStateUtil;
import net.coreprotect.fabric.util.Messages;
import net.coreprotect.fabric.util.TimeUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Inspection mode (CoreProtect-style): left-click shows the block history,
 * right-click shows container transactions (or the adjacent block's history),
 * and placing a block runs a lookup for that block type.
 */
public final class Inspector {
    private final Set<UUID> active = ConcurrentHashMap.newKeySet();

    public boolean isInspecting(ServerPlayer player) {
        return active.contains(player.getUUID());
    }

    /** Toggles inspection for the player; returns the new state. */
    public boolean toggle(ServerPlayer player) {
        if (!active.add(player.getUUID())) {
            active.remove(player.getUUID());
            return false;
        }
        return true;
    }

    public void showBlockHistory(ServerPlayer player, Level world, BlockPos pos) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null) return;
        CommandSourceStack source = player.createCommandSourceStack();
        String wid = world.dimension().identifier().toString();
        int lines = mod.config().lookup.inspectLines;
        List<DatabaseManager.BlockLog> blocks = mod.database().queryBlockHistory(wid, pos.getX(), pos.getY(), pos.getZ(), lines);
        List<DatabaseManager.ContainerLog> containers =
                mod.database().queryContainerHistory(wid, pos.getX(), pos.getY(), pos.getZ(), lines);
        BlockState current = world.getBlockState(pos);
        Messages.header(source, "coreprotect.inspect.block.header",
                pos.getX(), pos.getY(), pos.getZ(),
                BlockStateUtil.displayName(BlockStateUtil.stringify(current)));
        if (blocks == null || blocks.isEmpty()) {
            Messages.plain(source, "coreprotect.inspect.block.empty");
        } else {
            for (String row : LookupService.formatBlockRows(source, blocks, 1)) {
                Messages.send(source, Component.literal(row).withStyle(ChatFormatting.GRAY));
            }
        }
        if (containers != null && !containers.isEmpty()) {
            Messages.header(source, "coreprotect.inspect.container.header", pos.getX(), pos.getY(), pos.getZ());
            for (String row : LookupService.formatContainerRows(source, containers, 1)) {
                Messages.send(source, Component.literal(row).withStyle(ChatFormatting.GRAY));
            }
        }
    }

    public void showAdjacentHistory(ServerPlayer player, Level world, BlockHitResult hit) {
        BlockPos clicked = hit.getBlockPos();
        BlockEntity be = world.getBlockEntity(clicked);
        BlockPos target = be instanceof Container ? clicked : clicked.relative(hit.getDirection());
        showBlockHistory(player, world, target);
    }

    public void lookupForBlock(ServerPlayer player, Block block) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null) return;
        Criteria c = new Criteria();
        c.block = BuiltInRegistries.BLOCK.getKey(block).toString();
        c.time = TimeUtil.now() - mod.config().lookup.defaultTimeSeconds;
        c.action = "block";
        c.page = 1;
        LookupService.run(player.createCommandSourceStack(), c);
    }
}
