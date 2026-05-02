package com.magicjinn.chronos.shell.forge;

import com.magicjinn.chronos.shell.ChronosBrigadier;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.command.CommandSource;
import net.minecraft.util.text.TextComponentString;

/* Forge 1.13 command implementation. */
final class ChronosBackupCommand {
    private ChronosBackupCommand() {
    }

    static void register(CommandDispatcher<CommandSource> dispatcher) {
        ChronosBrigadier.register(
                dispatcher,
                new ChronosBrigadier.Hooks<CommandSource>() {
                    @Override
                    public void feedback(
                            CommandSource source, String message, boolean broadcastToOps) {
                        source.sendFeedback(new TextComponentString(message), broadcastToOps);
                    }

                    @Override
                    public boolean mayExecuteChronos(CommandSource source) {
                        return source.hasPermissionLevel(
                                ChronosBrigadier.REQUIRED_PERMISSION_LEVEL);
                    }

                    @Override
                    public int backupReturnCode(boolean started) {
                        return 1;
                    }

                    @Override
                    public int cancelReturnCode(boolean cancelled) {
                        return 1;
                    }
                });
    }
}
