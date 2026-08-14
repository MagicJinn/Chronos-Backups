# Changelog

This page documents the changes and fixes made in Chronos Backups, compared to the original Random Things mod, in reverse chronological order.

## 1.1.1

- Shortened the stack trace when a cloud sync fails
- Increased the cloud sync timeout from 20 seconds to 10 minutes, and improved the resumable upload logic
- Improved an issue where backups would hang if the server was tick-paused. Backups should now complete correctly more often

## 1.1.0

- Add Bukkit, Spigot, Paper, Purpur and Folia plugin support
- Improve world discovery to no longer rely on `level.dat` / dataversion
- Reduce main-thread blocking during world flush
- Fix speedtest failing to do a backup if the flush took longer than its duration
- Add optional Google Drive backup sync (enable in config, authorize via the URL printed in the console)
- - Add `shouldKeepLocalBackups` so uploads can leave or remove the local copy after a successful sync
- Changed `maxStoredBackups` to only delete Chronos-named backup files when trimming (manually placed files in the backup folder are left alone)
- Fixed a crash if a world name contained invalid characters (such as `:`)

## 1.0.1

- Fix backups blocking the main thread
- Fix set backup folder name not being respected

## 1.0.0

- Initial release for Forge, Fabric and NeoForge!
