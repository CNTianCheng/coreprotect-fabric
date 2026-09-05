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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Logs item pickups (CoreProtect-style item transactions). */
@Mixin(ItemEntity.class)
public abstract class ItemEntityPickupMixin {

    @Inject(method = "onPlayerCollision", at = @At("HEAD"))
    private void coreprotect$onPickup(PlayerEntity player, CallbackInfo ci) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null || !mod.config().logging.item) return;
        if (!(player instanceof ServerPlayerEntity sp)) return;
        ItemStack stack = ((ItemEntity) (Object) this).getStack();
        if (stack.isEmpty()) return;
        ServerWorld world = sp.getServerWorld();
        BlockPos pos = ((ItemEntity) (Object) this).getBlockPos();
        mod.database().insertItemAsync(new DatabaseManager.ItemLog(
                0, TimeUtil.now(), sp.getGameProfile().getName(),
                world.getRegistryKey().getValue().toString(),
                pos.getX(), pos.getY(), pos.getZ(), "+",
                Registries.ITEM.getId(stack.getItem()).toString(), stack.getCount()));
    }
}
