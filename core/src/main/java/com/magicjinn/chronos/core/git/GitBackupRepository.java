package com.magicjinn.chronos.core.git;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import com.magicjinn.chronos.core.RustPrunerBridge;
import com.magicjinn.chronos.core.config.Config;

/**
 * Per-world Git worktree under {@code backups/{world}/}. Prefers native
 * {@code git} when {@link Config#getAllowNativeGit()} and available, otherwise
 * bundled gitoxide via {@link RustPrunerBridge}.
 * Backups are commits on {@code main} tagged with the backup id.
 */
public final class GitBackupRepository {
    // Always commit to main
    public static final String BRANCH = "main";

    private final Path worktree;
    private final boolean useNative;

    public GitBackupRepository(Path worktree) {
        if (worktree == null)
            throw new IllegalArgumentException("worktree is null");

        this.worktree = worktree.toAbsolutePath().normalize();
        this.useNative = Config.getAllowNativeGit() && NativeGit.isAvailable();
    }

    public Path worktree() {
        return worktree;
    }

    public boolean usingNativeGit() {
        return useNative;
    }

    public void ensureInitialized() throws IOException {
        if (useNative) {
            NativeGit.ensureInitialized(worktree);
        } else {
            RustPrunerBridge.gitInit(worktree);
        }
    }

    public String commitSnapshot(String backupId, String message) throws IOException {
        if (useNative)
            return NativeGit.commitAndTag(worktree, backupId, message);

        return RustPrunerBridge.gitCommitAndTag(worktree, backupId, message);
    }

    public List<String> listBackupTagsNewestFirst() throws IOException {
        if (useNative)
            return NativeGit.listBackupTagsNewestFirst(worktree);

        return RustPrunerBridge.gitListBackupTags(worktree);
    }

    public void exportTagTo(String tagName, Path destDir) throws IOException {
        if (useNative) {
            NativeGit.exportTagTo(worktree, tagName, destDir);
        } else {
            RustPrunerBridge.gitExportTag(worktree, tagName, destDir);
        }
    }

    /** Remote push always needs native git (credentials / LFS). */
    public void push(String remoteUrl) throws IOException {
        NativeGit.push(worktree, remoteUrl);
    }

    public static String tagNameForBackupId(String backupId) {
        String id = backupId == null || backupId.trim().isEmpty()
                ? Instant.now().toString()
                : backupId.trim();
        return id.replace(' ', '_');
    }
}
