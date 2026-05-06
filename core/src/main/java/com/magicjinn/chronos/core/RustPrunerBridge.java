package com.magicjinn.chronos.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class RustPrunerBridge {
    private static final Object LOAD_LOCK = new Object();
    private static volatile boolean loaded;

    private RustPrunerBridge() {}

    static void pruneMinecraftWorld(Path worldPath, int spentTimeRequirementSeconds, int maxWorkerThreads)
            throws IOException {
        if (worldPath == null || !Files.isDirectory(worldPath)) {
            return;
        }
        ensureLoaded();
        int code = pruneWorldNative(
                worldPath.toAbsolutePath().toString(),
                spentTimeRequirementSeconds,
                maxWorkerThreads);
        if (code != 0) {
            throw new IOException("Native rust-pruner failed with status code " + code);
        }
    }

    private static void ensureLoaded() throws IOException {
        if (loaded) {
            return;
        }
        synchronized (LOAD_LOCK) {
            if (loaded) {
                return;
            }
            String resourcePath = "/natives/" + osId() + "-" + archId() + "/" + nativeLibraryFileName();
            try (InputStream in = RustPrunerBridge.class.getResourceAsStream(resourcePath)) {
                if (in == null) {
                    throw new IOException("Missing native rust-pruner resource: " + resourcePath);
                }
                String suffix = nativeLibraryFileName();
                int dot = suffix.lastIndexOf('.');
                String ext = dot >= 0 ? suffix.substring(dot) : "";
                Path extracted = Files.createTempFile("chronos-rust-pruner-", ext);
                extracted.toFile().deleteOnExit();
                Files.copy(in, extracted, StandardCopyOption.REPLACE_EXISTING);
                System.load(extracted.toAbsolutePath().toString());
                loaded = true;
            }
        }
    }

    private static String osId() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "macos";
        }
        return "linux";
    }

    private static String archId() {
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if ("amd64".equals(arch) || "x86_64".equals(arch)) {
            return "x86_64";
        }
        if ("aarch64".equals(arch) || "arm64".equals(arch)) {
            return "aarch64";
        }
        return arch.replaceAll("[^a-z0-9_]+", "_");
    }

    private static String nativeLibraryFileName() {
        String os = osId();
        if ("windows".equals(os)) {
            return "rust_pruner.dll";
        }
        if ("macos".equals(os)) {
            return "librust_pruner.dylib";
        }
        return "librust_pruner.so";
    }

    private static native int pruneWorldNative(
            String worldFolderPath,
            int spentTimeRequirementSeconds,
            int maxWorkerThreads);
}
