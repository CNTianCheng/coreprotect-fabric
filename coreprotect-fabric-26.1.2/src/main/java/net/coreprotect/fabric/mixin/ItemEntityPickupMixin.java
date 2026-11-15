package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabric;
import net.coreprotect.fabric.database.DatabaseManager;
import net.coreprotect.fabric.util.ItemStackCodec;
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
 *
 * <p>A stack that is absorbed completely is restored to its original count before the
 * entity is discarded, so it has to be recognised by the entity being removed.</p>
 */
@Mixin(ItemEntity.class)
public abstract class ItemEntityPickupMixin {

    @Unique
    private Item coreprotect$beforeItem;

    @Unique
    private int coreprotect$beforeCount;

    /**
     * Reference to the stack the entity carried before the pickup. Only a reference, never a
     * copy: the components have to be read after the fact (L3-17), and a fully absorbed stack
     * has its original count restored before the entity is discarded - the item stack object
     * and its components survive that, so encoding once at RETURN is enough.
     */
    @Unique
    private ItemStack coreprotect$beforeStack;

    @Inject(method = "playerTouch", at = @At("HEAD"))
    private void coreprotect$capturePickup(Player player, CallbackInfo ci) {
        // Only the item type, the count and a reference are remembered: copying the whole stack
        // here allocated an ItemStack on every collision tick, including the (much more common)
        // ticks where nothing is picked up at all.
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null || !mod.config().logging.item || !(player instanceof ServerPlayer)) {
            coreprotect$beforeItem = null;
            coreprotect$beforeStack = null;
            return;
        }
        ItemStack stack = ((ItemEntity) (Object) this).getItem();
        coreprotect$beforeItem = stack.isEmpty() ? null : stack.getItem();
        coreprotect$beforeStack = stack.isEmpty() ? null : stack;
        coreprotect$beforeCount = stack.getCount();
    }

    @Inject(method = "playerTouch", at = @At("RETURN"))
    private void coreprotect$onPickup(Player player, CallbackInfo ci) {
        Item before = coreprotect$beforeItem;
        ItemStack beforeStack = coreprotect$beforeStack;
        int beforeCount = coreprotect$beforeCount;
        coreprotect$beforeItem = null;
        coreprotect$beforeStack = null;
        coreprotect$beforeCount = 0;
        if (before == null) return;
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null || !mod.config().logging.item) return;
        if (!(player instanceof ServerPlayer sp)) return;
        int taken = beforeCount - ((ItemEntity) (Object) this).getItem().getCount();
        if (taken <= 0) {
            // E1: a fully absorbed stack never shows up here - vanilla puts the original
            // count back before discarding the entity (ItemEntity#playerTouch:
            // "if (itemStack.isEmpty()) { discard(); itemStack.setCount(i); }"), so the
            // stack size is unchanged even though the whole stack was picked up. A removed
            // entity is exactly that case (an unsuccessful insertStack does not discard).
            if (!((ItemEntity) (Object) this).isRemoved()) return; // nothing was actually picked up
            taken = beforeCount;
        }
        ServerLevel world = sp.level();
        BlockPos pos = ((ItemEntity) (Object) this).blockPosition();
        // L3-17: the pickup row used to store only the item id, so a picked-up enchanted sword,
        // shulker box or written book could not be identified afterwards - the rollback of an
        // item transaction had nothing to restore. Encode the components as SNBT like the drop
        // path does (PlayerEntityDropMixin).
        String nbt = beforeStack == null ? null
                : ItemStackCodec.encodeIdentity(beforeStack, world.registryAccess());
        mod.database().insertItemAsync(new DatabaseManager.ItemLog(
                0, TimeUtil.now(), sp.getGameProfile().name(),
                world.dimension().identifier().toString(),
                pos.getX(), pos.getY(), pos.getZ(), "+",
                BuiltInRegistries.ITEM.getKey(before).toString(), taken, nbt));
    }
}
