package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabric;
import net.coreprotect.fabric.util.TimeUtil;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Logs every command executed by players. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerPlayNetworkHandlerMixin {

    @Inject(method = "handleChatCommand", at = @At("HEAD"))
    private void coreprotect$onCommand(ServerboundChatCommandPacket packet, CallbackInfo ci) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null || !mod.config().logging.command) return;
        ServerPlayer player = ((ServerGamePacketListenerImpl) (Object) this).player;
        if (player == null) return;
        mod.database().insertCommandAsync(TimeUtil.now(), player.getGameProfile().name(), packet.command());
    }
}
