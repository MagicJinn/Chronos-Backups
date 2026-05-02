package com.magicjinn.chronos.shell;

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
    /**
     * Vanilla permission level 4 (maximum OP tier): matches sensitive commands such
     * as {@code
     * /stop}; dedicated-server console and RCON sources satisfy this check.
     */
    public static final int REQUIRED_PERMISSION_LEVEL = 4;

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
         * Whether {@code source} may run Chronos commands
         * ({@link #REQUIRED_PERMISSION_LEVEL}).
         */
        boolean mayExecuteChronos(S source);
    }

    public static <S> LiteralArgumentBuilder<S> buildRoot(Hooks<S> hooks) {
        return LiteralArgumentBuilder.<S>literal(ChronosCommandLiterals.ROOT)
                .requires(hooks::mayExecuteChronos)
                .then(LiteralArgumentBuilder.<S>literal(ChronosCommandLiterals.BACKUP)
                        .executes(ctx -> {
                            boolean started = ChronosCommandActions.startManualBackup();
                            if (started) {
                                hooks.feedback(
                                        ctx.getSource(),
                                        ChronosCommandActions.messageManualBackupStarted(),
                                        false);
                            } else {
                                hooks.feedback(
                                        ctx.getSource(),
                                        ChronosCommandActions.messageRuntimeInactive(),
                                        false);
                            }
                            return hooks.backupReturnCode(started);
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
