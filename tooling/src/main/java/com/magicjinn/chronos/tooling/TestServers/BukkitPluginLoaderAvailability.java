package com.magicjinn.chronos.tooling.TestServers;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Which Minecraft versions each Bukkit-family test-server loader can download from itzg/minecraft-server. */
public final class BukkitPluginLoaderAvailability {
    private static final Gson GSON = new Gson();
    private static final Path AVAILABILITY_FILE = Path.of(
            "tooling/src/main/java/com/magicjinn/chronos/tooling/TestServers/plugin-loader-availability.json");
    private static volatile BukkitPluginLoaderAvailability cached;

    private final Map<String, Set<String>> normalizedByLoader;

    private BukkitPluginLoaderAvailability(Map<String, Set<String>> normalizedByLoader) {
        this.normalizedByLoader = normalizedByLoader;
    }

    public static BukkitPluginLoaderAvailability load(Path repoRoot) throws IOException {
        Path file = repoRoot.resolve(AVAILABILITY_FILE);
        Type type = new TypeToken<Map<String, Object>>() {
        }.getType();
        @SuppressWarnings("unchecked")
        Map<String, Object> root = GSON.fromJson(Files.readString(file), type);
        @SuppressWarnings("unchecked")
        Map<String, List<String>> loaders = (Map<String, List<String>>) root.get("loaders");
        Map<String, Set<String>> out = new java.util.HashMap<>();
        for (Map.Entry<String, List<String>> entry : loaders.entrySet()) {
            Set<String> normalized = new HashSet<>();
            for (String version : entry.getValue()) {
                normalized.addAll(normalizeListedVersion(version));
            }
            out.put(entry.getKey().toUpperCase(Locale.ROOT), Set.copyOf(normalized));
        }
        BukkitPluginLoaderAvailability availability = new BukkitPluginLoaderAvailability(out);
        if (cached == null) {
            cached = availability;
        }
        return availability;
    }

    /** Loader keys listed under {@code loaders} in {@link #AVAILABILITY_FILE}. */
    public static boolean isBukkitFamilyLoader(String loaderKey) {
        try {
            return cached().listsLoader(loaderKey);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private boolean listsLoader(String loaderKey) {
        return normalizedByLoader.containsKey(loaderKey.toUpperCase(Locale.ROOT));
    }

    private static BukkitPluginLoaderAvailability cached() throws IOException {
        BukkitPluginLoaderAvailability instance = cached;
        if (instance != null) {
            return instance;
        }
        synchronized (BukkitPluginLoaderAvailability.class) {
            if (cached == null) {
                cached = load(TestServers.ROOT);
            }
            return cached;
        }
    }

    public boolean supports(String loaderKey, String compileVersion) {
        Set<String> versions = normalizedByLoader.get(loaderKey.toUpperCase(Locale.ROOT));
        if (versions == null) {
            return true;
        }
        return versions.contains(compileVersion)
                || versions.contains(normalizeCompileVersion(compileVersion));
    }

    /** Versions from a compile block that at least one listed loader can host. */
    public List<String> filterSupportedByAnyLoader(List<String> loaderKeys, List<String> compileVersions) {
        return compileVersions.stream()
                .filter(version -> loaderKeys.stream().anyMatch(loader -> supports(loader, version)))
                .toList();
    }

    private static Set<String> normalizeListedVersion(String version) {
        Set<String> keys = new HashSet<>();
        keys.add(version.trim());
        keys.add(normalizeCompileVersion(version.trim()));
        return keys;
    }

    /** Keys used for lookup: full semver plus short line aliases (1.14.0 -> also 1.14). */
    static String normalizeCompileVersion(String version) {
        int lastDot = version.lastIndexOf('.');
        if (lastDot > 0 && version.indexOf('.') != lastDot && "0".equals(version.substring(lastDot + 1))) {
            return version.substring(0, lastDot);
        }
        return version;
    }
}
