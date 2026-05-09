package com.magicjinn.chronos.shell.fabric;

import com.magicjinn.chronos.shell.ChronosBrigadier;
import com.magicjinn.chronos.shell.mojmap.ChronosMojmapCommandGate;
import net.minecraft.commands.CommandSourceStack;

/**
 * Builds {@link ChronosBrigadier.Hooks} for Fabric, only feedback differs by
 * Minecraft version.
 */
public final class FabricMojmapBrigadierHooks {
    private FabricMojmapBrigadierHooks() {
    }

    /**
     * How to forward Brigadier feedback to {@link CommandSourceStack#sendSuccess}.
     */
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
                return started ? 1 : 0;
            }

            @Override
            public int cancelReturnCode(boolean cancelled) {
                return cancelled ? 1 : 0;
            }
        };
    }
}
