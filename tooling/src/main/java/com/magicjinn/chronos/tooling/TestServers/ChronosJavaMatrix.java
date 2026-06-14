package com.magicjinn.chronos.tooling.TestServers;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Loads {@code gradle/chronos-java-matrix.json}
 */
public final class ChronosJavaMatrix {
    private static final Gson GSON = new Gson();

    private final List<YearPrefix> yearPrefixes;
    private final List<Map<String, Object>> preYearlyRules;
    private final String nonLegacyDefaultJavaMajor;
    private final String legacyFallbackJavaMajor;
    private final List<PrefixRule> prefixRules;
    private final String gradleToolchainYearlyJavaMajor;
    private final String gradleToolchainDefaultJavaMajor;
    private final String fabricJavaDepYearMinorFormat;

    private ChronosJavaMatrix(
            List<YearPrefix> yearPrefixes,
            List<Map<String, Object>> preYearlyRules,
            String nonLegacyDefaultJavaMajor,
            String legacyFallbackJavaMajor,
            List<PrefixRule> prefixRules,
            String gradleToolchainYearlyJavaMajor,
            String gradleToolchainDefaultJavaMajor,
            String fabricJavaDepYearMinorFormat) {
        this.yearPrefixes = yearPrefixes;
        this.preYearlyRules = preYearlyRules;
        this.nonLegacyDefaultJavaMajor = nonLegacyDefaultJavaMajor;
        this.legacyFallbackJavaMajor = legacyFallbackJavaMajor;
        this.prefixRules = prefixRules;
        this.gradleToolchainYearlyJavaMajor = gradleToolchainYearlyJavaMajor;
        this.gradleToolchainDefaultJavaMajor = gradleToolchainDefaultJavaMajor;
        this.fabricJavaDepYearMinorFormat = fabricJavaDepYearMinorFormat;
    }

    public static ChronosJavaMatrix load(Path file) throws IOException {
        Type type = new TypeToken<Map<String, Object>>() {
        }.getType();
        Map<String, Object> root = GSON.fromJson(Files.readString(file), type);

        List<YearPrefix> yearPrefixes = parseYearPrefixes(castList(root.get("yearPrefixes")));
        List<Map<String, Object>> preYearlyRules = castList(root.get("preYearlyRules"));
        String nonLegacy = str(root.get("nonLegacyDefaultJavaMajor"));
        String legacyFallback = str(root.get("legacyFallbackJavaMajor"));
        List<PrefixRule> prefixRules = parsePrefixRules(root.get("prefixRules"));
        Map<String, Object> gradleToolchain = castMap(root.get("gradleToolchain"));
        String toolchainYearly = str(gradleToolchain.get("yearlyJavaMajor"));
        String toolchainDefault = str(gradleToolchain.get("defaultJavaMajor"));
        Map<String, Object> fabricModJson = castMap(root.get("fabricModJson"));
        String fabricJavaDepY = str(fabricModJson.get("javaDependencyForYearMinorFormat"));

        if (yearPrefixes.isEmpty() || preYearlyRules.isEmpty() || nonLegacy.isBlank() || legacyFallback.isBlank()
                || toolchainYearly.isBlank() || toolchainDefault.isBlank() || fabricJavaDepY.isBlank()) {
            throw new IllegalStateException("Invalid or incomplete Java matrix: " + file);
        }

        return new ChronosJavaMatrix(
                yearPrefixes,
                preYearlyRules,
                nonLegacy,
                legacyFallback,
                prefixRules,
                toolchainYearly,
                toolchainDefault,
                fabricJavaDepY);
    }

    public boolean isYearMinorMc(String mc) {
        return yearPrefixJavaMajor(mc) != null;
    }

    /** JVM/runtime major for any loader (Docker, dedicated server expectations). */
    public String runtimeJavaMajor(String mc) {
        PrefixRule prefix = matchingPrefixRule(mc);
        if (prefix != null) {
            return prefix.runtimeJavaMajor(mc);
        }
        if (!mc.startsWith("1.")) {
            String yearly = yearPrefixJavaMajor(mc);
            return yearly != null ? yearly : nonLegacyDefaultJavaMajor;
        }
        return resolvePreYearlyRules(mc, legacyFallbackJavaMajor);
    }

    /** {@code fabric.mod.json} {@code depends.java} for non-yearly 1.x lines. */
    public String modJsonJavaMajor(String mc) {
        PrefixRule prefix = matchingPrefixRule(mc);
        if (prefix != null) {
            return prefix.modJsonJavaMajorOrCompileRelease();
        }
        if (!mc.startsWith("1.")) {
            return nonLegacyDefaultJavaMajor;
        }
        return resolvePreYearlyRules(mc, legacyFallbackJavaMajor);
    }

    public String compileRelease(String mc) {
        PrefixRule prefix = matchingPrefixRule(mc);
        if (prefix != null) {
            return prefix.compileRelease();
        }
        if (!mc.startsWith("1.")) {
            String yearly = yearPrefixJavaMajor(mc);
            return yearly != null ? yearly : nonLegacyDefaultJavaMajor;
        }
        return resolvePreYearlyRules(mc, legacyFallbackJavaMajor);
    }

