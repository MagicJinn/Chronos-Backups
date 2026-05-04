# Shell NeoForge

This module is the NeoForge integration layer. It hooks NeoForge lifecycle and command events into shared Chronos shell/core logic.

## What lives here

- NeoForge mod entrypoint and loader startup hooks.
- NeoForge command registrar wired to the shared Brigadier command tree.
- Server start/stop lifecycle forwarding through `HookBridge` using Mojmap common adapters.

## Structure

- `src/main/java/com/magicjinn/chronos/shell/neoforge/ChronosNeoForgeMod.java` - main NeoForge mod class, lifecycle subscriptions, and world hook wiring.
- `src/main/java/com/magicjinn/chronos/shell/neoforge/NeoForgeCommandRegistrar.java` - NeoForge command registration bridge.
