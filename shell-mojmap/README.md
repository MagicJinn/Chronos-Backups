# Shell Mojmap

This module contains code shared by shells that use Mojang mappings (Mojmap). It provides common server adapters and lightweight version gates used by modern loader targets.

## What lives here

- Common Mojmap adapters for server environment, chat messaging, and world control.
- Version-line overrides where Mojmap APIs differ between Minecraft versions.
- Small command gate classes (`ChronosMojmapCommandGate`) where permission checks differ by target line (int-based vs PermissionSet-based).
- Glue used by Fabric, NeoForge, and modern Forge lines.

## Structure

- `common/` - shared Mojmap adapters (`MojmapServerEnvironment`, `MojmapShellMessenger`, `MojmapBackupWorldController`).
- `v1_14/` - `ChronosMojmapCommandGate` for the int-based `CommandSourceStack#hasPermission(int)` era.
- `v1_14-v1_15/` - overrides for server environment, shell messenger, and backup world controller where 1.14-1.15 APIs diverge from `common`.
- `v1_16-v1_17/` - overrides for backup world controller and shell messenger for that line.
- `v1_18/` - shell messenger override where needed.
- `v1_21_11/` - `ChronosMojmapCommandGate` for the PermissionSet-based `PermissionSet#contains(String)` era.
