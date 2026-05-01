package com.magicjinn.chronos.shell.forge;

import com.magicjinn.chronos.core.ShellMessenger;
import com.magicjinn.chronos.shell.HookBridge;
import com.magicjinn.chronos.shell.ShellCommandRegistrar;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLServerStartedEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppedEvent;

@Mod(
    modid = ChronosForgeMod.MODID,
    name = ChronosForgeMod.NAME,
    version = ChronosForgeMod.VERSION,
    acceptableRemoteVersions = "*",
    acceptedMinecraftVersions = "[1.12,1.13)")
public final class ChronosForgeMod {
    public static final String MODID = "chronosbackup";
    public static final String NAME = "Chronos Backup";
    public static final String VERSION = "@VERSION@";
    private static volatile MinecraftServer activeServer;
    private static final ForgeBackupWorldController WORLD_CONTROLLER = new ForgeBackupWorldController();
    private static final ShellMessenger MESSENGER = new ForgeShellMessenger(() -> activeServer);
    private static final ShellCommandRegistrar COMMAND_REGISTRAR = new ForgeCommandRegistrar();

    @Mod.EventHandler
    public void onServerStarted(FMLServerStartedEvent event) {
        activeServer = FMLCommonHandler.instance().getMinecraftServerInstance();
        HookBridge.worldStarted(
                new ForgeServerEnvironment(activeServer), activeServer, MESSENGER, WORLD_CONTROLLER);
        COMMAND_REGISTRAR.register(activeServer.getCommandManager());
    }

    @Mod.EventHandler
    public void onServerStopped(FMLServerStoppedEvent event) {
        HookBridge.worldStopped();
        activeServer = null;
    }
}
