package com.magicjinn.chronos.shell.forge;

import com.magicjinn.chronos.core.Core;
import com.magicjinn.chronos.core.ShellMessenger;
import com.magicjinn.chronos.shell.HookBridge;
import com.magicjinn.chronos.shell.ShellCommandRegistrar;
import com.magicjinn.chronos.shell.mojmap.common.MojmapBackupWorldController;
import com.magicjinn.chronos.shell.mojmap.common.MojmapServerEnvironment;
import com.magicjinn.chronos.shell.mojmap.common.MojmapShellMessenger;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLDedicatedServerSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * Forge 1.20.6+ (Java FML 50.x, Mojang mappings) — same hooks as {@linkplain
 * com.magicjinn.chronos.shell.neoforge.ChronosNeoForgeMod NeoForge}, using
 * LexForge / MinecraftForge
 * events.
 */
@Mod(ChronosForgeMod.MOD_ID)
public final class ChronosForgeMod {
    public static final String MOD_ID = "chronosbackup";
    private static volatile MinecraftServer activeServer;
    private static final MojmapBackupWorldController WORLD_CONTROLLER = new MojmapBackupWorldController();
    private static final ShellMessenger MESSENGER = new MojmapShellMessenger(() -> activeServer);
    private static final ShellCommandRegistrar COMMAND_REGISTRAR = new ForgeCommandRegistrar();

    /** Forge may reflectively instantiate the mod class with a no-arg constructor. */
    public ChronosForgeMod() {
        this(FMLJavaModLoadingContext.get().getModEventBus());
    }

    public ChronosForgeMod(IEventBus modBus) {
        modBus.addListener(this::onClientSetup);
        modBus.addListener(this::onDedicatedServerSetup);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        Core.OnLoaderStarted(Core.LoaderEnvironment.CLIENT);
    }

    private void onDedicatedServerSetup(FMLDedicatedServerSetupEvent event) {
        Core.OnLoaderStarted(Core.LoaderEnvironment.DEDICATED_SERVER);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        activeServer = server;
        HookBridge.worldStarted(new MojmapServerEnvironment(server), server, MESSENGER, WORLD_CONTROLLER);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        COMMAND_REGISTRAR.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        HookBridge.worldStopped();
        activeServer = null;
    }
}
