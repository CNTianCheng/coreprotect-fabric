package net.coreprotect.fabric.event;

import net.coreprotect.fabric.CoreProtectFabric;
import net.coreprotect.fabric.database.DatabaseManager;
import net.coreprotect.fabric.util.BlockStateUtil;
import net.coreprotect.fabric.util.TimeUtil;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.Container;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

public final class BlockEventListener {

    private BlockEventListener() {
    }

    public static void register() {
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            CoreProtectFabric mod = CoreProtectFabric.instance();
            if (mod == null || world.isClientSide()) return true;
            // Inspection mode: block breaking is cancelled (history is shown by AttackBlockCallback).
            if (player instanceof ServerPlayer sp && mod.inspector().isInspecting(sp)) {
                return false;
            }
            return true;
        });

        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            CoreProtectFabric mod = CoreProtectFabric.instance();
            if (mod == null || world.isClientSide() || !(world instanceof ServerLevel)) return;
            if (!mod.config().logging.block) return;
            mod.database().insertBlockAsync(new DatabaseManager.BlockLog(
                    0, TimeUtil.now(), player.getGameProfile().name(),
                    world.dimension().identifier().toString(),
                    pos.getX(), pos.getY(), pos.getZ(),
                    DatabaseManager.TYPE_BREAK,
                    BlockStateUtil.stringify(state),
                    BlockStateUtil.stringify(Blocks.AIR.defaultBlockState()),
                    "-",
                    BlockStateUtil.signTextToJson(blockEntity)));
        });

        UseBlockCallback.EVENT.register(BlockEventListener::onUse);
        AttackBlockCallback.EVENT.register(BlockEventListener::onAttack);
    }

    private static InteractionResult onAttack(Player player, Level world, InteractionHand hand, BlockPos pos, Direction direction) {
        if (world.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null) return InteractionResult.PASS;
        if (mod.inspector().isInspecting(sp)) {
            mod.inspector().showBlockHistory(sp, world, pos);
            return InteractionResult.FAIL;
        }
        return InteractionResult.PASS;
    }

    private static InteractionResult onUse(Player player, Level world, InteractionHand hand, BlockHitResult hit) {
        if (world.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null) return InteractionResult.PASS;

        if (mod.inspector().isInspecting(sp)) {
            ItemStack stack = sp.getItemInHand(hand);
            if (world.getBlockState(hit.getBlockPos()).isAir()) {
                // Clicking air: nothing to inspect and no block-type lookup (matches CoreProtect).
                return InteractionResult.PASS;
            }
            if (stack.getItem() instanceof BlockItem item) {
                // CoreProtect behavior: cancel the placement and run a lookup for the held block type instead
                mod.inspector().lookupForBlock(sp, item.getBlock());
                return InteractionResult.FAIL;
            }
            mod.inspector().showAdjacentHistory(sp, world, hit);
            return InteractionResult.FAIL;
        }

        BlockPos clicked = hit.getBlockPos();
        BlockEntity be = world.getBlockEntity(clicked);

        // E12: not only BlockItems change the world (water/lava buckets, flint and steel,
        // bone meal, hoes, axes, shovels, ...), so the decision is made from the state of the
        // touched positions BEFORE the interaction instead of from the held item, and the
        // record keeps the state that was really replaced instead of a hard-coded "air".
        // E14: comparing the block type afterwards alone is not enough either - right-clicking
        // a chest while holding a block leaves the clicked block unchanged and used to be
        // recorded as a phantom "+stone".
        boolean interacts = be instanceof Container && !sp.isShiftKeyDown();
        if (!interacts) {
            // Vanilla places into the clicked position when it is replaceable and into the
            // position in front of it otherwise; items such as a hoe, an axe, shears or bone
            // meal act on the clicked position itself, so both are watched.
            BlockPos front = clicked.relative(hit.getDirection());
            BlockPos[] positions = front.equals(clicked) ? new BlockPos[]{clicked}
                    : new BlockPos[]{clicked, front};
            BlockState[] before = new BlockState[positions.length];
            boolean touched = false;
            for (int i = 0; i < positions.length; i++) {
                before[i] = world.getBlockState(positions[i]);
                touched |= before[i].isAir() || before[i].canBeReplaced();
            }
            if (touched) {
                // Level#getServer().execute() queues the task until the current server task
                // (this interaction) is done, so the follow-up sees the applied change.
                world.getServer().execute(() -> deferredPlace(sp, (ServerLevel) world, positions, before));
            }
        }

        if (be instanceof Container && !sp.isShiftKeyDown()) {
            mod.containerTracker().onPlayerOpened(sp, (ServerLevel) world, clicked);
        }
        return InteractionResult.PASS;
    }

    private static void deferredPlace(ServerPlayer player, ServerLevel world, BlockPos[] positions, BlockState[] before) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null) return;
        for (int i = 0; i < positions.length; i++) {
            BlockPos pos = positions[i];
            BlockState oldState = before[i];
            BlockState now = world.getBlockState(pos);
            // E14: nothing changed, or only the block state changed (a door opening, a lever
            // flipping, a cauldron filling, ...) - that is not a placement.
            if (now.equals(oldState) || now.getBlock() == oldState.getBlock()) continue;
            Block placed = now.getBlock();
            if (mod.config().logging.block) {
                BlockEntity be = world.getBlockEntity(pos);
                mod.database().insertBlockAsync(new DatabaseManager.BlockLog(
                        0, TimeUtil.now(), player.getGameProfile().name(),
                        world.dimension().identifier().toString(),
                        pos.getX(), pos.getY(), pos.getZ(),
                        DatabaseManager.TYPE_PLACE,
                        BlockStateUtil.stringify(oldState),
                        BlockStateUtil.stringify(now),
                        "+",
                        BlockStateUtil.signTextToJson(be)));
            }
            if (mod.inspector().isInspecting(player)) {
                mod.inspector().lookupForBlock(player, placed);
            }
        }
    }
}
