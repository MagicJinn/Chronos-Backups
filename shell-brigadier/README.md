# Shell Brigadier

This module contains the shared Brigadier command tree used by loaders that expose Brigadier on their compile/runtime path.

It exists separately from `shell-shared` so legacy targets (for example Forge 1.12) can still compile without a Brigadier dependency.

## What lives here

- A single `/chronos` Brigadier root with `backup` and `cancel` subcommands.
- Loader hook interfaces for permission checks, message delivery, and command return codes.
- Delegation to `shell-shared` for shared command text and action behavior.

## Structure

- `src/main/java/com/magicjinn/chronos/shell/ChronosBrigadier.java` - builds and registers the reusable Brigadier command tree.
