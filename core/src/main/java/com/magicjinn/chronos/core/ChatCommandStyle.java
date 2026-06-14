package com.magicjinn.chronos.core;

/**
 * How {@link BackupRuntimeContext#sendChat(String)} turns a message into a
 * server command. Pre-1.13 uses {@link ChatCommandStyle#LEGACY_SAY}; 1.13+
 * uses {@link ChatCommandStyle#MODERN_TELLRAW}.
 */
public enum ChatCommandStyle {
    LEGACY_SAY,
    MODERN_TELLRAW
}
