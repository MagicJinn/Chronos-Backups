package com.magicjinn.chronos.core.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.magicjinn.chronos.core.BackupRuntimeContext;
import com.magicjinn.chronos.core.RustPrunerBridge;
import com.magicjinn.chronos.core.SaveRootDiscovery;
import com.magicjinn.chronos.core.config.Config;

/**
 * Incremental Git backup. Hash live sources, copy/prune only changed files into
 * the per-world worktree, commit + tag. (Hash is not the same as a git diff. We
 * need to do this due to the nature of Chronos)
 */
public final class GitBackupEngine {
    private static final Gson META_GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Local incremental backup only (hash, copy, prune, commit).
     * {@code maxStoredBackups} is not applied in git mode. Remote push is
     * intentionally separate so backup duration excludes network time.
     */
    public static void runIncrementalBackup(
            BackupRuntimeContext context,
            Path layoutRoot,
            List<Path> saveContainers,
            Path worktree,
            String backupId)
            throws IOException {
        GitBackupRepository repo = new GitBackupRepository(worktree);
        repo.ensureInitialized();

        String policyHash = SourceManifest.computePrunePolicyHash(
                Config.getPruneChunksEnabled(),
                Config.getPruneTimeRequirementSeconds(),
                Config.getCopyBlacklist());
        SourceManifest previous = SourceManifest.load(SourceManifest.manifestPath(worktree));
        boolean forceAll = !previous.policyMatches(policyHash);

        List<Path> sources = saveContainersToCopy(layoutRoot, saveContainers);
        Map<String, SourceManifest.FileEntry> mergedFiles =
                new LinkedHashMap<String, SourceManifest.FileEntry>();
        List<String> allChangedWorktreeRel = new ArrayList<String>();
        List<String> allDeletedWorktreeRel = new ArrayList<String>();
        int copied = 0;

        Path scanTmp = Files.createTempFile("chronos-git-scan-", ".json");
        try {
            for (Path sourceRoot : sources) {
                Path dest = SaveRootDiscovery.snapshotPathForSourceRoot(
                        layoutRoot, sourceRoot, worktree);
                String prefix = worktreePrefix(worktree, dest);

                Map<String, SourceManifest.FileEntry> previousForSource =
                        filterByPrefix(previous.files, prefix);
                SourceManifest scoped = SourceManifest.empty(previous.prunePolicyHash);
                scoped.files.putAll(previousForSource);

                RustPrunerBridge.scanWorldSources(
                        sourceRoot,
                        Config.getCopyBlacklist(),
                        SourceScanResult.filesJson(scoped),
                        policyHash,
                        forceAll,
                        scanTmp,
                        Config.getPruneMaxWorkerThreads());
                SourceScanResult scan = SourceScanResult.load(scanTmp);

                List<String> changedRel = scan.changed;
                if (!changedRel.isEmpty()) {
                    int[] outCopied = new int[1];
                    RustPrunerBridge.copySelectedFiles(
                            sourceRoot,
                            dest,
                            changedRel,
                            Config.getPruneMaxWorkerThreads(),
                            outCopied);
                    copied += outCopied[0];
                }

                for (String rel : changedRel) {
                    allChangedWorktreeRel.add(joinPrefix(prefix, rel));
                }
                for (String rel : scan.deleted) {
                    String workRel = joinPrefix(prefix, rel);
                    allDeletedWorktreeRel.add(workRel);
                    Path victim = worktree.resolve(workRel.replace('/', java.io.File.separatorChar));
                    Files.deleteIfExists(victim);
                }
                for (Map.Entry<String, SourceManifest.FileEntry> e : scan.files.entrySet()) {
                    mergedFiles.put(joinPrefix(prefix, e.getKey()), e.getValue());
                }

                boolean needsPrune = Config.getPruneChunksEnabled()
                        && (forceAll || anyRegionChanged(changedRel));
                if (needsPrune) {
                    context.logInfo(
                            "Chronos backups: pruning changed regions in " + dest.getFileName() + "...");
                    RustPrunerBridge.pruneWorld(
                            dest,
                            Config.getPruneTimeRequirementSeconds(),
                            Config.getPruneMaxWorkerThreads());
                }
            }
        } finally {
            Files.deleteIfExists(scanTmp);
        }

        SourceManifest next = SourceManifest.empty(policyHash);
        next.files.putAll(mergedFiles);
        next.save(SourceManifest.manifestPath(worktree));
        writeBackupMeta(worktree, backupId, policyHash, repo.usingNativeGit());

        context.logInfo(
                "Chronos backups: git incremental copy updated "
                        + copied
                        + " file(s), removed "
                        + allDeletedWorktreeRel.size()
                        + ", unchanged skipped.");

        String sha = repo.commitSnapshot(backupId, "Chronos backup " + backupId);
        context.logInfo("Chronos backups: git commit " + sha + " ("
                + (repo.usingNativeGit() ? "native git" : "gitoxide") + ")");
    }

