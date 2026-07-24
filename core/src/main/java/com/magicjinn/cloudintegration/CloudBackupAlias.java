package com.magicjinn.cloudintegration;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import com.magicjinn.chronos.core.ChronosBackupArtifacts;
import com.magicjinn.chronos.core.Core;

/**
 * Per-install fruit alias that prefixes remote cloud world folders so multiple
 * Chronos servers sharing one Drive account do not trim each other's backups.
 *
 * <p>
 * Stored next to config as {@code config/chronos-alias.txt}. Empty (or
 * comment-only) means no prefix. A value like {@code cherry} makes the remote
 * folder {@code cherry-world}.
 */
public final class CloudBackupAlias {
    private static final Logger LOG = Logger.getLogger(CloudBackupAlias.class.getName());

    private static final String ALIAS_FILE_NAME = "chronos-alias.txt";
    private static final String CONFIG_FOLDER_NAME = "config";
    private static final String FRUITS_RESOURCE = "/chronos-fruits.json";

    private static final String FILE_HEADER =
            "# Chronos cloud backup folder alias.\n"
                    + "# Leave empty to use the world name as-is on the cloud.\n"
                    + "# If set (e.g. cherry), the remote folder becomes cherry-world.";

    private static final Gson GSON = new Gson();
    private static final TypeToken<List<String>> STRING_LIST = new TypeToken<List<String>>() {};

    private static volatile List<String> cachedFruits;

    private CloudBackupAlias() {}

    /** Path to {@code config/chronos-alias.txt} under the run directory. */
    public static Path aliasFilePath() {
        return Core.RunningDirectory.resolve(CONFIG_FOLDER_NAME).resolve(ALIAS_FILE_NAME);
    }

    /** Whether the alias file already exists on disk. */
    public static boolean aliasFileExists() {
        return Files.isRegularFile(aliasFilePath());
    }

