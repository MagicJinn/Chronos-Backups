package com.magicjinn.chronos.shell.forge;

import com.magicjinn.chronos.shell.ChronosBrigadier;
import com.magicjinn.chronos.shell.ShellCommandRegistrar;
import com.mojang.brigadier.CommandDispatcher;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

/** Forge 1.20+ (Mojmap), Supplier-based {@code sendSuccess}. */
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
                ForgeMojmapBrigadierHooks.hooks((source, message, broadcastToOps) -> {
                    Supplier<Component> text = () -> Component.literal(message);
                    source.sendSuccess(text, broadcastToOps);
                }));
    }
}
