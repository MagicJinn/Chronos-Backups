package com.magicjinn.chronos.core.git;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.magicjinn.chronos.core.ChronosLogger;

/**
 * Native {@code git} on PATH. Preferred over bundled gitoxide when available.
 */
public final class NativeGit {
    private static final Object PROBE_LOCK = new Object();
    private static volatile Boolean available;
    private static volatile String versionLine = "";

    public static boolean isAvailable() {
        Boolean cached = available;
        if (cached != null)
            return cached.booleanValue();

        synchronized (PROBE_LOCK) {
            if (available != null)
                return available.booleanValue();

            ProbeResult probe = probe();
            available = Boolean.valueOf(probe.ok);
            versionLine = probe.version;
            if (probe.ok) {
                ChronosLogger.info("Chronos git: using native git (" + probe.version + ")");
            } else {
                ChronosLogger.info(
                        "Chronos git: native git not on PATH; using bundled gitoxide."
                                + " Install git for faster ops and remote push (git-lfs recommended for large worlds).");
            }
            return available.booleanValue();
        }
    }

    public static String versionLine() {
        isAvailable();
        return versionLine;
    }

    public static void ensureInitialized(Path worktree) throws IOException {
        Files.createDirectories(worktree);
        Path gitDir = worktree.resolve(".git");
        if (!Files.exists(gitDir))
            runRequired(worktree, "init", "-b", "main");

        run(worktree, "config", "core.autocrlf", "false");
        run(worktree, "config", "pack.window", "0");
        run(worktree, "config", "core.bigFileThreshold", "1073741824");
        run(worktree, "config", "user.name", "Chronos Backups");
        run(worktree, "config", "user.email", "chronos@localhost");
    }

    public static String commitAndTag(Path worktree, String backupId, String message)
            throws IOException {
        ensureInitialized(worktree);
        // Export used to leave HEAD detached; keep commits on main so push advances the branch.
        ensureOnMain(worktree);
        runRequired(worktree, "add", "-A");
        String msg = message == null || message.isEmpty()
                ? "Chronos backup " + backupId
                : message;
        // Allow empty commit so scheduled backups still create a tagged restore point.
        runRequired(worktree, "commit", "--allow-empty", "-m", msg);
        String tag = GitBackupRepository.tagNameForBackupId(backupId);
        run(worktree, "tag", "-d", tag);
        runRequired(worktree, "tag", tag);
        String sha = runCapture(worktree, "rev-parse", "HEAD");
        return sha == null ? "" : sha.trim();
    }