    /**
     * Reads the alias token from the file. Returns {@code ""} when missing,
     * empty, or comment-only. Comments ({@code #...}) and blank lines are ignored.
     */
    public static String readAlias() {
        Path path = aliasFilePath();
        if (!Files.isRegularFile(path))
            return "";
        
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            return parseAliasLines(lines);
        } catch (IOException e) {
            LOG.warning("Could not read " + ALIAS_FILE_NAME + ": " + e.getMessage());
            return "";
        }
    }

    /** Parses alias lines. Returns the first non-blank, non-comment line. */
    static String parseAliasLines(List<String> lines) {
        if (lines == null)
            return "";
        
        for (String raw : lines) {
            if (raw == null)
                continue;
            
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#"))
                continue;
            
            return line;
        }
        return "";
    }

    /**
     * Writes the alias file with a comment header. {@code alias} may be empty
     * (no prefix).
     */
    public static void writeAlias(String alias) throws IOException {
        Path path = aliasFilePath();
        Path parent = path.getParent();
        if (parent != null)
            Files.createDirectories(parent);
        
        String token = alias == null ? "" : alias.trim();
        String body = FILE_HEADER + token + "\n";
        Files.write(
                path,
                body.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    /**
     * Remote world folder name for cloud sync. Empty alias keeps the
     * world name. Otherwise {@code {alias}-{worldname}}.
     */
    public static String remoteFolderName(String worldName) {
        return remoteFolderName(worldName, readAlias());
    }

    /** Same as {@link #remoteFolderName(String)} with an explicit alias token. */
    public static String remoteFolderName(String worldName, String alias) {
        String sanitized = ChronosBackupArtifacts.sanitizeWorldDirName(worldName);
        String token = alias == null ? "" : alias.trim();
        if (token.isEmpty())
            return sanitized;
        
        return token + "-" + sanitized;
    }

    /**
     * Picks the first free alias for {@code worldName} given existing remote
     * folder names under the Chronos root.
     *
     * <ol>
     * <li>bare sanitized world name (empty alias)</li>
     * <li>each fruit as {@code fruit-world}</li>
     * <li>{@code apple1-world}, {@code apple2-world}, ... using the first fruit</li>
     * </ol>
     *
     * @return the alias token to store (empty string when bare world is free)
     */
    public static String pickAlias(Set<String> existingFolderNames, String worldName) {
        String sanitized = ChronosBackupArtifacts.sanitizeWorldDirName(worldName);
        Set<String> taken = normalizeFolderSet(existingFolderNames);

        if (!taken.contains(sanitized.toLowerCase(Locale.ROOT)))
            return "";

        List<String> fruits = loadFruits();
        for (String fruit : fruits) {
            String candidate = fruit + "-" + sanitized;
            if (!taken.contains(candidate.toLowerCase(Locale.ROOT)))
                return fruit;
        }

        String base = fruits.isEmpty() ? "apple" : fruits.get(0);
        int n = 1;
        while (true) {
            String fruitN = base + n;
            String candidate = fruitN + "-" + sanitized;
            if (!taken.contains(candidate.toLowerCase(Locale.ROOT))) 
                return fruitN;

            n++;
            if (n > 10_000) {
                throw new IllegalStateException("Could not find a free cloud folder alias");
            }
        }
    }

    private static Set<String> normalizeFolderSet(Set<String> existingFolderNames) {
        Set<String> taken = new HashSet<String>();
        if (existingFolderNames == null)
            return taken;

        for (String name : existingFolderNames) {
            if (name == null || name.isEmpty())
                continue;
            
            taken.add(name.toLowerCase(Locale.ROOT));
        }
        return taken;
    }

    /** Fruits from the bundled JSON resource, cached after first load. */
    public static List<String> loadFruits() {
        List<String> cached = cachedFruits;
        if (cached != null)
            return cached;
        
        synchronized (CloudBackupAlias.class) {
            if (cachedFruits != null)
                return cachedFruits;
            
            List<String> loaded = readFruitsFromResource();
            cachedFruits = Collections.unmodifiableList(loaded);
            return cachedFruits;
        }
    }

    /** Test helper to inject fruits without touching the classpath resource. */
    static void setFruitsForTest(List<String> fruits) {
        if (fruits == null) {
            cachedFruits = null;
        } else {
            cachedFruits = Collections.unmodifiableList(new ArrayList<String>(fruits));
        }
    }

    private static List<String> readFruitsFromResource() {
        InputStream in = CloudBackupAlias.class.getResourceAsStream(FRUITS_RESOURCE);
        if (in == null) {
            LOG.warning("Missing classpath resource " + FRUITS_RESOURCE + ", using built-in fruits.");
            return defaultFruits();
        }
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            List<String> parsed = GSON.fromJson(reader, STRING_LIST.getType());
            List<String> cleaned = cleanFruitList(parsed);
            if (cleaned.isEmpty()) {
                LOG.warning(FRUITS_RESOURCE + " parsed empty, using built-in fruits.");
                return defaultFruits();
            }
            return cleaned;
        } catch (IOException | JsonParseException e) {
            LOG.warning("Could not read " + FRUITS_RESOURCE + ": " + e.getMessage());
            return defaultFruits();
        }
    }

    /** This is lame */
    private static List<String> defaultFruits() {
        List<String> out = new ArrayList<String>();
        out.add("apple");
        out.add("cherry");
        out.add("mango");
        return out;
    }

    /** Drops null/blank entries from a list of strings. */
    static List<String> cleanFruitList(List<String> parsed) {
        List<String> out = new ArrayList<String>();
        if (parsed == null)
            return out;
        
        for (String fruit : parsed) {
            if (fruit == null)
                continue;
            
            String trimmed = fruit.trim();
            if (!trimmed.isEmpty())
                out.add(trimmed);
        }
        return out;
    }

    /** Parses a JSON string array like {@code ["apple","cherry"]}. */
    static List<String> parseFruitJsonArray(String json) {
        try {
            return cleanFruitList(GSON.fromJson(json, STRING_LIST.getType()));
        } catch (JsonParseException e) {
            return new ArrayList<String>();
        }
    }
}
