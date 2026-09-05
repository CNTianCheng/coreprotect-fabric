# CoreProtect Fabric — Mod Overview

## One-Line Summary

**CoreProtect Fabric** is a block-level logging and rollback mod for Minecraft Fabric servers. Independently implemented with the renowned Bukkit/Spigot plugin **CoreProtect** as its blueprint, it brings "log everything, query and roll back anything" server governance to the Fabric ecosystem. It records blocks, containers, entities, chat, commands, and sessions, and ships with rollback, restore, lookup, inspection mode, and multi-language support.

> This mod is an **independent implementation** inspired by [CoreProtect](https://github.com/PlayPro/CoreProtect) — not a port and not an official work. Its features and command style follow CoreProtect closely, and the database schema stays close to the original, so server owners already familiar with CoreProtect can pick it up immediately.

## Design Goals

- **Grief prevention & forensics**: every break, theft, or malicious action is recorded — disputes are settled with evidence, not guesswork.
- **Reversibility**: accidental edits and griefed builds can be rolled back precisely by player, time window, and radius.
- **Low overhead**: asynchronous SQLite writes keep the main game thread free of disk I/O; a Fabric environment without permission nodes is managed with simple permission levels.
- **Works out of the box**: drop it into `mods/` and it runs — configuration and languages ship with sensible defaults.

## Core Features

### 1. Comprehensive Logging

| Category | What is recorded | Example |
|---|---|---|
| Blocks | Player placements/breaks (full block states plus sign text snapshots) | `Steve placed stone` |
| Natural events | Fire spread, water/lava washouts, explosions (TNT/Creeper/Wither/End Crystal), piston movement, enderman pickup/placement, leaf decay | `#fire destroyed wool`, `#water washed away torch` |
| Container transactions | Item deltas between opening and closing a container, logged per item type with counts | `Steve withdrew 5x diamond` |
| Entities | Player kills and player deaths (with killer attribution) | `Steve killed zombie` |
| Activity | Chat messages, executed commands, join/leave sessions | `Steve: /give ...` |

### 2. Rollback / Restore / Undo

- `/co rollback u:<user> t:<time> [r:<radius>] [b:<block>] [a:<action>] [e:<exclude>]` — reverses the target player's block changes and container item transactions (removed items are restored to the container, deposited items are removed and returned to the operator)
- `/co restore` — the reverse operation, replays the rolled-back changes
- `/co undo` — undoes your most recent rollback/restore in one command
- Safety guard: a rollback exceeding `maxBlocks` (default 5000) is refused automatically to prevent freezing the server

### 3. Lookup System

`/co lookup [u:<user>] [t:<time>] [a:<action>] [r:<radius>] [b:<block>] [e:<exclude>] [p:<page>]` with pagination and combined filters:

- Action types `a:`: `block` (all blocks), `+block` (placements only), `-block` (breaks only), `#container` (containers), `#kill` (kills), `#chat`, `#command`, `#session`, plus natural events such as `#fire` `#water` `#lava` `#tnt` `#creeper` `#piston` `#enderman` `#decay`
- Time format: `2w5d10h30m15s` (weeks/days/hours/minutes/seconds)

### 4. Inspection Mode

After `/co inspect`: **left-click** a block to view its history, **right-click** to view container transactions or the adjacent block, **place a block** to run a lookup for that block type. Block breaking is disabled in inspection mode to prevent accidental damage during investigation.

### 5. Data Maintenance & Multi-Language

- `/co purge t:<time>` prunes old data, `/co reload` hot-reloads configuration and languages, `/co status` shows database statistics
- **Four languages**: English / 简体中文 / 繁體中文 / 日本語
  - Automatically follows the client language, or switch manually with `/co language <code>` (persisted per player; `auto` restores automatic mode)
  - Adding a new language only requires a JSON translation file in `assets/coreprotect/lang/`

## Command Reference

| Command | Purpose | Default permission |
|---|---|---|
| `/co help` | Show help | Everyone (lookup level) |
| `/co inspect` | Toggle inspection mode | lookup level |
| `/co lookup [params]` | Query records | lookup level |
| `/co rollback u:<user> [params]` | Roll back changes | Ops (admin level) |
| `/co restore u:<user> [params]` | Restore changes | admin level |
| `/co undo` | Undo last rollback/restore | admin level |
| `/co purge t:<time>` | Prune old data | admin level |
| `/co reload` | Reload config & languages | admin level |
| `/co status` | Database statistics | lookup level |
| `/co language [list\|<code>]` | View/switch language | lookup level |
| `/co debug natural` | Places fire/water/explosion scenarios to verify logging | admin level |

`/coreprotect` is an alias of `/co`. Permission levels are configurable in `config/coreprotect-fabric.json` (`lookupLevel` defaults to 0, `adminLevel` defaults to 4).

## Technical Architecture

- **Event capture**: Fabric API events (break/place/use/kill/chat/session) plus 10 mixins hooking natural events (fire, fluids, explosions, pistons, endermen, leaves), attributing world changes without player involvement to virtual users such as `#fire` and `#water` via a thread-local cause-tracking mechanism
- **Storage**: SQLite (WAL mode) with a single async worker thread — the main game thread never touches the disk; the schema stays close to CoreProtect: `co_block` / `co_container` / `co_entity` / `co_session` / `co_command` / `co_chat` / `co_user`
- **Rollback engine**: newest log per position wins; "rollback applies the old state, restore applies the new state" with object-level `BlockState` comparison to avoid false mismatches from property order/format differences; container item operations run first so contents are never lost when a container block is removed
- **Translation**: server-side translation engine resolves language per player with the fallback chain: `/co language` override → client locale → configured default → `en_us`
- **Configuration**: `config/coreprotect-fabric.json` (language, database file name, per-category logging toggles, lookup page size, rollback cap, permission levels)

## Installation & Versions

| Requirement | Value |
|---|---|
| Minecraft | **1.21** or **1.21.1** (use the matching build) |
| Server | Fabric Loader ≥ 0.16 + Fabric API |
| Java | 21 |
| Install | Drop the matching jar into `mods/`; config and database are generated on first launch |

Version matrix:

| Game version | Jar | Project directory |
|---|---|---|
| 1.21 | `coreprotect-fabric-1.21-1.0.0.jar` | `coreprotect-fabric-1.21/` |
| 1.21.1 | `coreprotect-fabric-1.0.0.jar` | `coreprotect-fabric/` |

## Use Cases

- **Survival servers — grief protection**: find out who demolished a base or looted a chest, roll it back with one command
- **Building servers — protection**: every accidental break, explosion, or water washout is traceable and reversible
- **Dispute forensics**: inspection mode pinpoints the on-site history quickly
- **Server administration**: session/command/chat logs assist audits and moderation

## Known Limitations (v1)

- Non-player container transactions (hoppers, droppers) are not logged yet
- Item drop/pickup is not logged yet
- Sign *editing* (without breaking) is not logged yet; rollback restores the text recorded at placement/break time
- Kill records are not rolled back (same as the original CoreProtect)
- Large rollbacks run on the main thread (protected by the `maxBlocks` cap)
- Container rollback uses a simplified item-recovery strategy: withdrawn items are preferably taken from the container and returned to the operator, with missing amounts compensated
- Natural-event hooks are best-effort (`defaultRequire: 0`): if a future game update breaks an individual hook, only that natural-event category stops logging — everything else keeps working

## License & Credits

- Released under the **MIT License**
- Inspired by and functionally aligned with [CoreProtect](https://github.com/PlayPro/CoreProtect) — kudos to its development team
- Bundles [sqlite-jdbc](https://github.com/xerial/sqlite-jdbc) (Apache License 2.0)
