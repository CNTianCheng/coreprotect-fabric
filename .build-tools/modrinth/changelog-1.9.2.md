# v1.9.2 — far less disk load (batched writes, non-blocking lookups)

Minecraft **1.21**, **1.21.11**, **26.1.2** — all three maintained builds.

## English

* **Log rows are written in batches.** They are collected for at most `database.batchIntervalMs`
  (250 ms by default) and committed as one transaction, so a burst of chat or block activity
  costs a single disk sync instead of one per row. Same SQLite build, real `co_chat` schema,
  measured with `.build-tools/perf/BenchWrite.java`: **578–969 µs → 8.3 µs per row**, and at
  60 rows/s **60 fsyncs per second → 4**
* **Lookups no longer block the server thread.** `/co lookup`, `/co inspect`, `/co status`,
  `/co online` and `/co purge` run their queries on the read pool and send the result from the
  server thread; a large lookup used to stall the tick for as long as the query took (up to
  30 s per query, and `/co status` issued eight of them)
* **Rollback and restore are spread over ticks.** The world is edited in 4 ms slices, so a
  5000-block rollback no longer freezes the server in one tick
* **Item pickups no longer copy a stack on every collision tick** — only when something is
  actually picked up
* Fixed `/co lookup a:sign` (`ambiguous column name: id`) and `/co lookup a:#kill`
  (`no such column: e.action`)
* **Nothing was removed from the log.** Every block, container, item, entity, command, session
  and chat record is still stored; only the way it reaches the disk changed
* New config keys: `database.batchIntervalMs` (0–5000, default 250) and
  `database.batchMaxRows` (1–100000, default 1000). `database.syncMode` still defaults to
  `full`, so a crash can lose at most the last batch

**Also included**: everything from v1.9.1 (Russian translation) and v1.9.0 (database schema v3 —
roughly half the file size, automatic migration on first start with a `coreprotect.db.bak-v2`
safety copy) and every v1.8.2 fix.

## 中文

* **记录改为批量写库。** 最多收集 `database.batchIntervalMs`（默认 250 毫秒）后作为一个事务提交，
  聊天或方块刷屏时由“每行一次磁盘同步”变成“每批一次”。同一 SQLite 构建、真实 `co_chat` 表结构，
  用 `.build-tools/perf/BenchWrite.java` 实测：**每行 578–969 微秒 → 8.3 微秒**；每秒 60 行时
  **fsync 从 60 次/秒降到 4 次/秒**
* **查询不再阻塞服务器线程。** `/co lookup`、`/co inspect`、`/co status`、`/co online`、`/co purge`
  的查询移到读线程池执行，结果回到服务器线程输出；一次大范围查询过去最长可卡 30 秒
  （`/co status` 更是连续发 8 次查询）
* **回档分摊到多个 tick。** 世界改动按 4 毫秒切片执行，5000 方块的区域回档不再一次性冻结服务器
* **物品拾取不再每次碰撞都复制物品栈**，只在真的拾取时记录
* 修复 `/co lookup a:sign`（`ambiguous column name: id`）与 `/co lookup a:#kill`
  （`no such column: e.action`）
* **记录范围完全没有减少。** 方块、容器、物品、实体、命令、会话、聊天全部照旧记录，
  只是落盘方式变了
* 新增配置项：`database.batchIntervalMs`（0–5000，默认 250）、`database.batchMaxRows`
  （1–100000，默认 1000）；`database.syncMode` 默认仍为 `full`，崩溃最多丢最后一批

**同时包含 v1.9.1 与 v1.9.0 的全部内容**：俄语翻译、数据库 schema v3（体积约减半，旧库首次启动自动迁移并保留
`coreprotect.db.bak-v2` 备份），以及 v1.8.2 的全部修复。
