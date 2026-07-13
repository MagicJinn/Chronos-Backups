package com.magicjinn.chronos.shell.paper;

import com.magicjinn.chronos.core.BackupWorldController;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/** Flushes and pauses world saves through the stable Bukkit API. */
public final class PaperBackupWorldController implements BackupWorldController {
    private static volatile AsyncWorldPrep activePrep;
    private static volatile TickWorldPrep activeTickPrep;

    @Override
    public void saveAllWorldData(Object serverHandle) {
        if (!(serverHandle instanceof Server)) {
            return;
        }
        Server server = (Server) serverHandle;
        Plugin plugin = PaperRuntime.plugin(server);

        if (PaperSchedulers.usesRegionSchedulers()) {
            for (World world : server.getWorlds()) {
                if (world != null) {
                    PaperSchedulers.runOnWorldSync(plugin, world, world::save);
                }
            }
            if (PaperSchedulers.runsOnGlobalThread()) {
                server.savePlayers();
            } else {
                PaperSchedulers.runGlobalSync(plugin, server::savePlayers);
            }
            return;
        }

        runOnMainThread(
                server,
                () -> {
                    for (World world : server.getWorlds()) {
                        if (world != null) {
                            world.save();
                        }
                    }
                    server.savePlayers();
                });
    }

    @Override
    public boolean setWorldSavingDisabled(Object serverHandle, boolean disabled) {
        if (!(serverHandle instanceof Server)) {
            return false;
        }
        Server server = (Server) serverHandle;
        boolean autosave = !disabled;
        Plugin plugin = PaperRuntime.plugin(server);

        if (PaperSchedulers.usesRegionSchedulers()) {
            for (World world : server.getWorlds()) {
                if (world != null) {
                    PaperSchedulers.runOnWorldSync(plugin, world, () -> world.setAutoSave(autosave));
                }
            }
            return true;
        }

        runOnMainThread(
                server,
                () -> {
                    for (World world : server.getWorlds()) {
                        if (world != null) {
                            world.setAutoSave(autosave);
                        }
                    }
                });
        return true;
    }

    @Override
    public boolean prepareWorldFlush(Object serverHandle) {
        if (!(serverHandle instanceof Server)) {
            return true;
        }
        Server server = (Server) serverHandle;
        if (!needsTickBasedFlush()) {
            saveAllWorldData(serverHandle);
            return true;
        }

        if (PaperSchedulers.usesRegionSchedulers()) {
            AsyncWorldPrep prep = activePrep;
            if (prep == null || prep.kind != AsyncWorldPrep.Kind.FLUSH) {
                activePrep = AsyncWorldPrep.startFlush(server, PaperRuntime.plugin(server));
                return false;
            }
            if (!prep.isDone()) {
                return false;
            }
            activePrep = null;
            return true;
        }

        TickWorldPrep prep = activeTickPrep;
        if (prep == null || prep.kind != TickWorldPrep.Kind.FLUSH) {
            activeTickPrep = TickWorldPrep.startFlush(server);
            return false;
        }
        if (!prep.advance(server)) {
            return false;
        }
        activeTickPrep = null;
        return true;
    }

    @Override
    public boolean preparePauseSaves(Object serverHandle, boolean disabled) {
        if (!(serverHandle instanceof Server)) {
            return true;
        }
        Server server = (Server) serverHandle;
        if (!needsTickBasedPause()) {
            setWorldSavingDisabled(serverHandle, disabled);
            return true;
        }

        boolean autosave = !disabled;
        AsyncWorldPrep prep = activePrep;
        if (prep == null || prep.kind != AsyncWorldPrep.Kind.PAUSE || prep.autosave != autosave) {
            activePrep = AsyncWorldPrep.startPause(server, PaperRuntime.plugin(server), autosave);
            return false;
        }
        if (!prep.isDone()) {
            return false;
        }
        activePrep = null;
        return true;
    }

    /**
     * Folia global tick thread, or legacy Paper primary thread during backup prep.
     */
    private static boolean needsTickBasedFlush() {
        if (PaperSchedulers.usesRegionSchedulers()) {
            return PaperSchedulers.runsOnGlobalThread();
        }
        return Bukkit.isPrimaryThread();
    }

