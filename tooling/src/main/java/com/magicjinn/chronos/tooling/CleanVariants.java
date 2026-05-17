package com.magicjinn.chronos.tooling;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

public final class CleanVariants {
    // Backoff schedule for retrying deletes when a file is locked (typical on
    // Windows when an IDE/AV/Gradle daemon still holds a handle). Total ~4.1s.
    private static final long[] RETRY_DELAYS_MS = { 50, 150, 400, 1000, 2500 };

    private CleanVariants() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        Path root = locateRepoRoot();
        Path variants = root.resolve("variants");

        if (!Files.exists(variants)) {
            System.out.println("Nothing to clean: " + variants + " does not exist.");
            return;
        }
        if (!Files.isDirectory(variants)) {
            throw new IOException("Refusing to delete non-directory: " + variants);
        }

        System.out.println("Force-deleting " + variants);
        long start = System.nanoTime();
        forceDeleteTree(variants);
        List<Path> remaining = collectRemaining(variants);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        if (!remaining.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("Failed to delete ").append(remaining.size())
                    .append(" entries (likely locked by another process or pending-deletion on Windows):\n");
            int shown = 0;
            for (Path p : remaining) {
                if (shown >= 10) {
                    sb.append("  ... and ").append(remaining.size() - shown).append(" more\n");
                    break;
                }
                sb.append("  - ").append(p).append('\n');
                shown++;
            }
            sb.append("Try closing IDEs / running './gradlew --stop' and run cleanVariants again.");
            throw new IOException(sb.toString());
        }

        System.out.println("Deleted variants/ in " + elapsedMs + " ms.");
    }

    private static void forceDeleteTree(Path root) throws IOException, InterruptedException {
        List<Path> entries;
        try (var walk = Files.walk(root)) {
            entries = walk.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path p : entries) {
            tryDeleteWithRetries(p);
        }
    }

    private static void tryDeleteWithRetries(Path path) throws InterruptedException {
        for (int attempt = 0; attempt <= RETRY_DELAYS_MS.length; attempt++) {
            try {
                Files.deleteIfExists(path);
                // On Windows, delete on a path with an open handle can succeed (marks the
                // entry "delete pending") yet leave it visible until the handle releases.
                // Treat success as authoritative only if the entry actually disappears.
                if (!Files.exists(path)) {
                    return;
                }
            } catch (NoSuchFileException ignored) {
                return;
            } catch (AccessDeniedException ade) {
                // Most commonly: read-only attribute on Windows, or transient share lock.
                clearReadOnly(path);
            } catch (IOException ignored) {
                // Transient lock or non-empty directory (child still being released). Retry.
            }
            if (attempt < RETRY_DELAYS_MS.length) {
                Thread.sleep(RETRY_DELAYS_MS[attempt]);
            }
        }
        // Last-ditch: ask the JVM to retry at shutdown. Harmless if it doesn't help.
        path.toFile().deleteOnExit();
    }

    private static List<Path> collectRemaining(Path root) throws IOException {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (var walk = Files.walk(root)) {
            return walk.sorted(Comparator.reverseOrder()).toList();
        }
    }

    private static void clearReadOnly(Path path) {
        try {
            path.toFile().setWritable(true, false);
        } catch (Throwable ignored) {
            // Best effort. Some FS attributes can't be flipped, that's fine.
        }
    }

    private static Path locateRepoRoot() {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        Path cursor = cwd;
        for (int i = 0; i < 6 && cursor != null; i++) {
            Path marker = cursor.resolve("gradle/chronos-compile-groups.json");
            if (Files.exists(marker))
                return cursor;
            cursor = cursor.getParent();
        }
        return cwd;
    }
}
