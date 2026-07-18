package com.magicjinn.cloudintegration;

import java.io.IOException;

public class Dropbox implements CloudIntegration {
    public static final Dropbox INSTANCE = new Dropbox();

    private static boolean ready = false;

    private Dropbox() {}

    @Override
    public String getId() {
        return "dropbox";
    }

    @Override
    public boolean isEnabled() {
        return false; // TODO: config flag
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    @Override
    public void initialize() {
        // TODO: Implement Dropbox initialization
    }

    @Override
    public void synchronize() throws IOException {
        // TODO: Implement Dropbox synchronization
    }

    @Override
    public void shutdown() {
        // TODO
    }
}