    /**
     * JDK major Gradle uses to run the compiler (may be newer than bytecode
     * {@link #compileRelease(String)}).
     */
    public String gradleToolchainMajor(String mc) {
        PrefixRule prefix = matchingPrefixRule(mc);
        if (prefix != null) {
            return prefix.toolchainMajor();
        }
        return isYearMinorMc(mc) ? gradleToolchainYearlyJavaMajor : gradleToolchainDefaultJavaMajor;
    }

    public String fabricJavaDependencyLine(String mc) {
        if (isYearMinorMc(mc)) {
            return fabricJavaDepYearMinorFormat;
        }
        return ">=" + modJsonJavaMajor(mc);
    }

    public ToolchainAndRelease toolchainAndRelease(String mc) {
        PrefixRule prefix = matchingPrefixRule(mc);
        if (prefix != null) {
            return new ToolchainAndRelease(prefix.toolchainMajor(), prefix.compileRelease());
        }
        String major = isYearMinorMc(mc) ? gradleToolchainYearlyJavaMajor : gradleToolchainDefaultJavaMajor;
        return new ToolchainAndRelease(major, major);
    }

    public record ToolchainAndRelease(String toolchainMajor, String compileRelease) {
    }

    private PrefixRule matchingPrefixRule(String mc) {
        if (mc == null) {
            return null;
        }
        for (PrefixRule r : prefixRules) {
            if (mc.startsWith(r.minecraftVersionPrefix())) {
                return r;
            }
        }
        return null;
    }

    private String yearPrefixJavaMajor(String mc) {
        if (mc == null) {
            return null;
        }
        for (YearPrefix yp : yearPrefixes) {
            if (mc.startsWith(yp.prefix())) {
                return yp.javaMajor();
            }
        }
        return null;
    }

    private String resolvePreYearlyRules(String mc, String fallback) {
        String[] p = mc.split("\\.");
        try {
            if (p.length >= 2 && "1".equals(p[0])) {
                int minor = Integer.parseInt(p[1]);
                for (Map<String, Object> rule : preYearlyRules) {
                    Object eq = rule.get("minorEquals");
                    if (eq instanceof Number n && minor == n.intValue()) {
                        return str(rule.get("javaMajor"));
                    }
                    Object maxOnly = rule.get("minorMax");
                    Object min = rule.get("minorMin");
                    if (maxOnly instanceof Number && !(min instanceof Number)) {
                        if (minor <= ((Number) maxOnly).intValue()) {
                            return str(rule.get("javaMajor"));
                        }
                    }
                    if (min instanceof Number mn && maxOnly instanceof Number mx) {
                        if (minor >= mn.intValue() && minor <= mx.intValue()) {
                            return str(rule.get("javaMajor"));
                        }
                    }
                    if (min instanceof Number mn && !(maxOnly instanceof Number)) {
                        if (minor >= mn.intValue()) {
                            return str(rule.get("javaMajor"));
                        }
                    }
                }
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        return fallback;
    }

    private static List<YearPrefix> parseYearPrefixes(List<Map<String, Object>> rows) {
        List<YearPrefix> built = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String prefix = str(row.get("prefix"));
            String major = str(row.get("javaMajor"));
            if (!prefix.isBlank() && !major.isBlank()) {
                built.add(new YearPrefix(prefix, major));
            }
        }
        return List.copyOf(built);
    }

    private static List<PrefixRule> parsePrefixRules(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<PrefixRule> built = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> r = (Map<String, Object>) map;
            String prefix = str(r.get("minecraftVersionPrefix"));
            String tm = str(r.get("toolchainMajor"));
            String cr = str(r.get("compileRelease"));
            if (!prefix.isBlank() && !tm.isBlank() && !cr.isBlank()) {
                int runtimePatchMin = parseOptionalInt(r.get("runtimePatchMin"), -1);
                String runtimeJavaMajor = str(r.get("runtimeJavaMajor"));
                built.add(new PrefixRule(
                        prefix, tm, cr, str(r.get("modJsonJavaMajor")), runtimePatchMin, runtimeJavaMajor));
            }
        }
        return List.copyOf(built);
    }

    private record YearPrefix(String prefix, String javaMajor) {
    }

    private record PrefixRule(
            String minecraftVersionPrefix,
            String toolchainMajor,
            String compileRelease,
            String modJsonJavaMajorOverride,
            int runtimePatchMin,
            String runtimeJavaMajorOverride) {
        String modJsonJavaMajorOrCompileRelease() {
            return modJsonJavaMajorOverride.isBlank() ? compileRelease : modJsonJavaMajorOverride;
        }

        String runtimeJavaMajor(String mc) {
            if (runtimePatchMin >= 0
                    && !runtimeJavaMajorOverride.isBlank()
                    && minecraftPatch(mc) >= runtimePatchMin) {
                return runtimeJavaMajorOverride;
            }
            return compileRelease;
        }
    }

    private static int minecraftPatch(String mc) {
        String[] parts = mc.split("\\.");
        if (parts.length < 3) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int parseOptionalInt(Object value, int defaultValue) {
        if (!(value instanceof Number n)) {
            return defaultValue;
        }
        return n.intValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        if (value instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object value) {
        if (value instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return List.of();
    }

    private static String str(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Number n) {
            double d = n.doubleValue();
            if (d == Math.rint(d)) {
                return String.valueOf((long) d);
            }
        }
        return String.valueOf(value);
    }
}
