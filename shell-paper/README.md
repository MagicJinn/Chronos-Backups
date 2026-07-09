# shell-paper

Paper plugin integration for Chronos Backups. One Bukkit-only source tree is shared across every Paper unified jar from Minecraft 1.7.10 through current releases.

- `ChronosPaperPlugin.java` - plugin entrypoint, lifecycle, tick bridge
- `PaperServerEnvironment` / `PaperBackupWorldController` - stable Bukkit server and world APIs
- `PaperCommands` - `/chronos` via `LegacyCommandSupport` (works pre- and post-Brigadier)

No NMS or Mojmap. Most lines compile against the matching `paper-api` coordinate; 1.7.10 and 1.8 use the closest hosted `spigot-api` because those Paper API artifacts are no longer on Maven. On legacy CraftBukkit/Paper, `Bukkit.dispatchCommand` and RCON expect commands without a leading `/`.
