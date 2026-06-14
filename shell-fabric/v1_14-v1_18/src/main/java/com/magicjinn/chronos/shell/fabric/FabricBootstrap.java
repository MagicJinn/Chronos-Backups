package com.magicjinn.chronos.shell.fabric;

import com.magicjinn.chronos.shell.HookBridge;
import com.magicjinn.chronos.shell.mojmap.common.MojmapServerEnvironment;
import net.fabricmc.fabric.api.event.server.ServerStartCallback;
import net.fabricmc.fabric.api.event.server.ServerStopCallback;
import net.fabricmc.fabric.api.event.server.ServerTickCallback;

/**
 * Minecraft 1.14-1.18 - Fabric lifecycle v0 + {@link CommandRegistry} commands.
 */
final class FabricBootstrap {
    private FabricBootstrap() {
    }

    static void onInitialize() {
        new FabricCommandRegistrar().register(null);
        ServerStartCallback.EVENT.register(
                server -> {
                    ChronosFabricEntrypoint.activeServer = server;
                    HookBridge.worldStarted(
                            new MojmapServerEnvironment(server),
                            server,
                            ChronosFabricEntrypoint.MESSENGER,
                            ChronosFabricEntrypoint.WORLD_CONTROLLER);
                });
        ServerTickCallback.EVENT.register(server -> HookBridge.serverTick());
        ServerStopCallback.EVENT.register(
                server -> {
                    HookBridge.worldStopped();
                    ChronosFabricEntrypoint.activeServer = null;
                });
    }
}
