package net.coreprotect.fabric.mixin;

import java.util.List;

import net.coreprotect.fabric.CoreProtectFabric;
import net.coreprotect.fabric.database.DatabaseManager;
import net.coreprotect.fabric.util.BlockStateUtil;
import net.coreprotect.fabric.util.TimeUtil;
import net.minecraft.network.packet.c2s.play.UpdateSignC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Logs sign text edits.
 *
 * <p>Commands are NOT logged here any more (L5-03). Both packet entry points only queue the
 * command: {@code executeCommand} parses it first, and {@code onChatCommandSigned} ends in the
 * private {@code handleCommandExecution}, which returns early when the chat chain is invalid.
 * Recording at the end of the packet handler therefore wrote a {@code co_command} row for
 * commands the server never ran, and it missed console, RCON, command-block and sign commands
 * completely (L3-20). The command record now comes from
 * {@link CommandManagerMixin} - the single place every source really executes a command.</p>
 *
 * <p>The sign hook is attached to the vanilla method that performs the action rather than to the
 * packet handler that receives it (B8): the private {@code onSignUpdate} tail already checked that
 * the chunk is loaded and that a sign really is at that position, so a client cannot forge audit
 * rows for positions that hold no sign. {@code CoreProtectFabric#allowPacketLog} caps how many
 * records one player may produce per minute and counts every dropped one (L3-09).</p>
 *
 * <p>Known limits: a sign edit on a waxed sign is recorded although vanilla refuses the change,
 * and the text is stored as the client sent it rather than after the chat filter rewrote it.</p>
 */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class ServerPlayNetworkHandlerMixin {

    @Inject(method = "onSignUpdate(Lnet/minecraft/network/packet/c2s/play/UpdateSignC2SPacket;Ljava/util/List;)V",
            at = @At("RETURN"))
    private void coreprotect$onSignUpdate(UpdateSignC2SPacket packet, List<?> signText, CallbackInfo ci) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null || !mod.config().logging.signEdit) return;
        ServerPlayerEntity player = ((ServerPlayNetworkHandler) (Object) this).getPlayer();
        if (player == null) return;
        if (!CoreProtectFabric.allowPacketLog(player.getUuid(), CoreProtectFabric.LogKind.SIGN)) return;
        String lines = BlockStateUtil.signLinesToJson(packet.getText(), packet.isFront());
        if (lines == null) return;
        BlockPos pos = packet.getPos();
        mod.database().insertSignAsync(new DatabaseManager.SignLog(
                0, TimeUtil.now(), player.getGameProfile().name(),
                player.getEntityWorld().getRegistryKey().getValue().toString(),
                pos.getX(), pos.getY(), pos.getZ(), lines));
    }
}
