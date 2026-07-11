package com.magicjinn.chronos.tooling;

import com.magicjinn.chronos.tooling.TestServers.BukkitPluginLoaderAvailability;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Reads {@code loaderKeys} / legacy {@code loaderKey} from compile-group loader blocks. */
public final class CompileGroupLoaders {
    private CompileGroupLoaders() {
    }

    public static boolean definesLoaderConfig(Map<String, Object> config) {
        return !resolveLoaderKeys(config).isEmpty();
    }

    @SuppressWarnings("unchecked")
    public static List<String> resolveLoaderKeys(Map<String, Object> config) {
        Object keys = config.get("loaderKeys");
        if (keys instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                String value = str(item);
                if (!value.isBlank()) {
                    out.add(value);
                }
            }
            if (!out.isEmpty()) {
                return List.copyOf(out);
            }
        }
        String single = str(config.get("loaderKey"));
        if (!single.isBlank()) {
            return List.of(single);
        }
        return List.of();
    }

    /**
     * Loader suffix in collected jar names ({@code chronosbackups-*-plugin.jar}). Plugin
     * builds always emit {@code -plugin}; Bukkit-family test servers reuse that artifact.
     */
    public static String resolveJarLoaderKey(Map<String, Object> config, String testLoaderKey) {
        String jarLoader = str(config.get("jarLoaderKey"));
        if (!jarLoader.isBlank()) {
            return jarLoader;
        }
        if (BukkitPluginLoaderAvailability.isBukkitFamilyLoader(testLoaderKey)) {
            return "PLUGIN";
        }
        return testLoaderKey;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
