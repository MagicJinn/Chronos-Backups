package com.magicjinn.chronos.shell.forge;

import com.magicjinn.chronos.shell.ChronosBrigadier;
import com.magicjinn.chronos.shell.ShellCommandRegistrar;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

/* Forge 1.20 command registrar. */
final class ForgeCommandRegistrar implements ShellCommandRegistrar {
    @Override
    public void register(Object registrationContext) {
        if (!(registrationContext instanceof CommandDispatcher)) {
            return;
        }
        @SuppressWarnings("unchecked")
        CommandDispatcher<CommandSourceStack> dispatcher = (CommandDispatcher<CommandSourceStack>) registrationContext;
        ChronosBrigadier.register(
                dispatcher,
                new ChronosBrigadier.Hooks<>() {
                    @Override
                    public void feedback(
                            CommandSourceStack source, String message, boolean broadcastToOps) {
                        source.sendSuccess(() -> Component.literal(message), broadcastToOps);
                    }

                    @Override
                    public boolean mayExecuteChronos(CommandSourceStack source) {
                        return source.hasPermission(ChronosBrigadier.REQUIRED_PERMISSION_LEVEL);
                    }

                    @Override
                    public int backupReturnCode(boolean started) {
                        return started ? Command.SINGLE_SUCCESS : 0;
                    }

                    @Override
                    public int cancelReturnCode(boolean cancelled) {
                        return cancelled ? Command.SINGLE_SUCCESS : 0;
                    }
                });
    }
}
