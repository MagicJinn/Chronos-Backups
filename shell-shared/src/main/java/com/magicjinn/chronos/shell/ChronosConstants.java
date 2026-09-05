package com.magicjinn.chronos.shell;

public class ChronosConstants {
    public static final String NAME = "Chronos Backups";
    /** Log4j logger name used across Chronos core and shell code. */
    public static final String LOG_NAME = com.magicjinn.chronos.core.ChronosLogger.LOG_NAME;
    public static final String MODID = "chronosbackups";
    /** Divider for log messages. */
    public static final String DIVIDER = "============================================================";
    /** Default world/save folder name if not specified. */
    public static final String DEFAULT_WORLD_NAME = "world";

    /**
     * Minecraft's dedicated/integrated server thread name. Forge and Mojmap shells
     * use this for off-thread scheduling checks. Paper/Folia use their own APIs
     * instead.
     */
    public static final String MINECRAFT_SERVER_THREAD_NAME = "Server thread";

    /** Whether the current thread is Minecraft's main server thread. */
    public static boolean isMinecraftServerThread() {
        return MINECRAFT_SERVER_THREAD_NAME.equals(Thread.currentThread().getName());
    }
}
