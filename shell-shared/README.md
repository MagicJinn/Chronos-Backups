# Shell Shared

This module contains shell-side code that is loader-agnostic and version-agnostic. It sits between the loader entrypoints and the core backup runtime.

## What lives here

- Shared command literals and command action helpers (including legacy non-Brigadier flows).
- Shared bridge hooks used by loader modules to notify the core layer about server lifecycle events.
- Small shared interfaces and helpers for command registration across loaders.
- Optional Forge/FML launcher helpers where multiple loaders need the same behavior.

## Structure

- `src/main/java/com/magicjinn/chronos/shell/ChronosCommandActions.java` - shared behavior for `/chronos backup` and `/chronos cancel`.
- `src/main/java/com/magicjinn/chronos/shell/ChronosCommandLiterals.java` - command name constants used by shells.
- `src/main/java/com/magicjinn/chronos/shell/ChronosConstants.java` - mod id, display name, log tag, default world name, log divider, and Minecraft server-thread helpers used by Forge/Mojmap shells.
- `src/main/java/com/magicjinn/chronos/shell/HookBridge.java` - bridge from loader events to core lifecycle hooks.
- `src/main/java/com/magicjinn/chronos/shell/LegacyCommandSupport.java` - shared parsing/execution for pre-Brigadier command adapters.
- `src/main/java/com/magicjinn/chronos/shell/ShellCommandRegistrar.java` - tiny abstraction for loader-specific command registration.
- `src/main/java/com/magicjinn/chronos/shell/AbstractFmlSecurityManager.java` - shared Forge/FML security-manager workaround used where the launcher installs overlapping managers.
