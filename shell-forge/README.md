# Shell Forge

This module contains Forge integration code across multiple Minecraft version lines. Because Forge APIs differ heavily by era, sources are split by version directories.

## What lives here

- Forge mod/bootstrap entrypoints for each supported Forge line.
- Command registration hooks plus line-specific command wrappers around shared command behavior.
- Version-specific server environment and world controller adapters where API differences require them.

## Structure

- `v1_7_10/` - oldest supported legacy Forge/FML line (pre-Brigadier, legacy command + environment adapters).
- `v1_8/` - legacy Forge line (pre-Brigadier era, legacy command + environment adapters).
- `v1_9/` - legacy Forge line (pre-Brigadier era, legacy command + environment adapters).
- `v1_10/` - legacy Forge line (pre-Brigadier era, legacy command + environment adapters).
- `v1_11/` - legacy Forge line (pre-Brigadier era, legacy command + environment adapters).
- `v1_12/` - legacy Forge line (pre-Brigadier era, legacy command + environment adapters).
- `v1_13/` - transitional Forge line with its own command and lifecycle wiring.
- `v1_20/` - modern Forge (Mojmap-era) line with event-based lifecycle and command registration.

Each folder is intentionally self-contained to keep cross-version compatibility code isolated.

## Subdirectory contents

### Legacy lines: `v1_7_10/`, `v1_8/`, `v1_9/`, `v1_10/`, `v1_11/`, `v1_12/`

These folders all use old Forge/FML lifecycle patterns and the legacy `ICommand` path (no Brigadier). Each directory is self-contained for its target runtime but follows the same role split:

- `ChronosForgeMod` - mod entrypoint (`@Mod`) and FML lifecycle handlers (`init`, server start/stop).
- `ChronosBackupCommand` - line-specific legacy command adapter for `/chronos`, delegating shared backup/cancel/usage behavior through `shell-shared`'s `LegacyCommandSupport`.
- `ForgeCommandRegistrar` - registers `ChronosBackupCommand` into `ServerCommandManager`.
- `ForgeServerEnvironment` - adapts version-specific server APIs to `core`'s `ServerEnvironment`.
- `ForgeShellMessenger` - sends shell/user-facing messages through line-appropriate text/chat APIs.
- `ForgeBackupWorldController` - world-save and backup coordination adapted to each line's server internals.
- `FMLSecurityManager` - line-specific bridge class that extends `shell-shared` security-manager behavior under the package expected by that Forge/FML line.

### `v1_13/`

Forge 1.13 is transitional: it already uses Brigadier dispatchers but still has older Forge/FML lifecycle/event patterns compared to modern lines.

- `ChronosForgeMod` - lifecycle/event wiring for startup, command registration, and server start/stop.
- `ChronosBackupCommand` - Brigadier tree wiring for `/chronos`, backed by shared command action/state behavior.
- `ForgeCommandRegistrar` - connects the Forge command registration context to the line's command tree.
- `ForgeServerEnvironment` - 1.13-specific adapter to `core` server environment expectations.
- `ForgeShellMessenger` - message bridge for 1.13 server/player feedback APIs.
- `ForgeBackupWorldController` - world/IO coordination adapted to 1.13 internals.
- `src/main/resources/pack.mcmeta` - resource metadata needed by this line's packaging/runtime.

### `v1_20/`

Forge 1.20.x (in this repo, modern Mojmap-era Forge) aligns closely with NeoForge-style runtime hooks and reuses shared Mojmap adapters where possible.

- `ChronosForgeMod` - modern Forge entrypoint using mod bus + global event bus, hooks server start/stop and command registration.
- `ForgeCommandRegistrar` - registers the shared `shell-brigadier` command tree (using shared command actions) and adapts Forge return/feedback behavior via hooks.

Compared to older folders, `v1_20/` is smaller because common Mojmap adapters and shared command logic live in `shell-mojmap`, `shell-brigadier`, and `shell-shared`.
