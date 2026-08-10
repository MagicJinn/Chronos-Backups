package com.magicjinn.cloudintegration;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.magicjinn.chronos.core.Backupper;
import com.magicjinn.chronos.shell.ChronosConstants;

/**
 * Schedules cloud {@link CloudIntegration#synchronize()} work off the server
 * thread
 *
 * <p>
 * Retry: first attempt runs as soon as a sync is requested (after a
 * successful backup, startup, OAuth, or speedtest end). On failure, retries with
 * exponential backoff (30s → 1m → 2m → 5m → 15m cap)
 */
public final class CloudSync {
    private static final Logger LOG = LogManager.getLogger(ChronosConstants.LOG_NAME);

    /** Backoff steps after consecutive sync failures (in milliseconds) */
    private static final long[] FAILURE_BACKOFF_MS = {
            30_000L,
            60_000L,
            120_000L,
            300_000L,
            900_000L
    };

    private static final AtomicBoolean pending = new AtomicBoolean(false);
    private static final AtomicBoolean workerRunning = new AtomicBoolean(false);

    private static volatile boolean shutdown;
    private static volatile int failureCount;
    private static volatile long nextAttemptEpochMs;

    private static volatile List<CloudIntegration> integrations = java.util.Collections.emptyList();

    private CloudSync() {}

    public static void registerIntegrations(List<CloudIntegration> list) {
        if (list == null) {
            integrations = Collections.<CloudIntegration>emptyList();
        } else {
            integrations = list;
        }
    }

    /**
     * Queue a sync. Safe to call often, work is coalesced onto one worker.
     * No-op when no cloud integration is enabled.
     */
    public static void requestSync() {
        if (shutdown || !anyEnabled()) 
            return;

        if (anyReady()) {
            failureCount = 0;
            nextAttemptEpochMs = 0L;
        }

        pending.set(true);
        kickWorker();
    }

    /** Shuts down the cloud sync worker. */
    public static void shutdown() {
        shutdown = true;
        pending.set(false);
        failureCount = 0;
        nextAttemptEpochMs = 0L;
    }

    /** Clears shutdown so a later world session can sync again. */
    public static void resetForNewSession() {
        shutdown = false;
    }

    private static boolean anyEnabled() {
        for (CloudIntegration cloud : integrations) {
            if (cloud.isEnabled()) {
                return true;
            }
        }
        return false;
    }

    /** Starts the cloud sync worker. */
    private static void kickWorker() {
        if (!workerRunning.compareAndSet(false, true))
            return;

        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    drain();
                } finally {
                    workerRunning.set(false);
                    if (!shutdown && pending.get()) {
                        kickWorker();
                    }
                }
            }
        }, "chronos-cloud-sync");
        worker.setDaemon(true);
        worker.start();
    }

    /** Drains the cloud sync worker. */
    private static void drain() {
        while (!shutdown) {
            if (!pending.get() && nextAttemptEpochMs <= 0L)
                return;

            if (Backupper.isSpeedtestSessionActive()) {
                // Wait until the burst ends. Do not upload per speedtest backup.
                sleepyQuietly(1000L);
                continue;
            }

            long waitMs = nextAttemptEpochMs - System.currentTimeMillis();
            if (waitMs > 0L) {
                sleepyQuietly(Math.min(waitMs, 1000L));
                continue;
            }

            if (!pending.compareAndSet(true, false))
                return;

            // Just in case, check if a speedtest didn't start between the first and second check
            if (Backupper.isSpeedtestSessionActive()) {
                pending.set(true);
                continue;
            }

            boolean ok = runSynchronizeAll();
            if (shutdown)
                return;

            // Reset if the sync was successful, otherwise increment the failure count
            if (ok) {
                failureCount = 0;
                nextAttemptEpochMs = 0L;
            } else if (anyEnabled()) {
                failureCount++;
                long backoff = backoffMs(failureCount);
                nextAttemptEpochMs = System.currentTimeMillis() + backoff;
                pending.set(true);
                LOG.info("Cloud sync will retry in " + (backoff / 1000L) + " s.");
            }
        }
    }

    private static boolean runSynchronizeAll() {
        if (!anyEnabled())
            return true;

        boolean anyAttempted = false;
        boolean allOk = true;
        for (CloudIntegration cloud : integrations) {
            if (shutdown)
                return false;

            if (!cloud.isEnabled())
                continue;

            if (!cloud.isReady()) {
                // If enabled, but not ready, skip the sync
                LOG.info("Cloud sync skipped for " + cloud.getDisplayName() + " (not ready yet).");
                allOk = false;
                continue;
            }
            anyAttempted = true;
            try {
                LOG.info("Cloud sync starting for " + cloud.getDisplayName() + "...");
                cloud.synchronize();
                LOG.info("Cloud sync finished for " + cloud.getDisplayName() + ".");
            } catch (Exception e) {
                allOk = false;
                LOG.error(shortFailure(cloud.getDisplayName(), e));
            }
        }

        if (!anyAttempted) {
            // Enabled but none ready
            return false;
        }

        return allOk;
    }

    /**
     * Shorten the stack trace to not take up as much of the log when a cloud sync
     * fails
     */
    private static String shortFailure(String displayName, Exception e) {
        StringBuilder sb = new StringBuilder();
        sb.append("Cloud sync failed for ").append(displayName).append(": ");
        sb.append(e.getClass().getSimpleName());
        if (e.getMessage() != null && !e.getMessage().isEmpty())
            sb.append(": ").append(e.getMessage());

        int kept = 0;
        StackTraceElement[] stack = e.getStackTrace();
        for (int i = 0; i < stack.length && kept < 3; i++) {
            if (!stack[i].getClassName().startsWith("com.magicjinn."))
                continue;
            sb.append('\n').append("\tat ").append(stack[i]);
            kept++;
        }
        return sb.toString();
    }

    private static boolean anyReady() {
        for (CloudIntegration cloud : integrations) {
            if (cloud.isEnabled() && cloud.isReady()) 
                return true;
        }
        return false;
    }

    /** Calculates the backoff time based on the failure count. */
    private static long backoffMs(int count) {
        int idx = Math.min(Math.max(count, 1), FAILURE_BACKOFF_MS.length) - 1;
        return FAILURE_BACKOFF_MS[idx];
    }

    private static void sleepyQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
