package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabric;
import net.coreprotect.fabric.database.DatabaseManager;
import net.coreprotect.fabric.util.BlockStateUtil;
import net.coreprotect.fabric.util.TimeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Logs sign text edits.
 *
 * <p>Commands are NOT logged here any more (L5-03). Both packet entry points only queue the
 * command: {@code handleChatCommand} parses it first, and {@code handleSignedChatCommand} ends in
 * the private executor, which returns early when the chat chain is invalid. Recording at the end
 * of the packet handler therefore wrote a {@code co_command} row for commands the server never
 * ran, and it missed console, RCON, command-block and sign commands completely (L3-20). The
 * command record now comes from {@link CommandManagerMixin} - the single place every source
 * really executes a command.</p>
 *
 * <p>The sign hook is attached to the vanilla method that performs the action rather than to the
 * packet handler that receives it (B8): {@code handleSignUpdate} itself already checked that the
 * chunk is loaded and that a sign really is at that position, so a client cannot forge audit
 * rows for positions that hold no sign. {@code CoreProtectFabric#allowPacketLog} caps how many
 * records one player may produce per minute and counts every dropped one (L3-09).</p>
 *
 * <p>Known limits: a sign edit on a waxed sign is recorded although vanilla refuses the change,
 * and the text is stored as the client sent it rather than after the chat filter rewrote it.</p>
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerPlayNetworkHandlerMixin {

    @Inject(method = "handleSignUpdate(Lnet/minecraft/network/protocol/game/ServerboundSignUpdatePacket;)V",
            at = @At("RETURN"))
    private void coreprotect$handleSignUpdate(ServerboundSignUpdatePacket packet, CallbackInfo ci) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null || !mod.config().logging.signEdit) return;
        ServerPlayer player = ((ServerGamePacketListenerImpl) (Object) this).player;
        if (player == null) return;
        if (!CoreProtectFabric.allowPacketLog(player.getUUID(), CoreProtectFabric.LogKind.SIGN)) return;
        String[] raw = packet.getLines();
        if (raw == null) return;
        String lines = BlockStateUtil.signLinesToJson(raw, packet.isFrontText());
        if (lines == null) return;
        BlockPos pos = packet.getPos();
        mod.database().insertSignAsync(new DatabaseManager.SignLog(
                0, TimeUtil.now(), player.getGameProfile().name(),
                player.level().dimension().identifier().toString(),
                pos.getX(), pos.getY(), pos.getZ(), lines));
    }
}
