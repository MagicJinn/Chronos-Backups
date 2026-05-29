# Shell Forge

Forge integration across Minecraft version lines. Forge and MCP APIs diverge by era, so most code still lives in per-line directories, identical or era-stable pieces are pulled in through extra Gradle `sourceSets` (see **Shared source trees** below).

## What lives here

- Forge mod/bootstrap entrypoints for each supported Forge line.
- Command registration and `/chronos` wiring (legacy `ICommand` or Brigadier, depending on era).
- Server environment, world backup coordination, and chat/logging adapters where mappings differ.

## Shared source trees

These directories are not standalone projects, variant `build.gradle` files (from `tooling/templates/generate-variants/`) add their `src/main/java` paths alongside `core`, `shell-shared`, and the line folder.

| Directory | Role |
|-|-|
| `forge-common-1_8-1_12/` | Shared `ChronosForgeMod` for old FML (`@Mod`, `FMLServerStartedEvent`, …). Each line under `v1_8`-`v1_12` still supplies `ForgeShellMcRange` (compile-time `acceptedMinecraftVersions` for `@Mod`). |
| `forge-shared-command-registrar/` | Single `ForgeCommandRegistrar` for all pre-1.13 Forge lines. Kept separate from `forge-common-1_8-1_12` so `v1_7_10` does not pull in the shared `ChronosForgeMod`. |
| `forge-common-1_7-1_8/` | Shared `ForgeShellMessenger` for 1.7.10 and 1.8 (uses `/tellraw` command). |
| `forge-common-1_9-1_10/` | Shared `ForgeShellMessenger` for 1.9 and 1.10 (uses `/tellraw` command). |
| `forge-common-1_11-1_12/` | Shared `ForgeShellMessenger` for 1.11 and 1.12 (uses `/tellraw` command). |
| `forge-common-1_10-1_12/` | Shared `ChronosBackupCommand` where `CommandBase` uses `getName` / `execute` / `sendMessage` (same shape for 1.10-1.12). |

## Structure (per-line folders)

- `v1_7_10/` - oldest Forge/FML line (pre-Brigadier). Own `ChronosForgeMod`, `ChronosBackupCommand`, line-specific adapters, shares registrar + messenger via trees above. Minecraft `acceptedMinecraftVersions` for `@Mod` lives in `ForgeShellMcManifest` here.
- `v1_7_2/` - Only `ForgeShellMcManifest` (1.7.2 range). The `forge-line-1_7_2` variant compiles `v1_7_10` sources with this manifest (1.7.2 and 1.7.10 differ in launchwrapper/FML, a single 1.7.10-built jar must not run on 1.7.2). In `gradle/chronos-compile-groups.json`, compile group `minecraft_1_7_2` is present but `shouldBuild` is false until Unimined can remap FG2 1.7.2 (see [unimined/Unimined#184](https://github.com/unimined/Unimined/issues/184)); set `shouldBuild` to `true` there to emit `variants/minecraft_1_7_2/forge-line-1_7_2` once tooling supports it.
- `v1_8/` … `v1_12/` - Old FML lifecycle and legacy commands, each folder holds what still differs by Minecraft version (`ForgeServerEnvironment`, `ForgeBackupWorldController`, `FMLSecurityManager`, `ForgeShellMcRange`, …).
- `v1_13/` - Transitional Forge (Brigadier + older event wiring).
- `v1_20/` - Modern Forge (Mojmap-era), aligned with shared Mojmap helpers elsewhere in the repo.

## Subdirectory contents (typical roles)

### Old Forge lines: `v1_7_10/`, `v1_8/`, …, `v1_12/`

- `ChronosForgeMod` - In `forge-common-1_8-1_12` for 1.8-1.12, still under `v1_7_10` for 1.7.10 only. FML init and server start/stop hooks.
- `ChronosBackupCommand` - Per line where the `CommandBase` API differs (1.7, 1.8, 1.9), shared from `forge-common-1_10-1_12` for 1.10-1.12. Delegates to `shell-shared` `LegacyCommandSupport`.
- `ForgeCommandRegistrar` - Shared from `forge-shared-command-registrar`.
- `ForgeShellMessenger` - Shared from the `forge-common-1_*_*` messenger trees where the chat API matches.
- `ForgeServerEnvironment` - Usually per line (`worldServers` vs `worlds`, and related MCP names).
- `ForgeBackupWorldController` - Per line (save paths and server/world APIs change across versions).
- `FMLSecurityManager` - Thin subclass of `shell-shared` `AbstractFmlSecurityManager` in the package name expected on the classpath for that FML line.

### `v1_13/`

Brigadier dispatchers plus Forge/FML lifecycle for that era. Own `ChronosForgeMod`, command tree, environment, messenger, and backup controller, see sources under `v1_13/src`.

### `v1_20/`

Modern Forge entrypoint and `ForgeCommandRegistrar` integrating shared Brigadier command logic (`shell-brigadier`, `shell-mojmap`, `shell-shared`).
