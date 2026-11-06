package com.magicjinn.chronos.shell;

/**
 * Root and subcommand names for {@code /chronos} (Brigadier and legacy tab
 * completion).
 */
public final class ChronosCommandLiterals {
    public static final String ROOT = "chronos";
    public static final String BACKUP = "backup";
    public static final String CANCEL = "cancel";
    public static final String SPEEDTEST = "speedtest";
    public static final String LIST = "list";
    public static final String EXPORT = "export";
    public static final String RELOAD = "reload";

    /** Tab-completion candidates for the first argument. */
    public static final String[] SUBCOMMANDS = {
            BACKUP, CANCEL, SPEEDTEST, LIST, EXPORT, RELOAD
    };
}
