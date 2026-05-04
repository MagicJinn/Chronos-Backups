package com.magicjinn.chronos.shell.neoforge;

import com.magicjinn.chronos.shell.ChronosBrigadier;
import com.magicjinn.chronos.shell.ShellCommandRegistrar;
import com.magicjinn.chronos.shell.mojmap.ChronosMojmapCommandGate;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

final class NeoForgeCommandRegistrar implements ShellCommandRegistrar {
    @Override
    public void register(Object registrationContext) {
        if (!(registrationContext instanceof CommandDispatcher)) {
            return;
        }
        @SuppressWarnings("unchecked")
        CommandDispatcher<CommandSourceStack> dispatcher = (CommandDispatcher<CommandSourceStack>) registrationContext;
        ChronosBrigadier.register(
                dispatcher,
                new ChronosBrigadier.Hooks<CommandSourceStack>() {
                    @Override
                    public void feedback(
                            CommandSourceStack source, String message, boolean broadcastToOps) {
                        source.sendSuccess(() -> Component.literal(message), broadcastToOps);
                    }

                    @Override
                    public boolean mayExecuteChronos(CommandSourceStack source) {
                        return ChronosMojmapCommandGate.mayExecute(source);
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
