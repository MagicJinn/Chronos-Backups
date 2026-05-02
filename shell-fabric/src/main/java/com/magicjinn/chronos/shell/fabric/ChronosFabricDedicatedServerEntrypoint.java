package com.magicjinn.chronos.shell.fabric;

import com.magicjinn.chronos.core.Core;
import net.fabricmc.api.DedicatedServerModInitializer;

public final class ChronosFabricDedicatedServerEntrypoint implements DedicatedServerModInitializer {
    @Override
    public void onInitializeServer() {
        Core.OnLoaderStarted(Core.LoaderEnvironment.DEDICATED_SERVER);
    }
}
