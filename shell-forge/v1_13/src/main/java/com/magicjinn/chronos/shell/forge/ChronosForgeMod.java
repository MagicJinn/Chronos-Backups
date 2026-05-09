package com.magicjinn.chronos.shell.forge;

import com.magicjinn.chronos.core.Core;
import com.magicjinn.chronos.core.ShellMessenger;
import com.magicjinn.chronos.shell.ChronosConstants;
import com.magicjinn.chronos.shell.HookBridge;
import com.magicjinn.chronos.shell.ShellCommandRegistrar;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.command.CommandSource;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLDedicatedServerSetupEvent;
import net.minecraftforge.fml.event.server.FMLServerStartedEvent;
import net.minecraftforge.fml.event.server.FMLServerStartingEvent;
import net.minecraftforge.fml.event.server.FMLServerStoppedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(ChronosForgeMod.MODID)
public final class ChronosForgeMod {
    public static final String MODID = ChronosConstants.MODID;
    public static final String NAME = ChronosConstants.NAME;
    public static final String VERSION = "@VERSION@";
    private static volatile MinecraftServer activeServer;
    private static final ForgeBackupWorldController WORLD_CONTROLLER = new ForgeBackupWorldController();
    private static final ShellMessenger MESSENGER = new ForgeShellMessenger(() -> activeServer);
    private static final ShellCommandRegistrar COMMAND_REGISTRAR = new ForgeCommandRegistrar();

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
        CommandDispatcher<CommandSource> dispatcher = event.getCommandDispatcher();
        COMMAND_REGISTRAR.register(dispatcher);
    }

    @SubscribeEvent
    public void onServerStarted(FMLServerStartedEvent event) {
        activeServer = event.getServer();
        HookBridge.worldStarted(
                new ForgeServerEnvironment(activeServer), activeServer, MESSENGER, WORLD_CONTROLLER);
    }

    @SubscribeEvent
    public void onServerStopped(FMLServerStoppedEvent event) {
        HookBridge.worldStopped();
        activeServer = null;
    }
}