    /**
     * Folia global tick thread only. Legacy setAutoSave is cheap enough to run
     * synchronously.
     */
    private static boolean needsTickBasedPause() {
        return PaperSchedulers.usesRegionSchedulers() && PaperSchedulers.runsOnGlobalThread();
    }

    private static void runOnMainThread(Server server, Runnable task) {
        if (PaperSchedulers.runsOnGlobalThread() || org.bukkit.Bukkit.isPrimaryThread()) {
            task.run();
            return;
        }
        PaperSchedulers.runGlobal(PaperRuntime.plugin(server), task);
    }

    private static final class AsyncWorldPrep {
        private enum Kind {
            FLUSH,
            PAUSE
        }

        private final Kind kind;
        private final AtomicInteger pending = new AtomicInteger();
        private final boolean autosave;
        private volatile boolean playersSaved;

        private AsyncWorldPrep(Kind kind, boolean autosave) {
            this.kind = kind;
            this.autosave = autosave;
        }

        private static AsyncWorldPrep startFlush(Server server, Plugin plugin) {
            AsyncWorldPrep prep = new AsyncWorldPrep(Kind.FLUSH, false);
            scheduleWorldTasks(server, plugin, prep, world -> world.save(), () -> {
                server.savePlayers();
                prep.playersSaved = true;
            });
            return prep;
        }

        private static AsyncWorldPrep startPause(Server server, Plugin plugin, boolean autosave) {
            AsyncWorldPrep prep = new AsyncWorldPrep(Kind.PAUSE, autosave);
            scheduleWorldTasks(server, plugin, prep, world -> world.setAutoSave(autosave), null);
            return prep;
        }

        private static void scheduleWorldTasks(
                Server server,
                Plugin plugin,
                AsyncWorldPrep prep,
                java.util.function.Consumer<World> perWorld,
                Runnable onAllWorldsDone) {
            List<World> worlds = server.getWorlds();
            int worldCount = 0;
            for (World world : worlds) {
                if (world != null) {
                    worldCount++;
                }
            }
            prep.pending.set(worldCount);
            for (World world : worlds) {
                if (world == null) {
                    continue;
                }
                PaperSchedulers.runOnWorldAsync(plugin, world, () -> {
                    perWorld.accept(world);
                    if (prep.pending.decrementAndGet() == 0 && onAllWorldsDone != null) {
                        PaperSchedulers.runGlobal(plugin, onAllWorldsDone);
                    }
                });
            }
            if (worldCount == 0 && onAllWorldsDone != null) {
                PaperSchedulers.runGlobal(plugin, onAllWorldsDone);
            }
        }

        private boolean isDone() {
            if (kind == Kind.FLUSH) {
                return pending.get() <= 0 && playersSaved;
            }
            return pending.get() <= 0;
        }
    }

    /**
     * Legacy Paper/Spigot: flush one world (then players) per server tick so
     * {@code world.save()} does not block the main thread long enough to trip
     * Paper's watchdog.
     */
    private static final class TickWorldPrep {
        private enum Kind {
            FLUSH
        }

        private final Kind kind;
        private final List<World> worlds;
        private int nextWorldIndex;
        private boolean playersSaved;

        private TickWorldPrep(Kind kind, List<World> worlds) {
            this.kind = kind;
            this.worlds = worlds;
        }

        private static TickWorldPrep startFlush(Server server) {
            return new TickWorldPrep(Kind.FLUSH, collectWorlds(server));
        }

        private boolean advance(Server server) {
            if (nextWorldIndex < worlds.size()) {
                worlds.get(nextWorldIndex++).save();
                return false;
            }
            if (!playersSaved) {
                server.savePlayers();
                playersSaved = true;
            }
            return true;
        }

        private static List<World> collectWorlds(Server server) {
            List<World> out = new ArrayList<>();
            for (World world : server.getWorlds()) {
                if (world != null) {
                    out.add(world);
                }
            }
            return out;
        }
    }
}