    // This method is currently not used anywhere. Backing up to Google Drive is
    // good enough for now.
    /** Pushes the per-world git repo to {@code remoteUrl}. Not included in backup duration. */
    public static void pushRemote(Path worktree, String remoteUrl) throws IOException {
        if (remoteUrl == null || remoteUrl.trim().isEmpty())
            return;

        new GitBackupRepository(worktree).push(remoteUrl.trim());
    }

    public static List<String> listSnapshots(Path worktree) throws IOException {
        return new GitBackupRepository(worktree).listBackupTagsNewestFirst();
    }

    public static Path exportSnapshot(
            Path worktree, String tagOrId, Path destDir) throws IOException {
        GitBackupRepository repo = new GitBackupRepository(worktree);
        repo.exportTagTo(tagOrId, destDir);
        return destDir;
    }

    private static void writeBackupMeta(
            Path worktree, String backupId, String policyHash, boolean nativeGit)
            throws IOException {
        Map<String, Object> meta = new LinkedHashMap<String, Object>();
        meta.put("backupId", backupId);
        meta.put("prunePolicyHash", policyHash);
        meta.put("gitBackend", nativeGit ? "native" : "gitoxide");
        Path path = SourceManifest.metaPath(worktree);
        Files.createDirectories(path.getParent());
        Files.write(path, META_GSON.toJson(meta).getBytes(StandardCharsets.UTF_8));
    }

    private static boolean anyRegionChanged(List<String> changedRel) {
        for (String rel : changedRel) {
            String n = rel.replace('\\', '/');
            if (n.contains("/region/") || n.startsWith("region/"))
                if (n.endsWith(".mca"))
                    return true;
        }
        return false;
    }

    private static String worktreePrefix(Path worktree, Path dest) {
        Path w = worktree.toAbsolutePath().normalize();
        Path d = dest.toAbsolutePath().normalize();
        if (w.equals(d))
            return "";

        return w.relativize(d).toString().replace('\\', '/');
    }

    private static String joinPrefix(String prefix, String rel) {
        String r = rel.replace('\\', '/');
        if (prefix == null || prefix.isEmpty())
            return r;

        return prefix + "/" + r;
    }

    private static Map<String, SourceManifest.FileEntry> filterByPrefix(
            Map<String, SourceManifest.FileEntry> all, String prefix) {
        Map<String, SourceManifest.FileEntry> out =
                new LinkedHashMap<String, SourceManifest.FileEntry>();
        if (all == null)
            return out;

        if (prefix == null || prefix.isEmpty()) {
            out.putAll(all);
            return out;
        }

        String p = prefix.endsWith("/") ? prefix : prefix + "/";
        for (Map.Entry<String, SourceManifest.FileEntry> e : all.entrySet()) {
            String key = e.getKey().replace('\\', '/');
            if (key.startsWith(p))
                out.put(key.substring(p.length()), e.getValue());
        }
        return out;
    }

    private static List<Path> saveContainersToCopy(Path layoutRoot, List<Path> saveContainers) {
        if (saveContainers == null || saveContainers.isEmpty()) {
            List<Path> one = new ArrayList<Path>(1);
            one.add(layoutRoot);
            return one;
        }
        return saveContainers;
    }
}
