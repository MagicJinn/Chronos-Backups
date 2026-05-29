package com.magicjinn.chronos.shell.forge;

import com.magicjinn.chronos.shell.ChronosConstants;
import com.magicjinn.chronos.shell.HookBridge;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLDedicatedServerSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * Mojmap-era Forge 1.18+ using {@link ServerStartedEvent} /
 * {@link RegisterCommandsEvent}.
 */
@Mod(ChronosForgeMod.MOD_ID)
public final class ChronosForgeMod {
    public static final String MOD_ID = ChronosConstants.MODID;

    /**
     * Forge may reflectively instantiate the mod class with a no-arg constructor.
     */
    public ChronosForgeMod() {
        this(FMLJavaModLoadingContext.get().getModEventBus());
    }

    public ChronosForgeMod(IEventBus modBus) {
        modBus.addListener((FMLClientSetupEvent event) -> MojmapForgeModKernel.onLoaderStartedClient());
        modBus.addListener(
                (FMLDedicatedServerSetupEvent event) -> MojmapForgeModKernel.onLoaderStartedDedicated());
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            HookBridge.serverTick();
        }
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        MojmapForgeModKernel.onDedicatedServerStarted(event.getServer());
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        MojmapForgeModKernel.registerCommands(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        MojmapForgeModKernel.onDedicatedServerStopped();
    }
}
