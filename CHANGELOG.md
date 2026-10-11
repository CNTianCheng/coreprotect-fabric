# Changelog

All notable changes to this project. Release notes with the full bilingual text live on the
[releases page](https://github.com/CNTianCheng/coreprotect-fabric/releases).

中文版本见下方 [中文](#中文)。

## English

### v1.9.3 — security, data-integrity and logging fixes (60 findings)

Minecraft **1.21**, **1.21.11**, **26.1.2**.

A complete self-audit of the mod (code quality, security, functional bugs) produced 60 findings;
this release fixes them. Nothing is removed from the log, and an existing database is upgraded in
place (`co_container` gains an `nbt` column).

**Permissions and information exposure**
* Non-player sources (command blocks in particular) no longer bypass every permission level:
  `/co purge`, `/co rollback`, `/co restore` and `/co undo` now require `adminLevel`, the same
  check applies to the `coreprotect.*` nodes, and an out-of-range `permissions.adminLevel` /
  `lookupLevel` fails closed instead of granting access to everyone
* `permissions.lookupLevel` defaults to **1** (was 0 = everyone): only moderators can read chat,
  command, container and block history unless configured otherwise
* Permission groups are matched by player **UUID**; a name in `permissionGroups` is only honoured
  on an online-mode server, so an offline-mode player can no longer inherit the admin group by
  taking the configured name
* Chat, command and sign text is stripped of `§` colour codes and control characters before it is
  printed - a logged message can no longer forge a `CoreProtect - ...` line or hide content
* `r:` and `p:` are capped at 1000 instead of accepting `2147483647`, which turned a single
  command into a full-table scan

**Rollback / restore correctness**
* A rollback no longer creates items: what it takes out of a container is removed, and `/co undo`
  cannot duplicate it
* Restoring an item restores its **data components** - an enchanted, renamed or damaged stack is
  no longer restored as a plain one, and a swap between two different stacks of the same item is
  logged instead of silently ignored (`co_container.nbt`, `util/ItemStackCodec.java`)
* Block and sign chains are replayed per position (newest to oldest for a rollback, oldest to
  newest for a restore) so the final state is the oldest recorded one - container rows are
  replayed in the same order, so a stack that was stored and taken out again ends up where the
  log says it was; other dimensions are skipped and counted instead of being written into the
  wrong world
* Block and item limits apply to every log type, a rollback that throws still writes its undo
  record, the undo record survives a restart (`coreprotect-fabric-undo.json`, flushed every 5 s
  and on shutdown, written by a background thread instead of the server thread), and a second
  `/co rollback`, `/co restore` or `/co undo` from the same player is refused while one of their
  operations is still running - console, RCON and command blocks each count as one operator
* An emptied container no longer loses its contents: the stacks a rollback ejects are dropped
  next to the container **and** written into the undo record with their components, so a
  console-issued rollback can be undone instead of deleting the items
* A container a rollback changed is written back through the inventory API, so the change is
  marked dirty: it survives a save/reload and reaches the players who have the container open.
  Editing only the stack object could make the rollback look like it did nothing once the
  container screen was reopened
* Unknown block states are skipped instead of treated as air, a container is emptied before its
  block is replaced, and dropped item entities are the only item entities restored

**Logging completeness**
* A pick-up that takes the whole stack is logged (a `taken == 0` result was swallowed)
* Fire is attributed again (`#fire` set-state cause): burning, fire spread and extinguished
  blocks are recorded, while fire's own block changes are filtered
* The same block is no longer logged twice when `removeBlock` delegates to `setBlockState`
  (endermen, pistons), and right-click interactions are decided by comparing the block before and
  after, so buckets, flint and steel, bone meal, hoes, axes, shovels and shears are recorded and
  the fake `+stone` rows are gone
* Double chests are tracked as one 54-slot container, a container broken while its screen is open
  still logs its contents, hoppers/dispensers that would log a transfer twice are excluded, and
  the snapshot map drops its oldest entries instead of everything when it overflows
* Command and sign hooks target the validated execution path and are rate limited to 60 per
  player per minute; a failing hook is caught so it cannot break the world change it observes
* Player commands sent with chat signing enabled are recorded again: the hook covered only the
  unsigned path (`CommandExecutionC2SPacket`), while a client with signing enabled sends
  `ChatCommandSignedC2SPacket` and never reaches `executeCommand`, so on an online-mode server
  player commands were missing from `/co lookup` altogether
* On the **26.1.2** build both injections still used the yarn method names and therefore never
  applied - command and sign logging had been dead on that build. With `"defaultRequire"` now
  at `1` a stale target stops the server at startup instead of being ignored silently

**Concurrency and database**
* A failed write batch is re-queued instead of lost, dropped rows are warned about, and
  `/co status` reports committed batches, queued rows and dropped rows
* Nested read-pool queries no longer deadlock, a failed count shows as `n/a` instead of `0` (the
  eight counts share one 20 s budget, so `/co status` cannot stall the command for minutes), and
  `/co purge` reports an error instead of a successful "0 rows" when the query fails
* Console/RCON commands wait at most 10 s for a query and the result is written to the server log
  if it is still running, instead of the command silently printing nothing (an interrupted query
  now says so in the log instead of returning without a word)
* Shutting down drains the queued rows with a 30 s deadline and then reports - and drops - what
  could not be written, instead of hanging the server thread for five minutes
* A container whose contents cannot be read (unloaded chunk, block entity gone) is no longer
  reported as fully emptied by the player who had its screen open; only a container that really
  disappeared is logged, and then as an anonymous `#container` change
* The executor pools are recreated when the database is reopened, `/co reload` publishes its
  changes safely and warns that `database.file` needs a restart, and `maxLines`, `inspectLines`
  and `defaultTimeSeconds` are bounded
* `VACUUM`/checkpoint no longer collide with the cached statements; `a:sign` and entity filters no
  longer fail with `ambiguous column name: id` / `no such column: e.action`, and a radius lookup
  stays inside the dimension it was issued in

**Build, packaging and release tooling**
* `publish.ps1` checks SHA-256, size and the version inside `fabric.mod.json` before uploading,
  re-checks a failed upload, cross-checks `versions.json` and refuses stale jars
* Release scripts upload the new asset before deleting the old one, refuse to run in a dirty
  working tree, and need an explicit flag to re-run a historical release
* GitHub Actions are pinned to fixed versions and fail when no artifact is produced,
  `check-ascii.ps1` exits non-zero on failure, credentials come from the git credential store
  instead of a file on disk, the SQLite driver moves to 3.50.2.0 and the `fabric.mod.json`
  loader/API minimums now match each build

### v1.9.2 — far less disk load (batched writes, non-blocking lookups)

Minecraft **1.21**, **1.21.11**, **26.1.2**.

* Log rows are no longer written one statement at a time: they are collected for up to
  `database.batchIntervalMs` (250 ms) and committed as a single transaction, so a burst of
  chat or block activity costs one disk sync instead of one per row. Measured with the same
  SQLite build and the real `co_chat` schema: **578–969 µs → 8.3 µs per row**, and at 60 rows/s
  **60 fsyncs per second → 4**
* `/co lookup`, `/co inspect`, `/co status`, `/co online` and `/co purge` run their queries on
  the read pool and send the result from the server thread — a large lookup used to stall the
  tick for as long as the query took (up to 30 s per query)
* `/co rollback` and `/co restore` are applied in 4 ms slices spread over several ticks instead
  of all at once, so a 5000-block rollback no longer freezes the server
* Item pickups no longer copy an item stack on every collision tick — only when something is
  actually picked up
* Fixed `/co lookup a:sign` (`ambiguous column name: id`) and `a:#kill` (`no such column: e.action`)
* Nothing was removed from the log: every block, container, item, entity, command, session and
  chat record is still stored, only the way it reaches the disk changed
* New config keys: `database.batchIntervalMs` (0–5000, default 250) and
  `database.batchMaxRows` (1–100000, default 1000). `database.syncMode` still defaults to
  `full`, so a crash can lose at most the last batch

### v1.9.1 — Russian translation

Minecraft **1.21**, **1.21.11**, **26.1.2**.

* Added a complete Russian (`ru_ru`) translation — 114 keys, the same key set as `en_us`, with every `{0}` placeholder preserved
* Switch with `/co language ru_ru`, or let it follow a Russian client automatically

### v1.9.0 — smaller database (schema v3)

Minecraft **1.21**, **1.21.11**, **26.1.2**.

* Player names, world ids and action/cause strings are dictionary-encoded
  (`co_name`, `co_world`, `co_action`); log rows store an integer id instead of repeating the
  text on every row
* Indexes are narrower: `(wid_id, x, y, z)` for position lookups and one composite
  `(time, name_id)` index per log table, which also answers user+time lookups
* Measured on a 417,000-row survival-style database: **46.4 MB → 24.3 MB (48 % smaller)**;
  an existing v2 database is converted automatically on first start
  (**48.6 MB → 25.2 MB** in the migration test) and the old file is kept as
  `coreprotect.db.bak-v2` until you delete it
* Purging data also drops dictionary entries that nothing references any more
* No command, permission or config changes — the migration is transparent

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

### v1.9.3 —— 安全、数据完整性与记录完整性修复（60 条）

Minecraft **1.21**、**1.21.11**、**26.1.2**。

对模组做了一次完整的自查（代码质量 / 安全 / 功能性 bug），共 60 条，本次全部修复。
记录范围没有减少，旧数据库原地升级（`co_container` 增加 `nbt` 列）。

**权限与信息暴露**
* 非玩家来源（尤其是命令方块）不再绕过所有权限等级：`/co purge`、`/co rollback`、
  `/co restore`、`/co undo` 现在同样要求 `adminLevel`，`coreprotect.*` 权限节点走同一判定；
  `permissions.adminLevel` / `lookupLevel` 配成越界值现在是**拒绝**而不是对所有人放行
* `permissions.lookupLevel` 默认改为 **1**（原为 0 = 所有人）：默认只有管理员能查看聊天、
  命令、容器与方块历史
* 权限组按玩家 **UUID** 匹配；`permissionGroups` 里的名字只在正版（online-mode）服务器上生效，
  离线模式服务器上冒用配置名不再能继承管理组
* 聊天、命令、告示牌文本在输出前剥掉 `§` 颜色代码与控制字符，伪造的
  `CoreProtect - ...` 行与隐藏内容不再可能出现
* `r:` 与 `p:` 上限改为 1000（原来可填 `2147483647`，一条命令就会全表扫描）

**回档 / 还原正确性**
* 回档不再凭空造物品：从容器里取出的物品就是取出，`/co undo` 也无法复制
* 还原物品会一并还原**数据组件**：附魔、改名、耐久不同的物品不再被还原成白板，
  同物品不同组件之间的互换也会被记录（`co_container.nbt`、`util/ItemStackCodec.java`）
* 方块与告示牌按坐标成链回放（回档从最新到最旧、还原从最旧到最新），最终落到最旧一条记录的状态；
  容器行也按同样顺序回放，同一堆物品存取多次后落在记录中的位置；
  维度不符的位置计为 skipped，不再写进错误的世界
* 方块上限与物品上限对所有记录类型生效，回档中途异常也会写入 undo 记录，
  undo 记录重启后仍在（`coreprotect-fabric-undo.json`，每 5 秒与关服落盘，且改由后台线程写盘，不占用服务器线程），
  同一玩家在自己有任务运行时不能再开第二个 `/co rollback`、`/co restore` 或 `/co undo`
  （控制台、RCON、命令方块各算一个操作者）
* 被倒空的容器不再丢内容：回档倒出的每一栈都会掉在容器旁**并**带组件写进 undo 记录，
  控制台发起的回档也能被 undo 找回来，而不是把物品删掉
* 回档对容器内容的改动会通过容器 API 写回并标脏：存档/重载后仍然生效，也会同步给正开着
  该容器的玩家（此前只改物品对象，玩家重开界面后可能“变回原样”）
* 无法解析的方块状态一律跳过（不再当作空气），替换容器方块前先倒空内容，
  掉落物之外的物品实体不再被还原（避免复制）

**记录完整性**
* 整栈被捡走现在会记录（`taken == 0` 的结果原来被吞掉）
* 火焰重新可归因（`#fire` 纳入 set 原因）：燃烧、蔓延、熄灭都会记录，
  而火焰自身方块变化不会刷屏
* `removeBlock` 委派给 `setBlockState` 时不再记两遍（末影人、活塞）；
  右键交互改为比较交互前后方块，水桶/岩浆桶、打火石、骨粉、锄斧锹剪刀都能记录，
  虚假的 `+stone` 行消失
* 双箱按 54 格合并容器记录；GUI 打开期间容器被破坏仍记录内容；
  漏斗/发射器这类会重复记录的容器被排除；快照表超限时淘汰最旧条目而不是整表清空
* 命令与告示牌钩子挂在已校验的执行路径上，并限流 60 条/分钟/人；
  钩子内部异常被捕获，不再拖垮它正在观察的原版方块变化
* 开启聊天签名的客户端发出的命令重新被记录：原钩子只覆盖未签名路径
  （`CommandExecutionC2SPacket`），而签名客户端发的是 `ChatCommandSignedC2SPacket`，
  根本不经过 `executeCommand`——在线模式服务器上玩家的命令此前完全查不到
* **26.1.2** 版里两处注入仍在用 yarn 方法名，因此从未生效，该版本上的命令与告示牌
  记录一直是死的；`"defaultRequire"` 现在为 `1`，目标写错会在开服时直接失败而不是被静默忽略

**并发与数据库**
* 批量写入失败的行重新入队而不是丢弃，丢行会告警，`/co status` 显示已提交批次数、
  排队行数与丢弃行数
* 读池嵌套查询不再自锁，查询失败计数显示 `n/a` 而不是 `0`
  （八个计数共用 20 秒预算，`/co status` 不会再卡住命令几分钟），
  `/co purge` 查询失败时报错而不是报“成功删除 0 行”
* 控制台/RCON 命令最多等待 10 秒，仍未完成则把结果写进服务器日志，不再静默无输出
  （查询被中断时会在日志里说明，而不是一声不响地返回）
* 关服时以 30 秒为限清空待写队列，写不进去的行告警后丢弃，
  不再让服务器线程卡满五分钟
* 读不到内容的容器（区块未加载、方块实体消失）不再被记成“被开界面的玩家搬空”；
  只有真正消失的容器才记录，且署名为匿名的 `#container`
* 数据库重开后执行器池会重建，`/co reload` 安全发布配置并提示 `database.file` 需重启生效，
  `maxLines`、`inspectLines`、`defaultTimeSeconds` 都有上限
* `VACUUM`/checkpoint 不再与语句缓存冲突；`a:sign` 与实体过滤不再报
  `ambiguous column name: id` / `no such column: e.action`；
  半径查询限定在发起命令的维度内

**构建、打包与发布工具**
* `publish.ps1` 上传前校验 SHA-256、大小与 `fabric.mod.json` 内的版本，失败后复核，
  交叉校验 `versions.json`，并拒绝过期 jar
* 发布脚本先上传新附件再删除旧附件，工作区不干净时拒绝运行，
  历史版本重跑需要显式开关
* GitHub Actions 固定到具体版本、没有产物即失败，`check-ascii.ps1` 失败返回非零，
  凭据改从 git 凭据存储读取而不再落盘，SQLite 驱动升级到 3.50.2.0，
  `fabric.mod.json` 的 loader/API 下限与各版本对应

### v1.9.2 —— 大幅降低磁盘压力（批量写入、查询不再阻塞主线程）

Minecraft **1.21**、**1.21.11**、**26.1.2**。

* 记录不再逐条写库：最多等待 `database.batchIntervalMs`（默认 250 毫秒）后整批作为一个事务提交，
  聊天/方块刷屏时由“每行一次磁盘同步”变成“每批一次”。同一 SQLite 构建、真实 `co_chat` 表结构实测：
  **每行 578–969 微秒 → 8.3 微秒**；每秒 60 行时 **fsync 从 60 次/秒降到 4 次/秒**
* `/co lookup`、`/co inspect`、`/co status`、`/co online`、`/co purge` 的查询移到读线程池执行，
  结果回到服务器线程输出——一次大范围查询过去最长可卡住服务器 30 秒
* `/co rollback`、`/co restore` 改为 **4 毫秒时间切片**、分摊到多个 tick 执行，
  5000 方块的区域回档不再一次性冻结服务器
* 物品拾取不再每次碰撞都复制一份物品栈，只在真的拾取时记录
* 修复 `/co lookup a:sign`（`ambiguous column name: id`）与 `a:#kill`（`no such column: e.action`）
* 记录范围完全不变：方块、容器、物品、实体、命令、会话、聊天全部照旧记录，只是落盘方式变了
* 新增配置项：`database.batchIntervalMs`（0–5000，默认 250）、
  `database.batchMaxRows`（1–100000，默认 1000）；`database.syncMode` 默认仍为 `full`，
  崩溃最多丢最后一批

### v1.9.1 —— 新增俄语翻译

Minecraft **1.21**、**1.21.11**、**26.1.2**。

* 新增完整的俄语（`ru_ru`）翻译：114 个键与 `en_us` 完全一致，`{0}` 占位符全部保留
* 可用 `/co language ru_ru` 手动切换，客户端为俄语时自动生效

### v1.9.0 —— 更小的数据库（schema v3）

Minecraft **1.21**、**1.21.11**、**26.1.2**。

* 玩家名、世界 id、动作/来源字符串改为字典表存储（`co_name`、`co_world`、`co_action`），
  日志行只保存整数 id，不再逐行重复文本
* 索引更窄：位置查询用 `(wid_id, x, y, z)`，每张日志表只保留一个复合索引 `(time, name_id)`，
  同时服务于「按时间」和「按玩家 + 时间」的查询
* 以 41.7 万行的生存服式数据库实测：**46.4 MB → 24.3 MB（缩小 48%）**；
  已有 v2 数据库在首次启动时自动转换（迁移测试：**48.6 MB → 25.2 MB**），
  旧文件保留为 `coreprotect.db.bak-v2`，确认无误后可自行删除
* 清理旧数据（purge）时一并回收不再被引用的字典项
* 命令、权限、配置均无变化，迁移对使用者透明

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