    public static List<String> listBackupTagsNewestFirst(Path worktree) throws IOException {
        ensureInitialized(worktree);
        String out = runCapture(worktree, "tag", "-l");
        if (out == null || out.trim().isEmpty())
            return Collections.emptyList();

        List<String> tags = new ArrayList<String>();
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty())
                tags.add(t);
        }
        Collections.sort(tags, Comparator.reverseOrder());
        return tags;
    }

    public static void exportTagTo(Path worktree, String tagName, Path destDir) throws IOException {
        String tag = normalizeTag(tagName);
        ensureInitialized(worktree);
        if (Files.exists(destDir)) {
            throw new IOException("Export destination already exists: " + destDir);
        }
        Files.createDirectories(destDir);
        Path gitDir = worktree.resolve(".git");
        // restore --source does not move HEAD (unlike checkout, which detached the backup repo).
        runRequired(
                worktree,
                "--git-dir=" + gitDir.toAbsolutePath().normalize(),
                "--work-tree=" + destDir.toAbsolutePath().normalize(),
                "restore",
                "--source=" + tag,
                "--worktree",
                "--",
                ".");
    }

    public static void push(Path worktree, String remoteUrl) throws IOException {
        if (worktree == null || remoteUrl == null || remoteUrl.trim().isEmpty()) {
            return;
        }
        if (!isAvailable()) {
            throw new IOException(
                    "Native git is required for remote push but was not found on PATH."
                            + " Install git (and git-lfs for large worlds).");
        }
        String url = remoteUrl.trim();
        ensureInitialized(worktree);
        ensureOnMain(worktree);
        ensureRemote(worktree, url);
        // Chronos owns this remote. --force (not --force-with-lease) so recreated
        // remotes still push.
        runRequired(
                worktree,
                "push",
                "--force",
                "-u",
                "chronos-origin",
                GitBackupRepository.BRANCH);
        runRequired(worktree, "push", "chronos-origin", "--tags");
    }

    private static void ensureRemote(Path worktree, String url) throws IOException {
        String existing = runCapture(worktree, "remote");
        boolean hasOrigin = false;
        if (existing != null) {
            for (String line : existing.split("\n")) {
                if ("chronos-origin".equals(line.trim())) {
                    hasOrigin = true;
                    break;
                }
            }
        }
        if (hasOrigin) {
            runRequired(worktree, "remote", "set-url", "chronos-origin", url);
        } else {
            runRequired(worktree, "remote", "add", "chronos-origin", url);
        }
    }

    // This means something, I'm sure.
    /** Attach HEAD to main at the current tip without rewriting worktree files. */
    private static void ensureOnMain(Path worktree) throws IOException {
        String branch = runCapture(worktree, "rev-parse", "--abbrev-ref", "HEAD");
        if (branch != null && GitBackupRepository.BRANCH.equals(branch.trim()))
            return;

        String sha = runCapture(worktree, "rev-parse", "HEAD");
        if (sha == null || sha.trim().isEmpty())
            throw new IOException("Cannot resolve HEAD while repairing detached git backup repo");

        runRequired(worktree, "branch", "-f", GitBackupRepository.BRANCH, sha.trim());
        runRequired(worktree, "symbolic-ref", "HEAD", "refs/heads/" + GitBackupRepository.BRANCH);
    }

    private static String normalizeTag(String tagName) throws IOException {
        if (tagName == null || tagName.trim().isEmpty())
            throw new IOException("Backup tag is empty");

        return tagName.trim();
    }

    private static ProbeResult probe() {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String line;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                line = reader.readLine();
            }
            boolean finished = p.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return ProbeResult.fail();
            }

            if (p.exitValue() != 0 || line == null || line.trim().isEmpty())
                return ProbeResult.fail();

            return ProbeResult.ok(line.trim());
        } catch (Exception e) {
            return ProbeResult.fail();
        }
    }

    private static void run(Path worktree, String... args) {
        try {
            runRequired(worktree, args);
        } catch (IOException ignored) {
            // Whatever
        }
    }

    private static void runRequired(Path worktree, String... args) throws IOException {
        runInternal(worktree, true, args);
    }

    private static String runCapture(Path worktree, String... args) throws IOException {
        return runInternal(worktree, true, args);
    }

    private static String runInternal(Path worktree, boolean required, String... args)
            throws IOException {
        List<String> cmd = new ArrayList<String>();
        cmd.add("git");
        for (String arg : args) {
            cmd.add(arg);
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(worktree.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (out.length() > 0)
                    out.append('\n');
                out.append(line);
            }
        }
        try {
            boolean finished = p.waitFor(10, TimeUnit.MINUTES);
            if (!finished) {
                p.destroyForcibly();
                throw new IOException("git command timed out: " + cmd);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("git command interrupted: " + cmd, e);
        }
        if (p.exitValue() != 0) {
            if (!required)
                return out.toString();

            throw new IOException(
                    "git " + joinArgs(args) + " failed (" + p.exitValue() + "): " + out);
        }
        return out.toString();
    }

    private static String joinArgs(String[] args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0)
                sb.append(' ');

            sb.append(args[i]);
        }
        return sb.toString();
    }

    private static final class ProbeResult {
        final boolean ok;
        final String version;

        private ProbeResult(boolean ok, String version) {
            this.ok = ok;
            this.version = version;
        }

        static ProbeResult ok(String version) {
            return new ProbeResult(true, version);
        }

        static ProbeResult fail() {
            return new ProbeResult(false, "");
        }
    }
}
