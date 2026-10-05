package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabric;
import net.coreprotect.fabric.database.DatabaseManager;
import net.coreprotect.fabric.util.TimeUtil;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Logs item pickups (CoreProtect-style item transactions). The record is written at
 * RETURN of {@code playerTouch} and only when the stack actually shrank: logging at
 * HEAD wrote a phantom "+item" row on every tick a player merely stood next to an
 * item that could not be picked up (pickup delay, full inventory).
 */
@Mixin(ItemEntity.class)
public abstract class ItemEntityPickupMixin {

    @Unique
    private Item coreprotect$beforeItem;

    @Unique
    private int coreprotect$beforeCount;

    @Inject(method = "playerTouch", at = @At("HEAD"))
    private void coreprotect$capturePickup(Player player, CallbackInfo ci) {
        // Only the item type and the count are remembered: copying the whole stack here
        // allocated an ItemStack on every collision tick, including the (much more common)
        // ticks where nothing is picked up at all.
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null || !mod.config().logging.item || !(player instanceof ServerPlayer)) {
            coreprotect$beforeItem = null;
            return;
        }
        ItemStack stack = ((ItemEntity) (Object) this).getItem();
        coreprotect$beforeItem = stack.isEmpty() ? null : stack.getItem();
        coreprotect$beforeCount = stack.getCount();
    }

    @Inject(method = "playerTouch", at = @At("RETURN"))
    private void coreprotect$onPickup(Player player, CallbackInfo ci) {
        Item before = coreprotect$beforeItem;
        int beforeCount = coreprotect$beforeCount;
        coreprotect$beforeItem = null;
        coreprotect$beforeCount = 0;
        if (before == null) return;
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null || !mod.config().logging.item) return;
        if (!(player instanceof ServerPlayer sp)) return;
        int taken = beforeCount - ((ItemEntity) (Object) this).getItem().getCount();
        if (taken <= 0) return; // nothing was actually picked up
        ServerLevel world = sp.level();
        BlockPos pos = ((ItemEntity) (Object) this).blockPosition();
        mod.database().insertItemAsync(new DatabaseManager.ItemLog(
                0, TimeUtil.now(), sp.getGameProfile().name(),
                world.dimension().identifier().toString(),
                pos.getX(), pos.getY(), pos.getZ(), "+",
                BuiltInRegistries.ITEM.getKey(before).toString(), taken));
    }
}
