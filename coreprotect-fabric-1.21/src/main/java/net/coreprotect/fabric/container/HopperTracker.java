package net.coreprotect.fabric.container;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.coreprotect.fabric.CoreProtectFabric;
import net.coreprotect.fabric.database.DatabaseManager;
import net.coreprotect.fabric.util.TimeUtil;
import net.minecraft.block.BlockState;
import net.minecraft.block.HopperBlock;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

/**
 * CoreProtect-style {@code #hopper} logging: on every hopper tick the hopper's
 * own inventory, the container above it (source) and the container it faces
 * (target) are diffed against the previous snapshot; changes are logged as
 * user {@code #hopper} at the affected container's position.
 */
public final class HopperTracker {
    private final Map<BlockPos, Map<String, Integer>> snapshots = new ConcurrentHashMap<>();

    public void onHopperTick(World world, BlockPos pos, BlockState state, HopperBlockEntity blockEntity) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null || !mod.config().logging.hopper) return;
        if (!(world instanceof ServerWorld sw)) return;
        long time = TimeUtil.now();
        String wid = world.getRegistryKey().getValue().toString();

        // the hopper's own inventory
        diffAndLog(mod, sw, wid, pos, blockEntity, time);
        // source container above the hopper
        Inventory input = HopperBlockEntity.getInventoryAt(world, pos.up());
        if (input != null && !(input instanceof HopperBlockEntity)) {
            diffAndLog(mod, sw, wid, pos.up(), input, time);
        }
        // target container at the hopper's facing
        Direction facing = state.get(HopperBlock.FACING);
        Inventory output = HopperBlockEntity.getInventoryAt(world, pos.offset(facing));
        if (output != null && !(output instanceof HopperBlockEntity)) {
            diffAndLog(mod, sw, wid, pos.offset(facing), output, time);
        }
    }

    private void diffAndLog(CoreProtectFabric mod, ServerWorld world, String wid, BlockPos pos, Inventory inv, long time) {
        Map<String, Integer> after = snapshot(inv);
        Map<String, Integer> before = snapshots.put(pos, after);
        if (before == null) return; // first observation establishes the baseline
        for (Map.Entry<String, Integer> entry : after.entrySet()) {
            String id = entry.getKey();
            int delta = entry.getValue() - before.getOrDefault(id, 0);
            if (delta == 0) continue;
            mod.database().insertContainerAsync(new DatabaseManager.ContainerLog(
                    0, time, "#hopper", wid,
                    pos.getX(), pos.getY(), pos.getZ(),
                    delta > 0 ? DatabaseManager.CONTAINER_DEPOSIT : DatabaseManager.CONTAINER_WITHDRAW,
                    id, Math.abs(delta)));
        }
        for (String id : before.keySet()) {
            if (after.containsKey(id)) continue;
            mod.database().insertContainerAsync(new DatabaseManager.ContainerLog(
                    0, time, "#hopper", wid,
                    pos.getX(), pos.getY(), pos.getZ(),
                    DatabaseManager.CONTAINER_WITHDRAW, id, before.get(id)));
        }
    }

    private static Map<String, Integer> snapshot(Inventory inv) {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            String id = Registries.ITEM.getId(stack.getItem()).toString();
            map.merge(id, stack.getCount(), Integer::sum);
        }
        return map;
    }
}
