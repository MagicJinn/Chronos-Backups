package com.magicjinn.chronos.shell.fabric;

import com.magicjinn.chronos.core.ShellMessenger;
import com.magicjinn.chronos.shell.mojmap.common.MojmapBackupWorldController;
import com.magicjinn.chronos.shell.mojmap.common.MojmapShellMessenger;
import net.fabricmc.api.ModInitializer;
import net.minecraft.server.MinecraftServer;

public final class ChronosFabricEntrypoint implements ModInitializer {
    static volatile MinecraftServer activeServer;
    static final MojmapBackupWorldController WORLD_CONTROLLER = new MojmapBackupWorldController();
    static final ShellMessenger MESSENGER = new MojmapShellMessenger(() -> activeServer);

    @Override
    public void onInitialize() {
        FabricBootstrap.onInitialize();
    }
}
