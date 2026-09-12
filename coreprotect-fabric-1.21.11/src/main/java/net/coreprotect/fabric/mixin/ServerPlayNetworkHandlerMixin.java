package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabric;
import net.coreprotect.fabric.database.DatabaseManager;
import net.coreprotect.fabric.util.BlockStateUtil;
import net.coreprotect.fabric.util.TimeUtil;
import net.minecraft.network.packet.c2s.play.CommandExecutionC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSignC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Logs commands executed by players and sign text edits. */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class ServerPlayNetworkHandlerMixin {

    @Inject(method = "onCommandExecution", at = @At("HEAD"))
    private void coreprotect$onCommand(CommandExecutionC2SPacket packet, CallbackInfo ci) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null || !mod.config().logging.command) return;
        ServerPlayerEntity player = ((ServerPlayNetworkHandler) (Object) this).getPlayer();
        if (player == null) return;
        mod.database().insertCommandAsync(TimeUtil.now(), player.getGameProfile().name(), packet.command());
    }

    @Inject(method = "onUpdateSign", at = @At("HEAD"))
    private void coreprotect$onSignUpdate(UpdateSignC2SPacket packet, CallbackInfo ci) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null || !mod.config().logging.signEdit) return;
        ServerPlayerEntity player = ((ServerPlayNetworkHandler) (Object) this).getPlayer();
        if (player == null) return;
        String lines = BlockStateUtil.signLinesToJson(packet.getText(), packet.isFront());
        if (lines == null) return;
        BlockPos pos = packet.getPos();
        mod.database().insertSignAsync(new DatabaseManager.SignLog(
                0, TimeUtil.now(), player.getGameProfile().name(),
                player.getEntityWorld().getRegistryKey().getValue().toString(),
                pos.getX(), pos.getY(), pos.getZ(), lines));
    }
}
