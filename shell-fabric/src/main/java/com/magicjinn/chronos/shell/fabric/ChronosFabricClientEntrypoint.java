package com.magicjinn.chronos.shell.fabric;

import com.magicjinn.chronos.core.Core;
import net.fabricmc.api.ClientModInitializer;

public final class ChronosFabricClientEntrypoint implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Core.OnLoaderStarted(Core.LoaderEnvironment.CLIENT);
    }
}
