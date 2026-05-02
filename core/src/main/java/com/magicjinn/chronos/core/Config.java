package com.magicjinn.chronos.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

public class Config {
    private static final Logger LOG = Logger.getLogger(Config.class.getName());

    public static void InitializeConfig() {
        Path configFolder = Core.RunningDirectory.resolve("config");
        try {
            Files.createDirectories(configFolder);
        } catch (IOException e) {
            LOG.severe("Failed to create config folder: " + e.getMessage());
        }

        // TODO: Implement config initialization
    }
}
