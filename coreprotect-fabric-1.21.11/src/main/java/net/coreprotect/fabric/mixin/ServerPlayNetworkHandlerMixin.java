package net.coreprotect.fabric.mixin;

import java.util.List;

import net.coreprotect.fabric.CoreProtectFabric;
import net.coreprotect.fabric.database.DatabaseManager;
import net.coreprotect.fabric.util.BlockStateUtil;
import net.coreprotect.fabric.util.TimeUtil;
import net.minecraft.network.packet.c2s.play.ChatCommandSignedC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSignC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Logs commands executed by players and sign text edits.
 *
 * <p>Commands arrive on two packet paths, because a client that has chat signing enabled never
 * reaches {@code executeCommand}: {@code onCommandExecution} ends there, while
 * {@code onChatCommandSigned} ends in the private {@code handleCommandExecution} instead. Both
 * hooks are attached to the vanilla method that performs the action rather than to the packet
 * handler that receives it (B8): {@code executeCommand} is only reached after
 * {@code onCommandExecution} validated the packet (an illegal character disconnects the
 * player instead), and the private {@code onSignUpdate} tail already checked that the chunk
 * is loaded and that a sign really is at that position. Hooking the packet handler recorded
 * commands and sign texts the server never applied, so any client could forge audit rows.
 * {@code CoreProtectFabric#allowPacketLog} additionally caps how many records one player may
 * produce per minute.</p>
 *
 * <p>Known limits: a command that the command manager rejects is recorded as well (it is
 * still a command the player sent, which is what the audit trail is for), a sign edit on a
 * waxed sign is recorded although vanilla refuses the change, and the text is stored as the
 * client sent it rather than after the chat filter rewrote it.</p>
 */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class ServerPlayNetworkHandlerMixin {

    @Inject(method = "executeCommand(Ljava/lang/String;)V", at = @At("RETURN"))
    private void coreprotect$onCommand(String command, CallbackInfo ci) {
        coreprotect$logCommand(command);
    }

    @Inject(method = "onChatCommandSigned(Lnet/minecraft/network/packet/c2s/play/ChatCommandSignedC2SPacket;)V",
            at = @At("RETURN"))
    private void coreprotect$onSignedCommand(ChatCommandSignedC2SPacket packet, CallbackInfo ci) {
        coreprotect$logCommand(packet.command());
    }

    private void coreprotect$logCommand(String command) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null || !mod.config().logging.command) return;
        ServerPlayerEntity player = ((ServerPlayNetworkHandler) (Object) this).getPlayer();
        if (player == null) return;
        if (!CoreProtectFabric.allowPacketLog(player.getUuid())) return;
        mod.database().insertCommandAsync(TimeUtil.now(), player.getGameProfile().name(), command);
    }

    @Inject(method = "onSignUpdate(Lnet/minecraft/network/packet/c2s/play/UpdateSignC2SPacket;Ljava/util/List;)V",
            at = @At("RETURN"))
    private void coreprotect$onSignUpdate(UpdateSignC2SPacket packet, List<?> signText, CallbackInfo ci) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null || !mod.config().logging.signEdit) return;
        ServerPlayerEntity player = ((ServerPlayNetworkHandler) (Object) this).getPlayer();
        if (player == null) return;
        if (!CoreProtectFabric.allowPacketLog(player.getUuid())) return;
        String lines = BlockStateUtil.signLinesToJson(packet.getText(), packet.isFront());
        if (lines == null) return;
        BlockPos pos = packet.getPos();
        mod.database().insertSignAsync(new DatabaseManager.SignLog(
                0, TimeUtil.now(), player.getGameProfile().name(),
                player.getEntityWorld().getRegistryKey().getValue().toString(),
                pos.getX(), pos.getY(), pos.getZ(), lines));
    }
}
