package com.magicjinn.chronos.shell.fabric;

import com.magicjinn.chronos.shell.HookBridge;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public final class ChronosFabricEntrypoint implements ModInitializer {
    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(HookBridge::worldStarted);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> HookBridge.worldStopped());
    }
}
