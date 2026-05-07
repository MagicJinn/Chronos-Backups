# Chronos Backups

[![Mod Banner](banner.png)](https://www.artstation.com/mylenakrijnen)

<sup>The wonderful mod icon and banner were created by [Mylèna Yarah Krijnen](https://www.artstation.com/mylenakrijnen).</sup>

[![Modrinth](https://img.shields.io/badge/Modrinth-Chronos_Backups-00ae5d?logo=modrinth)](https://modrinth.com/mod/chronos-backups) [![CurseForge](https://img.shields.io/badge/CurseForge-Chronos_Backups-f16437?logo=curseforge)](https://www.curseforge.com/minecraft/mc-mods/chronos-backups)

**Chronos Backups** is a multi-loader Minecraft backup utility. Designed to save the most important parts of your world, keeping backups smaller.

The project is organized as a small, version-agnostic **core** (scheduler + backup runner) plus loader/version-specific **shells**, with one central place to edit mod metadata.

## Features

- Multi-version, multi-loader architecture (Fabric, NeoForge, Forge).
- Focused backups that include critical world files instead of copying everything.
- Shared core logic with generated loader/version variants for broad compatibility.

## Nightly releases

CI uploads built JARs as workflow artifacts on each push and pull request. To grab the latest builds, open [GitHub Actions](https://github.com/MagicJinn/Chronos-Backups/actions), pick a recent successful run, and download the artifact from the run summary. These are not the same as stable releases on Modrinth, CurseForge or Github Releases.

## Architecture

Chronos Backups is made of two layers:

- **Shell layer**: loader and version specific integration code that hooks into the server/runtime and exposes configuration per platform.
- **Core layer**: loader-agnostic scheduling and backup execution logic reused across all targets.

## Supported and moddable targets

All currently supported versions are listed below in `major.minor.x` format:

| Minecraft | Support | Loader(s) | Backup | Config | Notes |
| - | - | - | - | - | - |
| `26.1.x` | ✅ Supported | Fabric + NeoForge | ✅ | 🟠 File-only | - |
| `1.21.x` | ✅ Supported | Fabric + NeoForge | ✅ | 🟠 File-only | - |
| `1.20.x` | ✅ Supported | Forge + Fabric + NeoForge | ✅ | 🟠 File-only | Forge 1.20.0. Fabric 1.20.x. NeoForge 1.20.1 - 1.20.6. |
| `1.19.x` | ❌ Unsupported | Forge + Fabric | ❌ | 🔴 None | - |
| `1.18.x` | ❌ Unsupported | Forge + Fabric | ❌ | 🔴 None | - |
| `1.17.x` | ❌ Unsupported | Forge + Fabric | ❌ | 🔴 None | - |
| `1.16.x` | ❌ Unsupported | Forge + Fabric | ❌ | 🔴 None | - |
| `1.15.x` | ❌ Unsupported | Forge + Fabric | ❌ | 🔴 None | - |
| `1.14.x` | ❌ Unsupported | Forge + Fabric | ❌ | 🔴 None | - |
| `1.13.x` | ✅ Supported | Forge | ✅ | 🟠 File-only | - |
| `1.12.x` | ✅ Supported | Forge | ✅ | 🟠 File-only | - |
| `1.11.x` | ❌ Unsupported | Forge | ❌ | 🔴 None | - |
| `1.10.x` | ❌ Unsupported | Forge | ❌ | 🔴 None | - |
| `1.9.x` | ❌ Unsupported | Forge | ❌ | 🔴 None | - |
| `1.8.x` | ❌ Unsupported | Forge | ❌ | 🔴 None | - |
| `1.7.x` | ❌ Unsupported | Legacy Forge | ❌ | 🔴 None | - |
| `1.6.x` | ❌ Unsupported | Legacy Forge | ❌ | 🔴 None | - |
| `1.5.x` | ❌ Unsupported | Legacy Forge | ❌ | 🔴 None | - |
| `1.4.x` | ❌ Unsupported | Legacy Forge | ❌ | 🔴 None | - |
| `1.3.x` | ❌ Unsupported | Legacy Forge | ❌ | 🔴 None | - |
| `1.2.x` | ❌ Unsupported | Legacy Forge | ❌ | 🔴 None | - |
| `1.1.x` | ❌ Unsupported | Legacy Forge | ❌ | 🔴 None | - |

## Development

Chronos Backup is dedicated to making the development of this mod as easy as possible, on any OS, in any IDE, with simple automatic setups and tools for testing and building, but there are still some manual steps required.

### Prerequisites

- Install **JDK 25+** and set `JAVA_HOME` to it. Older variants use a Java 8, 17 or 21 toolchain via Gradle/Foojay automatically.
- Install **[Rustup](https://rustup.rs/)** and ensure `cargo` / `rustup` are on `PATH`, and install Rust **nightly** toolchain:

```powershell
rustup toolchain install nightly
```

### Rust native library

Chronos bundles the native custom built Rust pruner library for all supported platforms in each mod jar. This happens automatically during normal Gradle builds and run tasks through `buildRust`.

By default, local development builds compile and stage only the current host OS/arch native target, so setup stays simple and automatic.
In GitHub Actions, Chronos builds rust-pruner on Linux, Windows and macOS runners, then merges those artifacts before `buildAll`, so the produced CI jars include all supported native platforms.

> [!NOTE]
> Local host-native builds are intended for development. Release/nightly artifacts from [GitHub Actions](https://github.com/MagicJinn/Chronos-Backups/actions) are the unified jars with all rust-pruner native binaries included.

The full target matrix is:

- `x86_64-pc-windows-msvc`
- `aarch64-pc-windows-msvc`
- `x86_64-unknown-linux-gnu`
- `aarch64-unknown-linux-gnu`
- `x86_64-apple-darwin`
- `aarch64-apple-darwin`

On Windows, cross-linking non-Windows targets requires an actual cross-linker. If not configured, you can hit linker errors. CI is configured to build host-native binaries on each OS runner automatically.

```powershell
.\gradlew.bat buildRust
```

`buildRust` runs automatically as part of mod builds and run tasks. Running it directly is the fastest way to validate your Rust environment.

### Commands

Use the Gradle wrapper from the repo root.

#### PowerShell

```powershell
.\gradlew.bat buildAll
.\gradlew.bat smokeTestServers
.\gradlew.bat smokeTestServers -PchronosSmokeArgs="--workers 16"
```

### Most useful tasks

- `buildAll` - builds all enabled variants (Fabric, NeoForge, Forge), then collects output jars.
- `buildRust` - builds native `rust-pruner` libraries for active targets (host-native by default, auto-run by build/run tasks).
- `collectAllJars` - copies final jars to root `build/libs/`. Automatically run by `buildAll`.
- `:fabric-line-1_21_0-1_21_10:build`, `:fabric-line-1_21_11:build` - build one target.
- `:<version>-line-<compileGroup>:runClient` - Runs the client for the given version and compile group.
- `:<version>-line-<compileGroup>:runServer` - Runs the server for the given version and compile group. (will not automatically shut down like `smokeTestServers` does)
- `generateVariantProjects` - generates variant projects from `gradle/chronos-compile-groups.json`. Should be run automaticaly.
- `smokeTestServers` - runs dev servers and checks expected Chronos startup lines. Smoke tests are run against the highest supported minor version of the target line, meaning that Forge 1.20.0 will test against 1.20.0, and Fabric 1.20.x will test against 1.20.6. Arguments: `--workers <number>` (default 4), `--only <label>` (repeatable).

Example Run command:

```powershell
.\gradlew.bat :fabric-line-26_1:runClient
.\gradlew.bat :neoforge-line-1_21_11:runServer
```

Example Smoke Test command:

```powershell
.\gradlew.bat smokeTestServers -PchronosSmokeArgs="--workers 2 --only fabric-line-1_21_11"
```
