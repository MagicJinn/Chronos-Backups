package com.magicjinn.chronos.shell.forge;

import com.magicjinn.chronos.shell.ChronosBrigadier;
import com.magicjinn.chronos.shell.ShellCommandRegistrar;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

/** Forge 1.19.x (Mojmap): {@link Component#literal} / {@code sendSuccess(Component, boolean)}. */
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
                ForgeMojmapBrigadierHooks.hooks(
                        (source, message, broadcastToOps) ->
                                source.sendSuccess(Component.literal(message), broadcastToOps)));
    }
}
