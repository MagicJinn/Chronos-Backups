# shell-paper

Paper plugin integration for Chronos Backups. One Bukkit-only source tree (`shell-paper/`, `ChronosPaperPlugin`, etc.) is shared across every unified plugin jar from Minecraft 1.7.10 through current releases. Jars are merged per compile block (for example `1.7.10-1.12.x`, `1.13-1.16.x`, `1.17-1.20.x`, `1.21.x`, `26.x`) rather than one jar per Minecraft line.

Each release is built and named `-plugin.jar`. The same artifact runs on every loader listed in that compile block's `pluginConfig.loaderKeys` (Paper, Spigot, and Bukkit on 1.16 and below, Purpur from 1.17 onward, Folia on supported versions from 1.19.4 onward). Docker test servers exercise each listed loader.

- `ChronosPaperPlugin.java` - plugin entrypoint, lifecycle, tick bridge
- `PaperServerEnvironment` / `PaperBackupWorldController` - stable Bukkit server and world APIs
- `PaperCommands` - `/chronos` via `LegacyCommandSupport` (works pre- and post-Brigadier)

No NMS or Mojmap. Most lines compile against the matching `paper-api` coordinate. 1.7.10 and 1.8 use the closest hosted `spigot-api` because those Paper API artifacts are no longer on Maven. On legacy CraftBukkit/Paper, `Bukkit.dispatchCommand` and RCON expect commands without a leading `/`.
