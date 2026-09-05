package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabric;
import net.coreprotect.fabric.util.TimeUtil;
import net.minecraft.network.packet.c2s.play.CommandExecutionC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Logs every command executed by players. */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class ServerPlayNetworkHandlerMixin {

    @Inject(method = "onCommandExecution", at = @At("HEAD"))
    private void coreprotect$onCommand(CommandExecutionC2SPacket packet, CallbackInfo ci) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null || !mod.config().logging.command) return;
        ServerPlayerEntity player = ((ServerPlayNetworkHandler) (Object) this).getPlayer();
        if (player == null) return;
        mod.database().insertCommandAsync(TimeUtil.now(), player.getGameProfile().getName(), packet.command());
    }
}
