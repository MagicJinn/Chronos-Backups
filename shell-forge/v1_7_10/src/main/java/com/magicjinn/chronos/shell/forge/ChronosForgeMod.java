package com.magicjinn.chronos.shell.forge;

import com.magicjinn.chronos.core.Core;
import com.magicjinn.chronos.core.ShellMessenger;
import com.magicjinn.chronos.shell.ChronosConstants;
import com.magicjinn.chronos.shell.HookBridge;
import com.magicjinn.chronos.shell.ShellCommandRegistrar;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStoppedEvent;
import cpw.mods.fml.relauncher.FMLLaunchHandler;
import cpw.mods.fml.relauncher.Side;
import net.minecraft.server.MinecraftServer;

@Mod(modid = ChronosForgeMod.MODID, name = ChronosForgeMod.NAME, version = ChronosForgeMod.VERSION, acceptableRemoteVersions = "*", acceptedMinecraftVersions = "[1.7,1.8)")
public final class ChronosForgeMod {
    public static final String MODID = ChronosConstants.MODID;
    public static final String NAME = ChronosConstants.NAME;
    public static final String VERSION = "@VERSION@";
    private static volatile MinecraftServer activeServer;
    private static final ForgeBackupWorldController WORLD_CONTROLLER = new ForgeBackupWorldController();
    private static final ShellMessenger MESSENGER = new ForgeShellMessenger(() -> activeServer);
    private static final ShellCommandRegistrar COMMAND_REGISTRAR = new ForgeCommandRegistrar();

    @Mod.EventHandler
    public void onInitialization(FMLInitializationEvent event) {
        Side physical = FMLLaunchHandler.side();
        if (physical == Side.CLIENT) {
            Core.OnLoaderStarted(Core.LoaderEnvironment.CLIENT);
        } else if (physical == Side.SERVER) {
            Core.OnLoaderStarted(Core.LoaderEnvironment.DEDICATED_SERVER);
        }
    }

    @Mod.EventHandler
    public void onServerStarted(FMLServerStartedEvent event) {
        activeServer = FMLCommonHandler.instance().getMinecraftServerInstance();
        HookBridge.worldStarted(new ForgeServerEnvironment(activeServer), activeServer, MESSENGER, WORLD_CONTROLLER);
        COMMAND_REGISTRAR.register(activeServer.getCommandManager());
    }

    @Mod.EventHandler
    public void onServerStopped(FMLServerStoppedEvent event) {
        HookBridge.worldStopped();
        activeServer = null;
    }
}
