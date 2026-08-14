package com.magicjinn.chronos.core;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Unified logging for Chronos core and shell code.
 * <p>
 * All Chronos log lines use the {@value #LOG_NAME} Log4j logger so output is
 * consistent across loaders and subsystems.
 */
public final class ChronosLogger {
    public static final String LOG_NAME = "ChronosBackups";

    private static final Logger LOG = LogManager.getLogger(LOG_NAME);

    private ChronosLogger() {}

    public static void info(String message) {
        LOG.info(message);
    }

    public static void warn(String message) {
        LOG.warn(message);
    }

    public static void error(String message) {
        LOG.error(message);
    }

    public static void error(String message, Throwable throwable) {
        LOG.error(message, throwable);
    }
}
