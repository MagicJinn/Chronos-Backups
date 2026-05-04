# Shell Forge

This module contains Forge integration code across multiple Minecraft version lines. Because Forge APIs differ heavily by era, sources are split by version directories.

## What lives here

- Forge mod/bootstrap entrypoints for each supported Forge line.
- Version-specific command registration and command adapters.
- Version-specific server environment and world controller adapters where API differences require them.

## Structure

- `v1_12/` - legacy Forge line (pre-Brigadier era, legacy command path and environment adapters).
- `v1_13/` - transitional Forge line with its own command and lifecycle wiring.
- `v1_20/` - modern Forge (Mojmap-era) line with event-based lifecycle and command registration.

Each folder is intentionally self-contained to keep cross-version compatibility code isolated.

## Subdirectory contents

### `v1_12/`

Forge 1.12 uses the old FML event model and the legacy `ICommand` API (no Brigadier). This folder includes everything needed for that runtime shape:

- `ChronosForgeMod` - mod entrypoint (`@Mod`) and FML lifecycle handlers (`init`, server start/stop).
- `ChronosBackupCommand` - legacy command implementation for `/chronos backup|cancel`.
- `ForgeCommandRegistrar` - registers `ChronosBackupCommand` into `ServerCommandManager`.
- `ForgeServerEnvironment` - adapts 1.12 server APIs to `core`'s `ServerEnvironment`.
- `ForgeShellMessenger` - sends shell/user-facing messages using 1.12 text APIs.
- `ForgeBackupWorldController` - world-save and backup coordination for 1.12 server internals.

### `v1_13/`

Forge 1.13 is transitional: it already uses Brigadier dispatchers but still has older Forge/FML lifecycle/event patterns compared to modern lines.

- `ChronosForgeMod` - lifecycle/event wiring for startup, command registration, and server start/stop.
- `ChronosBackupCommand` - Brigadier command registration for `/chronos` on this line.
- `ForgeCommandRegistrar` - connects the Forge command registration context to the line's command tree.
- `ForgeServerEnvironment` - 1.13-specific adapter to `core` server environment expectations.
- `ForgeShellMessenger` - message bridge for 1.13 server/player feedback APIs.
- `ForgeBackupWorldController` - world/IO coordination adapted to 1.13 internals.
- `src/main/resources/pack.mcmeta` - resource metadata needed by this line's packaging/runtime.

### `v1_20/`

Forge 1.20.x (in this repo, modern Mojmap-era Forge) aligns closely with NeoForge-style runtime hooks and reuses shared Mojmap adapters where possible.

- `ChronosForgeMod` - modern Forge entrypoint using mod bus + global event bus, hooks server start/stop and command registration.
- `ForgeCommandRegistrar` - registers the shared `shell-brigadier` command tree and adapts Forge return/feedback behavior via hooks.

Compared to older folders, `v1_20/` is smaller because common Mojmap adapters and shared command logic live in `shell-mojmap`, `shell-brigadier`, and `shell-shared`.
