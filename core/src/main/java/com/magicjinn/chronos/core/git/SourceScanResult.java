package com.magicjinn.chronos.core.git;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.magicjinn.chronos.core.git.SourceManifest.FileEntry;

/** Parsed output of the native xxHash3 source scan. */
public final class SourceScanResult {
    private static final Gson GSON = new Gson();

    public int version = 1;
    public String prunePolicyHash = "";
    public List<String> changed = new ArrayList<String>();
    public List<String> deleted = new ArrayList<String>();
    public List<String> unchanged = new ArrayList<String>();
    public Map<String, FileEntry> files = new LinkedHashMap<String, FileEntry>();

    public static SourceScanResult load(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            SourceScanResult loaded = GSON.fromJson(reader, SourceScanResult.class);
            if (loaded == null)
                return empty();

            if (loaded.changed == null)
                loaded.changed = new ArrayList<String>();

            if (loaded.deleted == null)
                loaded.deleted = new ArrayList<String>();

            if (loaded.unchanged == null)
                loaded.unchanged = new ArrayList<String>();

            if (loaded.files == null)
                loaded.files = new LinkedHashMap<String, FileEntry>();
            return loaded;
        } catch (JsonParseException e) {
            throw new IOException("Invalid source scan result at " + path + ": " + e.getMessage(), e);
        }
    }

    public static SourceScanResult empty() {
        return new SourceScanResult();
    }

    public SourceManifest toManifest(String prunePolicyHash) {
        SourceManifest m = SourceManifest.empty(prunePolicyHash);
        if (files != null)
            m.files.putAll(files);

        return m;
    }

    /** Serialize previous manifest files map for the native scanner. */
    public static String filesJson(SourceManifest previous) {
        Map<String, FileEntry> files = previous == null || previous.files == null
                ? Collections.<String, FileEntry>emptyMap()
                : previous.files;

        return GSON.toJson(files);
    }
}
