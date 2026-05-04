# Shell Fabric

This module is the Fabric integration layer for Chronos. It wires Fabric lifecycle callbacks to shared shell/core behavior and registers commands on Fabric servers.

## What lives here

- Fabric mod entrypoints (main/client/dedicated server).
- Fabric-specific command registration that plugs into the shared Brigadier tree.
- Server lifecycle wiring that forwards start/stop events through `HookBridge`.

## Structure

- `src/main/java/com/magicjinn/chronos/shell/fabric/ChronosFabricEntrypoint.java` - main Fabric initializer that connects lifecycle events and world hooks.
- `src/main/java/com/magicjinn/chronos/shell/fabric/FabricCommandRegistrar.java` - Fabric-side command dispatcher integration.
- `src/main/java/com/magicjinn/chronos/shell/fabric/ChronosFabricClientEntrypoint.java` - client-side loader startup hook.
- `src/main/java/com/magicjinn/chronos/shell/fabric/ChronosFabricDedicatedServerEntrypoint.java` - dedicated server startup hook.
