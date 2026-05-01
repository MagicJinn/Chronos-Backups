package com.magicjinn.chronos.core;

public final class Pruner {

    private Pruner() {}

    public static String getMinecraftServerVersion(ServerEnvironment environment) {
        if (environment == null) {
            throw new IllegalArgumentException("environment");
        }
        return environment.getMinecraftVersion();
    }
}
