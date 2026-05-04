# Shell Shared

This module contains shell-side code that is loader-agnostic and version-agnostic. It sits between the loader entrypoints and the core backup runtime.

## What lives here

- Shared command literals and command action helpers.
- Shared bridge hooks used by loader modules to notify the core layer about server lifecycle events.
- Small shared interfaces for command registration across loaders.

## Structure

- `src/main/java/com/magicjinn/chronos/shell/ChronosCommandActions.java` - shared behavior for `/chronos backup` and `/chronos cancel`.
- `src/main/java/com/magicjinn/chronos/shell/ChronosCommandLiterals.java` - command name constants used by all shells.
- `src/main/java/com/magicjinn/chronos/shell/HookBridge.java` - bridge from loader events to core lifecycle hooks.
- `src/main/java/com/magicjinn/chronos/shell/ShellCommandRegistrar.java` - tiny abstraction for loader-specific command registration.
