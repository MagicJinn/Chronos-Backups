package com.magicjinn.chronos.shell.forge;

import com.magicjinn.chronos.core.Core;
import com.magicjinn.chronos.core.ShellMessenger;
import com.magicjinn.chronos.shell.HookBridge;
import com.magicjinn.chronos.shell.ShellCommandRegistrar;
import com.magicjinn.chronos.shell.mojmap.common.MojmapBackupWorldController;
import com.magicjinn.chronos.shell.mojmap.common.MojmapServerEnvironment;
import com.magicjinn.chronos.shell.mojmap.common.MojmapShellMessenger;
import net.minecraft.server.MinecraftServer;

/**
 * Shared Mojmap-era Forge server wiring (command registrar, messenger, backup hooks). Per-line
 * {@link ChronosForgeMod} classes subscribe to version-specific Forge events and delegate here.
 */
public final class MojmapForgeModKernel {
    private MojmapForgeModKernel() {}

    private static volatile MinecraftServer activeServer;
    private static final MojmapBackupWorldController WORLD_CONTROLLER = new MojmapBackupWorldController();
    private static final ShellMessenger MESSENGER = new MojmapShellMessenger(() -> activeServer);
    private static final ShellCommandRegistrar COMMAND_REGISTRAR = new ForgeCommandRegistrar();

    public static void onLoaderStartedClient() {
        Core.OnLoaderStarted(Core.LoaderEnvironment.CLIENT);
    }

    public static void onLoaderStartedDedicated() {
        Core.OnLoaderStarted(Core.LoaderEnvironment.DEDICATED_SERVER);
    }

    public static void registerCommands(Object dispatcher) {
        COMMAND_REGISTRAR.register(dispatcher);
    }

    public static void onDedicatedServerStarted(MinecraftServer server) {
        activeServer = server;
        HookBridge.worldStarted(new MojmapServerEnvironment(server), server, MESSENGER, WORLD_CONTROLLER);
    }

    public static void onDedicatedServerStopped() {
        HookBridge.worldStopped();
        activeServer = null;
    }
}
