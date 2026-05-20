# Chronos Backups

[//]: # (This is supposed to be a comment. If you see this, you are either editing/reading the source Markdown file, or using a markdown viewer that does not support comments.)

[![Mod Banner](https://raw.githubusercontent.com/MagicJinn/Chronos-Backups/main/banner.png)](https://www.artstation.com/mylenakrijnen)

<sup>The wonderful mod icon and banner were created by [Mylèna Yarah Krijnen](https://www.artstation.com/mylenakrijnen).</sup>

[![Modrinth](https://img.shields.io/badge/Modrinth-Chronos_Backups-00ae5d?logo=modrinth)](https://modrinth.com/mod/chronos-backups) [![CurseForge](https://img.shields.io/badge/CurseForge-Chronos_Backups-f16437?logo=curseforge)](https://www.curseforge.com/minecraft/mc-mods/chronos-backups)

**Chronos Backups** is a multi-loader, multi-version Minecraft backup utility. Saves only the most important parts of your world, keeping backups smaller.

## Features

- Multi-version, multi-loader architecture (Fabric, NeoForge, Forge).
- Scheduled and manual backups via an in-game `/chronos` command with a configurable permission level.
- Backups are pruned and filtered to only include the most important parts of your world, keeping backups much smaller than traditional backups.
- Configurable file copy blacklist to exclude specific files and folders from the backup, prefilled with common server-related files and folders.

## Limitations

Chronos prioritizes aged world data, chunks that do not yet count as "old enough" for pruning can be left out of a snapshot. If you enter a **new** chunk, change blocks or items, and a backup runs **before** that area is included, those changes may be missing from that backup (depending on configuration). That situation is seen as extremely rare and can only cause **loss** on restore, never duplication.

## Supported versions

All currently supported versions are listed below. If a specific subversion does not have a modloader associated with it (eg 1.7.3-1.7.9), it may still appear as supported if the major version is supported.

| Minecraft | Support | Loader(s) | Backup | Config | Notes |
| - | - | - | - | - | - |
| `26.1.x` | ✅ Supported | Fabric + NeoForge | ✅ | 🟠 File-only | - |
| `1.21.x` | ✅ Supported | Fabric + NeoForge | ✅ | 🟠 File-only | - |
| `1.20.x` | ✅ Supported | Forge + Fabric + NeoForge | ✅ | 🟠 File-only | Forge 1.20.0-1.20.1. Fabric 1.20.x. NeoForge 1.20.2-1.20.6. |
| `1.19.x` | ✅ Supported | Fabric + Forge | ✅ | 🟠 File-only | [^1], [^4] |
| `1.18.x` | ✅ Supported | Fabric + Forge | ✅ | 🟠 File-only | [^1] |
| `1.17.x` | ✅ Supported | Fabric + Forge | ✅ | 🟠 File-only | [^1] |
| `1.16.x` | ✅ Supported | Fabric + Forge | ✅ | 🟠 File-only | - |
| `1.15.x` | ✅ Supported | Fabric + Forge | ✅ | 🟠 File-only | - |
| `1.14.x` | ✅ Supported | Fabric + Forge | ✅ | 🟠 File-only | - |
| `1.13.x` | ✅ Supported | Forge | ✅ | 🟠 File-only | [^3] |
| `1.12.x` | ✅ Supported | Forge | ✅ | 🟠 File-only | [^3] |
| `1.11.x` | ✅ Supported | Forge | ✅ | 🟠 File-only | [^3] |
| `1.10.x` | ✅ Supported | Forge | ✅ | 🟠 File-only | [^3] |
| `1.9.x` | ✅ Supported | Forge | ✅ | 🟠 File-only | [^3] |
| `1.8.x` | ✅ Supported | Forge | ✅ | 🟠 File-only | [^3] |
| `1.7.x` | ✅ Supported | Forge | ✅ | 🟠 File-only | [^2], [^3] |
| `1.6.x` | ❌ Unsupported | Forge | ❌ | 🔴 None | [^3] |
| `1.5.x` | ❌ Unsupported | Forge | ❌ | 🔴 None | [^3] |
| `1.4.x` | ❌ Unsupported | Forge | ❌ | 🔴 None | [^3] |
| `1.3.x` | ❌ Unsupported | Forge | ❌ | 🔴 None | [^3] |
| `1.2.x` | ❌ Unsupported | Forge | ❌ | 🔴 None | - |
| Beta & Alpha | ❌ Unsupported | Babric | ❌ | 🔴 None | Beta & Alpha versions may be supported in the future, but the flagship Chronos feature (world pruning) will be unavailable. |

[//]: # (If a note occurs more than once, move it to a footnote as shown below. If a note appears once, but the version has multiple notes, move it to a footnote as well. If a note appears once and the version has no other notes, leave it in the notes section.)

[^1]: Working, but fails our tests. See [issue #7](https://github.com/MagicJinn/Chronos-Backups/issues/7).

[^2]: Forge 1.7.2 does not build yet ([Unimined#184](https://github.com/unimined/Unimined/issues/184)).

[^3]: Might also be supported on Fabric through [Legacy Fabric](https://legacyfabric.net/) in the future.

[^4]: 1.19.0 builds, but silently fails when trying to create a backup. See [issue #10](https://github.com/MagicJinn/Chronos-Backups/issues/10).

## Configuration

The mod's configuration is stored in the `config/chronos.toml` file. This file is automatically created when the mod is first run, and is located in the `config` folder of the mod's directory. For the forseeable future, there is no GUI configuration available. Config options include:

- `backupFolderName`: The name of the folder that will contain the backups.
- `pruneChunks`: Whether chunk pruning is enabled for backup snapshots.
- `pruneTimeRequirementSeconds`: Minimum playtime (in seconds) for a region to count toward snapshot pruning.
- `pruneMaxWorkerThreads`: Maximum worker threads for pruning. 0 (or less) means "auto" (pruner picks a sensible default).
- `scheduleBackups`: Whether to run backups on a timer.
- `backupIntervalSeconds`: Seconds between automatic backup runs.
- `maxStoredBackups`: Maximum backups kept per world. After a successful backup, oldest snapshots are removed if the limit is exceeded. Recommended value: 5. Values lower than 3 can be used to save space, but risks serious data loss if a catastrophic error occurs. Values below 1 disable automatic removal.
- `compressionMethod`: Whether to compress the backup snapshot into a zip file or store it as an uncompressed folder. Accepts `"zip"` or `"none"`.
- `commandRequiredPermissionLevel`: Permission level required to run `/chronos`.
- `copyBlacklist`: Folders and files to exclude from the backup snapshot copy.

This list may be out of date. To see the latest available configuration options and their descriptions, see [ChronosTomlSpec.java](https://github.com/MagicJinn/Chronos-Backups/blob/main/core/src/main/java/com/magicjinn/chronos/core/config/ChronosTomlSpec.java).

## Nightly releases

Github Actions automatically builds and uploads nightly releases to the [GitHub Releases](https://github.com/MagicJinn/Chronos-Backups/releases) page. These are not the same as stable releases found on Modrinth, CurseForge or Github Releases, and should not be relied upon or assumed to be stable.

## Development

> [!Note]
> This repository is meant to compile with the build tool (for example ./gradlew buildAll). Editors are not guaranteed to work, so you may see persistent missing imports and other false errors in the IDE even when the command-line build succeeds. This might even result in ludicrous errors such as `String cannot be resolved to a type`. Treat the build output as the real check until further notice. Your IDE **will** complain. Sorry.

Chronos Backups is dedicated to making the development of this mod as easy as possible, on any OS, in any IDE, with simple automatic setups and tools for testing and building, but there are still some manual steps required.

### Prerequisites

- Install **JDK 25+** and set `JAVA_HOME` to it. Older variants use a Java 8, 17 or 21 toolchain via Gradle/Foojay automatically.
- Install **[Rustup](https://rustup.rs/)** and ensure `cargo` / `rustup` are on `PATH`, and install Rust **nightly** toolchain:

```powershell
rustup toolchain install nightly
```

### Rust native library

Chronos Backups bundles a custom built native Rust pruning library, which in turn makes use of [mca](https://github.com/VilleOlof/mca) and [simdnbt](https://github.com/azalea-rs/simdnbt) for maximum performance. This is automatically built and bundled with the mod during normal Gradle builds, or can be built manually through `buildRust` for verification. By default, due to platform constraints, local development builds compile and stage only the current host OS/arch native target. In GitHub Actions, Chronos builds rust-pruner on Linux, Windows and macOS runners, then merges those artifacts before `buildAll`, so the produced jars include all supported native platforms. This means that local builds:

- are intended for development only, and should not be released through release channels, or even shared with other developers or users (to avoid confusion).
- cannot be used on a non-native platform. For example, a Windows build will not work on Linux or macOS (this might stop you from building the mod locally and testing it on a dedicated server hosting provider).

> [!Note]
> Rust builds are only produced for 64-bit systems. 32-bit architectures are not supported.

### Project structure

The project is organized as a small, version-agnostic **core** (scheduler + backup runner) plus loader/version-specific **shells**, with one central place to edit mod metadata.

- **Shell layer**: loader and version specific integration code that hooks into the server/runtime and registers commands, events and other hooks.
- **Core layer**: loader-agnostic scheduling and backup execution logic reused across all targets.

Project variants can use code from multiple shells to reduce duplicate code and improve maintainability. For example, the `fabric-line-1_21_11` variant uses code from the `shell-fabric` and `shell-mojmap` shells, while the `neoforge-line-26_1` variant uses code from the `shell-neoforge` and `shell-mojmap` shells. Minecraft versions that use Brigadier for command registration (1.13+) use the `shell-brigadier` shell, etc etc.

New variants are defined in `gradle/chronos-compile-groups.json`. `gradle/chronos-java-matrix.json` defines the Java language levels and toolchain majors for generated Fabric/NeoForge Gradle files.

The project has a dedicated `tooling/` folder that contains the Java tools for generating variants and running smoke tests.

### Gradle commands

All commands should be run from the repository root. The Gradle project provides several useful commands for building and testing the mod.

- `buildAll`: Builds all enabled variants, then collects output jars.
- `collectAllJars`: Copies final jars to root `build/libs/` (runs as part of `buildAll`).
- `buildRust`: Builds native `rust-pruner` libraries (host-native by default, runs automatically as part of any build/run tasks).
- `generateVariants`: Regenerates `variants/` from `gradle/chronos-compile-groups.json` (should be run automatically when appropriate).
- `cleanVariants`: Clears the `variants/` folder. Useful when encountering issues with stale/locked variant directories.
- `smokeTest`: Automated dedicated-server smoke runs. Spins up a server for each variant and runs specific tests to ensure the mod is working correctly.
- `:fabric-line-…:build` / `:neoforge-line-…:build`: Build a specific variant's jar.
- `:fabric-line-…:runClient` / `:neoforge-line-…:runServer`: Run a specific variant's server or client.

#### Variant generation (`generateVariants`)

`variants/` is a folder that contains the Gradle projects for each variant. Each variant is a separate project, and is built separately. The `generateVariants` task is used to regenerate the `variants/` folder from the `gradle/chronos-compile-groups.json` file. `generateVariants` should be run automatically when appropriate, but can be run manually alongside `cleanVariants` to force a regeneration. `CHRONOS_VARIANT_GENERATION` environment variable can be set to `skip` to skip the automatic variant regeneration.

#### Smoke testing (`smokeTest`)

For each variant, `smokeTest` starts a dedicated server, watches the log for pass/fail markers defined in `tooling/smoke-test-servers.config.json`, triggers a backup over RCON and waits for it to finish, then triggers a clean shutdown. A non-zero exit, failed backup, or timeout fails the test.

Flags for `smokeTest` (passed via `"-Pchronos.smoke.args="`):

| Flag | Description |
|-|-|
| `--workers <n>` | Thread pool size (default `4`). Multiple smoke jobs may run concurrently, each with its own `./gradlew` subprocess. |
| `--only <label>` | Restrict to one job label (repeat flag for multiple). Labels match Gradle project names, e.g. `fabric-line-1_21_11`. |
| `--verbose` | Mirror child Gradle output to the console. |

#### Examples

```powershell
./gradlew cleanVariants
./gradlew buildAll
./gradlew :neoforge-line-26_1:runClient
./gradlew :fabric-line-1_21_11:runServer
./gradlew smokeTest "-Pchronos.smoke.args=--workers 8"
./gradlew smokeTest "-Pchronos.smoke.args=--only fabric-line-1_21_11"
./gradlew smokeTest "-Pchronos.smoke.args=--only neoforge-line-1_21_11 --only neoforge-line-26_1"
```
