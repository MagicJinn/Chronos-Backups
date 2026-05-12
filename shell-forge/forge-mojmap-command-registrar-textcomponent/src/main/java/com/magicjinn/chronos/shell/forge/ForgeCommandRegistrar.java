package com.magicjinn.chronos.shell.forge;

import com.magicjinn.chronos.shell.ChronosBrigadier;
import com.magicjinn.chronos.shell.ShellCommandRegistrar;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.TextComponent;

/**
 * Mojmap-era Forge 1.14–1.18: {@link TextComponent} and
 * {@code sendSuccess(Component, boolean)}.
 */
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
                        (source, message, broadcastToOps) -> source.sendSuccess(new TextComponent(message),
                                broadcastToOps)));
    }
}
