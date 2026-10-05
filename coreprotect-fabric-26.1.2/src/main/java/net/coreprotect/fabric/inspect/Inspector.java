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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.Container;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

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

    /** Enables inspection (idempotent). */
    public void enable(ServerPlayer player) {
        active.add(player.getUUID());
    }

    /** Disables inspection (idempotent). */
    public void disable(ServerPlayer player) {
        active.remove(player.getUUID());
    }

    /** The three history lists of one position, fetched together on the read pool. */
    private record History(List<DatabaseManager.BlockLog> blocks,
                           List<DatabaseManager.ContainerLog> containers,
                           List<DatabaseManager.SignLog> signs) {
    }

    public void showBlockHistory(ServerPlayer player, Level world, BlockPos pos) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null) return;
        CommandSourceStack source = player.createCommandSourceStack();
        String wid = world.dimension().identifier().toString();
        int lines = mod.config().lookup.inspectLines;
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        // World access stays on the server thread, before the read hop.
        BlockState current = world.getBlockState(pos);
        String currentName = BlockStateUtil.displayName(BlockStateUtil.stringify(current));
        // The three queries run on the read pool; the messages below are sent from the
        // callback, which DatabaseManager re-schedules onto the server thread.
        mod.database().submitRead(() -> new History(
                mod.database().queryBlockHistory(wid, x, y, z, lines),
                mod.database().queryContainerHistory(wid, x, y, z, lines),
                mod.database().querySignHistory(wid, x, y, z, lines)), history -> {
            List<DatabaseManager.BlockLog> blocks = history == null ? null : history.blocks();
            List<DatabaseManager.ContainerLog> containers = history == null ? null : history.containers();
            List<DatabaseManager.SignLog> signs = history == null ? null : history.signs();
            Messages.coreHeader(source, "coreprotect.inspect.block.coords",
                    x, y, z, currentName);
            if (blocks == null || blocks.isEmpty()) {
                Messages.cmd(source, "coreprotect.inspect.block.empty");
            } else {
                for (Component row : LookupService.formatBlockRows(source, blocks)) {
                    Messages.send(source, row);
                }
            }
            if (containers != null && !containers.isEmpty()) {
                Messages.title(source, "coreprotect.inspect.container.header", x, y, z);
                for (Component row : LookupService.formatContainerRows(source, containers)) {
                    Messages.send(source, row);
                }
            }
            if (signs != null && !signs.isEmpty()) {
                Messages.title(source, "coreprotect.inspect.sign.header", x, y, z);
                for (Component row : LookupService.formatSignRows(source, signs)) {
                    Messages.send(source, row);
                }
            }
        });
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
