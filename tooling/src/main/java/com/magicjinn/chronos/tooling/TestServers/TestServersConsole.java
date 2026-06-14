package com.magicjinn.chronos.tooling.TestServers;

/**
 * ANSI-colored console output for Docker test runs. Respects {@code NO_COLOR}.
 */
final class TestServersConsole {
    private static final boolean ANSI = System.getenv("NO_COLOR") == null && useAnsi();

    private TestServersConsole() {
    }

    static void success(String message) {
        println(color(message, "32"));
    }

    static void failure(String message) {
        println(color(message, "31"), true);
    }

    static void warn(String message) {
        println(color(message, "33"), true);
    }

    static void info(String message) {
        System.out.println(message);
    }

    static void retry(String message) {
        println(color(message, "90"));
    }

    private static void println(String message) {
        System.out.println(message);
    }

    private static void println(String message, boolean err) {
        if (err) {
            System.err.println(message);
        } else {
            System.out.println(message);
        }
    }

    private static String color(String message, String code) {
        if (!ANSI) {
            return message;
        }
        return "\u001B[" + code + "m" + message + "\u001B[0m";
    }

    private static boolean useAnsi() {
        if (Boolean.getBoolean("chronos.testServers.forceColor")) {
            return true;
        }
        if (Boolean.getBoolean("chronos.testServers.noColor")) {
            return false;
        }
        String term = System.getenv("TERM");
        if (term != null && !term.isBlank() && !"dumb".equals(term)) {
            return true;
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win");
    }
}
