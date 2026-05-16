package com.magicjinn.chronos.shell;

import com.magicjinn.chronos.core.Scheduler.EnqueueResult;

/** Shared execution flow for legacy (pre-Brigadier) Chronos command adapters. */
public final class LegacyCommandSupport {
    private LegacyCommandSupport() {}

    public interface MessageSink {
        void send(String message);
    }

    public static void execute(String[] args, MessageSink sink) throws UnknownSubcommandException {
        if (args.length == 0) {
            sink.send(ChronosCommandActions.messageChronosUsage());
            return;
        }

        String subcommand = args[0];
        if (ChronosCommandLiterals.BACKUP.equalsIgnoreCase(subcommand)) {
            EnqueueResult start = ChronosCommandActions.tryStartManualBackup();
            if (start == EnqueueResult.QUEUED) {
                sink.send(ChronosCommandActions.messageManualBackupStarted());
            } else if (start == EnqueueResult.ALREADY_RUNNING) {
                sink.send(ChronosCommandActions.messageManualBackupAlreadyRunning());
            } else {
                sink.send(ChronosCommandActions.messageRuntimeInactive());
            }
            return;
        }

        if (ChronosCommandLiterals.CANCEL.equalsIgnoreCase(subcommand)) {
            if (!ChronosCommandActions.requestCancelInFlightBackup()) {
                sink.send(ChronosCommandActions.messageCancelNothingRunning());
            }
            return;
        }

        if (ChronosCommandLiterals.SPEEDTEST.equalsIgnoreCase(subcommand)) {
            if (args.length < 2) {
                sink.send(ChronosCommandActions.messageSpeedtestUsage());
                return;
            }
            try {
                int s = Integer.parseInt(args[1]);
                EnqueueResult start = ChronosCommandActions.tryStartSpeedtest(s);
                if (start == EnqueueResult.QUEUED) {
                    sink.send(ChronosCommandActions.messageSpeedtestStarted(s));
                } else if (start == EnqueueResult.ALREADY_RUNNING) {
                    sink.send(ChronosCommandActions.messageSpeedtestAlreadyRunning());
                } else {
                    sink.send(ChronosCommandActions.messageRuntimeInactive());
                }
            } catch (NumberFormatException e) {
                sink.send(ChronosCommandActions.messageSpeedtestInvalidInteger(args[1]));
            }
            return;
        }

        throw new UnknownSubcommandException(ChronosCommandActions.messageUnknownSubcommand(subcommand));
    }

    public static final class UnknownSubcommandException extends Exception {
        private static final long serialVersionUID = 1L;

        public UnknownSubcommandException(String message) {
            super(message);
        }
    }
}
