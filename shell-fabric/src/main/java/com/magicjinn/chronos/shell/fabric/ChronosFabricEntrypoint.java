package com.magicjinn.chronos.shell.fabric;

import com.magicjinn.chronos.core.ShellMessenger;
import com.magicjinn.chronos.shell.HookBridge;
import com.magicjinn.chronos.shell.ShellCommandRegistrar;
import com.magicjinn.chronos.shell.mojmap.common.MojmapBackupWorldController;
import com.magicjinn.chronos.shell.mojmap.common.MojmapServerEnvironment;
import com.magicjinn.chronos.shell.mojmap.common.MojmapShellMessenger;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;

public final class ChronosFabricEntrypoint implements ModInitializer {
    private static volatile MinecraftServer activeServer;
    private static final MojmapBackupWorldController WORLD_CONTROLLER = new MojmapBackupWorldController();
    private static final ShellMessenger MESSENGER = new MojmapShellMessenger(() -> activeServer);
    private static final ShellCommandRegistrar COMMAND_REGISTRAR = new FabricCommandRegistrar();

    @Override
    public void onInitialize() {
        COMMAND_REGISTRAR.register(null);
        ServerLifecycleEvents.SERVER_STARTED.register(
                server -> {
                    activeServer = server;
                    HookBridge.worldStarted(
                            new MojmapServerEnvironment(server), server, MESSENGER, WORLD_CONTROLLER);
                });
        ServerLifecycleEvents.SERVER_STOPPED.register(
                server -> {
                    HookBridge.worldStopped();
                    activeServer = null;
                });
    }
}
