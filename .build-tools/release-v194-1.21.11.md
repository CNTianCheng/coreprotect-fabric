# CoreProtect Fabric v1.9.4 (Minecraft 1.21.11)

A block-level logging and rollback mod for **Fabric servers**, independently implemented with the renowned [CoreProtect](https://github.com/PlayPro/CoreProtect) plugin as its blueprint. This is the **v1.9.4 second-audit release** for the Minecraft 1.21.11 build; the same fixes ship for the other two maintained builds.

> **Identical feature set across the three maintained builds** (1.21 / 1.21.11 / 26.1.2): same commands, logging coverage, colours, database schema and crash/power-loss protection.

---

## English

### v1.9.4 — second audit round (84 findings)

A second, adversarial audit of the mod - this time checking not only whether an event is logged but whether it is logged *correctly* - produced 84 new findings (2 critical, 16 high, 29 medium, 37 low). This release fixes all of them. An existing database is upgraded in place on the next start: `co_command` and `co_chat` gain `wid_id`, `co_user` gains `uuid` and a new `co_user_uuid(uuid, language)` table keeps the language per account, and `co_entity` gains a position index.

**The two critical defects**

- Piston logging wrote the wrong coordinates: an extension was recorded one block too far (+2) and a retraction one block too close (+1), because the moving block entity's position is its *destination*. A rollback therefore placed blocks at the wrong coordinates and cleared innocent ones
- The `#fire` cause window was closed with `@At("TAIL")`, which never runs when the fire tick returns early - the two normal returns (nothing flammable around, burn-out) left the window open for the rest of the session, so every later block change was attributed to fire and recorded twice

**Rollback / restore correctness**

- Containers are replayed *after* the block phase: a rollback used to empty a container into a block that the same rollback then replaced, so the inventory could never come back
- Double chests are replayed as the merged 54-slot container that was logged, not as the 27 slots of the clicked half; the tracker resolves both halves to one canonical position, so two hoppers no longer log the same transaction twice
- Replacing half of a double chest ejects only that half's contents, never the surviving half's
- Everything a rollback removes from a container is recorded with its full item data and put back by `/co undo`, so rollback + undo is a net zero again (it used to lose the inventory)
- Undo records are written atomically with an atomic move, the in-memory record is kept until the operation really finished, and a second rollback/undo/restore from the same operator is refused while one runs (console, RCON and command blocks each count as their own operator)
- Unloaded chunks are skipped and counted instead of being silently "restored"; transient media (fire, water, lava) no longer block a replay forever; sign chains replay both sides

**Data integrity**

- A row that fails to insert is counted as dropped and shown by `/co status`, instead of only appearing in the log while "dropped" stayed 0
- `/co purge` runs in one transaction, honours `u:`, `a:`, `b:`, `e:` and `r:`, reports how long it took, and no longer reports "0 rows" when the result is unknown
- Backups use `VACUUM INTO` (a consistent snapshot), check free disk space before a backup or a vacuum, and a failed batch is retried with a 1 s -> 30 s back-off before it is dropped
- `close()` stops the write queue first, then the read pool, then checkpoints: the WAL is checkpointed only after the cached statements are gone (that order made the truncating checkpoint fail silently)
- A failed first open retries three times in the background and says clearly that logging is disabled; when the writer connection breaks, the batch is retried on a new connection
- `b:` / `e:` escape `%` and `_`, so `b:stone` no longer matches `b:cobblestone`; `/co status` counts share a 20 second budget instead of blocking the command for minutes

**Logging completeness**

- Signed chat commands are logged again (the vanilla path bypasses the plain command hook), and commands from the console, RCON and command blocks are recorded through the command manager
- Added: random block ticks, falling blocks, farmland trampling, beds and respawn anchors destroyed by explosions, entities killed (no longer only despawned ones), both halves of double blocks, both sides of a sign, and loot picked up with its full item data
- Chat text is sanitised (newlines, control characters, length) and every kind of record has its own per-player rate limit; a dropped record is counted instead of being lost silently
- `logging.commandBlacklist` (default `/login /register /l /reg /changepassword /newpassword`) keeps credentials out of `co_command`
- An unticked piston that cannot move a block, a pushed block and a container removed by an explosion are now recorded, so a rollback restores them instead of leaving holes

**Commands, permissions and configuration**

- `/co purge` accepts the documented filters; `/co lookup` prints the list of valid actions for an invalid one; a query that takes too long says so and delivers its result later
- `coreprotect.` prefixes are accepted in permission nodes, a group can grant `chat`/`command` separately, and `/co help` / `/co language` are public
- Unknown configuration keys are reported after `/co reload`, a reload copies every field, and changing `database.file` warns that a restart is needed
- `database.syncMode` is applied by `/co reload`; radius and page limits are clamped with a notice instead of overflowing the footer

**Main-thread cost**

- The container tracker compares a 64-bit digest first and does one deep comparison per container per tick, so a busy hopper line no longer serialises every stack of every container each tick

**Self-test and display**

- `/co debug natural` keeps its test area force-loaded for sixty seconds (1.21.9+ stops ticking chunks without players, so the fire scenario never burned on an empty server) and covers the piston scenario's chunk, which the old nine-chunk window missed entirely
- Container and item rows no longer print two spaces before the item name in console/RCON output, and the non-player causes (`#server`, `#entity`, `#container`, `#gravity`, `#random_tick`, `#trample`) have display labels instead of a missing-translation warning

**Also included**: everything from v1.9.3 (60-finding audit), v1.9.2 (batched writes - **578-969 µs -> 8.3 µs per row**, fsync 60/s -> 4/s - non-blocking lookups and 4 ms time-sliced rollbacks), v1.9.1 (Russian translation), v1.9.0 (schema v3, roughly half the disk use, automatic in-place migration with a `coreprotect.db.bak-v2` backup) and v1.8.2.

### Installation

- Requires: Minecraft **1.21.11**, Java **21**, Fabric Loader 0.16+ (0.19+ on newer builds) and Fabric API
- Drop `coreprotect-fabric-1.21.11-1.9.4.jar` into the server `mods/` folder; the configuration and database are created on first start

### Quick start

```
/co help
/co i                        # inspect mode: left-click block history, right-click container trades
/co lookup u:Steve t:1h      # query
/co rollback t:1h r:30       # roll back (u: optional)
/co language ru_ru           # switch language
/co status                   # status
```

### Links

- Source and documentation: https://github.com/CNTianCheng/coreprotect-fabric
- Bug reports: https://github.com/CNTianCheng/coreprotect-fabric/issues/new/choose
- License: MIT; bundles sqlite-jdbc (Apache License 2.0)
- Independent implementation inspired by CoreProtect; not affiliated with the CoreProtect team

---

## 中文

### v1.9.4 —— 第二轮深度复查（84 条）

对模组做了第二轮对抗式复查 —— 这次不只问「有没有记录」，还问「记得对不对」，共发现 84 条新问题（严重 2 / 高危 16 / 中危 29 / 轻微 37），本次全部修复。旧数据库下次启动自动原地升级：`co_command`、`co_chat` 增加 `wid_id`，`co_user` 增加 `uuid`，新增 `co_user_uuid(uuid, language)` 按账号记语言，`co_entity` 增加位置索引。

**两条严重问题**

- 活塞记录写错了坐标：延伸偏 +2、回缩偏 +1（移动中的方块实体坐标是它的**终点**）。照此回档会把方块放到错误的位置，并把本来无辜的方块清掉
- `#fire` 归因窗口用 `@At("TAIL")` 关闭，而火焰 tick 的两条正常返回（周围无可燃物、烧尽）根本不会执行到那里 —— 窗口在整个会话里一直开着，之后所有方块变化都被记成火焰并重复记录一次

**回档 / 还原正确性**

- 容器改为在方块阶段**之后**回放：以前回档会先把容器内容倒进一个即将被它替换掉的方块，库存永远回不来
- 双箱按写入时的合并 54 格回放，而不是被点开那一半的 27 格；追踪侧把两半归一为同一位置，两个漏斗不再把同一笔交易记两遍
- 替换半边双箱时只倒出该格自己的内容，不再把幸存的那半也撒出来
- 回档从容器里取走的物品现在带完整数据写进 undo，`/co undo` 会放回容器 —— 回档+撤销重新回到净零（以前会净丢库存）
- undo 记录以原子替换方式写盘，操作真正结束后才移除内存记录；同一执行者的第二个回档/还原/撤销在运行期间被拒绝（控制台、RCON、命令方块各算一个执行者）
- 未加载的区块跳过并计数，不再被当成「已还原」；火焰/水/岩浆等过渡介质不再让位置永远跳过；告示牌按正反两面成链回放

**数据完整性**

- 单行写入失败会计入 dropped 并显示在 `/co status`，不再只写日志而 dropped 一直是 0
- `/co purge` 单事务执行，支持 `u:`、`a:`、`b:`、`e:`、`r:`，回报耗时，结果未知时不再谎报「删除 0 行」
- 备份改用 `VACUUM INTO`（一致快照），备份与整理前检查磁盘空间；某批失败会以 1 秒→30 秒退避重试，超过阈值才丢弃并计数
- `close()` 顺序改为：先停写队列 → 再停读池 → 最后 checkpoint；缓存语句已关闭后才做截断式 checkpoint（原顺序会让它静默失败）
- 首次打开失败会后台重试 3 次并明确提示「记录已停用」；写连接断开时在新连接上重试该批
- `b:` / `e:` 转义 `%` 与 `_`，`b:stone` 不再匹配 `b:cobblestone`；`/co status` 的计数共用 20 秒预算，不再卡住命令几分钟

**记录完整性**

- 带聊天签名的命令重新被记录（官方路径绕过了普通命令钩子），控制台、RCON、命令方块的命令统一经命令管理器记录
- 新增：随机方块 tick、下落方块、耕地踩踏、床与重生锚被爆炸破坏、实体被击杀（不再只有自然消失）、双格方块的另一半、告示牌双面、掉落物被拾取时带完整物品数据
- 聊天文本净化（换行、控制字符、长度），每类记录各自独立的每人每分钟配额，被丢弃的记录计入统计
- 新增 `logging.commandBlacklist`（默认 `/login /register /l /reg /changepassword /newpassword`），密码类命令不再入库
- 未推动任何方块的活塞、被推动的方块、被爆炸摧毁的容器都会被记录，回档能还原而不是留下空洞

**命令、权限与配置**

- `/co purge` 接受文档里写的全部过滤条件；`/co lookup` 遇到非法动作会列出合法动作表；查询超时会告知并把结果稍后送到控制台日志
- 权限节点接受 `coreprotect.` 前缀，权限组可分别授予 `chat`/`command`，`/co help` 与 `/co language` 对所有人开放
- `/co reload` 会报告未知配置项，逐字段拷贝配置，`database.file` 变更会提示需要重启
- `database.syncMode` 支持 `/co reload` 生效；半径与页码上限改为「钳制并提示」，不再把页脚算溢出

**主线程开销**

- 容器追踪先比 64 位指纹，每容器每 tick 只做一次深比较，繁忙的漏斗线路不再每 tick 序列化所有容器内容

**自检命令与显示**

- `/co debug natural` 会把测试区域强制加载保持六十秒（1.21.9+ 在无玩家时区块不再 tick，火焰场景在空服务器上从不燃烧），并把活塞场景所在的区块也覆盖进来 —— 原来的九个区块窗口完全没有包含它
- 容器行与物品行在控制台/RCON 输出里不再多打一个空格；非玩家来源（`#server`、`#entity`、`#container`、`#gravity`、`#random_tick`、`#trample`）现在有显示名，不再打印缺翻译告警

**同时包含 v1.9.3 的全部内容**（60 条完整自查）、v1.9.2（批量写库 —— **每行 578–969 微秒 → 8.3 微秒**，fsync 60 次/秒 → 4 次/秒 —— 查询不阻塞主线程、4 毫秒切片回档）、v1.9.1（俄语翻译）、v1.9.0（数据库 schema v3，体积约减半，旧库首次启动自动迁移并保留 `coreprotect.db.bak-v2` 备份）与 v1.8.2。

### 安装

- 要求：Minecraft **1.21.11**、Java **21**、Fabric Loader ≥ 0.16（新版构建需 0.19+）与 Fabric API
- 将 `coreprotect-fabric-1.21.11-1.9.4.jar` 放入服务器 `mods/` 目录，首次启动自动生成配置与数据库

### 快速上手

```
/co help
/co i                        # 检查模式：左键方块历史、右键容器交易
/co lookup u:Steve t:1h      # 查询
/co rollback t:1h r:30       # 回滚（u: 可省略）
/co language ru_ru           # 切换语言
/co status                   # 状态
```

### 相关链接

- 源码与文档：https://github.com/CNTianCheng/coreprotect-fabric
- Bug 反馈：https://github.com/CNTianCheng/coreprotect-fabric/issues/new/choose
- 许可：MIT；内置 sqlite-jdbc（Apache License 2.0）
- 本项目是受 CoreProtect 启发的独立实现，与 CoreProtect 团队无关