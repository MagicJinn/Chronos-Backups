package com.magicjinn.chronos.tooling.TestServers.docker;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
// import java.nio.ByteBuffer;
// import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
// import java.nio.file.Files;
// import java.nio.file.Path;
// import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Streams Minecraft server output from Docker: {@code docker logs -f}
 */
public final class DockerContainerLogs {
    private DockerContainerLogs() {
    }

    /**
     * Follows container stdout/stderr via {@code docker logs -f}.
     */
    public static Closeable followContainer(String containerName, Consumer<String> onLine) throws IOException {
        Objects.requireNonNull(containerName, "containerName");
        Objects.requireNonNull(onLine, "onLine");
        ProcessBuilder pb = new ProcessBuilder("docker", "logs", "-f", "--since", "0s", containerName)
                .redirectErrorStream(true);
        Process process = pb.start();
        Thread reader = new Thread(() -> readLines(process, onLine), "docker-logs-" + containerName);
        reader.setDaemon(true);
        reader.start();
        return () -> {
            process.destroy();
            reader.interrupt();
            try {
                reader.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }

    // /**
    // * Polls {@code dataDir/logs/latest.log} for new lines (same approach as smoke
    // * tests).
    // */
    // public static Closeable followLatestLog(Path dataDir, Consumer<String>
    // onLine) {
    // Objects.requireNonNull(dataDir, "dataDir");
    // Objects.requireNonNull(onLine, "onLine");
    // Path latestLog = dataDir.resolve("logs").resolve("latest.log");
    // Thread tail = new Thread(() -> tailLatestLog(latestLog, onLine),
    // "latest-log-" + dataDir.getFileName());
    // tail.setDaemon(true);
    // tail.start();
    // return () -> tail.interrupt();
    // }

    /** Reads lines from the process's input stream. */
    private static void readLines(Process process, Consumer<String> onLine) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                onLine.accept(line);
            }
        } catch (IOException ignored) {
        }
    }

    // private static void tailLatestLog(Path latestLog, Consumer<String> onLine) {
    // long lastSize = 0;
    // StringBuilder pending = new StringBuilder();
    // while (!Thread.currentThread().isInterrupted()) {
    // try {
    // Thread.sleep(250L);
    // } catch (InterruptedException e) {
    // Thread.currentThread().interrupt();
    // return;
    // }
    // try {
    // if (!Files.isRegularFile(latestLog)) {
    // continue;
    // }
    // long size = Files.size(latestLog);
    // if (size < lastSize) {
    // lastSize = 0;
    // pending.setLength(0);
    // }
    // if (size <= lastSize) {
    // continue;
    // }
    // int delta = (int) (size - lastSize);
    // if (delta <= 0) {
    // continue;
    // }
    // ByteBuffer bb = ByteBuffer.allocate(delta);
    // try (FileChannel ch = FileChannel.open(latestLog, StandardOpenOption.READ)) {
    // ch.position(lastSize);
    // while (bb.hasRemaining()) {
    // int n = ch.read(bb);
    // if (n <= 0) {
    // break;
    // }
    // }
    // }
    // lastSize = size;
    // bb.flip();
    // pending.append(StandardCharsets.UTF_8.decode(bb));
    // int nl;
    // while ((nl = pending.indexOf("\n")) >= 0) {
    // String line = pending.substring(0, nl);
    // if (!line.isEmpty() && line.charAt(line.length() - 1) == '\r') {
    // line = line.substring(0, line.length() - 1);
    // }
    // pending.delete(0, nl + 1);
    // if (!line.isEmpty()) {
    // onLine.accept(line);
    // }
    // }
    // } catch (IOException ignored) {
    // }
    // }
    // }
}
