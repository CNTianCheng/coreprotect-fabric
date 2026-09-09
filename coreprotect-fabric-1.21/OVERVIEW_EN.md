# CoreProtect Fabric — Mod Overview (v1.8.1)

## One-Line Summary

**CoreProtect Fabric** is a block-level logging and rollback mod for Minecraft 1.21 Fabric servers. Independently implemented with the renowned Bukkit/Spigot plugin **CoreProtect** as its blueprint, it brings "log everything, query and roll back anything" server governance to the Fabric ecosystem. It records blocks, containers, hoppers, item drops, sign edits, entities, chat, commands, and online sessions, and ships with rollback, restore, lookup, inspection mode, online-record queries, and multi-language support — with the same output color scheme as CoreProtect.

> This mod is an **independent implementation** inspired by [CoreProtect](https://github.com/PlayPro/CoreProtect) — not a port and not an official work. Its features and command style follow CoreProtect closely, and the database schema stays close to the original, so server owners already familiar with CoreProtect can pick it up immediately.

## Design Goals

- **Grief prevention & forensics**: every break, theft, or malicious action is recorded — disputes are settled with evidence, not guesswork.
- **Reversibility**: accidental edits and griefed builds can be rolled back precisely by player, time window, and radius.
- **Low overhead**: asynchronous SQLite writes keep the main game thread free of disk I/O; a Fabric environment without permission nodes is managed with simple permission levels, with optional fine-grained permission groups.
- **Works out of the box**: drop it into `mods/` and it runs — configuration and languages ship with sensible defaults.

## Core Features

### 1. Comprehensive Logging

| Category | What is recorded | Example |
|---|---|---|
| Blocks | Player placements/breaks (full block states plus sign text snapshots) | `Steve placed stone` |
| Natural events | Fire spread, water/lava washouts, explosions (TNT/Creeper/Wither/End Crystal), piston movement, enderman pickup/placement, leaf decay | `#fire destroyed wool`, `#water washed away torch` |
| Container transactions | Item deltas between opening and closing a container, logged per item type with counts | `Steve withdrew 5x diamond` |
| Hopper transactions | Item changes of the hopper itself, its source above, and its target container every tick, attributed to the virtual user #hopper | `#hopper deposited 3x diamond` |
| Droppers/dispensers | Every dispensed item and external deposit is logged as a container transaction, attributed to the virtual users #dropper / #dispenser | `#dropper withdrew 1x emerald` |
| Item drops | Player item drops and pickups (verbs dropped / picked up) | `Steve dropped 1x stone` |
| Sign edits | Snapshots of sign text edited directly by players (no need to break and replace) | `Steve edited sign text [...]` |
| Entities | Player kills and player deaths (with killer attribution) | `Steve killed zombie` |
| Activity | Chat messages, executed commands, join/leave sessions | `Steve: /give ...` |

### 2. Rollback / Restore / Undo

- `/co rollback u:<user> t:<time> [r:<radius>] [b:<block>] [a:<action>] [e:<exclude>]` — reverses the target player's block changes and container item transactions (removed items are restored to the container, deposited items are removed and returned to the operator)
- `/co restore` — the reverse operation, replays the rolled-back changes
- `/co undo` — undoes your most recent rollback/restore in one command
- Safety guard: a rollback exceeding `maxBlocks` (default 5000) is refused automatically to prevent freezing the server

### 3. Lookup System

`/co lookup [u:<user>] [t:<time>] [a:<action>] [r:<radius>] [b:<block>] [e:<exclude>] [p:<page>]` with pagination and combined filters:

- Action types `a:`: `block` (all blocks), `+block` (placements only), `-block` (breaks only), `#container` (containers), `item`/`#item` (drops), `#sign` (sign edits), `#kill` (kills), `#chat`, `#command`, `#session`, plus natural events such as `#fire` `#water` `#lava` `#tnt` `#creeper` `#piston` `#enderman` `#decay`
- Time format: `2w5d10h30m15s` (weeks/days/hours/minutes/seconds)
- Output uses CoreProtect's color scheme (v22+ style): generic messages use the dark-aqua `CoreProtect - ` prefix with white text; the lookup header is `----- CoreProtect | Lookup Results -----`; lookup rows are "gray time + green `+`/red `-` tag + dark-aqua player + white verb + dark-aqua subject"; status lines are dark-aqua labels with white values

### 4. Inspection Mode

After `/co inspect` (or the shorthand `/co i`, with optional `on`/`off`): **left-click** a block to view its history, **right-click** to view container transactions or the adjacent block, **place a block** to run a lookup for that block type. Block breaking is disabled in inspection mode to prevent accidental damage during investigation.

### 5. Online Records

- `/co online` — lists the players currently online
- `/co online <player> [t:<time>] [p:<page>]` — shows whether the player is online right now and lists their join/leave records as clock times (`yyyy-MM-dd HH:mm:ss`)

### 6. Permission System

- **Level-based (default)**: `permissions.lookupLevel` (default 0 = everyone) / `adminLevel` (default 4 = ops) — zero configuration on Fabric servers without a permission plugin
- **Permission groups (optional)**: with `permissionGroups.enabled` set, players are granted fine-grained nodes by name list (12 nodes such as `coreprotect.help`, `coreprotect.inspect`, `coreprotect.lookup`, `coreprotect.online`, `coreprotect.rollback`); memberships from multiple groups merge, names are case-insensitive, and unmatched players fall back to the level-based rules

### 7. Data Maintenance & Multi-Language

- `/co purge t:<time>` prunes old data manually; with `dataRetention` enabled the server **automatically deletes** all records older than `maxDays` at startup (row count is written to the server log; `/co status` shows the current state)
- Database location is configurable: `databaseFile` accepts a path relative to the game directory (subdirectories are created automatically) or an absolute path
- `/co reload` hot-reloads configuration and languages, `/co status` shows database statistics
- **Four languages**: English / 简体中文 / 繁體中文 / 日本語
  - Automatically follows the client language, or switch manually with `/co language <code>` (persisted per player; `auto` restores automatic mode)
  - Adding a new language only requires a JSON translation file in `assets/coreprotect/lang/`

### 8. bStats Metrics & Update Notifications

- **bStats usage statistics**: anonymous metrics following the official bStats v2 protocol (player count, online mode, Minecraft/loader/Java/OS info), submitted every 30 minutes; ships with the registered service id (33739), and `config/bstats/config.txt` provides a one-line opt-out
- **New-version notifications**: the mod checks the Modrinth API automatically after startup (slug/URL configurable); when a newer release exists it prints a notice to the **server console**, and admin-level players receive the same notice (in their own language) **when they join**

## Command Reference

| Command | Purpose | Default permission |
|---|---|---|
| `/co help` | Show help | Everyone (lookup level) |
| `/co inspect [on\|off]`, `/co i [on\|off]` | Toggle inspection mode | lookup level |
| `/co lookup [params]` | Query records | lookup level |
| `/co online [player] [t:<time>]` | Query online records / list online players | lookup level |
| `/co rollback u:<user> [params]` | Roll back changes | Ops (admin level) |
| `/co restore u:<user> [params]` | Restore changes | admin level |
| `/co undo` | Undo last rollback/restore | admin level |
| `/co purge t:<time>` | Prune old data | admin level |
| `/co reload` | Reload config & languages | admin level |
| `/co status` | Database statistics | lookup level |
| `/co language [list\|<code>]` | View/switch language | lookup level |
| `/co debug natural` | Places fire/water/explosion scenarios to verify logging | admin level |

`/coreprotect` is an alias of `/co`. Both permission levels and permission groups are configurable in `config/coreprotect-fabric.json`.

## Technical Architecture

- **Event capture**: Fabric API events (break/place/use/kill/chat/session/drops) plus mixins hooking natural events, hoppers, item pickups, and sign edits, attributing world changes without player involvement to virtual users such as `#fire`, `#water`, and `#hopper` via a thread-local cause-tracking mechanism
- **Storage**: SQLite (WAL mode) with a single async worker thread — the main game thread never touches the disk; the schema stays close to CoreProtect: `co_block` / `co_container` / `co_item` / `co_sign` / `co_entity` / `co_session` / `co_command` / `co_chat` / `co_user`
- **Rollback engine**: newest log per position wins; "rollback applies the old state, restore applies the new state" with object-level `BlockState` comparison to avoid false mismatches from property order/format differences; container item operations run first so contents are never lost when a container block is removed
- **Translation**: server-side translation engine resolves language per player with the fallback chain: `/co language` override → client locale → configured default → `en_us`
- **Configuration**: `config/coreprotect-fabric.json` (language, database location, per-category logging toggles, lookup page size, rollback cap, permission levels/groups, data-retention limit)

## Installation & Versions

| Requirement | Value |
|---|---|
| Minecraft | **1.21** (for 1.21.1, use the matching build in the `coreprotect-fabric/` directory) |
| Server | Fabric Loader ≥ 0.16 + Fabric API |
| Java | 21 |
| Install | Drop `coreprotect-fabric-1.21-1.8.1.jar` into `mods/`; config and database are generated on first launch |

> The mod is also available for the full range 1.21.2 ~ 1.21.11 and 26.1 ~ 26.2 (each build lives in its own project directory) with the same feature set.

## Use Cases

- **Survival servers — grief protection**: find out who demolished a base or looted a chest, roll it back with one command
- **Building servers — protection**: every accidental break, explosion, or water washout is traceable and reversible
- **Dispute forensics**: inspection mode pinpoints the on-site history quickly; `/co online` verifies player join/leave times
- **Server administration**: session/command/chat logs plus automatic data pruning assist audits and moderation

## Known Limitations (v1.8.1)

- Hopper (`#hopper`), dropper (`#dropper`) and dispenser (`#dispenser`) transactions are all logged
- Item-drop logging covers player drops and pickups; drops spawned by breaking blocks are not recorded separately (they are already part of the block-break record)
- Sign edits are logged; rollback restores the text recorded at placement/break time, and the edit history does not take part in rollback yet
- Kill records are not rolled back (same as the original CoreProtect)
- Large rollbacks run on the main thread (protected by the `maxBlocks` cap)
- Container rollback uses a simplified item-recovery strategy: withdrawn items are preferably taken from the container and returned to the operator, with missing amounts compensated
- Natural-event and similar hooks are best-effort (`defaultRequire: 0`): if a future game update breaks an individual hook, only that category stops logging — everything else keeps working

## License & Credits

- Released under the **MIT License**
- Inspired by and functionally aligned with [CoreProtect](https://github.com/PlayPro/CoreProtect) — kudos to its development team
- Bundles [sqlite-jdbc](https://github.com/xerial/sqlite-jdbc) (Apache License 2.0)
