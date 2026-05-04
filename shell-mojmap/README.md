# Shell Mojmap

This module contains code shared by shells that use Mojang mappings (Mojmap). It provides common server adapters and lightweight version gates used by modern loader targets.

## What lives here

- Common Mojmap adapters for server environment, chat messaging, and world control.
- Small version-line command gate classes where command API signatures differ by target line.
- Shared glue used by Fabric, NeoForge, and modern Forge lines.

## Structure

- `common/` - reusable Mojmap adapters (`MojmapServerEnvironment`, `MojmapShellMessenger`, `MojmapBackupWorldController`).
- `v1_14/` - command gate shim for the 1.14-era Mojmap command surface (int-based `CommandSourceStack#hasPermission(int)` checks).
- `v1_21_11/` - command gate shim for the 1.21.11-era command surface (PermissionSet-based `PermissionSet#contains(String)` checks).
