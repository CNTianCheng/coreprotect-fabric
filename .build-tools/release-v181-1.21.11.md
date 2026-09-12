# CoreProtect Fabric v1.8.1 (Minecraft 1.21.11)

A block-level logging and rollback mod for **Fabric servers**, independently implemented with the renowned [CoreProtect](https://github.com/PlayPro/CoreProtect) plugin as its blueprint. This release is the **Minecraft 1.21.11 build**. Other Minecraft versions (1.21.1 / 1.21.2~1.21.11 / 26.1~26.2) are available in their own releases.

---

## English

> **This build brings Minecraft 1.21.11 to full feature parity with the 1.21 flagship build (v1.8.1)**: identical commands, logging coverage, colors, database schema v2 with crash/power-loss protection, bStats metrics and update checks.

### What's new in v1.8.0 / v1.8.1 — Crash & power-loss protection

- **Per-commit WAL fsync** (`database.syncMode: full`, default): committed data survives even a sudden power cut
- **Unclean shutdown detection**: a crash marker is detected on the next start and logged
- **Startup integrity check**: after an unclean shutdown the database is verified with `quick_check`
- **Periodic WAL checkpoints** (`database.checkpointMinutes`, default 10): smaller WAL = faster crash recovery
- **Periodic hot backups** (`database.backupMinutes`, default 360): a consistent snapshot is written to `coreprotect.db.backup` via `VACUUM INTO`
- **Automatic restore** (`database.autoRestoreBackup`, default true): if corruption is detected, the broken files are kept aside in `<db>.corrupt-<timestamp>/` and the database is restored from the latest backup automatically; reader connections reopen on their own

### Full changelog

| Version | Highlights |
|---|---|
| **v1.0.0** | Core feature set: block/container/entity/chat/command/session logging, natural-event attribution (`#fire` `#water` `#lava` `#tnt` `#creeper` `#wither` `#end_crystal` `#piston` `#enderman` `#decay`), rollback/restore/undo, combined lookups, inspection mode, four languages, async SQLite (WAL) |
| **v1.1.0** | Hopper transactions (`#hopper`), item drop/pickup logging (`a:item`), sign-edit logging (`a:#sign`), CoreProtect v22+ output colors |
| **v1.2.0** | `/co i` inspect shorthand (with `on`/`off`); permission groups (12 nodes, OP-level fallback) |
| **v1.3.0** | `/co online` online records; `databaseFile` custom location; `dataRetention` retention limit; fixed session queries |
| **v1.3.1** | Fixed inspect-mode placement bug; colors fully aligned with the plugin's v22+ style |
| **v1.4.0** | bStats usage metrics (official v2 protocol); automatic update checks with console + admin join notifications |
| **v1.4.1** | bStats service id built in (33739) |
| **v1.5.0** | Dropper (`#dropper`) / dispenser (`#dispenser`) item transaction logging |
| **v1.5.1** | Reworked piston records (no more "changed/destroyed air" noise); no lookup on air clicks in inspect mode; clickable next-page hints |
| **v1.6.0** | Command parameter suggestions (with player-name completion); faster lookups: parallel read pool, more indexes, index-backed radius queries, parallel status counts |
| **v1.7.0** | Database compression: dictionary-encoded states (schema v2, auto-migration with backup, measured 12.55MB -> 8.3MB); auto-compact after `/co purge`; mmap, configurable cache, 8-thread read pool |
| **v1.7.1** | Fixed bStats registration (server-implementation platform), configs migrate automatically |
| **v1.7.2** | bStats player-count reporting (`players` single-line chart) |
| **v1.8.0** | Crash protection: per-commit WAL fsync, unclean-shutdown detection + startup quick_check, periodic WAL checkpoints |
| **v1.8.1** | Power-loss protection: periodic hot backups, automatic restore from backup on detected corruption, reader connections reopen after restore |

### Install

- Requirements: Minecraft **1.21.11**, Java **21**, Fabric Loader >= 0.16 + Fabric API
- Drop `coreprotect-fabric-1.21.11-1.8.1.jar` into the server's `mods/` folder; config and database are created on first launch

### Quick start

```
/co help
/co i                        # inspect: left-click block history, right-click container transactions
/co lookup u:Steve t:1h      # lookup
/co rollback u:Steve t:1h    # rollback
/co online Steve             # online records
/co status                   # status
```

### Links

- Source & docs: https://github.com/CNTianCheng/coreprotect-fabric
- Bug reports: https://github.com/CNTianCheng/coreprotect-fabric/issues/new/choose
- License: MIT; bundles sqlite-jdbc (Apache License 2.0)
- Independent implementation inspired by CoreProtect; not affiliated with the CoreProtect team

---

## 中文

> **本构建使 Minecraft 1.21.11 与 1.21 旗舰版 v1.8.1 功能完全一致**：命令、记录范围、输出配色、v2 数据库结构与崩溃/断电防护、bStats 统计和更新检查全部相同。

### v1.8.0 / v1.8.1 更新内容 —— 崩溃与断电防护

- **每次提交刷盘**（`database.syncMode: full`，默认）：突然断电也不丢失已提交的数据
- **异常关闭检测**：崩溃标记残留时下次启动自动告警
- **启动完整性校验**：异常关闭后自动执行 `quick_check` 校验数据库
- **周期性 WAL 检查点**（`database.checkpointMinutes`，默认 10 分钟）：WAL 越小崩溃恢复越快
- **定期在线热备份**（`database.backupMinutes`，默认 360 分钟）：用 `VACUUM INTO` 生成一致性快照 `coreprotect.db.backup`，不影响在线运行
- **自动恢复**（`database.autoRestoreBackup`，默认开）：检测到损坏时，损坏原件移存 `<db>.corrupt-<时间>/` 保全，自动从最新备份恢复，读连接自动重开

### 完整更新历史

| 版本 | 更新内容 |
|---|---|
| **v1.0.0** | 核心功能：方块/容器/实体/聊天/命令/会话记录，自然事件归因，回滚/恢复/撤销，组合查询，检查模式，四种语言，SQLite（WAL）异步写入 |
| **v1.1.0** | 漏斗交易记录（`#hopper`）、掉落物记录（`a:item`）、告示牌编辑记录（`a:#sign`）、CoreProtect v22+ 同款输出配色 |
| **v1.2.0** | `/co i` 检查模式简写；权限组（12 个节点，OP 等级回退） |
| **v1.3.0** | `/co online` 在线记录；`databaseFile` 自定义数据库位置；`dataRetention` 保存时间限制；修复会话查询 |
| **v1.3.1** | 修复检查模式放置方块问题；配色完全对齐插件 v22+ |
| **v1.4.0** | bStats 使用人数统计；新版本自动检查与通知 |
| **v1.4.1** | bStats 服务编号内置（33739） |
| **v1.5.0** | 投掷器（`#dropper`）/发射器（`#dispenser`）物品交易记录 |
| **v1.5.1** | 重做活塞记录（消除"改变/摧毁空气"噪音）；检查模式点空气不查询；翻页可点击 |
| **v1.6.0** | 命令参数自动提示（含玩家名补全）；查询提速：并行读、补索引、半径包围盒、并行统计 |
| **v1.7.0** | 数据库压缩：字典化存储（v2 schema 自动迁移，实测 12.55MB→8.3MB）；purge 后自动压缩；mmap、可配缓存、8 线程读池 |
| **v1.7.1** | 修复 bStats 注册（server-implementation 平台），旧配置自动迁移 |
| **v1.7.2** | bStats 玩家数上报（`players` 单线图） |
| **v1.8.0** | 崩溃防护：每次提交刷盘、异常关闭检测 + 启动 quick_check、周期性 WAL 检查点 |
| **v1.8.1** | 断电级防护：定期热备份、检测到损坏自动从备份恢复、读连接自动重开 |

### 安装

- 要求：Minecraft **1.21.11**、Java **21**、Fabric Loader ≥ 0.16 + Fabric API
- 将 `coreprotect-fabric-1.21.11-1.8.1.jar` 放入 `mods/`，首次启动自动生成配置与数据库

### 快速上手

```
/co help
/co i                        # 检查模式：左键方块历史、右键容器交易
/co lookup u:Steve t:1h      # 查询
/co rollback u:Steve t:1h    # 回滚
/co online Steve             # 在线记录
/co status                   # 状态
```

### 相关链接

- 源码与文档：https://github.com/CNTianCheng/coreprotect-fabric
- Bug 反馈：https://github.com/CNTianCheng/coreprotect-fabric/issues/new/choose
- 许可：MIT；内置 sqlite-jdbc（Apache License 2.0）
- 本项目是受 CoreProtect 启发的独立实现，与 CoreProtect 团队无关
