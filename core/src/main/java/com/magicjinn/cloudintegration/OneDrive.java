package com.magicjinn.cloudintegration;

import java.io.IOException;

public class OneDrive implements CloudIntegration {
    public static final OneDrive INSTANCE = new OneDrive();

    private static boolean ready = false;

    private OneDrive() {}

    @Override
    public String getId() {
        return "onedrive";
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
        // TODO: Implement OneDrive initialization
    }

    @Override
    public void synchronize() throws IOException {
        // TODO: Implement OneDrive synchronization
    }

    @Override
    public void shutdown() {
        // TODO
    }
}
