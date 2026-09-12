package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabric;
import net.coreprotect.fabric.database.DatabaseManager;
import net.coreprotect.fabric.util.TimeUtil;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Logs item drops (CoreProtect-style item transactions). Recorded at RETURN of
 * {@code dropItem}: vanilla returns {@code null} for an empty stack, which used to
 * produce phantom "-item amount=0" rows when logged at HEAD.
 */
@Mixin(PlayerEntity.class)
public abstract class PlayerEntityDropMixin {

    @Inject(method = "dropItem(Lnet/minecraft/item/ItemStack;Z)Lnet/minecraft/entity/ItemEntity;",
            at = @At("RETURN"))
    private void coreprotect$onDrop(ItemStack stack, boolean throwRandomly,
                                    CallbackInfoReturnable<ItemEntity> cir) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null || !mod.config().logging.item) return;
        if (!((Object) this instanceof ServerPlayerEntity sp)) return;
        ItemEntity dropped = cir.getReturnValue();
        if (dropped == null) return; // empty stack / nothing was dropped
        ItemStack droppedStack = dropped.getStack();
        if (droppedStack.isEmpty()) return;
        ServerWorld world = sp.getEntityWorld();
        BlockPos pos = sp.getBlockPos();
        mod.database().insertItemAsync(new DatabaseManager.ItemLog(
                0, TimeUtil.now(), sp.getGameProfile().name(),
                world.getRegistryKey().getValue().toString(),
                pos.getX(), pos.getY(), pos.getZ(), "-",
                Registries.ITEM.getId(droppedStack.getItem()).toString(), droppedStack.getCount()));
    }
}
