package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabric;
import net.coreprotect.fabric.database.DatabaseManager;
import net.coreprotect.fabric.util.BlockStateUtil;
import net.coreprotect.fabric.util.TimeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Logs commands executed by players and sign text edits.
 *
 * <p>Both hooks are attached to the vanilla method that performs the action instead of the
 * packet handler that receives it (B8), and both fire at {@code RETURN}, so only a command the
 * server really ran and a sign the server really rewrote are recorded. This mapping has no single
 * shared command executor - {@code handleChatCommand} and {@code handleSignedChatCommand} are the
 * two public packet entries and they end in {@code performUnsignedChatCommand(String)} and
 * {@code performSignedChatCommand} respectively - so the unsigned path is hooked on the string it
 * carries and the signed path on its packet, which is the same point in the pipeline as the yarn
 * trees' single {@code executeCommand} hook. Hooking the packet handler itself would record
 * commands and sign texts the server never applied, so any client could forge audit rows.
 * {@code CoreProtectFabric#allowPacketLog} additionally caps how many records one player may
 * produce per minute.</p>
 *
 * <p>Known limits: a command that the command manager rejects is recorded as well (it is
 * still a command the player sent, which is what the audit trail is for), a sign edit on a
 * waxed sign is recorded although vanilla refuses the change, and the text is stored as the
 * client sent it rather than after the chat filter rewrote it.</p>
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerPlayNetworkHandlerMixin {

    @Inject(method = "performUnsignedChatCommand(Ljava/lang/String;)V", at = @At("RETURN"))
    private void coreprotect$onCommand(String command, CallbackInfo ci) {
        coreprotect$logCommand(command);
    }

    @Inject(method = "handleSignedChatCommand(Lnet/minecraft/network/protocol/game/ServerboundChatCommandSignedPacket;)V",
            at = @At("RETURN"))
    private void coreprotect$onSignedCommand(ServerboundChatCommandSignedPacket packet, CallbackInfo ci) {
        coreprotect$logCommand(packet.command());
    }

    private void coreprotect$logCommand(String command) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null || !mod.config().logging.command) return;
        ServerPlayer player = ((ServerGamePacketListenerImpl) (Object) this).player;
        if (player == null) return;
        if (!CoreProtectFabric.allowPacketLog(player.getUUID())) return;
        mod.database().insertCommandAsync(TimeUtil.now(), player.getGameProfile().name(), command);
    }

    @Inject(method = "handleSignUpdate(Lnet/minecraft/network/protocol/game/ServerboundSignUpdatePacket;)V",
            at = @At("RETURN"))
    private void coreprotect$onSignUpdate(ServerboundSignUpdatePacket packet, CallbackInfo ci) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null || !mod.config().logging.signEdit) return;
        ServerPlayer player = ((ServerGamePacketListenerImpl) (Object) this).player;
        if (player == null) return;
        if (!CoreProtectFabric.allowPacketLog(player.getUUID())) return;
        String lines = BlockStateUtil.signLinesToJson(packet.getLines(), packet.isFrontText());
        if (lines == null) return;
        BlockPos pos = packet.getPos();
        mod.database().insertSignAsync(new DatabaseManager.SignLog(
                0, TimeUtil.now(), player.getGameProfile().name(),
                player.level().dimension().identifier().toString(),
                pos.getX(), pos.getY(), pos.getZ(), lines));
    }
}
