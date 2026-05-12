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
| `1.19.x` | ✅ Supported | Fabric + Forge | ✅ | 🟠 File-only | Working, but fails our tests. See [issue 7](https://github.com/MagicJinn/Chronos-Backups/issues/7)|
| `1.18.x` | ✅ Supported | Fabric + Forge | ✅ | 🟠 File-only | Working, but fails our tests. See [issue 7](https://github.com/MagicJinn/Chronos-Backups/issues/7)|
| `1.17.x` | ✅ Supported | Fabric + Forge | ✅ | 🟠 File-only | Working, but fails our tests. See [issue 7](https://github.com/MagicJinn/Chronos-Backups/issues/7)|
| `1.16.x` | ✅ Supported | Fabric + Forge | ✅ | 🟠 File-only | - |
| `1.15.x` | ✅ Supported | Fabric + Forge | ✅ | 🟠 File-only | - |
| `1.14.x` | ✅ Supported | Fabric + Forge | ✅ | 🟠 File-only | - |
| `1.13.x` | ✅ Supported | Forge | ✅ | 🟠 File-only | - |
| `1.12.x` | ✅ Supported | Forge | ✅ | 🟠 File-only | - |
| `1.11.x` | ✅ Supported | Forge | ✅ | 🟠 File-only | - |
| `1.10.x` | ✅ Supported | Forge | ✅ | 🟠 File-only | - |
| `1.9.x` | ✅ Supported | Forge | ✅ | 🟠 File-only | - |
| `1.8.x` | ✅ Supported | Forge | ✅ | 🟠 File-only | - |
| `1.7.x` | ✅ Supported | Forge | ✅ | 🟠 File-only | Forge **1.7.2** does not build yet ([Unimined#184](https://github.com/unimined/Unimined/issues/184)). |
| `1.6.x` | ❌ Unsupported | Forge | ❌ | 🔴 None | - |
| `1.5.x` | ❌ Unsupported | Forge | ❌ | 🔴 None | - |
| `1.4.x` | ❌ Unsupported | Forge | ❌ | 🔴 None | - |
| `1.3.x` | ❌ Unsupported | Forge | ❌ | 🔴 None | - |
| `1.2.x` | ❌ Unsupported | Forge | ❌ | 🔴 None | - |

## Development

Chronos Backups is dedicated to making the development of this mod as easy as possible, on any OS, in any IDE, with simple automatic setups and tools for testing and building, but there are still some manual steps required.

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

Use the Gradle wrapper from the repo root. Examples below use Windows (`.\gradlew.bat`); on Unix use `./gradlew`.

```powershell
.\gradlew.bat buildAll
.\gradlew.bat generateVariants
.\gradlew.bat smokeTest
```

### Variant generation (`generateVariants`)

Gradle loads every project under `variants/` during **settings** evaluation. If `variants/` is missing or stale, the root `settings.gradle.kts` runs **`generateVariants`** in a nested Gradle invocation so the IDE and CLI always see an up-to-date variant layout.

#### Environment: `CHRONOS_VARIANT_GENERATION`

By convention, **environment variables use uppercase** so they stand out in CI logs and match common POSIX shells (`export VAR=value`). Chronos uses one flag for the nested generator:

| Value | Effect |
|-------|--------|
| *(unset)* | Allow automatic nested `generateVariants` during settings when needed. |
| `skip`, `0`, `false`, `off` | Skip nested variant generation (CI and scripted builds set this when variants are generated explicitly). |

Variant definitions live in **`gradle/chronos-compile-groups.json`**. Java language levels and toolchain majors for generated Fabric/NeoForge Gradle files are driven by **`gradle/chronos-java-matrix.json`** so version breakpoints are not scattered through Java code.

### Smoke testing (`smokeTest`)

The **`smokeTest`** task runs `SmokeTestServers`: it starts each variant’s **`runServer`** (or a filtered subset), waits for Chronos log markers, sends an RCON backup command, and asserts shutdown markers. Logs land under `build/smoke-server-logs/<session-id>/`.

**Gradle arguments** are passed only through **`-Pchronos.smoke.args="..."`** (quoted). That string is tokenized and forwarded to the Java `main` as program arguments.

**CLI flags** (passed via `-Pchronos.smoke.args`):

| Flag | Meaning |
|------|---------|
| `--workers <n>` | Thread pool size (default `4`). Multiple smoke jobs may run concurrently, each with its own `./gradlew` subprocess. |
| `--only <label>` | Restrict to one job label (repeat flag for multiple). Labels match Gradle project names, e.g. `fabric-line-1_21_11`. |
| `--reuse-gradle-daemon` | Omit `--no-daemon` on subprocess Gradle invocations so daemons can stay warm between jobs (faster local runs; default remains cold subprocesses for isolation). |
| `--verbose` | Mirror child Gradle output to the console. |

**`--workers` and Gradle:** Each smoke job runs **`./gradlew` from the repo root** for its variant. Higher `--workers` runs more jobs in parallel; use **`--workers 1`** if you prefer strictly sequential smoke or hit rare OS file-lock contention on shared paths.

**Optional Gradle overrides:** Unified-line variant builds accept **`-Pchronos.smokeTest.*`** properties (see generated `build.gradle` / `build.gradle.kts`) so you can pin Minecraft, loader, and API or NeoForge coordinates when experimenting locally.

**Minecraft version strings** must match **Mojang / Loom** ids (see `com.mojang:minecraft` resolution). For example the first **1.21** Java release is typically **`1.21`**, not `1.21.0`; using a non-existent id makes Loom fail with “Failed to find minecraft version”.

### Most useful tasks

- **`buildAll`** - builds all enabled variants, then collects output jars.
- **`buildRust`** - builds native `rust-pruner` libraries (host-native by default; auto-run by build/run tasks).
- **`collectAllJars`** - copies final jars to root `build/libs/` (runs as part of `buildAll`).
- **`generateVariants`** - regenerates `variants/` from `gradle/chronos-compile-groups.json` (also invoked automatically from settings when appropriate).
- **`smokeTest`** - automated dedicated-server smoke runs (see above).
- **`:fabric-line-…:build`** / **`:neoforge-line-…:build`** - build a single unified line.
- **`:…:runClient`** / **`:…:runServer`** - interactive dev runs (do not auto-stop; unlike `smokeTest`).

Examples:

```powershell
.\gradlew.bat :fabric-line-26_1:runClient
.\gradlew.bat :neoforge-line-1_21_11:runServer
.\gradlew.bat smokeTest "-Pchronos.smoke.args=--workers 8 --only fabric-line-1_21_11"
.\gradlew.bat smokeTest "-Pchronos.smoke.args=--reuse-gradle-daemon --workers 4"
```

### Speed notes

Root **`gradle.properties`** already enables **`org.gradle.parallel`** and **`org.gradle.caching`**. Smoke runs pass **`--configure-on-demand`** to subprocess Gradle invocations. **`--reuse-gradle-daemon`** trades stronger isolation for faster repeated smoke locally.
