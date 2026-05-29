package com.magicjinn.chronos.shell;

import com.magicjinn.chronos.core.Scheduler.EnqueueResult;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;

/**
 * Single Brigadier tree for {@code /chronos backup|cancel|speedtest}, shared by
 * loaders that ship Brigadier. Uses {@link LiteralArgumentBuilder#literal} so
 * we do not depend on version-specific {@code Commands} classes.
 *
 * <p>
 * Kept in {@code shell-brigadier} (not {@code shell-shared}) so Forge 1.12
 * variants, which do not put Brigadier on the compile classpath, can still
 * compile shared sources.
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

        /** Successful completion of a subcommand that does not map to backup/cancel semantics. */
        default int successReturnCode() {
            return backupReturnCode(true);
        }
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
                            EnqueueResult start = ChronosCommandActions.tryStartManualBackup();
                            if (start == EnqueueResult.ALREADY_RUNNING) {
                                hooks.feedback(
                                        ctx.getSource(),
                                        ChronosCommandActions.messageManualBackupAlreadyRunning(),
                                        false);
                            } else if (start == EnqueueResult.NO_RUNTIME) {
                                hooks.feedback(
                                        ctx.getSource(),
                                        ChronosCommandActions.messageRuntimeInactive(),
                                        false);
                            }
                            return hooks.backupReturnCode(start == EnqueueResult.QUEUED);
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
                        }))
                .then(LiteralArgumentBuilder.<S>literal(ChronosCommandLiterals.SPEEDTEST)
                        .then(RequiredArgumentBuilder.<S, Integer>argument(
                                        "s", IntegerArgumentType.integer())
                                .executes(ctx -> {
                                    int s = IntegerArgumentType.getInteger(ctx, "s");
                                    EnqueueResult start = ChronosCommandActions.tryStartSpeedtest(s);
                                    if (start == EnqueueResult.ALREADY_RUNNING) {
                                        hooks.feedback(
                                                ctx.getSource(),
                                                ChronosCommandActions.messageSpeedtestAlreadyRunning(),
                                                false);
                                    } else if (start == EnqueueResult.NO_RUNTIME) {
                                        hooks.feedback(
                                                ctx.getSource(),
                                                ChronosCommandActions.messageRuntimeInactive(),
                                                false);
                                    }
                                    return hooks.backupReturnCode(start == EnqueueResult.QUEUED);
                                })));
    }

    public static <S> void register(CommandDispatcher<S> dispatcher, Hooks<S> hooks) {
        dispatcher.register(buildRoot(hooks));
    }
}
