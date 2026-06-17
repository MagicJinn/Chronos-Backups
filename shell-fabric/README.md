# Shell Fabric

This module is the Fabric integration layer for Chronos. It wires Fabric lifecycle callbacks to shared shell/core behavior and registers commands on Fabric servers.

## What lives here

- Fabric mod entrypoints (main/client/dedicated server).
- Fabric-specific command registration that plugs into the shared Brigadier tree (split by Minecraft line where APIs differ).
- Mojmap-oriented Brigadier hooks shared across Fabric targets (`FabricMojmapBrigadierHooks`).
- Server lifecycle wiring that forwards start/stop events through `HookBridge`.

## Structure

Shared sources under `src/main/java/com/magicjinn/chronos/shell/fabric/`:

- `ChronosFabricEntrypoint.java` - main Fabric initializer that connects lifecycle events and world hooks.
- `ChronosFabricClientEntrypoint.java` - client-side loader startup hook.
- `ChronosFabricDedicatedServerEntrypoint.java` - dedicated server startup hook.
- `FabricMojmapBrigadierHooks.java` - Mojmap `Hooks` implementation for `shell-brigadier`.

Version-specific Fabric slices (lifecycle and command APIs differ by line):

- `v1_14-v1_18/src/main/java/com/magicjinn/chronos/shell/fabric/` - lifecycle v0 + command API v1
- `v1_19-v1_20/src/main/java/com/magicjinn/chronos/shell/fabric/FabricBootstrap.java` - shared lifecycle v1 bootstrap for 1.19+
- `v1_19/src/main/java/com/magicjinn/chronos/shell/fabric/FabricCommandRegistrar.java` - command API v2 (`sendSuccess` overload)
- `v1_20/src/main/java/com/magicjinn/chronos/shell/fabric/FabricCommandRegistrar.java` - command API v2 (`Supplier<Component>` overload)
