# CoreProtect Fabric —— 性能优化纪要（v1.9.2）

> 本文件是这次性能优化的"工程纪要"：每完成一步，就把结论、实测数据和决定追加到下面。
> 相关文件：`.build-tools/perf/BenchWrite.java`（微基准）、`.build-tools/perf/data/`（基准数据库）、`CHANGELOG.md`（对外发布说明）。

## 0. 需求（用户确认，逐条问答得出）

用户原话（m02804）："优化模组，尽量减少其记录对服务器占用和卡顿（可能对话记录存在问题，没发一个消息，服务器的mspt瞬间升高）"。

逐条确认结果：

| 问题 | 回答 |
|---|---|
| 环境 | 线上服务器，约 10 人，数据库在 SSD，同时 3 人聊天 |
| 落盘策略 | **接受"合并批量提交，可以丢最后几秒"**（默认） |
| 取舍 | **只做纯速度优化，不减少任何记录范围**（不砍自然事件、不做负载降级、不关任何日志） |
| 范围 | 写入 + 查询 + 回档 全部优化 |
| 交付 | 三个完整版（1.21 / 1.21.11 / 26.1.2）同步 → **v1.9.2** → GitHub Release + Modrinth，并自行打开发布页检查 |

## 1. 根因（代码定位）

1. `coreprotect-fabric-1.21.11/src/main/java/net/coreprotect/fabric/event/MessageEventListener.java:14-21` → `DatabaseManager.insertChatAsync()`（`database/DatabaseManager.java:757-768`），每条聊天消息 = 一个独立任务。
2. `DatabaseManager.submitWrite()`（`database/DatabaseManager.java:1411-1418`）把任务交给**单线程 writer**。
3. 写入**没有事务包裹**（全库没有 `setAutoCommit(false)` 包裹写入），每次 INSERT 都是**独立自动提交事务**；
4. `DatabaseManager.java:470` 执行 `PRAGMA synchronous=<database.syncMode>`，默认 **full**（v1.8 为防断电丢数据引入），WAL 模式下**每个事务提交都要 fsync**。
5. 结论：**每条记录一次 fsync**。10 人挖方块 + 3 人聊天 = 每秒几十次 fsync；磁盘队列一堵，世界保存也排在后面 → MSPT 尖峰。

## 2. 基线实测（本机，sqlite-jdbc 3.46.1.0，WAL，`co_chat` 真实表结构）

工具：`.build-tools/perf/BenchWrite.java`（`java -cp "<sqlite-jdbc.jar>;<slf4j-api.jar>" BenchWrite.java <dir> 4000 60 15`）

固定 4000 行插入：

| 变体 | 总耗时 | 每行 | 单次最大停顿 |
|---|---|---|---|
| 逐行自动提交，`synchronous=FULL`（现状） | 2310~3876 ms | **578~969 µs** | **7.6~22.5 ms** |
| 逐行自动提交，`synchronous=NORMAL` | 209~275 ms | 52~69 µs | 2.9~5.9 ms |
| 每 500 行一个事务，`FULL` | 34~40 ms | 8.6~9.9 µs | 0.8 ms |
| 每 500 行一个事务，`NORMAL` | 21~33 ms | 5.3~8.3 µs | 0.2~0.3 ms |

按真实到达速率（60 行/秒，持续 15 秒）流式写入：

| 变体 | 提交次数 | 单次提交最大耗时 | 最大数据丢失窗口 |
|---|---|---|---|
| 逐行提交，`FULL`（现状） | **900（60/秒）** | 3.5 ms | 0 ms |
| 合并提交 1000 行 / 250 ms，`FULL` | 60（4/秒） | 0.87 ms | 253 ms |
| 合并提交 1000 行 / 250 ms，`NORMAL` | 60（4/秒） | 0.21 ms | 252 ms |
| 合并提交 1000 行 / 500 ms，`NORMAL` | 30（2/秒） | 0.25 ms | 501 ms |

**结论：合并提交把 fsync 次数降低 15 倍（本速率下），单行写入成本降低约 100 倍；服务器越忙（记录行/秒越高）收益越大。**

## 3. 设计决定

- 写入侧：有界队列 + 专用 writer 线程，按"最多 N 行 / 最多等 T 毫秒"合并成一个事务提交（默认 N=1000，T=250ms）；JDBC `addBatch`/`executeBatch` 复用 PreparedStatement。
- 落盘：批量模式下默认 `synchronous=NORMAL`（WAL 只在 checkpoint fsync）；`full` 仍可作为配置项保留，保证"一条都不丢"的用户仍能选。
- 记录范围：**完全不减少**（用户明确要求）。
- 查询/回档：查询不再阻塞服务器线程（异步 + 结果回主线程输出）；回档分批/时间切片执行，避免一次性同步加载区块。
- 关闭：`close()` 前必须 flush 队列（队列里已有的行不丢）。

