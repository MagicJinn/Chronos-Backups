package com.magicjinn.chronos.shell.forge;

import com.magicjinn.chronos.shell.HookBridge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLServerStartedEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppedEvent;

@Mod(
    modid = ChronosForgeMod.MODID,
    name = ChronosForgeMod.NAME,
    version = ChronosForgeMod.VERSION,
    acceptableRemoteVersions = "*",
    acceptedMinecraftVersions = "[1.12,1.13)"
)
public final class ChronosForgeMod {
    public static final String MODID = "chronosbackup";
    public static final String NAME = "Chronos Backup";
    public static final String VERSION = "@VERSION@";

    @Mod.EventHandler
    public void onServerStarted(FMLServerStartedEvent event) {
        HookBridge.worldStarted();
    }

    @Mod.EventHandler
    public void onServerStopped(FMLServerStoppedEvent event) {
        HookBridge.worldStopped();
    }
}
