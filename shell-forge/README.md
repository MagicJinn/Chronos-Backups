# Shell Forge

Forge integration across Minecraft version lines. Forge and MCP APIs diverge by era, so most code still lives in per-line directories, identical or era-stable pieces are pulled in through extra Gradle `sourceSets` (see **Shared source trees** below).

## What lives here

- Forge mod/bootstrap entrypoints for each supported Forge line.
- Command registration and `/chronos` wiring (legacy `ICommand` or Brigadier, depending on era).
- Server environment, world backup coordination, and chat/logging adapters where mappings differ.

## Shared source trees

These directories are not standalone projects, variant `build.gradle` files (from `tooling/templates/generate-variants/`) add their `src/main/java` paths alongside `core`, `shell-shared`, and the line folder.

### Old FML (1.7-1.12)

| Directory | Role |
|-|-|
| `forge-common-1_8-1_12/` | Shared `ChronosForgeMod` for old FML (`@Mod`, `FMLServerStartedEvent`, ...). Each line under `v1_8`-`v1_12` still supplies `ForgeShellMcRange` (compile-time `acceptedMinecraftVersions` for `@Mod`). |
| `forge-shared-command-registrar/` | Single `ForgeCommandRegistrar` for all pre-1.13 Forge lines. Kept separate from `forge-common-1_8-1_12` so `v1_7_10` does not pull in the shared `ChronosForgeMod`. |
| `forge-common-1_9-1_10/` | Shared `ForgeShellMessenger` for 1.9 and 1.10 (uses `/tellraw` command). |
| `forge-common-1_11-1_12/` | Shared `ForgeShellMessenger` for 1.11 and 1.12 (uses `/tellraw` command). |
| `forge-common-1_10-1_12/` | Shared `ChronosBackupCommand` where `CommandBase` uses `getName` / `execute` / `sendMessage` (same shape for 1.10-1.12). |

### Mojmap-era Forge (1.14+)

| Directory | Role |
|-|-|
| `forge-mojmap-chronos-forge-mod-kernel/` | Shared `MojmapForgeModKernel` (messenger, Mojmap env/backup hooks, command registrar wiring). Per-line `ChronosForgeMod` classes subscribe to version-specific events and delegate here. |
| `forge-mojmap-brigadier-hooks/` | Shared `ForgeMojmapBrigadierHooks` for `shell-brigadier`. |
| `forge-mojmap-command-registrar-textcomponent/` | `ForgeCommandRegistrar` for 1.14-1.18 (`TextComponent` + `sendSuccess(Component, boolean)`). |
| `forge-mojmap-chronos-forge-mod-serverevents/` | Shared `ChronosForgeMod` for 1.18+ (`ServerStartedEvent` / `RegisterCommandsEvent`, mod-bus via `@Mod.EventBusSubscriber`). |

## Structure (per-line folders)

- `v1_7_10/` - oldest Forge/FML line (pre-Brigadier). Own `ChronosForgeMod`, `ChronosBackupCommand`, `ForgeShellMessenger`, line-specific adapters, shares registrar via trees above. Minecraft `acceptedMinecraftVersions` for `@Mod` lives in `ForgeShellMcManifest` here.
- `v1_7_2/` - Only `ForgeShellMcManifest` (1.7.2 range). The `forge-line-1_7_2` variant compiles `v1_7_10` sources with this manifest (1.7.2 and 1.7.10 differ in launchwrapper/FML, a single 1.7.10-built jar must not run on 1.7.2). In `gradle/chronos-compile-groups.json`, compile group `minecraft_1_7_2` is present but `shouldBuild` is false until Unimined can remap FG2 1.7.2 (see [unimined/Unimined#184](https://github.com/unimined/Unimined/issues/184)). Set `shouldBuild` to `true` there to emit `variants/minecraft_1_7_2/forge-line-1_7_2` once tooling supports it.
- `v1_8/` ... `v1_12/` - Old FML lifecycle and legacy commands, each folder holds what still differs by Minecraft version (`ForgeServerEnvironment`, `ForgeBackupWorldController`, `FMLSecurityManager`, `ForgeShellMcRange`, ...). Own `ForgeShellMessenger` under `v1_8/` (schedule API names differ from 1.7.10).
- `v1_13/` - Transitional Forge (Brigadier + older event wiring).
- `v1_14_v1_15/` - Forge 1.14-1.15: own `ChronosForgeMod`, reflective `Forge114*` adapters (safe across 1.14.2-1.14.4 remaps), and `Forge114CommandRegistrar`. Built with Mojmap kernel + TextComponent registrar.
- `v1_16_mojmap/` / `v1_17_mojmap/` - Line-specific `ChronosForgeMod` (FML server lifecycle package differs: `fml.event.server` vs `fmlserverevents`). Share Mojmap kernel + TextComponent registrar.
- `v1_19_mojmap/` - `ForgeCommandRegistrar` for `Component.literal` + `sendSuccess(Component, boolean)`. Shares Mojmap kernel + serverevents mod entrypoint.
- `v1_20/` - `ForgeCommandRegistrar` for Supplier-based `sendSuccess`. Shares Mojmap kernel + serverevents mod entrypoint.

## Subdirectory contents (typical roles)

### Old Forge lines: `v1_7_10/`, `v1_8/`, ..., `v1_12/`

- `ChronosForgeMod` - In `forge-common-1_8-1_12` for 1.8-1.12, still under `v1_7_10` for 1.7.10 only. FML init and server start/stop hooks.
- `ChronosBackupCommand` - Per line where the `CommandBase` API differs (1.7, 1.8, 1.9), shared from `forge-common-1_10-1_12` for 1.10-1.12. Delegates to `shell-shared` `LegacyCommandSupport`.
- `ForgeCommandRegistrar` - Shared from `forge-shared-command-registrar`.
- `ForgeShellMessenger` - Shared from the `forge-common-1_*_*` messenger trees for 1.9-1.12. Own copy under `v1_7_10/` and `v1_8/` (schedule API names differ).
- `ForgeServerEnvironment` - Usually per line (`worldServers` vs `worlds`, and related MCP names).
- `ForgeBackupWorldController` - Per line (save paths and server/world APIs change across versions).
- `FMLSecurityManager` - Thin subclass of `shell-shared` `AbstractFmlSecurityManager` in the package name expected on the classpath for that FML line.

### `v1_13/`

Brigadier dispatchers plus Forge/FML lifecycle for that era. Own `ChronosForgeMod`, command tree, environment, messenger, and backup controller, see sources under `v1_13/src`.

### Mojmap-era lines: `v1_14_v1_15/` … `v1_20/`

- Shared wiring lives in the `forge-mojmap-*` trees above, plus `shell-mojmap` / `shell-brigadier` / `shell-shared`.
- Line folders only hold what the Forge event or chat API still splits by version (`ChronosForgeMod` lifecycle packages, or `ForgeCommandRegistrar` `sendSuccess` overloads).
- 1.14-1.15 also keep reflective world/env adapters under `v1_14_v1_15/` so one early-1.14 jar can still run on 1.14.2-1.14.3.
