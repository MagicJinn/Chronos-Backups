package com.magicjinn.chronos.shell.forge;

import com.magicjinn.chronos.core.ChronosLogger;
import com.magicjinn.chronos.core.Scheduler.EnqueueResult;
import com.magicjinn.chronos.shell.ChronosBrigadier;
import com.magicjinn.chronos.shell.ChronosCommandActions;
import com.magicjinn.chronos.shell.ChronosCommandLiterals;
import com.magicjinn.chronos.shell.ChronosConstants;
import com.magicjinn.chronos.shell.ShellCommandRegistrar;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.server.MinecraftServer;

/**
 * Forge 1.14-1.15: RCON seem to run Brigadier off the server thread (?).
 * Long-running {@code /chronos} work is queued with
 * {@link MinecraftServer#execute} instead of blocking the caller (which
 * deadlocks when the server thread is already busy).
 */
final class Forge114CommandRegistrar implements ShellCommandRegistrar {

    @Override
    public void register(Object registrationContext) {
        if (!(registrationContext instanceof CommandDispatcher)) {
            return;
        }
        @SuppressWarnings("unchecked")
        CommandDispatcher<CommandSourceStack> dispatcher = (CommandDispatcher<CommandSourceStack>) registrationContext;
        ChronosBrigadier.Hooks<CommandSourceStack> hooks = ForgeMojmapBrigadierHooks.hooks(
                (source, message, broadcastToOps) -> source.sendSuccess(new TextComponent(message), broadcastToOps));
        dispatcher.register(buildRoot(hooks));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildRoot(
            ChronosBrigadier.Hooks<CommandSourceStack> hooks) {
        return LiteralArgumentBuilder.<CommandSourceStack>literal(ChronosCommandLiterals.ROOT)
                .requires(hooks::mayExecuteChronos)
                .executes(ctx -> onServerThread(ctx, c -> {
                    hooks.feedback(c.getSource(), ChronosCommandActions.messageChronosUsage(), false);
                    return hooks.backupReturnCode(false);
                }))
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal(ChronosCommandLiterals.BACKUP)
                        .executes(ctx -> onServerThread(ctx, c -> {
                            EnqueueResult start = ChronosCommandActions.tryStartManualBackup();
                            if (start == EnqueueResult.ALREADY_RUNNING) {
                                hooks.feedback(
                                        c.getSource(),
                                        ChronosCommandActions.messageManualBackupAlreadyRunning(),
                                        false);
                            } else if (start == EnqueueResult.NO_RUNTIME) {
                                hooks.feedback(
                                        c.getSource(),
                                        ChronosCommandActions.messageRuntimeInactive(),
                                        false);
                            }
                            return hooks.backupReturnCode(start == EnqueueResult.QUEUED);
                        })))
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal(ChronosCommandLiterals.CANCEL)
                        .executes(ctx -> onServerThread(ctx, c -> {
                            boolean cancelled = ChronosCommandActions.requestCancelInFlightBackup();
                            if (!cancelled) {
                                hooks.feedback(
                                        c.getSource(),
                                        ChronosCommandActions.messageCancelNothingRunning(),
                                        false);
                            }
                            return hooks.cancelReturnCode(cancelled);
                        })))
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal(ChronosCommandLiterals.SPEEDTEST)
                        .then(RequiredArgumentBuilder.<CommandSourceStack, Integer>argument(
                                "s", IntegerArgumentType.integer())
                                .executes(ctx -> onServerThread(ctx, c -> {
                                    int s = IntegerArgumentType.getInteger(c, "s");
                                    EnqueueResult start = ChronosCommandActions.tryStartSpeedtest(s);
                                    if (start == EnqueueResult.ALREADY_RUNNING) {
                                        hooks.feedback(
                                                c.getSource(),
                                                ChronosCommandActions.messageSpeedtestAlreadyRunning(),
                                                false);
                                    } else if (start == EnqueueResult.NO_RUNTIME) {
                                        hooks.feedback(
                                                c.getSource(),
                                                ChronosCommandActions.messageRuntimeInactive(),
                                                false);
                                    }
                                    return hooks.backupReturnCode(start == EnqueueResult.QUEUED);
                                }))));
    }

    @FunctionalInterface
    private interface ServerCommandAction {
        int run(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException;
    }

    private static int onServerThread(CommandContext<CommandSourceStack> ctx, ServerCommandAction action)
            throws CommandSyntaxException {
        if (ChronosConstants.isMinecraftServerThread())
            return action.run(ctx);

        MinecraftServer server = ctx.getSource().getServer();
        server.execute(
                () -> {
                    try {
                        action.run(ctx);
                    } catch (CommandSyntaxException e) {
                        ChronosLogger.error("Chronos command failed on server thread: " + e.getMessage());
                    } catch (RuntimeException e) {
                        ChronosLogger.error("Chronos command failed on server thread", e);
                    }
                });
        return Command.SINGLE_SUCCESS;
    }
}
