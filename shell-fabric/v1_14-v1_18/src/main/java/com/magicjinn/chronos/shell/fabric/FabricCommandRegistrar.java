package com.magicjinn.chronos.shell.fabric;

import com.magicjinn.chronos.shell.ChronosBrigadier;
import com.magicjinn.chronos.shell.ShellCommandRegistrar;
import net.fabricmc.fabric.api.registry.CommandRegistry;
import net.minecraft.network.chat.TextComponent;

/**
 * Minecraft 1.14-1.18 - legacy {@link CommandRegistry} ({@link TextComponent}
 * chat).
 */
final class FabricCommandRegistrar implements ShellCommandRegistrar {
    @Override
    public void register(Object registrationContext) {
            CommandRegistry.INSTANCE.register(
                            true,
                            dispatcher -> ChronosBrigadier.register(
                        dispatcher,
                        FabricMojmapBrigadierHooks.hooks(
                                (source, message, broadcastToOps) -> source.sendSuccess(
                                        new TextComponent(message),
                                        broadcastToOps))));
    }
}
