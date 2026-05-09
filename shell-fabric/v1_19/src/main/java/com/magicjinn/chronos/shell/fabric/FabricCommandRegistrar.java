package com.magicjinn.chronos.shell.fabric;

import com.magicjinn.chronos.shell.ChronosBrigadier;
import com.magicjinn.chronos.shell.ShellCommandRegistrar;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.network.chat.Component;

/** Minecraft 1.19.x - Fabric command API v2 */
final class FabricCommandRegistrar implements ShellCommandRegistrar {
    @Override
    public void register(Object registrationContext) {
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> ChronosBrigadier.register(
                        dispatcher,
                        FabricMojmapBrigadierHooks.hooks(
                                (source, message, broadcastToOps) -> source.sendSuccess(
                                        Component.literal(message),
                                        broadcastToOps))));
    }
}
