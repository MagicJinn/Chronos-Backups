package com.magicjinn.cloudintegration;

import java.io.IOException;
import java.nio.file.Path;

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
    public void uploadBackup(Path localBackup, String worldName) throws IOException {
        throw new IOException("Dropbox upload is not implemented yet");
    }

    @Override
    public void trimOldBackups(String worldName, int maxStored) throws IOException {
        if (maxStored < 1) {
            return;
        }
        throw new IOException("Dropbox retention trim is not implemented yet");
    }

    @Override
    public void shutdown() {
        // TODO
    }
}
