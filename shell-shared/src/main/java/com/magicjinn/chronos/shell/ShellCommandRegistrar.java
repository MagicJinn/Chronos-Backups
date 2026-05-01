package com.magicjinn.chronos.shell;

/**
 * Loader-specific adapter for registering shell commands.
 */
public interface ShellCommandRegistrar {
    void register(Object registrationContext);
}
