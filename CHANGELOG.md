# Changelog

This page documents the changes and fixes made in Chronos Backups, compared to the original Random Things mod, in reverse chronological order.

## 1.1.0

- Add Bukkit, Spigot, Paper, Purpur and Folia plugin support
- Improve world discovery to no longer rely on `level.dat` / dataversion
- Reduce main-thread blocking during world flush
- Fix speedtest failing to do a backup if the flush took longer than its duration

## 1.0.1

- Fix backups blocking the main thread
- Fix set backup folder name not being respected

## 1.0.0

- Initial release for Forge, Fabric and NeoForge!
