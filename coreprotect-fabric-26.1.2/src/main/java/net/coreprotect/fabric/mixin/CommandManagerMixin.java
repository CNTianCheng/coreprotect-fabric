package net.coreprotect.fabric.mixin;

import com.mojang.brigadier.ParseResults;
import net.coreprotect.fabric.CoreProtectFabric;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records commands at the point where the server really executes them (L3-20, L5-03).
 *
 * <p>{@code Commands#performCommand(ParseResults, String)} is the single funnel every source goes
 * through: the console, RCON, command blocks, sign commands, datapack functions invoked directly
 * and both player packet paths ({@code handleChatCommand} and {@code handleSignedChatCommand}).
 * Hooking the player packet handler instead recorded commands that were rejected afterwards and
 * missed every non-player source entirely.</p>
 *
 * <p>Command execution that is queued through a command execution context does not pass here, so
 * a function that runs hundreds of commands cannot flood the audit table.</p>
 *
 * <p>The hit is at HEAD so a command is recorded even when its execution throws; the source is
 * read from {@code parseResults.getContext().getSource()} because the method itself only gets the
 * unparsed string.</p>
 */
@Mixin(Commands.class)
public abstract class CommandManagerMixin {

    @Inject(method = "performCommand(Lcom/mojang/brigadier/ParseResults;Ljava/lang/String;)V", at = @At("HEAD"))
    private void coreprotect$logCommand(ParseResults<CommandSourceStack> parseResults, String command,
                                       CallbackInfo ci) {
        // Never let the audit hook break a command: the hook itself swallows its own errors, but
        // reading the source could still fail on an unusual parse result, so it is guarded here too.
        try {
            if (parseResults == null || parseResults.getContext() == null) return;
            CoreProtectFabric.hookCommandExecution(parseResults.getContext().getSource(), command);
        } catch (Throwable ignored) {
            // see above
        }
    }
}
