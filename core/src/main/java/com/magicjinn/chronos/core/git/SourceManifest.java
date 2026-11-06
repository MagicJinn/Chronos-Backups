package com.magicjinn.chronos.core.git;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

/**
 * We cannot do a git-diff of the post-prune files, because this would require
 * pruning files before diff, which wastes compute. Therefor, we store a
 * manifest of the previously live source files in a special directory. We
 * compare it to the current live source files to get the list of files that
 * were changed. If changed, re-prune the files.
 */
public final class SourceManifest {
    public static final int SCHEMA_VERSION = 1; // Increment when the on-disk layout changes.
    public static final String CHRONOS_DIR = ".chronos";
    public static final String MANIFEST_FILE_NAME = "source-manifest.json";
    public static final String META_FILE_NAME = "backup-meta.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public int version = SCHEMA_VERSION;

    /**
     * Hash of prune settings + blacklist that affect pruned output. If changed, all
     * source files must be re-pruned.
     */
    public String prunePolicyHash = "";

    public Map<String, FileEntry> files = new LinkedHashMap<String, FileEntry>();

    public static final class FileEntry {
        public String hash = "";
        public long size;

        public FileEntry(String hash, long size) {
            this.hash = hash;
            this.size = size;
        }
    }

    public static Path chronosDir(Path worktree) {
        return worktree.resolve(CHRONOS_DIR);
    }

    public static Path manifestPath(Path worktree) {
        return chronosDir(worktree).resolve(MANIFEST_FILE_NAME);
    }

    public static Path metaPath(Path worktree) {
        return chronosDir(worktree).resolve(META_FILE_NAME);
    }

    public static SourceManifest empty(String prunePolicyHash) {
        SourceManifest m = new SourceManifest();
        m.prunePolicyHash = prunePolicyHash == null ? "" : prunePolicyHash;
        return m;
    }

    public static SourceManifest load(Path path) throws IOException {
        if (path == null || !Files.isRegularFile(path))
            return empty("");

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            SourceManifest loaded = GSON.fromJson(reader, SourceManifest.class);
            if (loaded == null)
                return empty("");

            if (loaded.files == null)
                loaded.files = new LinkedHashMap<String, FileEntry>();

            if (loaded.prunePolicyHash == null)
                loaded.prunePolicyHash = "";

            if (loaded.version <= 0)
                loaded.version = SCHEMA_VERSION;

            return loaded;
        } catch (JsonParseException e) {
            throw new IOException("Invalid Chronos source manifest at " + path + ": " + e.getMessage(), e);
        }
    }

    public void save(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null)
            Files.createDirectories(parent);

        if (files == null)
            files = new LinkedHashMap<String, FileEntry>();

        version = SCHEMA_VERSION;
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(this, writer);
        }
    }

    /**
     * Stable policy id. When this changes, every region file must be re-pruned even
     * if source hashes match.
     */
    public static String computePrunePolicyHash(
            boolean pruneChunks,
            int pruneTimeRequirementSeconds,
            List<String> copyBlacklist) {
        StringBuilder sb = new StringBuilder(128);
        sb.append("schema=").append(SCHEMA_VERSION);
        sb.append(";pruneChunks=").append(pruneChunks);
        sb.append(";pruneSeconds=").append(Math.max(0, pruneTimeRequirementSeconds));
        sb.append(";blacklist=");
        List<String> sorted = new ArrayList<String>();
        if (copyBlacklist != null)
            for (String entry : copyBlacklist) {
                if (entry != null && !entry.trim().isEmpty())
                    sorted.add(entry.trim().replace('\\', '/'));
            }

        // Sort the blacklist to ensure consistent ordering.
        Collections.sort(sorted);
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(sorted.get(i));
        }
        return sha256Hex(sb.toString());
    }

    public boolean policyMatches(String expectedPolicyHash) {
        return Objects.equals(
                prunePolicyHash == null ? "" : prunePolicyHash,
                expectedPolicyHash == null ? "" : expectedPolicyHash);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(dig.length * 2);
            for (byte b : dig) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
