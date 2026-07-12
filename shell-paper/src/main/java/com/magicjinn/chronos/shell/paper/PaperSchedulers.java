package com.magicjinn.chronos.shell.paper;

import java.lang.reflect.Method;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/** Routes tasks through region schedulers on Folia/modern Paper, else BukkitScheduler. */
final class PaperSchedulers {
    private static final boolean USE_REGION_SCHEDULERS;
    private static final Method SERVER_GET_GLOBAL_SCHEDULER;
    private static final Method GLOBAL_RUN;
    private static final Method GLOBAL_RUN_AT_FIXED_RATE;
    private static final Method IS_GLOBAL_TICK_THREAD;
    private static final Method SERVER_GET_REGION_SCHEDULER;
    private static final Method REGION_EXECUTE;
    private static final Method REGION_RUN;
    private static final Method IS_OWNED_BY_CURRENT_REGION;

    static {
        Method getGlobal = null;
        Method run = null;
        Method runAtFixedRate = null;
        Method isGlobalTick = null;
        Method getRegion = null;
        Method regionExecute = null;
        Method regionRun = null;
        Method isOwnedByRegion = null;
        boolean useRegion = false;
        try {
            getGlobal = org.bukkit.Server.class.getMethod("getGlobalRegionScheduler");
            Class<?> globalClass =
                    Class.forName("io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler");
            Class<?> regionSchedulerClass =
                    Class.forName("io.papermc.paper.threadedregions.scheduler.RegionScheduler");
            Class<?> regionized =
                    Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            run = globalClass.getMethod("run", Plugin.class, Consumer.class);
            runAtFixedRate =
                    globalClass.getMethod(
                            "runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class);
            isGlobalTick = regionized.getMethod("isGlobalTickThread");
            getRegion = org.bukkit.Server.class.getMethod("getRegionScheduler");
            try {
                regionExecute =
                        regionSchedulerClass.getMethod(
                                "execute", Plugin.class, World.class, int.class, int.class, Runnable.class);
            } catch (NoSuchMethodException ignored) {
                // Older Folia builds only expose run().
            }
            regionRun =
                    regionSchedulerClass.getMethod(
                            "run", Plugin.class, World.class, int.class, int.class, Consumer.class);
            try {
                isOwnedByRegion =
                        Bukkit.class.getMethod(
                                "isOwnedByCurrentRegion", World.class, int.class, int.class);
            } catch (NoSuchMethodException ignored) {
                // Optional fast-path on older builds.
            }
            useRegion = true;
        } catch (ReflectiveOperationException ignored) {
            // Pre-Folia Paper, or Spigot/Bukkit without region schedulers.
        }
        SERVER_GET_GLOBAL_SCHEDULER = getGlobal;
        GLOBAL_RUN = run;
        GLOBAL_RUN_AT_FIXED_RATE = runAtFixedRate;
        IS_GLOBAL_TICK_THREAD = isGlobalTick;
        SERVER_GET_REGION_SCHEDULER = getRegion;
        REGION_EXECUTE = regionExecute;
        REGION_RUN = regionRun;
        IS_OWNED_BY_CURRENT_REGION = isOwnedByRegion;
        USE_REGION_SCHEDULERS = useRegion;
    }

    private PaperSchedulers() {}

    static boolean usesRegionSchedulers() {
        return USE_REGION_SCHEDULERS;
    }

    /** Blocks until {@code task} has run on the global region thread. */
    static void runGlobalSync(Plugin plugin, Runnable task) {
        if (runsOnGlobalThread()) {
            task.run();
            return;
        }
        awaitCompletion(plugin, task, GLOBAL_RUN, "Failed to schedule synchronous global region task.");
    }

    /**
     * Blocks until {@code task} has run on the region thread that owns {@code world}'s spawn
     * chunk. Must not be called from Folia's global tick thread.
     */
    static void runOnWorldSync(Plugin plugin, World world, Runnable task) {
        if (!USE_REGION_SCHEDULERS) {
            runOnPrimaryThread(plugin, task);
            return;
        }
        if (runsOnGlobalThread()) {
            throw new IllegalStateException("Cannot block the global tick thread waiting for region work.");
        }
        if (isOwnedByCurrentRegion(world)) {
            task.run();
            return;
        }
        int chunkX = world.getSpawnLocation().getBlockX() >> 4;
        int chunkZ = world.getSpawnLocation().getBlockZ() >> 4;
        if (REGION_EXECUTE != null) {
            awaitRegionCompletion(plugin, world, chunkX, chunkZ, task, true);
            return;
        }
        awaitRegionCompletion(plugin, world, chunkX, chunkZ, task, false);
    }

    /** Schedules {@code task} on the region thread that owns {@code world}'s spawn chunk. */
    static void runOnWorldAsync(Plugin plugin, World world, Runnable task) {
        if (!USE_REGION_SCHEDULERS) {
            runOnPrimaryThread(plugin, task);
            return;
        }
        if (isOwnedByCurrentRegion(world)) {
            task.run();
            return;
        }
        int chunkX = world.getSpawnLocation().getBlockX() >> 4;
        int chunkZ = world.getSpawnLocation().getBlockZ() >> 4;
        try {
            Object scheduler = SERVER_GET_REGION_SCHEDULER.invoke(Bukkit.getServer());
            if (REGION_EXECUTE != null) {
                REGION_EXECUTE.invoke(scheduler, plugin, world, chunkX, chunkZ, task);
            } else {
                REGION_RUN.invoke(scheduler, plugin, world, chunkX, chunkZ, (Consumer<?>) ignored -> task.run());
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to schedule region task.", e);
        }
    }

    private static void awaitRegionCompletion(
            Plugin plugin, World world, int chunkX, int chunkZ, Runnable task, boolean useExecute) {
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        Runnable wrapped =
                () -> {
                    try {
                        task.run();
                    } finally {
                        latch.countDown();
                    }
                };
        try {
            Object scheduler = SERVER_GET_REGION_SCHEDULER.invoke(Bukkit.getServer());
            if (useExecute) {
                REGION_EXECUTE.invoke(scheduler, plugin, world, chunkX, chunkZ, wrapped);
            } else {
                REGION_RUN.invoke(scheduler, plugin, world, chunkX, chunkZ, (Consumer<?>) ignored -> wrapped.run());
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to schedule synchronous region task.", e);
        }
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Failed to schedule synchronous region task.", e);
        }
    }

    static void runGlobal(Plugin plugin, Runnable task) {
        if (USE_REGION_SCHEDULERS) {
            invokeScheduler(GLOBAL_RUN, plugin, task, "Failed to schedule global region task.");
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    static void runGlobalTimer(Plugin plugin, Runnable task, long initialDelay, long period) {
        if (USE_REGION_SCHEDULERS) {
            invokeScheduler(
                    GLOBAL_RUN_AT_FIXED_RATE,
                    plugin,
                    task,
                    "Failed to schedule global region repeating task.",
                    initialDelay,
                    period);
            return;
        }
        Bukkit.getScheduler().runTaskTimer(plugin, task, initialDelay, period);
    }

    static boolean runsOnGlobalThread() {
        if (!USE_REGION_SCHEDULERS) {
            return Bukkit.isPrimaryThread();
        }
        try {
            return Boolean.TRUE.equals(IS_GLOBAL_TICK_THREAD.invoke(null));
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private static boolean isOwnedByCurrentRegion(World world) {
        if (IS_OWNED_BY_CURRENT_REGION == null) {
            return false;
        }
        int chunkX = world.getSpawnLocation().getBlockX() >> 4;
        int chunkZ = world.getSpawnLocation().getBlockZ() >> 4;
        try {
            return Boolean.TRUE.equals(IS_OWNED_BY_CURRENT_REGION.invoke(null, world, chunkX, chunkZ));
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private static void runOnPrimaryThread(Plugin plugin, Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
            return;
        }
        Bukkit.getScheduler().runTask(plugin, task);
    }

    private static void awaitCompletion(
            Plugin plugin, Runnable task, Method schedulerMethod, String failureMessage, Object... extraArgs) {
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        Runnable wrapped =
                () -> {
                    try {
                        task.run();
                    } finally {
                        latch.countDown();
                    }
                };
        invokeScheduler(schedulerMethod, plugin, wrapped, failureMessage, extraArgs);
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failureMessage, e);
        }
    }

    private static void invokeScheduler(
            Method schedulerMethod, Plugin plugin, Runnable task, String failureMessage, Object... extraArgs) {
        try {
            Object scheduler = SERVER_GET_GLOBAL_SCHEDULER.invoke(Bukkit.getServer());
            Consumer<Object> wrapped = ignored -> task.run();
            Object[] args = new Object[2 + extraArgs.length];
            args[0] = plugin;
            args[1] = wrapped;
            System.arraycopy(extraArgs, 0, args, 2, extraArgs.length);
            schedulerMethod.invoke(scheduler, args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(failureMessage, e);
        }
    }
}
