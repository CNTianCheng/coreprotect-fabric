package net.coreprotect.fabric.container;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.coreprotect.fabric.CoreProtectFabric;
import net.coreprotect.fabric.database.DatabaseManager;
import net.coreprotect.fabric.util.TimeUtil;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.DropperBlockEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;

/**
 * CoreProtect-style {@code #dropper} / {@code #dispenser} logging: the 9-slot
 * inventory is diffed against the previous snapshot at the start of each
 * scheduled tick (catches deposits and previous dispenses) and again right
 * after a dispense (catches one-shot activations). Both blocks are covered
 * because {@code DropperBlock} inherits {@code DispenserBlock#scheduledTick}.
 */
public final class DispenserTracker {
    private final Map<BlockPos, Map<String, Integer>> snapshots = new ConcurrentHashMap<>();

    /** Called at the start of {@code DispenserBlock#scheduledTick} (both block types). */
    public void onScheduledTick(ServerLevel world, BlockPos pos) {
        observe(world, pos);
    }

    /** Called right after a dispense (both block types). */
    public void onDispensed(ServerLevel world, BlockPos pos) {
        observe(world, pos);
    }

    private void observe(ServerLevel world, BlockPos pos) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null || !mod.config().logging.dispenser) return;
        BlockEntity be = world.getBlockEntity(pos);
        if (!(be instanceof DispenserBlockEntity dispenser)) return;
        String user = be instanceof DropperBlockEntity ? "#dropper" : "#dispenser";
        long time = TimeUtil.now();
        String wid = world.dimension().identifier().toString();

        Map<String, Integer> after = snapshot(dispenser);
        Map<String, Integer> before = snapshots.put(pos, after);
        if (before == null) return; // first observation establishes the baseline
        for (Map.Entry<String, Integer> entry : after.entrySet()) {
            String id = entry.getKey();
            int delta = entry.getValue() - before.getOrDefault(id, 0);
            if (delta == 0) continue;
            mod.database().insertContainerAsync(new DatabaseManager.ContainerLog(
                    0, time, user, wid,
                    pos.getX(), pos.getY(), pos.getZ(),
                    delta > 0 ? DatabaseManager.CONTAINER_DEPOSIT : DatabaseManager.CONTAINER_WITHDRAW,
                    id, Math.abs(delta)));
        }
        for (String id : before.keySet()) {
            if (after.containsKey(id)) continue;
            mod.database().insertContainerAsync(new DatabaseManager.ContainerLog(
                    0, time, user, wid,
                    pos.getX(), pos.getY(), pos.getZ(),
                    DatabaseManager.CONTAINER_WITHDRAW, id, before.get(id)));
        }
    }

    private static Map<String, Integer> snapshot(DispenserBlockEntity dispenser) {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < dispenser.getContainerSize(); i++) {
            ItemStack stack = dispenser.getItem(i);
            if (stack.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            map.merge(id, stack.getCount(), Integer::sum);
        }
        return map;
    }
}
