# Chronos Backup

![Mod Icon](icon.png)

[![Modrinth](https://img.shields.io/badge/Modrinth-Chronos_Backup-00ae5d?logo=modrinth)](https://modrinth.com/mod/chronos-backup) [![CurseForge](https://img.shields.io/badge/CurseForge-Chronos_Backup-f16437?logo=curseforge)](https://www.curseforge.com/minecraft/mc-mods/chronos-backup)

**Chronos Backup** is a multi-loader Minecraft backup utility. Designed to save the most important parts of your world, keeping backups smaller.

The project is organized as a small, version-agnostic **core** (scheduler + backup runner) plus loader/version-specific **shells**, with one central place to edit mod metadata.

## Features

- Multi-version, multi-loader architecture (Fabric, NeoForge, Forge).
- Focused backups that include critical world files instead of copying everything.
- Shared core logic with generated loader/version variants for broad compatibility.

## Architecture

Chronos Backup is made of two layers:

- **Shell layer**: loader and version specific integration code that hooks into the server/runtime and exposes configuration per platform.
- **Core layer**: loader-agnostic scheduling and backup execution logic reused across all targets.

## Supported and moddable targets

All currently supported versions are listed below in `major.minor.x` format:

| Minecraft | Support       | Loader(s)                         | Backup | Config       | Notes                                     |
| --------- | ------------- | --------------------------------- | ------ | ------------ | ----------------------------------------- |
| `26.1.x`  | ✅ Supported   | Fabric + NeoForge                 | ✅      | 🟡 File-only | Current target with ready-made jars.      |
| `1.21.x`  | ✅ Supported   | Fabric + NeoForge                 | ✅      | 🟡 File-only | Current target with ready-made jars.      |
| `1.20.x`  | ❌ Unsupported | NeoForge / Forge / Fabric / Quilt | ❌      | 🔴 None      | -                                         |
| `1.19.x`  | ❌ Unsupported | Forge / Fabric / Quilt            | ❌      | 🔴 None      | -                                         |
| `1.18.x`  | ❌ Unsupported | Forge / Fabric / Quilt            | ❌      | 🔴 None      | -                                         |
| `1.17.x`  | ❌ Unsupported | Forge / Fabric / Quilt            | ❌      | 🔴 None      | -                                         |
| `1.16.x`  | ❌ Unsupported | Forge / Fabric                    | ❌      | 🔴 None      | -                                         |
| `1.15.x`  | ❌ Unsupported | Forge / Fabric                    | ❌      | 🔴 None      | -                                         |
| `1.14.x`  | ❌ Unsupported | Forge / Fabric                    | ❌      | 🔴 None      | -                                         |
| `1.13.x`  | ❌ Unsupported | Forge                             | ❌      | 🔴 None      | -                                         |
| `1.12.x`  | ✅ Supported   | Forge                             | ✅      | 🟡 File-only | Classic Forge target with ready-made jar. |
| `1.11.x`  | ❌ Unsupported | Forge                             | ❌      | 🔴 None      | -                                         |
| `1.10.x`  | ❌ Unsupported | Forge                             | ❌      | 🔴 None      | -                                         |
| `1.9.x`   | ❌ Unsupported | Forge                             | ❌      | 🔴 None      | -                                         |
| `1.8.x`   | ❌ Unsupported | Forge                             | ❌      | 🔴 None      | -                                         |
| `1.7.x`   | ❌ Unsupported | Legacy Forge                      | ❌      | 🔴 None      | -                                         |
| `1.6.x`   | ❌ Unsupported | Legacy Forge                      | ❌      | 🔴 None      | -                                         |
| `1.5.x`   | ❌ Unsupported | Legacy Forge                      | ❌      | 🔴 None      | -                                         |
| `1.4.x`   | ❌ Unsupported | Legacy Forge                      | ❌      | 🔴 None      | -                                         |
| `1.3.x`   | ❌ Unsupported | Legacy Forge                      | ❌      | 🔴 None      | -                                         |
| `1.2.x`   | ❌ Unsupported | Legacy Forge                      | ❌      | 🔴 None      | -                                         |
| `1.1.x`   | ❌ Unsupported | Legacy Forge                      | ❌      | 🔴 None      | -                                         |

`Config` values:

- `🟢 Full` = in-game config menu available.
- `🟡 File-only` = config by file/editing only (no in-game menu).
- `🔴 None` = no user-facing config.

## Development

### Prerequisites

- Install **JDK 25+** and set `JAVA_HOME` to it.
- `forge/` uses a Java 8 toolchain via Gradle/Foojay automatically.

### Commands

Use the Gradle wrapper from the repo root.

#### PowerShell

```powershell
.\gradlew.bat buildAll
.\gradlew.bat smokeTestServers
.\gradlew.bat smokeTestServers -PchronosSmokeArgs="--workers 16"
```

Bash/zsh:

```bash
./gradlew buildAll
./gradlew smokeTestServers
```

### Most useful tasks

- `buildAll` - builds all enabled variants and Forge, then collects output jars.
- `collectAllJars` - copies final jars to root `build/libs/`. Automatically run by `buildAll`.
- `:fabric-line-1_21:build`, `:neoforge-line-1_21:build`, `:neoforge-1_21_4:build` - build one target.
- `generateVariantProjects` - generates variant projects from `gradle/chronos-versions.json` and `gradle/chronos-compile-groups.json`. Should be run automaticaly.
- `smokeTestServers` - runs dev servers and checks expected Chronos startup lines. Arguments: `--workers <number>` (default 4), `--only <label>` (repeatable).

Example Smoke Test command:

```powershell
.\gradlew.bat smokeTestServers "-Pchronos.smoke.args=--workers 2 --only fabric-line-1_21"
# equivalent:
.\gradlew.bat smokeTestServers -PchronosSmokeArgs="--workers 2 --only fabric-line-1_21"
# IMPORTANT: do not insert a space before `=` in `-P...=...`
```
