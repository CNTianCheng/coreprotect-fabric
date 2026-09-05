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

/** Logs item drops (CoreProtect-style item transactions). */
@Mixin(PlayerEntity.class)
public abstract class PlayerEntityDropMixin {

    @Inject(method = "dropItem(Lnet/minecraft/item/ItemStack;ZZ)Lnet/minecraft/entity/ItemEntity;",
            at = @At("HEAD"))
    private void coreprotect$onDrop(ItemStack stack, boolean throwRandomly, boolean retainOwnership,
                                    CallbackInfoReturnable<ItemEntity> cir) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null || !mod.config().logging.item) return;
        if (!((Object) this instanceof ServerPlayerEntity sp)) return;
        if (stack.isEmpty()) return;
        ServerWorld world = sp.getServerWorld();
        BlockPos pos = sp.getBlockPos();
        mod.database().insertItemAsync(new DatabaseManager.ItemLog(
                0, TimeUtil.now(), sp.getGameProfile().getName(),
                world.getRegistryKey().getValue().toString(),
                pos.getX(), pos.getY(), pos.getZ(), "-",
                Registries.ITEM.getId(stack.getItem()).toString(), stack.getCount()));
    }
}
