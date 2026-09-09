# CoreProtect Fabric — Full Mod Overview (All Versions)

## One-Line Summary

**CoreProtect Fabric** is a Fabric server mod independently implemented with the renowned Bukkit/Spigot plugin **CoreProtect** as its blueprint, bringing "log everything, query and roll back anything" server governance to the Fabric ecosystem. This repository ships dedicated builds for **16 Minecraft versions, from 1.21 through 26.2**: full logging of blocks, containers, entities, chat, commands, and sessions, plus rollback, restore, lookup, inspection mode, and multi-language support — all working out of the box.

> This mod is an **independent implementation** inspired by [CoreProtect](https://github.com/PlayPro/CoreProtect) — not a port and not an official work. Its features and command style follow CoreProtect closely, and the database schema stays close to the original, so server owners already familiar with CoreProtect can pick it up immediately.

## Design Goals

- **Grief prevention & forensics**: every break, theft, or malicious action is recorded — disputes are settled with evidence, not guesswork.
- **Reversibility**: accidental edits and griefed builds can be rolled back precisely by player, time window, and radius.
- **Low overhead**: asynchronous SQLite writes keep the main game thread free of disk I/O; a Fabric environment without permission plugins is managed with simple permission levels.
- **Full version coverage**: one dedicated build per game version, each with its own mapping (yarn / mojmap) and API adaptation, so a change to one version never affects the others.
- **Works out of the box**: drop it into `mods/` and it runs — configuration and languages ship with sensible defaults.

## Features Shared by All Versions

### 1. Behavior Logging

| Category | What is recorded | Example |
|---|---|---|
| Blocks | Player placements/breaks (full block states plus sign text snapshots) | `Steve placed stone` |
| Natural events | Fire spread, water/lava washouts, explosions (TNT/Creeper/Wither/End Crystal), piston movement, enderman pickup/placement, leaf decay | `#fire destroyed wool`, `#water washed away torch` |
| Container transactions | Item deltas between opening and closing a container, logged per item type with counts | `Steve withdrew 5x diamond` |
| Entities | Player kills and player deaths (with killer attribution) | `Steve killed zombie` |
| Activity | Chat messages, executed commands, join/leave sessions | `Steve: /give ...` |

### 2. Rollback / Restore / Undo

- `/co rollback u:<user> t:<time> [r:<radius>] [b:<block>] [a:<action>] [e:<exclude>]` — reverses the target player's block changes and container item transactions
- `/co restore` — the reverse operation, replays the rolled-back changes
- `/co undo` — undoes your most recent rollback/restore in one command
- Safety guard: a rollback exceeding `maxBlocks` (default 5000) is refused automatically

### 3. Lookup System

`/co lookup [u:<user>] [t:<time>] [a:<action>] [r:<radius>] [b:<block>] [e:<exclude>] [p:<page>]` with pagination and combined filters. Action types `a:` include `block`, `+block`, `-block`, `#container`, `#kill`, `#chat`, `#command`, `#session`, plus natural events such as `#fire` `#water` `#lava` `#tnt` `#creeper` `#piston` `#enderman` `#decay`. Time format: `2w5d10h30m15s`.

### 4. Inspection Mode

After `/co inspect`: **left-click** a block to view its history, **right-click** to view container transactions or the adjacent block, **place a block** to run a lookup for that block type. Block breaking is disabled in inspection mode.

### 5. Data Maintenance & Multi-Language

- `/co purge t:<time>` prunes old data, `/co reload` hot-reloads, `/co status` shows statistics
- **Four languages**: English / 简体中文 / 繁體中文 / 日本語, automatically following the client language, with manual switching via `/co language <code>` (persisted per player)

## 1.21-Build Enhancements (v1.8.1 Flagship)

The `coreprotect-fabric-1.21/` project keeps evolving on top of the shared feature set:

| Version | Added |
|---|---|
| v1.1.0 | Hopper transaction logging (`#hopper`), item drop/pickup logging (`a:item`), sign-edit logging (`a:#sign`), CoreProtect-style output colors |
| v1.2.0 | `/co i` inspection shorthand (with `on`/`off`), permission groups (12 fine-grained nodes granted per player name list) |
| v1.3.0 | `/co online` online-record queries, configurable `databaseFile` location, `dataRetention` data-retention limit (automatic startup pruning) |
| v1.3.1 | Fixed inspect mode placing blocks on right-click (now cancels the placement and runs a lookup instead); output colors fully aligned with the plugin''s v22+ style |
| v1.4.0 | bStats usage statistics (official v2 protocol); automatic update checks with console and admin join notifications |
| v1.5.0 | Dropper (#dropper) / dispenser (#dispenser) item transaction logging |
| v1.5.1 | Fixed piston record noise (no more "piston changed/destroyed air"), no lookup when clicking air in inspect mode, clickable next-page hints |
| v1.6.0 | Command parameter suggestions (u:/t:/a:/r:/b:/e:/p: with player-name completion); faster lookups: separate parallel read pool, session/command/chat indexes, index-backed radius queries, parallel /co status counts |
| v1.7.0 | Database space compression: dictionary-encoded block states/items (schema v2, legacy DBs auto-migrate with backup + VACUUM, measured 12.55MB -> 8.3MB); auto-compact after /co purge; faster queries: mmap memory mapping, configurable cache (database.cacheSizeMB), read pool up to 8 threads |
| v1.7.1 | Fixed bStats registration: report platform corrected from fabric to server-implementation (the platform service 33739 is registered under), old configs migrate automatically |
| v1.7.2 | bStats now reports the player count (custom "players" single-line chart; pair it with a players chart on the bstats.org service page to display online players) |
| v1.8.0 | Crash protection: per-commit WAL fsync (synchronous=full), unclean-shutdown detection with startup quick_check, periodic WAL checkpoints |
| v1.8.1 | Power-loss protection: periodic hot backups (VACUUM INTO, default every 6 hours), automatic restore from the backup when corruption is detected (original kept aside), reader connections reopen on restore |

## Version Matrix (16 Builds)

Every build requires **Fabric Loader ≥ 0.16 + Fabric API**; the Java requirement is split into two tiers (Java 21 for 1.21.x, Java 25 for 26.x).

| Minecraft | Mod version | Jar file | Project directory | Java |
|---|---|---|---|---|
| 1.21 | 1.8.1 (flagship) | `coreprotect-fabric-1.21-1.8.1.jar` | `coreprotect-fabric-1.21/` | 21 |
| 1.21.1 | 1.0.0 | `coreprotect-fabric-1.0.0.jar` | `coreprotect-fabric/` | 21 |
| 1.21.2 | 1.0.0 | `coreprotect-fabric-1.21.2-1.0.0.jar` | `coreprotect-fabric-1.21.2/` | 21 |
| 1.21.3 | 1.0.0 | `coreprotect-fabric-1.21.3-1.0.0.jar` | `coreprotect-fabric-1.21.3/` | 21 |
| 1.21.4 | 1.0.0 | `coreprotect-fabric-1.21.4-1.0.0.jar` | `coreprotect-fabric-1.21.4/` | 21 |
| 1.21.5 | 1.0.0 | `coreprotect-fabric-1.21.5-1.0.0.jar` | `coreprotect-fabric-1.21.5/` | 21 |
| 1.21.6 | 1.0.0 | `coreprotect-fabric-1.21.6-1.0.0.jar` | `coreprotect-fabric-1.21.6/` | 21 |
| 1.21.7 | 1.0.0 | `coreprotect-fabric-1.21.7-1.0.0.jar` | `coreprotect-fabric-1.21.7/` | 21 |
| 1.21.8 | 1.0.0 | `coreprotect-fabric-1.21.8-1.0.0.jar` | `coreprotect-fabric-1.21.8/` | 21 |
| 1.21.9 | 1.0.0 | `coreprotect-fabric-1.21.9-1.0.0.jar` | `coreprotect-fabric-1.21.9/` | 21 |
| 1.21.10 | 1.0.0 | `coreprotect-fabric-1.21.10-1.0.0.jar` | `coreprotect-fabric-1.21.10/` | 21 |
| 1.21.11 | 1.0.0 | `coreprotect-fabric-1.21.11-1.0.0.jar` | `coreprotect-fabric-1.21.11/` | 21 |
| 26.1 | 1.0.0 | `coreprotect-fabric-26.1-1.0.0.jar` | `coreprotect-fabric-26.1/` | 25 |
| 26.1.1 | 1.0.0 | `coreprotect-fabric-26.1.1-1.0.0.jar` | `coreprotect-fabric-26.1.1/` | 25 |
| 26.1.2 | 1.0.0 | `coreprotect-fabric-26.1.2-1.0.0.jar` | `coreprotect-fabric-26.1.2/` | 25 |
| 26.2 | 1.0.0 | `coreprotect-fabric-26.2-1.0.0.jar` | `coreprotect-fabric-26.2/` | 25 |

> Note: 1.21.2 ~ 1.21.11 use yarn mappings, 26.x uses official mojmap mappings. Except for the 1.21 flagship, all builds carry the v1.0.0 core feature set; each project directory contains its own `OVERVIEW.md` / `OVERVIEW_EN.md` and `README.md`.

## Installation

1. Pick the jar matching your server's **exact Minecraft version** from the table above (1.21.x needs **Java 21**, 26.x needs **Java 25**).
2. Install **Fabric Loader ≥ 0.16** and **Fabric API** on the server.
3. Drop the jar into `mods/` and start the server. On first launch the configuration is generated at `config/coreprotect-fabric.json` and the SQLite database is created in the game directory.

## Technical Architecture (Shared by All Builds)

- **Event capture**: Fabric API events (break/place/use/kill/chat/session) plus mixins hooking natural events, attributing world changes without player involvement to virtual users such as `#fire` and `#water` via a thread-local cause-tracking mechanism (best-effort, `defaultRequire: 0`)
- **Storage**: SQLite (WAL mode) with a single async worker thread — the main game thread never touches the disk; the schema stays close to CoreProtect: `co_block` / `co_container` / `co_entity` / `co_session` / `co_command` / `co_chat` / `co_user` (the 1.21 build adds `co_item` / `co_sign`)
- **Rollback engine**: newest log per position wins; "rollback applies the old state, restore applies the new state" with object-level `BlockState` comparison; container item operations run first so contents are never lost when a container block is removed
- **Translation**: server-side resolution per player with the fallback chain: `/co language` override → client locale → configured default → `en_us`

## Known Limitations

- Kill records are not rolled back (same as the original CoreProtect); large rollbacks run on the main thread (protected by the `maxBlocks` cap)
- Container rollback uses a simplified item-recovery strategy: withdrawn items are preferably taken from the container and returned to the operator, with missing amounts compensated
- Natural-event hooks are best-effort: if a future game update breaks an individual hook, only that category stops logging — everything else keeps working
- The 1.21 build additionally logs hoppers (`#hopper`), droppers/dispensers (`#dropper`/`#dispenser`), item drops, and sign edits; **the other builds do not include** these four record types yet, nor the `/co i` shorthand, permission groups, `/co online`, or the data-retention limit (the 1.21 changes can be ported on demand)

## License & Credits

- The whole family is released under the **MIT License**
- Inspired by and functionally aligned with [CoreProtect](https://github.com/PlayPro/CoreProtect) — kudos to its development team
- Bundles [sqlite-jdbc](https://github.com/xerial/sqlite-jdbc) (Apache License 2.0)
