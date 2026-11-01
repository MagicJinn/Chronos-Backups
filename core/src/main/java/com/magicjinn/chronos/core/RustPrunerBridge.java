package com.magicjinn.chronos.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** JNI bridge to the rust native library. */
public final class RustPrunerBridge {
    private static final Object LOAD_LOCK = new Object();
    private static volatile boolean loaded;

    private RustPrunerBridge() {}

    /**
     * Prunes region/entities/poi MCA files in {@code worldPath} (when pruning is
     * enabled and time threshold is positive) and writes a deflate-compressed zip
     * of the full snapshot to {@code zipOutputPath}. Pruned files are streamed into
     * the archive as they are produced. Any files left on disk after pruning are
     * zipped in a follow-up walk.
     */
    /**
     * Prunes region/entities/poi MCA files in {@code worldPath} in place
     */
    public static void pruneWorld(
            Path worldPath,
            int spentTimeRequirementSeconds,
            int maxWorkerThreads)
            throws IOException {
        if (worldPath == null || !Files.isDirectory(worldPath)) {
            return;
        }
        ensureLoaded();
        int code = pruneWorldNative(
                worldPath.toAbsolutePath().normalize().toString(),
                spentTimeRequirementSeconds,
                maxWorkerThreads);
        if (code == 2) {
            throw new InterruptedIOException(
                    "Chronos prune aborted (shutdown, cancel, or interrupt requested).");
        }
        if (code == 4) {
            throw new IOException("Native rust-pruner prune failed to read paths from Java.");
        }
        if (code != 0) {
            throw new IOException("Native rust-pruner prune failed with status code " + code);
        }
    }

    static void pruneWorldToZip(
            Path worldPath,
            Path zipOutputPath,
            int spentTimeRequirementSeconds,
            int maxWorkerThreads)
            throws IOException {
        if (worldPath == null || !Files.isDirectory(worldPath)) {
            return;
        }
        if (zipOutputPath == null) {
            throw new IllegalArgumentException("zipOutputPath is null");
        }
        ensureLoaded();
        int code = pruneWorldToZipNative(
                worldPath.toAbsolutePath().normalize().toString(),
                zipOutputPath.toAbsolutePath().normalize().toString(),
                spentTimeRequirementSeconds,
                maxWorkerThreads);
        if (code == 2) {
            throw new InterruptedIOException(
                    "Chronos prune+zip aborted (shutdown, cancel, or interrupt requested).");
        }
        if (code == 4) {
            throw new IOException("Native rust-pruner prune+zip failed to read paths from Java.");
        }
        if (code != 0) {
            throw new IOException("Native rust-pruner prune+zip failed with status code " + code);
        }
    }

    /**
     * Copies a world folder into the backup cache using the native implementation
     * (parallel file copies, same blacklist rules as the former Java tree walk).
     *
     * @param outCopiedFileCount single-element array, element 0 is set to the
     *                           number of files processed on success
     */
    static void copyWorldToCache(
            Path worldRoot,
            Path cacheDestRoot,
            List<String> copyBlacklistPatterns,
            int maxCopyWorkerThreads,
            int[] outCopiedFileCount)
            throws IOException {
        ensureLoaded();
        if (outCopiedFileCount == null || outCopiedFileCount.length < 1) {
            throw new IllegalArgumentException("outCopiedFileCount must hold at least one int");
        }
        String[] arr = copyBlacklistPatterns == null || copyBlacklistPatterns.isEmpty()
                ? new String[0]
                : copyBlacklistPatterns.toArray(new String[0]);
        int code = copyWorldToCacheNative(
                worldRoot.toAbsolutePath().normalize().toString(),
                cacheDestRoot.toAbsolutePath().normalize().toString(),
                arr,
                maxCopyWorkerThreads,
                outCopiedFileCount);
        if (code == 2) {
            throw new InterruptedIOException(
                    "Chronos world copy aborted (shutdown, cancel, or interrupt requested).");
        }
        if (code == 5) {
            throw new IOException(
                    "Native world copy refused: cache path appears to be inside the world directory.");
        }
        if (code != 0) {
            throw new IOException("Native rust-pruner world copy failed with status code " + code);
        }
    }

    /**
     * Hashes live source files under {@code worldRoot} with xxHash3 and writes a
     * scan-result JSON to {@code resultJsonPath}.
     *
     * @param previousFilesJson Gson JSON object of path -> {@code {hash,size}} from
     *                          the previous manifest (may be {@code "{}"})
     * @param forceAll          when true, every file is treated as changed (prune
     *                          policy invalidation)
     */
    public static void scanWorldSources(
            Path worldRoot,
            List<String> copyBlacklistPatterns,
            String previousFilesJson,
            String prunePolicyHash,
            boolean forceAll,
            Path resultJsonPath,
            int maxWorkerThreads)
            throws IOException {
        ensureLoaded();

        if (resultJsonPath == null)
            throw new IllegalArgumentException("resultJsonPath is null");

        String[] arr = copyBlacklistPatterns == null || copyBlacklistPatterns.isEmpty()
                ? new String[0]
                : copyBlacklistPatterns.toArray(new String[0]);
        int code = scanWorldSourcesNative(
                worldRoot.toAbsolutePath().normalize().toString(),
                arr,
                previousFilesJson == null ? "{}" : previousFilesJson,
                prunePolicyHash == null ? "" : prunePolicyHash,
                forceAll ? 1 : 0,
                resultJsonPath.toAbsolutePath().normalize().toString(),
                maxWorkerThreads);

        if (code != 0)
            throw new IOException("Native rust-pruner source scan failed with status code " + code);
    }

