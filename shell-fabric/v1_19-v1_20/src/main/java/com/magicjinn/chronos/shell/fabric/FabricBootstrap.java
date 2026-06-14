package com.magicjinn.chronos.shell.fabric;

import com.magicjinn.chronos.shell.HookBridge;
import com.magicjinn.chronos.shell.mojmap.common.MojmapServerEnvironment;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/** Minecraft 1.19+ - Fabric lifecycle v1 + command API v2. */
final class FabricBootstrap {
    private FabricBootstrap() {
    }

    static void onInitialize() {
        new FabricCommandRegistrar().register(null);
        ServerLifecycleEvents.SERVER_STARTED.register(
                server -> {
                    ChronosFabricEntrypoint.activeServer = server;
                    HookBridge.worldStarted(
                            new MojmapServerEnvironment(server),
                            server,
                            ChronosFabricEntrypoint.MESSENGER,
                            ChronosFabricEntrypoint.WORLD_CONTROLLER);
                });
        ServerTickEvents.END_SERVER_TICK.register(server -> HookBridge.serverTick());
        ServerLifecycleEvents.SERVER_STOPPED.register(
                server -> {
                    HookBridge.worldStopped();
                    ChronosFabricEntrypoint.activeServer = null;
                });
    }
}
