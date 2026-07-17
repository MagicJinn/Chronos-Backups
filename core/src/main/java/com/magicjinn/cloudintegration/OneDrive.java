package com.magicjinn.cloudintegration;

import java.io.IOException;
import java.nio.file.Path;

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
    public void uploadBackup(Path localBackup, String worldName) throws IOException {
        throw new IOException("OneDrive upload is not implemented yet");
    }

    @Override
    public void trimOldBackups(String worldName, int maxStored) throws IOException {
        if (maxStored < 1) {
            return;
        }
        throw new IOException("OneDrive retention trim is not implemented yet");
    }

    @Override
    public void shutdown() {
        // TODO
    }
}
