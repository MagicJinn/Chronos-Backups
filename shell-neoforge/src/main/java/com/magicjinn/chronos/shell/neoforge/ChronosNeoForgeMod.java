package com.magicjinn.chronos.shell.neoforge;

import com.magicjinn.chronos.core.ShellMessenger;
import com.magicjinn.chronos.shell.HookBridge;
import com.magicjinn.chronos.shell.ShellCommandRegistrar;
import com.magicjinn.chronos.shell.mojmap.MojmapBackupWorldController;
import com.magicjinn.chronos.shell.mojmap.MojmapServerEnvironment;
import com.magicjinn.chronos.shell.mojmap.MojmapShellMessenger;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

@Mod(ChronosNeoForgeMod.MOD_ID)
public final class ChronosNeoForgeMod {
    public static final String MOD_ID = "chronosbackup";
    private static volatile MinecraftServer activeServer;
    private static final MojmapBackupWorldController WORLD_CONTROLLER = new MojmapBackupWorldController();
    private static final ShellMessenger MESSENGER = new MojmapShellMessenger(() -> activeServer);
    private static final ShellCommandRegistrar COMMAND_REGISTRAR = new NeoForgeCommandRegistrar();

    public ChronosNeoForgeMod(IEventBus modBus) {
        NeoForge.EVENT_BUS.register(this);
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
