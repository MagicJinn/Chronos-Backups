package com.magicjinn.chronos.shell.forge;

import com.magicjinn.chronos.core.Core;
import com.magicjinn.chronos.core.ShellMessenger;
import com.magicjinn.chronos.shell.ChronosConstants;
import com.magicjinn.chronos.shell.HookBridge;
import com.magicjinn.chronos.shell.ShellCommandRegistrar;
import com.magicjinn.chronos.shell.mojmap.common.MojmapShellMessenger;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLDedicatedServerSetupEvent;
import net.minecraftforge.fml.event.server.FMLServerStartedEvent;
import net.minecraftforge.fml.event.server.FMLServerStartingEvent;
import net.minecraftforge.fml.event.server.FMLServerStoppedEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * Forge 1.14-1.15: Brigadier via
 * {@link FMLServerStartingEvent#getCommandDispatcher()}.
 * Uses {@link Forge114ServerEnvironment} / {@link Forge114BackupWorldController} so one
 * 1.14.4-built jar still runs on 1.14.2-1.14.3 Forge servers.
 */
@Mod(ChronosForgeMod.MOD_ID)
public final class ChronosForgeMod {
    public static final String MOD_ID = ChronosConstants.MODID;

    private static volatile MinecraftServer activeServer;
    private static final Forge114BackupWorldController WORLD_CONTROLLER = new Forge114BackupWorldController();
    private static final ShellMessenger MESSENGER = new MojmapShellMessenger(() -> activeServer);
    private static final ShellCommandRegistrar COMMAND_REGISTRAR = new Forge114CommandRegistrar();

    public ChronosForgeMod() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onClientSetup);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onDedicatedSetup);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        Core.OnLoaderStarted(Core.LoaderEnvironment.CLIENT);
    }

    private void onDedicatedSetup(FMLDedicatedServerSetupEvent event) {
        Core.OnLoaderStarted(Core.LoaderEnvironment.DEDICATED_SERVER);
    }

    @SubscribeEvent
    public void onServerStarting(FMLServerStartingEvent event) {
        COMMAND_REGISTRAR.register(event.getCommandDispatcher());
    }

    @SubscribeEvent
    public void onServerStarted(FMLServerStartedEvent event) {
        activeServer = event.getServer();
        event.getServer().addTickable(HookBridge::serverTick);
        HookBridge.worldStarted(
                new Forge114ServerEnvironment(activeServer), activeServer, MESSENGER, WORLD_CONTROLLER);
    }

    @SubscribeEvent
    public void onServerStopped(FMLServerStoppedEvent event) {
        HookBridge.worldStopped();
        activeServer = null;
    }
}
