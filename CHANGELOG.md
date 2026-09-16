# Changelog

All notable changes to this project. Release notes with the full bilingual text live on the
[releases page](https://github.com/CNTianCheng/coreprotect-fabric/releases).

中文版本见下方 [中文](#中文)。

## English

### v1.8.2 — bug fixes for all three full-feature builds

Minecraft **1.21**, **1.21.11**, **26.1.2**. See the [v1.8.2 release](https://github.com/CNTianCheng/coreprotect-fabric/releases/tag/v1.8.2) for the complete list.

* Rollback safety limit is applied **before** the world is touched, and the query is capped
  (SQLite treats `LIMIT -1` as unlimited, so a wide rollback could exhaust memory)
* Corrupt-database recovery closes every read connection before moving files, and can no
  longer leave the mod writing into a `null` connection
* Shutdown always checkpoints and closes the database; the crash marker is written at start
  and removed only after a clean stop
* `/co undo` also inverts container item changes; the undo record is always replaced
* `/co rollback` and `/co restore` no longer require `u:` (a time/radius/block filter is enough)
* `#fire` logging fixed (the mixin targeted a method that no longer exists)
* Sign edits are stored and restored **per face** (front/back)
* Item drops/pickups are only logged when items really moved
* Natural-cause nesting is a stack; change deduplication is state-based; state-only changes
  (water level, piston extension, dropper `triggered`) are no longer logged
* Hopper/dispenser snapshots are keyed per dimension and expire
* CoreProtect-compatible action names (`a:container`, `a:kill`, `a:death`, `a:sign`, `+item`, …)
* `e:` really excludes blocks/actions; `a:#kill` honours `r:`; player names match
  case-insensitively; command completion works again
* Config corrections are persisted; bStats honours the official opt-out file; the update
  check no longer spams warnings when the project is not on Modrinth

### v1.8.1

Minecraft 1.21 / 1.21.11 / 26.1.2.

* Power-loss protection: periodic hot backups (`VACUUM INTO`, default every 6 hours),
  automatic restore when corruption is detected (broken files kept aside), reader
  connections reopen after a restore

### v1.8.0

* Crash protection: per-commit WAL fsync (`synchronous=full`), unclean-shutdown detection
  with a startup `quick_check`, periodic WAL checkpoints

### v1.7.2

* bStats reports the player count (`players` single-line chart)

### v1.7.1

* Fixed bStats registration (platform `server-implementation`); old configs migrate

### v1.7.0

* Database compression: dictionary-encoded block states (schema v2 with automatic
  migration), automatic compaction after `/co purge`, memory-mapped I/O, configurable cache,
  8-thread read pool

### v1.6.0

* Command parameter suggestions with player-name completion; faster lookups (parallel reads,
  more indexes, index-backed radius queries, parallel status counts)

### v1.5.1

* Reworked piston records (no more "changed/destroyed air" noise); no lookup on air clicks;
  clickable next-page hint

### v1.5.0

* Dropper (`#dropper`) and dispenser (`#dispenser`) item transaction logging

### v1.4.1

* bStats service id built in (33739)

### v1.4.0

* bStats usage metrics (official v2 protocol); automatic update checks with console and
  admin-join notifications

### v1.3.1

* Fixed the inspect-mode placement bug; colours aligned with the plugin's v22+ style

### v1.3.0

* `/co online` records; `databaseFile` custom location; `dataRetention` limit; session query fix

### v1.2.0

* `/co i` inspect shorthand (`on`/`off`); permission groups (12 nodes with OP-level fallback)

### v1.1.0

* Hopper transactions (`#hopper`), item drop/pickup logging (`a:item`), sign-edit logging
  (`a:#sign`), CoreProtect v22+ output colours

### v1.0.0

* Initial release: block/container/entity/chat/command/session logging, natural-event
  attribution, rollback/restore/undo, combined lookups, inspection mode, four languages,
  asynchronous SQLite (WAL)

---

## 中文

### v1.8.2 —— 三个完整功能版的缺陷修复

Minecraft **1.21**、**1.21.11**、**26.1.2**。完整列表见 [v1.8.2 Release](https://github.com/CNTianCheng/coreprotect-fabric/releases/tag/v1.8.2)。

* 回滚安全上限**提前到动世界之前**，且查询带上限（SQLite 中 `LIMIT -1` 等于不限量，大范围回滚可能耗尽内存）
* 数据库损坏恢复会先关闭全部读取连接再移动文件，且不会再留下「写入空连接」的状态
* 关服必定执行 WAL 检查点并关闭数据库；崩溃标记改为启动即写、正常停止后才删
* `/co undo` 会一并撤销容器物品变更；撤销记录每次覆盖
* `/co rollback`、`/co restore` 不再强制 `u:`（只给时间/半径/方块条件即可）
* 修复 `#fire` 记录（mixin 指向的方法已不存在）
* 告示牌按**正/反面**记录与还原
* 掉落/拾取只在物品真的移动时记录
* 自然归因改栈式嵌套；去重按状态对；仅状态变化（水位、活塞伸出、发射器 triggered）不再记录
* 漏斗/发射器快照按维度区分并带过期时间
* 兼容 CoreProtect 的写法（`a:container`、`a:kill`、`a:death`、`a:sign`、`+item` 等）
* `e:` 真正排除方块/动作；`a:#kill` 支持 `r:`；玩家名忽略大小写；命令补全恢复
* 配置修正值会写回文件；bStats 支持官方退出开关；项目未上架 Modrinth 时更新检查不再刷警告

### v1.8.1

Minecraft 1.21 / 1.21.11 / 26.1.2。

* 断电级防护：定期在线热备份（`VACUUM INTO`，默认 6 小时）、检测到损坏自动从备份恢复（损坏原件另存）、读连接在恢复后自动重开

### v1.8.0

* 崩溃防护：每次提交刷盘（`synchronous=full`）、异常关闭检测 + 启动 `quick_check`、周期性 WAL 检查点

### v1.7.2

* bStats 上报在线人数（`players` 单线图）

### v1.7.1

* 修复 bStats 注册（平台 `server-implementation`）；旧配置自动迁移

### v1.7.0

* 数据库压缩：方块状态字典化（v2 schema 自动迁移）、`/co purge` 后自动压缩、mmap 内存映射、可配缓存、8 线程读池

### v1.6.0

* 命令参数自动提示（含玩家名补全）；查询提速（并行读、补索引、半径包围盒、并行统计）

### v1.5.1

* 重做活塞记录（消除「改变/摧毁空气」噪音）；检查模式点空气不查询；翻页可点击

### v1.5.0

* 投掷器（`#dropper`）/ 发射器（`#dispenser`）物品交易记录

### v1.4.1

* 内置 bStats 服务编号（33739）

### v1.4.0

* bStats 使用人数统计（官方 v2 协议）；新版本自动检查与控制台/管理员登录提示

### v1.3.1

* 修复检查模式放置方块的问题；配色对齐插件 v22+

### v1.3.0

* `/co online` 在线记录；`databaseFile` 自定义数据库位置；`dataRetention` 保存期限；修复会话查询

### v1.2.0

* `/co i` 检查模式简写（`on`/`off`）；权限组（12 个节点，OP 等级回退）

### v1.1.0

* 漏斗交易（`#hopper`）、掉落物记录（`a:item`）、告示牌编辑记录（`a:#sign`）、CoreProtect v22+ 同款配色

### v1.0.0

* 首个版本：方块/容器/实体/聊天/命令/会话记录、自然事件归因、回滚/恢复/撤销、组合查询、检查模式、四种语言、SQLite（WAL）异步写入
