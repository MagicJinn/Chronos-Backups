# Shell NeoForge

This module is the NeoForge integration layer. It hooks NeoForge lifecycle and command events into shared Chronos shell/core logic.

## What lives here

- NeoForge mod entrypoint and loader startup hooks.
- NeoForge command registrar wired to the shared Brigadier command tree.
- Server start/stop lifecycle forwarding through `HookBridge` using Mojmap common adapters.
- A line-specific mod class where the NeoForge tick API still differs.

## Structure

Shared sources under `src/main/java/com/magicjinn/chronos/shell/neoforge/`:

- `ChronosNeoForgeMod.java` - main NeoForge mod class for current lines (`ServerTickEvent.Post`), lifecycle subscriptions, and world hook wiring.
- `NeoForgeCommandRegistrar.java` - NeoForge command registration bridge.

Version-specific override (compiled instead of the shared mod class when `neoShellVariant` is set in `gradle/chronos-compile-groups.json`):

- `v1_20_early/` - `ChronosNeoForgeMod` for NeoForge 1.20.2-1.20.4, uses legacy `TickEvent.ServerTickEvent` because `ServerTickEvent.Post` is not available yet.
