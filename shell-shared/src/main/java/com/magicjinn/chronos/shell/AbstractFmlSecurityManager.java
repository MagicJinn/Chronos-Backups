package com.magicjinn.chronos.shell;

import java.security.Permission;

/**
 * Shared implementation for Forge/FML launcher security manager.
 * For some reason, in some Forge versions, the security manager can get installed multiple times.
 * This is a workaround to allow for this to work without crashing.
 * I truly have no idea why this is happening, but this fixes it.
 */
@SuppressWarnings("removal")
public abstract class AbstractFmlSecurityManager extends SecurityManager {
    private final String fmlCallerPrefix;

    protected AbstractFmlSecurityManager(String fmlCallerPrefix) {
        this.fmlCallerPrefix = fmlCallerPrefix;
    }

    @Override
    public void checkPermission(Permission permission) {
        String name = permission.getName() == null ? "missing" : permission.getName();
        if (name.startsWith("exitVM")) {
            Class<?>[] classContext = getClassContext();
            String callingClass = classContext.length > 4 ? classContext[4].getName() : "none";
            String callingParent = classContext.length > 5 ? classContext[5].getName() : "none";

            boolean isFmlCaller = callingClass.startsWith(fmlCallerPrefix);
            boolean isWatchdogCaller = "net.minecraft.server.dedicated.ServerHangWatchdog$1".equals(callingClass)
                    || "net.minecraft.server.dedicated.ServerHangWatchdog".equals(callingClass);
            boolean isClientMain = "net.minecraft.client.Minecraft".equals(callingClass)
                    && "net.minecraft.client.Minecraft".equals(callingParent);
            boolean isServerMain = "net.minecraft.server.dedicated.DedicatedServer".equals(callingClass)
                    && "net.minecraft.server.MinecraftServer".equals(callingParent);

            if (!(isFmlCaller || isWatchdogCaller || isClientMain || isServerMain)) {
                throw new ExitTrappedException();
            }
            return;
        }

        // Some legacy Forge launches can try to install this manager twice.
        if ("setSecurityManager".equals(name)) {
            return;
        }
    }

    @Override
    public void checkPermission(Permission permission, Object context) {
        checkPermission(permission);
    }

    public static class ExitTrappedException extends SecurityException {
        private static final long serialVersionUID = 1L;
    }
}
