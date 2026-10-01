# CoreProtect Fabric

**CoreProtect-style block logging, rollback and anti-grief forensics for Fabric servers — everything is logged, anything can be rolled back.**

CoreProtect Fabric is a **server-side Fabric mod**, independently implemented with the renowned Bukkit/Spigot plugin [CoreProtect](https://github.com/PlayPro/CoreProtect) as its blueprint. Drop the jar into `mods/`, start the server, and every block placement, break, container transaction, item move, kill, chat line, command and session is written to a local SQLite database — ready to be queried and rolled back when something goes wrong.

> This is an **independent implementation** inspired by CoreProtect — not a port, and not an official work of the CoreProtect team. Commands and behaviour deliberately follow the plugin closely, so server owners already familiar with CoreProtect can use it immediately.

---

## English

### Features

**Logging (all of it, asynchronously)**

| Category | What is recorded |
|---|---|
| Blocks | Player placements and breaks, with full block states and sign-text snapshots (`Steve placed stone`) |
| Natural events | Fire spread, water/lava washouts, explosions (TNT, creeper, wither, end crystal), piston movement, enderman pickup/placement, leaf decay |
| Containers | Item deltas between opening and closing a container, hoppers, droppers and dispensers (`Steve withdrew 5x diamond`) |
| Items | Item drops and pickups, attributed to the player |
| Entities | Player kills and player deaths, with killer attribution |
| Activity | Chat messages, executed commands, join/leave sessions |

**Rollback, restore and undo**

- `/co rollback`, `/co restore`, `/co undo` — replay or reverse any player's edits
- Rollbacks are precise: newest log per position wins, block states are compared per object, and container item operations run first so contents are never lost when a container block is removed
- A safety cap (`maxBlocks`, default 5000) refuses oversized rollbacks instead of freezing the server

**Lookup and inspection**

- `/co lookup` with combined filters, action aliases and pagination
- `/co inspect` (or `/co i`): left-click a block for its history, right-click a container for its transactions or the adjacent block — block breaking is disabled while inspecting

**Maintenance and languages**

- `/co purge t:<time>` prunes old data (and reclaims space), `/co status` reports statistics, `/co reload` hot-reloads configuration
- `/co language <code>` switches between **English / 简体中文 / 繁體中文 / 日本語 / Русский**; by default the mod follows each player's client language

### Commands

```
/co help
/co inspect | /co i [on|off]
/co lookup   [u:<user>] [t:<time>] [a:<action>] [r:<radius>] [b:<block>] [e:<exclude>] [p:<page>]
/co rollback u:<user>   [t:<time>] [r:<radius>] [a:<action>] [b:<block>] [e:<exclude>]
/co restore  u:<user>   [t:<time>] [r:<radius>] [a:<action>] [b:<block>] [e:<exclude>]
/co undo
/co purge    t:<time>
/co online   [player] [t:<time>] [p:<page>]
/co status | /co reload | /co language [list|<code>]
```

Time format: `2w5d10h30m15s` (weeks, days, hours, minutes, seconds).
Action filters include `block`, `+block`, `-block`, `#container`, `#kill`, `#chat`, `#command`, `#session`, `#item`, and natural causes such as `#fire` `#water` `#lava` `#tnt` `#creeper` `#piston` `#enderman` `#decay`.

### Storage (schema v3, as of v1.9.0)

The database was rebuilt around dictionaries, so repeated text is no longer stored on every row:

- `user`, `world` and `action` live in `co_name` / `co_world` / `co_action`, block states in `co_state`; log tables keep only integer ids
- Each log table carries exactly two indexes: `(wid_id, x, y, z)` and a composite `(time, name_id)` that also covers per-player queries
- Measured on a 417k-row fixture: **46.38 MB → 24.32 MB (−47.6 %), 116.6 → 61.2 bytes per row**; `/co purge` also garbage-collects unused dictionary rows
- **Upgrading is automatic**: an existing v2 (or v1) database is migrated in place on the first start, after keeping `coreprotect.db.bak-v2` as a safety net. Migration needs roughly 2.2× the database size in free disk space (backup copy + pre-VACUUM file); delete the `.bak-v2` once the new database looks correct
- Crash and power-loss protection: WAL journal with `synchronous=full` per commit, a crash marker with `quick_check` on the next start, periodic WAL checkpoints and periodic hot backups (`VACUUM INTO`, default every 6 hours) with automatic restore when corruption is detected

### Supported Minecraft versions

| Minecraft | Java | Jar |
|---|---|---|
| **1.21** | 21 | `coreprotect-fabric-1.21-1.9.1.jar` |
| **1.21.11** | 21 | `coreprotect-fabric-1.21.11-1.9.1.jar` |
| **26.1.2** | 25 | `coreprotect-fabric-26.1.2-1.9.1.jar` |

These three builds carry the complete feature set and are kept in sync. Builds for 13 further versions (1.21.1 – 1.21.10, 26.1, 26.1.1, 26.2) ship the v1.0.0 core feature set and are available from the [GitHub releases](https://github.com/CNTianCheng/coreprotect-fabric/releases).

### Installation

1. Install **Fabric Loader ≥ 0.16** and **Fabric API** on your server.
2. Pick the jar that matches your server's **exact Minecraft version** and its Java runtime (Java 21 for 1.21.x, Java 25 for 26.x).
3. Drop it into the server's `mods/` folder and start the server. The mod is server-side — players do not need to install anything.
4. On first launch the configuration is generated at `config/coreprotect-fabric.json` and the database at `coreprotect.db` in the server directory.

### Known limitations

- Kill records are not rolled back (same as the original CoreProtect)
- Large rollbacks run on the main thread, protected by the `maxBlocks` cap
- Natural-event hooks are best-effort: if a future game update breaks one hook, only that category stops logging
- Container rollback uses a simplified item-recovery strategy (withdrawn items are taken back from the container where possible, missing amounts are compensated)

### Links

[Source code](https://github.com/CNTianCheng/coreprotect-fabric) · [Releases](https://github.com/CNTianCheng/coreprotect-fabric/releases) · [Changelog](https://github.com/CNTianCheng/coreprotect-fabric/blob/main/CHANGELOG.md) · [Issues](https://github.com/CNTianCheng/coreprotect-fabric/issues) · [Contributing](https://github.com/CNTianCheng/coreprotect-fabric/blob/main/CONTRIBUTING.md) · [Security](https://github.com/CNTianCheng/coreprotect-fabric/blob/main/SECURITY.md)

MIT licensed. Bundles [sqlite-jdbc](https://github.com/xerial/sqlite-jdbc) (Apache License 2.0).

---

## 中文

**为 Fabric 服务端提供 CoreProtect 风格的方块记录、回滚与防破坏取证 —— 一切都会被记录，任何改动都能回滚。**

CoreProtect Fabric 是一个**纯服务端** Fabric 模组，以著名的 Bukkit/Spigot 插件 [CoreProtect](https://github.com/PlayPro/CoreProtect) 为蓝本独立实现。把 jar 放进 `mods/` 启动服务端，所有方块放置/破坏、容器存取、物品移动、击杀、聊天、命令与上下线记录都会写入本地 SQLite 数据库，出现问题时可以立即查询与回滚。

> 本项目是受 CoreProtect 启发的**独立实现**，不是移植版，也不属于 CoreProtect 官方作品。命令与行为刻意保持接近，熟悉该插件的服主可以零成本上手。

### 功能

**记录（全异步写入）**

| 类别 | 记录内容 |
|---|---|
| 方块 | 玩家放置与破坏，包含完整方块状态与告示牌文本快照（`Steve placed stone`） |
| 自然事件 | 火焰蔓延、水流/岩浆冲刷、爆炸（TNT、苦力怕、凋灵、末地水晶）、活塞推动、末影人搬运、树叶枯萎 |
| 容器 | 打开与关闭之间的物品增减，含漏斗、投掷器与发射器（`Steve withdrew 5x diamond`） |
| 物品 | 掉落与拾取，并归属到玩家 |
| 实体 | 玩家击杀与玩家死亡（含凶手归属） |
| 行为 | 聊天内容、执行的命令、上下线会话 |

**回滚 / 恢复 / 撤销**

- `/co rollback`、`/co restore`、`/co undo` —— 重放或撤销任意玩家的改动
- 回滚精确：同一坐标以最新记录为准，按对象比较方块状态；容器物品操作优先执行，避免移除容器方块时丢失内容
- 安全上限（`maxBlocks`，默认 5000）会自动拒绝超量回滚，避免卡服

**查询与检查**

- `/co lookup` 支持组合筛选、动作别名与分页
- `/co inspect`（或 `/co i`）：左键查看方块历史，右键查看容器交易或相邻方块；检查模式下禁止破坏方块

**维护与多语言**

- `/co purge t:<time>` 清理旧数据并回收空间，`/co status` 查看统计，`/co reload` 热重载配置
- `/co language <code>` 在 **English / 简体中文 / 繁體中文 / 日本語 / Русский** 之间切换，默认跟随玩家客户端语言

### 命令

```
/co help
/co inspect | /co i [on|off]
/co lookup   [u:<用户>] [t:<时间>] [a:<动作>] [r:<半径>] [b:<方块>] [e:<排除>] [p:<页码>]
/co rollback u:<用户>   [t:<时间>] [r:<半径>] [a:<动作>] [b:<方块>] [e:<排除>]
/co restore  u:<用户>   [t:<时间>] [r:<半径>] [a:<动作>] [b:<方块>] [e:<排除>]
/co undo
/co purge    t:<时间>
/co online   [玩家] [t:<时间>] [p:<页码>]
/co status | /co reload | /co language [list|<语言代码>]
```

时间格式：`2w5d10h30m15s`（周、天、小时、分钟、秒）。
动作筛选支持 `block`、`+block`、`-block`、`#container`、`#kill`、`#chat`、`#command`、`#session`、`#item`，以及 `#fire` `#water` `#lava` `#tnt` `#creeper` `#piston` `#enderman` `#decay` 等自然原因。

### 存储（v1.9.0 起为 schema v3）

数据库改为字典化存储，重复文本不再出现在每一行：

- `user`、`world`、`action` 分别存入 `co_name` / `co_world` / `co_action`，方块状态存入 `co_state`，日志表只保留整数 id
- 每张日志表只有两个索引：`(wid_id, x, y, z)` 与复合 `(time, name_id)`（后者同时覆盖按玩家查询）
- 41.7 万行测试数据实测：**46.38 MB → 24.32 MB（−47.6%），每行 116.6 → 61.2 字节**；`/co purge` 会同时回收字典表中的无用行
- **自动升级**：首次启动会原地迁移旧版 v2 / v1 数据库，并保留 `coreprotect.db.bak-v2` 作为保险。迁移过程大约需要数据库体积 2.2 倍的可用磁盘空间（备份副本 + VACUUM 前的文件）；确认新库无误后可删除 `.bak-v2`
- 崩溃与断电防护：WAL 日志 + 每次提交 `synchronous=full`、崩溃标记与下次启动 `quick_check`、定期 WAL checkpoint，以及定期热备份（`VACUUM INTO`，默认每 6 小时），检测到损坏时自动恢复

### 支持的 Minecraft 版本

| Minecraft | Java | 文件 |
|---|---|---|
| **1.21** | 21 | `coreprotect-fabric-1.21-1.9.1.jar` |
| **1.21.11** | 21 | `coreprotect-fabric-1.21.11-1.9.1.jar` |
| **26.1.2** | 25 | `coreprotect-fabric-26.1.2-1.9.1.jar` |

以上三个版本功能完全一致并保持同步；另外 13 个版本（1.21.1 – 1.21.10、26.1、26.1.1、26.2）为 v1.0.0 核心功能集，可在 [GitHub Releases](https://github.com/CNTianCheng/coreprotect-fabric/releases) 下载。

### 安装

1. 服务端安装 **Fabric Loader ≥ 0.16** 与 **Fabric API**。
2. 选择与服务器 **Minecraft 版本完全一致** 的 jar（1.21.x 需要 Java 21，26.x 需要 Java 25）。
3. 放入服务端 `mods/` 目录后启动即可，属于服务端模组，玩家无需安装。
4. 首次启动会生成配置 `config/coreprotect-fabric.json`，数据库位于服务端目录下的 `coreprotect.db`。

### 已知限制

- 击杀记录不会随回滚撤销（与原版 CoreProtect 一致）
- 大规模回滚在主线程执行，受 `maxBlocks` 上限保护
- 自然事件钩子为尽力而为：若未来游戏更新使某个钩子失效，仅该类记录停止工作
- 容器回滚采用简化的物品恢复策略（优先从容器取回，缺失部分予以补偿）

### 链接

[源代码](https://github.com/CNTianCheng/coreprotect-fabric) · [下载](https://github.com/CNTianCheng/coreprotect-fabric/releases) · [更新日志](https://github.com/CNTianCheng/coreprotect-fabric/blob/main/CHANGELOG.md) · [问题反馈](https://github.com/CNTianCheng/coreprotect-fabric/issues) · [贡献指南](https://github.com/CNTianCheng/coreprotect-fabric/blob/main/CONTRIBUTING.md)

使用 MIT 许可证。内置 [sqlite-jdbc](https://github.com/xerial/sqlite-jdbc)（Apache License 2.0）。
