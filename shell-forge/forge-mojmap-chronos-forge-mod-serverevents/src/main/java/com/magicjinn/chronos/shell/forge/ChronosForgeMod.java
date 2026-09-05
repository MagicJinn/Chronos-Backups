package com.magicjinn.chronos.shell.forge;

import com.magicjinn.chronos.shell.ChronosConstants;
import com.magicjinn.chronos.shell.HookBridge;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLDedicatedServerSetupEvent;

/**
 * Mojmap-era Forge 1.18+ using {@link ServerStartedEvent} /
 * {@link RegisterCommandsEvent}.
 *
 * <p>Mod-bus setup uses {@link Mod.EventBusSubscriber} so this shared entrypoint
 * never needs {@code FMLJavaModLoadingContext.get()} (deprecated on 1.19+/1.20.1)
 * and still constructs with a no-arg ctor on Forge 1.18.
 */
@Mod(ChronosForgeMod.MOD_ID)
public final class ChronosForgeMod {
    public static final String MOD_ID = ChronosConstants.MODID;

    public ChronosForgeMod() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Mod.EventBusSubscriber(modid = MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
    private static final class ModBusHooks {
        private ModBusHooks() {}

        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            MojmapForgeModKernel.onLoaderStartedClient();
        }

        @SubscribeEvent
        public static void onDedicatedSetup(FMLDedicatedServerSetupEvent event) {
            MojmapForgeModKernel.onLoaderStartedDedicated();
        }
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
