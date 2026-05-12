package com.magicjinn.chronos.shell.forge;

import com.magicjinn.chronos.shell.ChronosBrigadier;
import com.magicjinn.chronos.shell.mojmap.ChronosMojmapCommandGate;
import com.mojang.brigadier.Command;
import net.minecraft.commands.CommandSourceStack;

/**
 * Builds {@link ChronosBrigadier.Hooks} for Mojmap-era Forge. Only
 * {@link Feedback} differs by Minecraft version (chat API / {@code sendSuccess}
 * overloads).
 */
public final class ForgeMojmapBrigadierHooks {
    private ForgeMojmapBrigadierHooks() {
    }

    @FunctionalInterface
    public interface Feedback {
        void send(CommandSourceStack source, String message, boolean broadcastToOps);
    }

    public static ChronosBrigadier.Hooks<CommandSourceStack> hooks(Feedback feedback) {
        return new ChronosBrigadier.Hooks<CommandSourceStack>() {
            @Override
            public void feedback(CommandSourceStack source, String message, boolean broadcastToOps) {
                feedback.send(source, message, broadcastToOps);
            }

            @Override
            public boolean mayExecuteChronos(CommandSourceStack source) {
                return ChronosMojmapCommandGate.mayExecute(source);
            }

            @Override
            public int backupReturnCode(boolean started) {
                return started ? Command.SINGLE_SUCCESS : 0;
            }

            @Override
            public int cancelReturnCode(boolean cancelled) {
                return cancelled ? Command.SINGLE_SUCCESS : 0;
            }
        };
    }
}
