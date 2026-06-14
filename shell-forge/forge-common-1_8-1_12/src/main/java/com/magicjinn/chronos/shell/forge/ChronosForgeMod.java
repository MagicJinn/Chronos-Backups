package com.magicjinn.chronos.shell.forge;

import com.magicjinn.chronos.core.Core;
import com.magicjinn.chronos.core.ChatCommandStyle;
import com.magicjinn.chronos.core.ShellMessenger;
import com.magicjinn.chronos.shell.ChronosConstants;
import com.magicjinn.chronos.shell.HookBridge;
import com.magicjinn.chronos.shell.ShellCommandRegistrar;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartedEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.FMLLaunchHandler;
import net.minecraftforge.fml.relauncher.Side;

/**
 * Shared {@code ChronosForgeMod} for Minecraft 1.8-1.12 Forge shells,
 * {@link ForgeShellMcRange} supplies acceptedMinecraftVersions per line.
 */
@Mod(modid = ChronosForgeMod.MODID, name = ChronosForgeMod.NAME, version = ChronosForgeMod.VERSION, acceptableRemoteVersions = "*", acceptedMinecraftVersions = ForgeShellMcRange.ACCEPTED_MINECRAFT_VERSIONS)
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
        FMLCommonHandler.instance().bus().register(this);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            HookBridge.serverTick();
        }
    }

    @Mod.EventHandler
    public void onServerStarted(FMLServerStartedEvent event) {
        activeServer = FMLCommonHandler.instance().getMinecraftServerInstance();
        HookBridge.worldStarted(
                new ForgeServerEnvironment(activeServer),
                activeServer,
                MESSENGER,
                WORLD_CONTROLLER,
                ChatCommandStyle.LEGACY_SAY);
        COMMAND_REGISTRAR.register(activeServer.getCommandManager());
    }

    @Mod.EventHandler
    public void onServerStopped(FMLServerStoppedEvent event) {
        HookBridge.worldStopped();
        activeServer = null;
    }
}
