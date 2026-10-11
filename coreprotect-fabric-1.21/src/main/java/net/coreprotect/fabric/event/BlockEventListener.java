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
        BlockEntity be = world.getBlockEntity(clicked);

        // E12: not only BlockItems change the world (water/lava buckets, flint and steel,
        // bone meal, hoes, axes, shovels, ...), so the decision is made from the state of the
        // touched positions BEFORE the interaction instead of from the held item, and the
        // record keeps the state that was really replaced instead of a hard-coded "air".
        // E14: comparing the block type afterwards alone is not enough either - right-clicking
        // a chest while holding a block leaves the clicked block unchanged and used to be
        // recorded as a phantom "+stone".
        boolean interacts = be instanceof Inventory && !sp.isSneaking();
        if (!interacts) {
            // Vanilla places into the clicked position when it is replaceable and into the
            // position in front of it otherwise; items such as a hoe, an axe, shears or bone
            // meal act on the clicked position itself, so both are watched.
            BlockPos front = clicked.offset(hit.getSide());
            BlockPos[] positions = front.equals(clicked) ? new BlockPos[]{clicked}
                    : new BlockPos[]{clicked, front};
            BlockState[] before = new BlockState[positions.length];
            boolean touched = false;
            for (int i = 0; i < positions.length; i++) {
                before[i] = world.getBlockState(positions[i]);
                touched |= before[i].isAir() || before[i].isReplaceable();
            }
            if (touched) {
                // World#getServer().execute() queues the task until the current server task
                // (this interaction) is done, so the follow-up sees the applied change.
                world.getServer().execute(() -> deferredPlace(sp, (ServerWorld) world, positions, before));
            }
        }

        if (be instanceof Inventory && !sp.isSneaking()) {
            mod.containerTracker().onPlayerOpened(sp, (ServerWorld) world, clicked);
        }
        return ActionResult.PASS;
    }

    private static void deferredPlace(ServerPlayerEntity player, ServerWorld world, BlockPos[] positions, BlockState[] before) {
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
                        0, TimeUtil.now(), player.getGameProfile().getName(),
                        world.getRegistryKey().getValue().toString(),
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
