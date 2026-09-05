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
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Inspection mode (CoreProtect-style): left-click shows the block history,
 * right-click shows container transactions (or the adjacent block's history),
 * and placing a block runs a lookup for that block type.
 */
public final class Inspector {
    private final Set<UUID> active = ConcurrentHashMap.newKeySet();

    public boolean isInspecting(ServerPlayerEntity player) {
        return active.contains(player.getUuid());
    }

    /** Toggles inspection for the player; returns the new state. */
    public boolean toggle(ServerPlayerEntity player) {
        if (!active.add(player.getUuid())) {
            active.remove(player.getUuid());
            return false;
        }
        return true;
    }

    /** Enables inspection (idempotent). */
    public void enable(ServerPlayerEntity player) {
        active.add(player.getUuid());
    }

    /** Disables inspection (idempotent). */
    public void disable(ServerPlayerEntity player) {
        active.remove(player.getUuid());
    }

    public void showBlockHistory(ServerPlayerEntity player, World world, BlockPos pos) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null) return;
        ServerCommandSource source = player.getCommandSource();
        String wid = world.getRegistryKey().getValue().toString();
        int lines = mod.config().lookup.inspectLines;
        List<DatabaseManager.BlockLog> blocks = mod.database().queryBlockHistory(wid, pos.getX(), pos.getY(), pos.getZ(), lines);
        List<DatabaseManager.ContainerLog> containers =
                mod.database().queryContainerHistory(wid, pos.getX(), pos.getY(), pos.getZ(), lines);
        List<DatabaseManager.SignLog> signs =
                mod.database().querySignHistory(wid, pos.getX(), pos.getY(), pos.getZ(), lines);
        BlockState current = world.getBlockState(pos);
        Messages.coreHeader(source, "coreprotect.inspect.block.coords",
                pos.getX(), pos.getY(), pos.getZ(),
                BlockStateUtil.displayName(BlockStateUtil.stringify(current)));
        if (blocks == null || blocks.isEmpty()) {
            Messages.cmd(source, "coreprotect.inspect.block.empty");
        } else {
            for (Text row : LookupService.formatBlockRows(source, blocks)) {
                Messages.send(source, row);
            }
        }
        if (containers != null && !containers.isEmpty()) {
            Messages.title(source, "coreprotect.inspect.container.header", pos.getX(), pos.getY(), pos.getZ());
            for (Text row : LookupService.formatContainerRows(source, containers)) {
                Messages.send(source, row);
            }
        }
        if (signs != null && !signs.isEmpty()) {
            Messages.title(source, "coreprotect.inspect.sign.header", pos.getX(), pos.getY(), pos.getZ());
            for (Text row : LookupService.formatSignRows(source, signs)) {
                Messages.send(source, row);
            }
        }
    }

    public void showAdjacentHistory(ServerPlayerEntity player, World world, BlockHitResult hit) {
        BlockPos clicked = hit.getBlockPos();
        BlockEntity be = world.getBlockEntity(clicked);
        BlockPos target = be instanceof Inventory ? clicked : clicked.offset(hit.getSide());
        showBlockHistory(player, world, target);
    }

    public void lookupForBlock(ServerPlayerEntity player, Block block) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null) return;
        Criteria c = new Criteria();
        c.block = Registries.BLOCK.getId(block).toString();
        c.time = TimeUtil.now() - mod.config().lookup.defaultTimeSeconds;
        c.action = "block";
        c.page = 1;
        LookupService.run(player.getCommandSource(), c);
    }
}
