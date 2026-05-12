package com.magicjinn.chronos.shell.forge;

import com.magicjinn.chronos.shell.ChronosConstants;
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
 * Forge 1.14–1.15: Brigadier via {@link FMLServerStartingEvent#getCommandDispatcher()}.
 */
@Mod(ChronosForgeMod.MOD_ID)
public final class ChronosForgeMod {
    public static final String MOD_ID = ChronosConstants.MODID;

    public ChronosForgeMod() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onClientSetup);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onDedicatedSetup);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        MojmapForgeModKernel.onLoaderStartedClient();
    }

    private void onDedicatedSetup(FMLDedicatedServerSetupEvent event) {
        MojmapForgeModKernel.onLoaderStartedDedicated();
    }

    @SubscribeEvent
    public void onServerStarting(FMLServerStartingEvent event) {
        MojmapForgeModKernel.registerCommands(event.getCommandDispatcher());
    }

    @SubscribeEvent
    public void onServerStarted(FMLServerStartedEvent event) {
        MojmapForgeModKernel.onDedicatedServerStarted(event.getServer());
    }

    @SubscribeEvent
    public void onServerStopped(FMLServerStoppedEvent event) {
        MojmapForgeModKernel.onDedicatedServerStopped();
    }
}
