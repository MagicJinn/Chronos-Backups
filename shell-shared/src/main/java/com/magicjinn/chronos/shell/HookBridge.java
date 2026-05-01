package com.magicjinn.chronos.shell;

import com.magicjinn.chronos.core.Scheduler;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Small glue between loader-specific hooks and the version-agnostic core.
 */
public final class HookBridge {
    private static final AtomicBoolean WORLD_START_FIRED = new AtomicBoolean(false);

    public static void worldStarted() {
        if (!WORLD_START_FIRED.compareAndSet(false, true)) {
            return;
        }
        System.out.println("Hook checking in");
        Scheduler.onWorldStarted();
    }

    public static void worldStopped() {
        WORLD_START_FIRED.set(false);
        Scheduler.onWorldStopped();
    }

    private HookBridge() {}
}
