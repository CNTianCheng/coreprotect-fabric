package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabric;
import net.coreprotect.fabric.database.DatabaseManager;
import net.coreprotect.fabric.util.TimeUtil;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Logs item pickups (CoreProtect-style item transactions). */
@Mixin(ItemEntity.class)
public abstract class ItemEntityPickupMixin {

    @Inject(method = "playerTouch", at = @At("HEAD"))
    private void coreprotect$onPickup(Player player, CallbackInfo ci) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null || !mod.config().logging.item) return;
        if (!(player instanceof ServerPlayer sp)) return;
        ItemStack stack = ((ItemEntity) (Object) this).getItem();
        if (stack.isEmpty()) return;
        ServerLevel world = sp.level();
        BlockPos pos = ((ItemEntity) (Object) this).blockPosition();
        mod.database().insertItemAsync(new DatabaseManager.ItemLog(
                0, TimeUtil.now(), sp.getGameProfile().name(),
                world.dimension().identifier().toString(),
                pos.getX(), pos.getY(), pos.getZ(), "+",
                BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount()));
    }
}
