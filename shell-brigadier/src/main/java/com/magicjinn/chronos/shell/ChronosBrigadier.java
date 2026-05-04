package com.magicjinn.chronos.shell;

import com.magicjinn.chronos.core.Scheduler.ManualBackupStart;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

/**
 * Single Brigadier tree for {@code /chronos backup|cancel}, shared by loaders
 * that ship Brigadier.
 * Uses {@link LiteralArgumentBuilder#literal} so we do not depend on
 * version-specific {@code Commands}
 * classes.
 *
 * <p>
 * Kept in {@code shell-brigadier} (not {@code shell-shared}) so Forge 1.12
 * variants, which do not
 * put Brigadier on the compile classpath, can still compile shared sources.
 */
public final class ChronosBrigadier {
    private ChronosBrigadier() {
    }

    /**
     * Loader-specific chat and return-code behavior (Mojang mapped
     * {@code sendSuccess} vs Forge
     * 1.13 {@code sendFeedback}, etc.).
     */
    public interface Hooks<S> {
        void feedback(S source, String message, boolean broadcastToOps);

        int backupReturnCode(boolean started);

        int cancelReturnCode(boolean cancelled);

        /**
         * Whether {@code source} may run Chronos commands (see config
         * {@code commandRequiredPermissionLevel}).
         */
        boolean mayExecuteChronos(S source);
    }

    public static <S> LiteralArgumentBuilder<S> buildRoot(Hooks<S> hooks) {
        return LiteralArgumentBuilder.<S>literal(ChronosCommandLiterals.ROOT)
                .requires(hooks::mayExecuteChronos)
                .executes(ctx -> {
                    hooks.feedback(
                            ctx.getSource(), ChronosCommandActions.messageChronosUsage(), false);
                    return hooks.backupReturnCode(false);
                })
                .then(LiteralArgumentBuilder.<S>literal(ChronosCommandLiterals.BACKUP)
                        .executes(ctx -> {
                            ManualBackupStart start = ChronosCommandActions.tryStartManualBackup();
                            if (start == ManualBackupStart.QUEUED) {
                                hooks.feedback(
                                        ctx.getSource(),
                                        ChronosCommandActions.messageManualBackupStarted(),
                                        false);
                            } else if (start == ManualBackupStart.ALREADY_RUNNING) {
                                hooks.feedback(
                                        ctx.getSource(),
                                        ChronosCommandActions.messageManualBackupAlreadyRunning(),
                                        false);
                            } else {
                                hooks.feedback(
                                        ctx.getSource(),
                                        ChronosCommandActions.messageRuntimeInactive(),
                                        false);
                            }
                            return hooks.backupReturnCode(start == ManualBackupStart.QUEUED);
                        }))
                .then(LiteralArgumentBuilder.<S>literal(ChronosCommandLiterals.CANCEL)
                        .executes(ctx -> {
                            boolean cancelled = ChronosCommandActions.requestCancelInFlightBackup();
                            if (!cancelled) {
                                hooks.feedback(
                                        ctx.getSource(),
                                        ChronosCommandActions.messageCancelNothingRunning(),
                                        false);
                            }
                            return hooks.cancelReturnCode(cancelled);
                        }));
    }

    public static <S> void register(CommandDispatcher<S> dispatcher, Hooks<S> hooks) {
        dispatcher.register(buildRoot(hooks));
    }
}