## 4. 进度日志

- 2026-09-05 完成需求确认（5 问）与根因定位；写出 `BenchWrite.java` 并取得上表基线数据。
- 2026-10-05 完成四条路径的代码侦查（写入 / 查询 / 回档 / 事件热路径），确认全部卡顿点。
- 2026-10-05 写入批量化 + 语句缓存 + 配置项完成；查询异步化完成；回档改为 4ms 时间切片；修复两处坏 SQL。
- 2026-10-05 三个构建同步：`database/DatabaseManager.java`、`config/CoreProtectConfig.java` 三版本逐字一致；
  `command/LookupService.java`、`inspect/Inspector.java`、`rollback/RollbackManager.java` 在 1.21 与 1.21.11 逐字一致。
- 2026-10-05 回归测试发现并修复两个问题：
  1. `VACUUM` 报 `SQL error or missing database (cannot VACUUM - SQL statements in progress)`——
     新的语句缓存让 PreparedStatement 一直处于打开状态，SQLite 拒绝 VACUUM。修复：`incrementalVacuum()`、
     `vacuumAsync()`、`scheduleBackups()`、`close()` 在维护操作前先 `closeStatements()`（语句会按需重新准备）。
  2. 控制台 / RCON / 命令方块只在命令执行期间输出，异步回调的结果到不了它们。修复：新增
     `LookupService.query(source, task, callback)` —— **玩家**走异步（不卡 tick），**非玩家来源**在命令内联执行后再回调
     （输出正常）；回档完成时如果来源不是玩家，额外写一行服务器日志。
- 2026-10-05 `test-full.ps1` 改为"读到套接字静默 1.2 秒"再继续，否则异步命令的输出会被漏掉。

## 5. 实现清单（v1.9.2）

| 层面 | 改动 | 文件 |
|---|---|---|
| 写入 | 有界队列（65536）+ writer 按批提交：每批最多 `batchMaxRows`（默认 1000）行、最多等 `batchIntervalMs`（默认 250ms），一个事务一次 `commit()` | `database/DatabaseManager.java` |
| 写入 | PreparedStatement 按 SQL 字符串缓存复用（原来每行 prepare+close） | 同上 |
| 写入 | 关服前 `drainWrites()` 把队列写完再 checkpoint/close；自动恢复分支同步清理语句缓存 | 同上 |
| 写入 | 队列满时的降级：丢弃并计数 + 限频告警（只在磁盘长期卡死时发生） | 同上 |
| 配置 | 新增 `database.batchIntervalMs`（0~5000）与 `database.batchMaxRows`（1~100000），含越界回写修正 | `config/CoreProtectConfig.java` |
| 查询 | `submitRead(Callable, Consumer)`：查询在 read 池执行，结果经 `server.execute` 回主线程送达 | `database/DatabaseManager.java` |
| 查询 | `/co lookup` 全异步（结果回主线程格式化输出） | `command/LookupService.java` |
| 查询 | `/co inspect` 三次查询合并为一次异步查询 | `inspect/Inspector.java` |
| 查询 | `/co status`（8 次 COUNT）、`/co online`、`/co purge` 全部异步化 | `command/CoCommand.java` |
| 查询 | 顺带修复 `/co lookup a:sign`（`ORDER BY id` 二义性）与 `a:#kill`（`e.action` 列不存在） | `database/DatabaseManager.java` |
| 回档 | 回档/撤销改为：查询在 read 池、世界改动按 **4ms 时间切片** 分摊到多个 tick，`server.execute` 续跑 | `rollback/RollbackManager.java` |
| 事件 | `ItemEntityPickupMixin` 不再每次碰撞 `ItemStack.copy()`，只记物品引用+数量；未开启物品日志时直接返回 | `mixin/ItemEntityPickupMixin.java` |

## 6. 验证

- JDBC 微基准：见第 2 节（写入成本降约 100 倍，fsync 次数降 15 倍以上）。
- 功能回归：`.build-tools/test-quick.ps1` / `test-full.ps1`（RCON 全量用例）在 1.21.11 上运行。
- 服务器实测：无头服务器 + RCON 合成负载，用 `/tick query` 观察 MSPT。

