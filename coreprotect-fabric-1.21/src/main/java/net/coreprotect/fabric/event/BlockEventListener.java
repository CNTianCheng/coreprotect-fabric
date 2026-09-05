package net.coreprotect.fabric.event;

import net.coreprotect.fabric.CoreProtectFabric;
import net.coreprotect.fabric.database.DatabaseManager;
import net.coreprotect.fabric.util.BlockStateUtil;
import net.coreprotect.fabric.util.TimeUtil;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

public final class BlockEventListener {

    private BlockEventListener() {
    }

    public static void register() {
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            CoreProtectFabric mod = CoreProtectFabric.instance();
            if (mod == null || world.isClient()) return true;
            // Inspection mode: block breaking is cancelled (history is shown by AttackBlockCallback).
            if (player instanceof ServerPlayerEntity sp && mod.inspector().isInspecting(sp)) {
                return false;
            }
            return true;
        });

        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            CoreProtectFabric mod = CoreProtectFabric.instance();
            if (mod == null || world.isClient() || !(world instanceof ServerWorld)) return;
            if (!mod.config().logging.block) return;
            mod.database().insertBlockAsync(new DatabaseManager.BlockLog(
                    0, TimeUtil.now(), player.getGameProfile().getName(),
                    world.getRegistryKey().getValue().toString(),
                    pos.getX(), pos.getY(), pos.getZ(),
                    DatabaseManager.TYPE_BREAK,
                    BlockStateUtil.stringify(state),
                    BlockStateUtil.stringify(Blocks.AIR.getDefaultState()),
                    "-",
                    BlockStateUtil.signTextToJson(blockEntity)));
        });

        UseBlockCallback.EVENT.register(BlockEventListener::onUse);
        AttackBlockCallback.EVENT.register(BlockEventListener::onAttack);
    }

    private static ActionResult onAttack(PlayerEntity player, World world, Hand hand, BlockPos pos, Direction direction) {
        if (world.isClient() || !(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null) return ActionResult.PASS;
        if (mod.inspector().isInspecting(sp)) {
            mod.inspector().showBlockHistory(sp, world, pos);
            return ActionResult.FAIL;
        }
        return ActionResult.PASS;
    }

    private static ActionResult onUse(PlayerEntity player, World world, Hand hand, BlockHitResult hit) {
        if (world.isClient() || !(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;
        if (hand != Hand.MAIN_HAND) return ActionResult.PASS;
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null) return ActionResult.PASS;

        if (mod.inspector().isInspecting(sp)) {
            ItemStack stack = sp.getStackInHand(hand);
            if (world.getBlockState(hit.getBlockPos()).isAir()) {
                // Clicking air: nothing to inspect and no block-type lookup (matches CoreProtect).
                return ActionResult.PASS;
            }
            if (stack.getItem() instanceof BlockItem item) {
                // CoreProtect behavior: cancel the placement and run a lookup for the held block type instead
                mod.inspector().lookupForBlock(sp, item.getBlock());
                return ActionResult.FAIL;
            }
            mod.inspector().showAdjacentHistory(sp, world, hit);
            return ActionResult.FAIL;
        }

        BlockPos clicked = hit.getBlockPos();
        BlockState clickedState = world.getBlockState(clicked);
        BlockEntity be = world.getBlockEntity(clicked);
        ItemStack stack = sp.getStackInHand(hand);

        if (stack.getItem() instanceof BlockItem item) {
            boolean interacts = be instanceof Inventory && !sp.isSneaking();
            if (!interacts) {
                BlockPos candidate = clickedState.isReplaceable() ? clicked : clicked.offset(hit.getSide());
                Block placedBlock = item.getBlock();
                world.getServer().execute(() -> deferredPlace(sp, (ServerWorld) world, candidate, placedBlock));
            }
        }

        if (be instanceof Inventory && !sp.isSneaking()) {
            mod.containerTracker().onPlayerOpened(sp, (ServerWorld) world, clicked);
        }
        return ActionResult.PASS;
    }

    private static void deferredPlace(ServerPlayerEntity player, ServerWorld world, BlockPos candidate, Block placedBlock) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null) return;
        BlockState now = world.getBlockState(candidate);
        if (now.getBlock() != placedBlock) return; // interaction was cancelled, or the item is not a block
        if (mod.config().logging.block) {
            BlockEntity be = world.getBlockEntity(candidate);
            mod.database().insertBlockAsync(new DatabaseManager.BlockLog(
                    0, TimeUtil.now(), player.getGameProfile().getName(),
                    world.getRegistryKey().getValue().toString(),
                    candidate.getX(), candidate.getY(), candidate.getZ(),
                    DatabaseManager.TYPE_PLACE,
                    BlockStateUtil.stringify(Blocks.AIR.getDefaultState()),
                    BlockStateUtil.stringify(now),
                    "+",
                    BlockStateUtil.signTextToJson(be)));
        }
        if (mod.inspector().isInspecting(player)) {
            mod.inspector().lookupForBlock(player, placedBlock);
        }
    }
}
