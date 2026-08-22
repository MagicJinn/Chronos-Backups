[//]: # (This is supposed to be a comment. If you see this, you are either editing/reading the source Markdown file, or using a markdown viewer that does not support comments.)

[//]: # (BEGIN MOD PAGE DESCRIPTION)

# Chronos Backups

[![Mod Banner](https://raw.githubusercontent.com/MagicJinn/Chronos-Backups/main/banner.png)](https://www.artstation.com/mylenakrijnen)

<sup>The wonderful mod icon and banner were created by [Mylèna Yarah Krijnen](https://www.artstation.com/mylenakrijnen).</sup>

[//]: # (END MOD PAGE DESCRIPTION)

[![Modrinth](https://img.shields.io/badge/Modrinth-Chronos_Backups-00ae5d?logo=modrinth)](https://modrinth.com/mod/chronos-backups) [![CurseForge](https://img.shields.io/badge/CurseForge-Chronos_Backups-f16437?logo=curseforge)](https://www.curseforge.com/minecraft/mc-mods/chronos-backups)

[//]: # (BEGIN MOD PAGE DESCRIPTION)

**Chronos Backups** is a multi-loader, multi-version Minecraft backup utility. Saves only the most important parts of your world, keeping backups smaller.

## Features

- Multi-version, multi-loader architecture (Fabric, NeoForge, Forge, Bukkit, Spigot, Paper, Purpur and Folia).
- Scheduled and manual backups via an in-game `/chronos` command with a configurable permission level.
- Backups are pruned and filtered to only include the most important parts of your world, keeping backups much smaller than traditional backups.
- Configurable file copy blacklist to exclude specific files and folders from the backup, prefilled with common server-related files and folders.
- Cloud sync: upload finished backups to your cloud provider of choice, and optionally delete them locally after a successful upload. Currently supported: ![Google Drive](https://img.shields.io/badge/Google%20Drive-4285F4?logo=googledrive&logoColor=white)

## Commands

| Command                        | Description                                                             |
| ------------------------------ | ----------------------------------------------------------------------- |
| `/chronos backup`              | Run a manual backup immediately.                                        |
| `/chronos cancel`              | Stop the backup currently in progress.                                  |
| `/chronos speedtest <seconds>` | Run repeated backups for benchmarking (diagnostic. not for normal use). |

The required permission level defaults to **4**.

## Performance and benchmarks

On default settings, Chronos backups typically shrink to about **1–15%** of the original world size. When Distant Horizons and/or Voxy data is filtered out as well, the backup is often **under 7%** of the full on-disk footprint (world + LOD).

| World                                                                                                         | Size    | Distant Horizon size (256 render distance) | Voxy size (Full world) | Average backup time | Final size (%pruned, %filtered + pruned) |
|---------------------------------------------------------------------------------------------------------------|---------|--------------------------------------------|------------------------|---------------------|------------------------------------------|
| [MY 100 DAYS SURVIVAL WORLD (1.19)](https://www.planetminecraft.com/project/my-100-days-survival-world-1-19/) | 189 MB  | 106 MB                                     | 85,8 MB                | 1,25s               | 28,0 MB (15%, 7%)                        |
| [my survival world](https://www.planetminecraft.com/project/my-survival-world-6880390/)                       | 4840 MB | 888 MB                                     | 3060 MB                | 10,76s              | 46,5 MB (0.96%, 0.53%)                   |
| [My survival World (Mission)](https://www.planetminecraft.com/project/my-survival-world-mission/)             | 1290 MB | 630 MB                                     | 708MB                  | 4,56s               | 156 MB (12%, 6%)                         |
| [My Survival World](https://www.planetminecraft.com/project/my-survival-world-6766250/)                       | 1260 MB | 501 MB                                     | 816 MB                 | 6,68s               | 29,3 MB (2%, 1%)                         |

Distant Horizons (render distance 256, existing chunks only). Voxy (`/voxy import current`, existing chunks only).

Method: Minecraft 26.2 Fabric, default Chronos config. Avg. time is mean backup duration over 120 seconds of continuous runs. CPU: AMD Ryzen 7 9800X3D. SSD: Samsung 970 EVO Plus 2TB.

## Cloud sync

Chronos can upload finished backups to a remote cloud provider, then optionally remove the local copy.

### Behaviour

- Local backups missing from the remote are uploaded, including catch-up after a failed upload or a restart.
- `maxStoredBackups` also caps how many Chronos backups each enabled provider keeps per world (oldest removed first). Values below 1 disable automatic removal locally and remotely.
- If `shouldKeepLocalBackups` is `false`, a backup is deleted from disk (only after it has been uploaded successfully).

When multiple worlds would map to the same remote folder (e.g. two servers both named "world"), Chronos attempts to resolve this by assigning a unique alias in `chronos-alias.txt` instead of overwriting. This favors safety over storage, so orphaned remote data may remain. Watch the console when enabling cloud sync on multiple servers.

### Providers

Currently supported: ![Google Drive](https://img.shields.io/badge/Google%20Drive-4285F4?logo=googledrive&logoColor=white). Enable it with `googleDriveEnabled = true`, restart, then follow the authorization URL printed in the console. Sign in once, tokens are stored on that machine under `google-drive-tokens/`. (Anyone with access to this token will be able to upload and delete backups from your Google Drive. They will not be able to access any other files in your Google Drive.) If you cannot open a browser on the server (for example a headless console), complete the OAuth flow on a local machine, then copy the `google-drive-tokens/` folder to the server.

Google Drive was chosen because it is the most popular cloud provider, and has the most generous free tier, with 15GB of storage. OneDrive and Dropbox are planned. They are not available yet.

## Limitations

Chronos prioritizes aged world data, chunks that do not yet count as "old enough" can be left out of a backup. If you enter a **new** chunk, change blocks or items, and a backup runs **before** that area is included, those changes may be missing from that backup (depending on configuration). That situation is seen as extremely rare and can only cause **loss** on restore, never duplication.

## Supported versions

All currently supported versions are listed below. Chronos is committed to never drop support for a version.

| Minecraft    | Support       | Loader(s)                                                           | Backup | Config       | Notes                                                                                                                                  |
|--------------|---------------|---------------------------------------------------------------------|--------|--------------|----------------------------------------------------------------------------------------------------------------------------------------|
| `26.x`       | ✅ Supported   | Fabric + NeoForge + Bukkit, Spigot, Paper, Purpur and Folia         | ✅      | 🟠 File-only | Paper plugin: 26.1.1+ only. Folia: 26.1.2 only.                                                                                        |
| `1.21.x`     | ✅ Supported   | Fabric + NeoForge + Bukkit, Spigot, Paper, Purpur and Folia         | ✅      | 🟠 File-only | Fabric/NeoForge: pre-1.21.11 vs 1.21.11+ split. Paper plugin: one jar for all 1.21.x. Folia: subset of patches.                        |
| `1.20.x`     | ✅ Supported   | Forge + Fabric + NeoForge + Bukkit, Spigot, Paper, Purpur and Folia | ✅      | 🟠 File-only | Forge: 1.20.0–1.20.1 only. Fabric: 1.20.x. NeoForge: 1.20.2–1.20.6. Paper plugin: all patches except 1.20.3. Folia: subset of patches. |
| `1.19.x`     | ✅ Supported   | Fabric + Forge + Bukkit, Spigot, Paper, Purpur and Folia            | ✅      | 🟠 File-only | Folia: 1.19.4 only.                                                                                                                    |
| `1.18.x`     | ✅ Supported   | Fabric + Forge + Bukkit, Spigot, Paper and Purpur                   | ✅      | 🟠 File-only | -                                                                                                                                      |
| `1.17.x`     | ✅ Supported   | Fabric + Forge + Bukkit, Spigot, Paper and Purpur                   | ✅      | 🟠 File-only | Forge: 1.17.1 only.                                                                                                                    |
| `1.16.x`     | ✅ Supported   | Fabric + Forge + Bukkit, Spigot and Paper                           | ✅      | 🟠 File-only | Paper plugin: 1.16.1+ only.                                                                                                            |
| `1.15.x`     | ✅ Supported   | Fabric + Forge + Bukkit, Spigot and Paper                           | ✅      | 🟠 File-only | Fabric + Forge: unified `1.14-1.15.x` jar.                                                                                             |
| `1.14.x`     | ✅ Supported   | Fabric + Forge + Bukkit, Spigot and Paper                           | ✅      | 🟠 File-only | Fabric + Forge: unified `1.14-1.15.x` jar. Forge: 1.14.2–1.14.4 only.                                                                  |
| `1.13.x`     | ✅ Supported   | Forge + Bukkit, Spigot and Paper                                    | ✅      | 🟠 File-only | Forge: 1.13.2 only. [^2]                                                                                                               |
| `1.12.x`     | ✅ Supported   | Forge + Bukkit, Spigot and Paper                                    | ✅      | 🟠 File-only | Forge: all 1.12.x patches. Paper plugin: 1.12.2 only. [^2], [^3]                                                                       |
| `1.11.x`     | ✅ Supported   | Forge + Bukkit, Spigot and Paper                                    | ✅      | 🟠 File-only | Forge: 1.11.0, 1.11.2 only. Paper plugin: 1.11.2 only. [^2]                                                                            |
| `1.10.x`     | ✅ Supported   | Forge + Bukkit, Spigot and Paper                                    | ✅      | 🟠 File-only | Forge: 1.10.0, 1.10.2 only. Paper plugin: 1.10.2 only. [^2]                                                                            |
| `1.9.x`      | ✅ Supported   | Forge + Bukkit, Spigot and Paper                                    | ✅      | 🟠 File-only | Forge: 1.9.0, 1.9.4 only. Paper plugin: 1.9.4 only. [^2]                                                                               |
| `1.8.x`      | ✅ Supported   | Forge + Bukkit, Spigot and Paper                                    | ✅      | 🟠 File-only | Forge: 1.8.0, 1.8.8, 1.8.9 only. Paper plugin: 1.8.8 only. [^2]                                                                        |
| `1.7.x`      | ✅ Supported   | Forge + Bukkit, Spigot and Paper                                    | ✅      | 🟠 File-only | Forge: 1.7.10 only. Paper plugin: 1.7.10 only. [^1], [^2]                                                                              |
| `1.6.x`      | ❌ Unsupported | Forge                                                               | ❌      | 🔴 None      | [^2]                                                                                                                                   |
| `1.5.x`      | ❌ Unsupported | Forge                                                               | ❌      | 🔴 None      | [^2]                                                                                                                                   |
| `1.4.x`      | ❌ Unsupported | Forge                                                               | ❌      | 🔴 None      | [^2]                                                                                                                                   |
| `1.3.x`      | ❌ Unsupported | Forge                                                               | ❌      | 🔴 None      | [^2]                                                                                                                                   |
| `1.2.x`      | ❌ Unsupported | Forge                                                               | ❌      | 🔴 None      | -                                                                                                                                      |
| Beta & Alpha | ❌ Unsupported | Babric                                                              | ❌      | 🔴 None      | Beta & Alpha versions may be supported in the future, but the flagship Chronos feature (world pruning) will be unavailable.            |

[//]: # (If a note occurs more than once, move it to a footnote as shown below. If a note appears once, but the version has multiple notes, move it to a footnote as well. If a note appears once and the version has no other notes, leave it in the notes section.)

[^1]: Forge 1.7.2 does not build yet ([Unimined#184](https://github.com/unimined/Unimined/issues/184)).

[^2]: Might also be supported on Fabric through [Legacy Fabric](https://legacyfabric.net/) in the future.

[^3]: Paper `1.12.0` and `1.12.1` are excluded because Mojang removed the vanilla server jars from their old S3 download URLs. Paperclip fails during bootstrap with `FileNotFoundException` when fetching `minecraft_server.1.12.jar` / `minecraft_server.1.12.1.jar`. Use Paper `1.12.2` (Forge still supports all 1.12.x patches).

## Configuration

The mod's configuration is stored in the `config/chronos.toml` file. This file is automatically created when the mod is first run, and is located in the `config` folder. For the forseeable future, there is no GUI configuration available. Config options include:

- `backupFolderName`: The name of the folder that will contain the backups.
- `pruneChunks`: Whether chunk pruning is enabled for backups.
- `pruneTimeRequirementSeconds`: Minimum playtime (in seconds) for a chunk to count toward world pruning.
- `pruneMaxWorkerThreads`: Maximum worker threads for pruning. 0 (or less) means "auto" (pruner picks a sensible default).
- `scheduleBackups`: Whether to run backups on a timer.
- `backupIntervalSeconds`: Seconds between automatic backup runs.
- `maxStoredBackups`: Maximum backups kept per world locally and on each enabled cloud destination. After a successful backup, oldest local backups are removed if the limit is exceeded. Cloud sync also trims remote Chronos backups to this cap. Recommended value: 5. Values lower than 3 can be used to save space, but risks serious data loss if a catastrophic error occurs. Values below 1 disable automatic removal.
- `compressionMethod`: Whether to compress the backups into a zip file or store it as an uncompressed folder. Accepts `"zip"` or `"none"`.
- `googleDriveEnabled`: Enable Google Drive as a cloud sync destination (see [Cloud sync](#cloud-sync)).
- `shouldKeepLocalBackups`: When `true`, keep local backups after a successful cloud upload. When `false`, delete each local backup once it has been uploaded.
- `commandRequiredPermissionLevel`: Permission level required to run `/chronos`.
- `copyBlacklist`: Folders and files to exclude from the backup.

This list may be out of date. To see the latest available configuration options and their descriptions, see [ChronosTomlSpec.java](https://github.com/MagicJinn/Chronos-Backups/blob/main/core/src/main/java/com/magicjinn/chronos/core/config/ChronosTomlSpec.java).

## Nightly releases

Github Actions automatically builds and uploads nightly releases to the [GitHub Releases](https://github.com/MagicJinn/Chronos-Backups/releases) page. These are not the same as stable releases found on Modrinth, CurseForge or Github Releases, and should not be relied upon or assumed to be stable.

[//]: # (END MOD PAGE DESCRIPTION)

## Development

> [!Note]
> This repository is meant to compile with the build tool (for example ./gradlew buildAll). Editors are not guaranteed to work, so you may see persistent missing imports and other false errors in the IDE even when the command-line build succeeds. This might even result in ludicrous errors such as `String cannot be resolved to a type`. Treat the build output as the real check until further notice. Your IDE **will** complain. Sorry.

Chronos Backups is dedicated to making the development of this mod as easy as possible, on any OS, in any IDE, with simple automatic setups and tools for testing and building, but there are still some manual steps required.

### Prerequisites

![Java](https://img.shields.io/badge/java-%23ED8B00.svg?logo=java&logoColor=white)
![Rust](https://img.shields.io/badge/rust-%23000000.svg?logo=rust&logoColor=white)
![Docker](https://img.shields.io/badge/docker-%230db7ed.svg?logo=docker&logoColor=white)

- Install **JDK 25+** and set `JAVA_HOME` to it. Older variants use a Java 8, 17 or 21 toolchain via Gradle/Foojay automatically.
- Install **[Rustup](https://rustup.rs/)** and ensure `cargo` / `rustup` are on `PATH`, and install Rust **nightly** toolchain:

```powershell
rustup toolchain install nightly
```

- Install **Docker** to run most tests. Follow your operating system specific installation instructions, ensuring the `docker` command is available in your environment.

### Rust native library

Chronos Backups bundles a custom built native Rust pruning library, which in turn makes use of [mca](https://github.com/VilleOlof/mca) and [na_nbt](https://docs.rs/na_nbt) for maximum performance. This is automatically built and bundled with the mod during normal Gradle builds, or can be built manually through `buildRust` for verification. By default, due to platform constraints, local development builds compile and stage only the current host OS/arch native target. In GitHub Actions, Chronos builds rust-pruner on Linux (x86_64 and aarch64), Windows and macOS runners, then merges those artifacts before `buildAll`, so the produced jars include all supported native platforms. This means that local builds:

- are intended for development on the current machine only, and should not be released through release channels, or even shared with other developers or users (to avoid confusion).
- cannot be used on a non-native platform unless you add extra targets or use merged CI artifacts.

**Docker tests (`testServers`)** require **Docker** to be installed and running. One command runs `buildAll`, cross-compiles `linux-x86_64` rust-pruner in Docker via `cargo-zigbuild` (glibc 2.17 max, compatible with old dedicated-server images such as `itzg/minecraft-server:java8`), and tests every supported loader/version in Docker:

```powershell
./gradlew testServers
```

`linux-x86_64` natives are always built that way (including on Linux CI) so they load on older server distros.

> [!Note]
> Rust builds are only produced for 64-bit systems. 32-bit architectures are not supported.

To benchmark pruning locally, place a world at `core/native/rust-pruner/test/world/` (gitignored) and run:

```bash
cd core/native/rust-pruner
cargo +nightly run --release --features world-test --bin prune-world-test
```

It copies the world, prunes the snapshot, prints the results, and discards the backup.

### Project structure

The project is organized as a small, version-agnostic **core** (scheduler + backup runner) plus loader/version-specific **shells**, with gradle.properties as the central place to edit mod metadata shared across all variants/loaders.

- **Shell layer**: loader and version specific integration code that hooks into the server/runtime and registers commands, events and other hooks.
- **Core layer**: loader-agnostic scheduling and backup execution logic reused across all targets.

Project variants can use code from multiple shells to reduce duplicate code and improve maintainability. For example, the `fabric-line-1_21_11` variant uses code from the `shell-fabric` and `shell-mojmap` shells, while the `neoforge-line-26_1` variant uses code from the `shell-neoforge` and `shell-mojmap` shells. Minecraft versions that use Brigadier for command registration (1.13+) use the `shell-brigadier` shell, etc etc.

New variants are defined in `gradle/chronos-compile-groups.json`. `gradle/chronos-java-matrix.json` defines the Java language levels and toolchains for all supported versions. The project has a dedicated `tooling/` folder that contains the Java tools for generating variants and running tests.

### Gradle commands

All commands should be run from the repository root. The Gradle project provides several useful commands for building and testing the mod.

- `buildAll`: Builds all enabled variants, then collects output jars.
- `collectAllJars`: Copies final jars to root `build/libs/` (runs as part of `buildAll`).
- `buildRust`: Builds native `rust-pruner` libraries (host-only by default. `testServers` adds `linux-x86_64` via Docker on Windows/macOS).
- `testServers`: Builds jars (with Docker-compatible natives) and runs Docker-based server integration tests.
- `generateVariants`: Regenerates `variants/` from `gradle/chronos-compile-groups.json` (should be run automatically when appropriate).
- `cleanVariants`: Clears the `variants/` folder. Useful when encountering issues with stale/locked variant directories.
- `:fabric-line-...:build` / `:neoforge-line-...:build`: Build a specific variant's jar.
- `:fabric-line-...:runClient` / `:neoforge-line-...:runServer`: Run a specific variant's server or client.

#### Variant generation (`generateVariants`)

`variants/` is a folder that contains the Gradle projects for each variant. Each variant is a separate project, and is built separately. The `generateVariants` task is used to regenerate the `variants/` folder from the `gradle/chronos-compile-groups.json` file. `generateVariants` should be run automatically when appropriate, but can be run manually alongside `cleanVariants` to force a regeneration. `CHRONOS_VARIANT_GENERATION` environment variable can be set to `skip` to skip the automatic variant regeneration.

#### Server testing (`testServers`)

testServers is a utility that uses Docker and the `itzg/minecraft-server` image to run integration tests for every supported loader/version combination. It first creates a Rust build environment in Docker and uses cargo-zigbuild to compile the rust-pruner library for linux-x86_64. It then builds all JARs with the native library included.

Once the artifacts are built, testServers starts a Dockerized Minecraft server for each supported loader/version combination. During startup, it checks the server logs for a number of markers to verify that the mod loaded and initialized correctly. Finally, it runs the speedtest command to confirm that backups function correctly and meet expected performance targets.

To run one or a few targets instead of the full matrix, pass `-Pchronos.testServers.args` with `--only` (repeatable). Targets use the form `loader-version`, with dots or underscores in the version (e.g. `fabric-1.14.4`, `forge-1_14_4`). These match the Docker container id suffix (`chronos-fabric-1_14_4`).

You can also pass `--filter` (repeatable) to include or exclude servers by substring in the target name (case ignored). Prefix a filter with `!` to exclude matches, e.g. `--filter folia` or `--filter !folia`.

This test is meant for power users and should be run at least once before any release to ensure the mod is working correctly on every supported version. On the initial run, it will need to pull every Docker Image (7 in total), as well as create and install every loader and version in the server, for over 250 combinations. This can take an extremely long time on first install, but will mostly be cached after that. The entire test run, after the initial install, can still take several hours in the best case scenario.

> [!Note]
> Keep in mind that Docker Images and containers are stored and managed by Docker. Commands like `cleanVariants` and `clean` will not remove them. You will need to manually remove them with your operating system's Docker management tools.

#### Command examples

```powershell
./gradlew cleanVariants
./gradlew buildAll
./gradlew :forge-line-1_10:runClient
./gradlew :fabric-line-1_21_11:runServer
./gradlew testServers
./gradlew testServers "-Pchronos.testServers.args=--only fabric-1.14.4"
./gradlew testServers "-Pchronos.testServers.args=--only fabric-1_14_4 --only forge-1_14_4"
./gradlew testServers "-Pchronos.testServers.args=--filter folia"
./gradlew testServers "-Pchronos.testServers.args=--filter !folia"
```
