package com.magicjinn.chronos.shell.fabric;

import com.magicjinn.chronos.shell.ChronosBrigadier;
import com.magicjinn.chronos.shell.ShellCommandRegistrar;
import com.magicjinn.chronos.shell.mojmap.ChronosMojmapCommandGate;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

final class FabricCommandRegistrar implements ShellCommandRegistrar {
    @Override
    public void register(Object registrationContext) {
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) ->
                        ChronosBrigadier.register(
                                dispatcher,
                                new ChronosBrigadier.Hooks<CommandSourceStack>() {
                                    @Override
                                    public void feedback(
                                            CommandSourceStack source,
                                            String message,
                                            boolean broadcastToOps) {
                                        source.sendSuccess(
                                                () -> Component.literal(message), broadcastToOps);
                                    }

                                    @Override
                                    public boolean mayExecuteChronos(CommandSourceStack source) {
                                return ChronosMojmapCommandGate.mayExecute(source);
                                    }

                                    @Override
                                    public int backupReturnCode(boolean started) {
                                        return started ? 1 : 0;
                                    }

                                    @Override
                                    public int cancelReturnCode(boolean cancelled) {
                                        return cancelled ? 1 : 0;
                                    }
                                }));
    }
}
