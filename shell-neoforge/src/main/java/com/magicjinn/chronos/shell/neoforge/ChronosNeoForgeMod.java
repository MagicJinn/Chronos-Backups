package com.magicjinn.chronos.shell.neoforge;

import com.magicjinn.chronos.shell.HookBridge;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

@Mod(ChronosNeoForgeMod.MOD_ID)
public final class ChronosNeoForgeMod {
    public static final String MOD_ID = "chronosbackup";

    public ChronosNeoForgeMod(IEventBus modBus) {
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        HookBridge.worldStarted();
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        HookBridge.worldStopped();
    }
}