    /**
     * Copies only the listed relative paths from {@code worldRoot} into
     * {@code destRoot}.
     */
    public static void copySelectedFiles(
            Path worldRoot,
            Path destRoot,
            List<String> relativePaths,
            int maxCopyWorkerThreads,
            int[] outCopiedFileCount)
            throws IOException {
        ensureLoaded();

        if (outCopiedFileCount == null || outCopiedFileCount.length < 1)
            throw new IllegalArgumentException("outCopiedFileCount must hold at least one int");

        String[] arr = relativePaths == null || relativePaths.isEmpty()
                ? new String[0]
                : relativePaths.toArray(new String[0]);
        int code = copySelectedFilesNative(
                worldRoot.toAbsolutePath().normalize().toString(),
                destRoot.toAbsolutePath().normalize().toString(),
                arr,
                maxCopyWorkerThreads,
                outCopiedFileCount);

        if (code == 5)
            throw new IOException(
                    "Native selected copy refused: dest path appears to be inside the world directory.");

        if (code != 0)
            throw new IOException("Native rust-pruner selected copy failed with status code " + code);
    }

    /**
     * Polled from native code between copy batches, must stay cheap.
     */
    @SuppressWarnings("unused")
    private static boolean pollAbortCopy() {
        return Backupper.shouldAbortBackupWork();
    }

    @SuppressWarnings("unused")
    private static void logNativeInfo(String message) {
        ChronosLogger.info(message);
    }

    @SuppressWarnings("unused")
    private static void logNativeWarn(String message) {
        ChronosLogger.warn(message);
    }

    @SuppressWarnings("unused")
    private static void logNativeError(String message) {
        ChronosLogger.error(message);
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

    private static native int pruneWorldToZipNative(
            String worldFolderPath,
            String zipOutputPath,
            int spentTimeRequirementSeconds,
            int maxWorkerThreads);

    private static native int copyWorldToCacheNative(
            String worldRootPath,
            String cacheDestPath,
            String[] copyBlacklistPatterns,
            int maxCopyWorkerThreads,
            int[] outCopiedFileCount);

    private static native int scanWorldSourcesNative(
            String worldRootPath,
            String[] copyBlacklistPatterns,
            String previousFilesJson,
            String prunePolicyHash,
            int forceAll,
            String resultJsonPath,
            int maxWorkerThreads);

    private static native int copySelectedFilesNative(
            String worldRootPath,
            String destRootPath,
            String[] relativePaths,
            int maxCopyWorkerThreads,
            int[] outCopiedFileCount);

    private static native int gitInitNative(String worktreePath);

    private static native String gitCommitAndTagNative(
            String worktreePath, String backupId, String message);

    private static native String gitListBackupTagsNative(String worktreePath);

    private static native int gitExportTagNative(
            String worktreePath, String tagName, String destPath);

    /**
     * Bundled gitoxide: init a Git worktree (no-op if {@code .git} already exists).
     */
    public static void gitInit(Path worktree) throws IOException {
        ensureLoaded();
        int code = gitInitNative(worktree.toAbsolutePath().normalize().toString());
        if (code != 0) {
            throw new IOException("gitoxide init failed with status " + code);
        }
    }

    /** Bundled gitoxide: commit the full worktree and create a backup tag. */
    public static String gitCommitAndTag(Path worktree, String backupId, String message)
            throws IOException {
        ensureLoaded();
        String sha = gitCommitAndTagNative(
                worktree.toAbsolutePath().normalize().toString(),
                backupId == null ? "" : backupId,
                message == null ? "" : message);
        if (sha == null || sha.isEmpty()) {
            throw new IOException("gitoxide commit returned no SHA");
        }
        return sha;
    }

    /** Bundled gitoxide: list backup tags, newest first. */
    public static java.util.List<String> gitListBackupTags(Path worktree) throws IOException {
        ensureLoaded();
        String raw = gitListBackupTagsNative(worktree.toAbsolutePath().normalize().toString());
        if (raw == null || raw.trim().isEmpty()) {
            return java.util.Collections.emptyList();
        }
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (String line : raw.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /** Bundled gitoxide: export a tagged snapshot into {@code destDir}. */
    public static void gitExportTag(Path worktree, String tagName, Path destDir)
            throws IOException {
        ensureLoaded();
        int code = gitExportTagNative(
                worktree.toAbsolutePath().normalize().toString(),
                tagName,
                destDir.toAbsolutePath().normalize().toString());
        if (code != 0) {
            throw new IOException("gitoxide export failed with status " + code);
        }
    }
}
