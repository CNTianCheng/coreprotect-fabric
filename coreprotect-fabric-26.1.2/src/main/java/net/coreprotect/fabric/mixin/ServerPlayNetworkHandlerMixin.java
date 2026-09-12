package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabric;
import net.coreprotect.fabric.database.DatabaseManager;
import net.coreprotect.fabric.util.BlockStateUtil;
import net.coreprotect.fabric.util.TimeUtil;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Logs commands executed by players and sign text edits. */
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

    @Inject(method = "handleSignUpdate", at = @At("HEAD"))
    private void coreprotect$onSignUpdate(ServerboundSignUpdatePacket packet, CallbackInfo ci) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null || !mod.config().logging.signEdit) return;
        ServerPlayer player = ((ServerGamePacketListenerImpl) (Object) this).player;
        if (player == null) return;
        String lines = BlockStateUtil.signLinesToJson(packet.getLines());
        if (lines == null) return;
        BlockPos pos = packet.getPos();
        mod.database().insertSignAsync(new DatabaseManager.SignLog(
                0, TimeUtil.now(), player.getGameProfile().name(),
                player.level().dimension().identifier().toString(),
                pos.getX(), pos.getY(), pos.getZ(), lines));
    }
}
