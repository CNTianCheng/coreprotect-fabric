# v1.9.3 — security, data-integrity and logging fixes (60 findings)

Minecraft **1.21**, **1.21.11**, **26.1.2** — all three maintained builds.

## English

A complete self-audit of the mod (code quality, security, functional bugs) produced 60 findings;
this release fixes all of them, plus a second round of problems found by an adversarial review of
the fixes themselves.

* **Permissions are no longer bypassable.** Command blocks and other non-player sources no longer
  skip every permission level; `/co purge`, `/co rollback`, `/co restore` and `/co undo` require
  `adminLevel`, out-of-range levels fail closed, and `permissions.lookupLevel` now defaults to
  **1** (moderators only) instead of 0 (everyone). Permission groups are matched by UUID, so an
  offline-mode player can no longer inherit a group by taking the configured name
* **A rollback no longer creates or deletes items.** What a rollback takes out of a container is
  really taken out, `/co undo` cannot duplicate it, and an emptied container's stacks are dropped
  next to it *and* written into the undo record — a console-issued rollback is undoable rather
  than destructive
* **Item data components survive a rollback.** An enchanted, renamed or damaged stack is restored
  with its components (`co_container`/`co_item` gain an `nbt` column); the old database is upgraded
  in place on first start
* **Restore order is correct** — block, sign and container rows are replayed per position (newest →
  oldest for a rollback, oldest → newest for a restore), other dimensions are skipped and counted
* **Concurrency is bounded:** a rollback, restore or undo of the same operator cannot overlap, the
  undo file is written by a background thread instead of the server thread, and a shutdown drains
  the write queue with a 30 s deadline instead of hanging the server thread for five minutes
* **A container that merely can't be read** (unloaded chunk, block entity gone) is no longer
  reported as fully emptied by the player who had its screen open
* **Logging completeness:** whole-stack pick-ups, fire attribution, buckets/flint/bone meal/
  hoes/axes/shovels/shears, endermen and pistons, double chests as one container, and rate-limited
  command/sign hooks that cannot break the change they observe
* **Player commands are logged again**, on all three builds: a client with chat signing enabled
  sends `ChatCommandSignedC2SPacket`, a path that never reached the hook — on an online-mode
  server a player's commands were missing from `/co lookup` altogether. On the **26.1.2** build
  both command and sign injections still used the 1.21 yarn method names and had never applied
  either, so command/sign logging was dead on that build
* **Reads no longer stall the server:** lookups run on the read pool, console/RCON commands wait at
  most 10 s and report to the log afterwards, `/co status` counts share one 20 s budget
* **Build/packaging:** release scripts verify SHA-256, size and the version inside the jar, use the
  git credential store instead of a file, and the SQLite driver moves to 3.50.2.0

**Also included**: everything from v1.9.2 (batched writes — **578–969 µs → 8.3 µs per row**, fsyncs
60/s → 4/s; non-blocking lookups; 4 ms sliced rollback), v1.9.1 (Russian translation) and v1.9.0
(database schema v3, roughly half the file size, automatic migration with a `coreprotect.db.bak-v2`
safety copy) — plus every v1.8.2 fix.

## 中文

对模组做了一次完整的自查（代码质量 / 安全 / 功能性 bug），共 60 条，本次全部修复；此外还修掉了对
修复本身做对抗式复查时发现的问题。

* **权限不再可绕过**：命令方块等非玩家来源不再跳过权限判定；`/co purge`、`/co rollback`、
  `/co restore`、`/co undo` 需要 `adminLevel`，越界值一律拒绝；`permissions.lookupLevel` 默认值
  由 0（所有人）改为 **1**（仅管理员）。权限组按 UUID 匹配，离线服玩家不能再靠改名字继承权限组
* **回档不再凭空造物品、也不再删物品**：从容器取出的就是取出，`/co undo` 无法复制；
  被倒空的容器内容会掉在容器旁**并**写进 undo 记录，控制台发起的回档也能被 undo 找回
* **物品数据组件在回档后保留**：附魔、改名、耐久不同的物品按原样还原
  （`co_container`/`co_item` 增加 `nbt` 列），旧数据库首次启动原地升级
* **还原顺序正确**：方块、告示牌与容器行按坐标成链回放（回档从最新到最旧、还原从最旧到最新），
  维度不符的位置计为 skipped
* **并发有界**：同一操作者的回档 / 还原 / undo 不能重叠；undo 文件改由后台线程写盘；
  关服以 30 秒为限清空待写队列，不再卡住服务器线程五分钟
* **读不到内容的容器**（区块未加载、方块实体消失）不再被记成“被开界面的玩家搬空”
* **记录更完整**：整栈被捡走、火焰归因、水桶/打火石/骨粉/锄斧锹剪刀、末影人与活塞、
  双箱按一个容器记录；命令与告示牌钩子限流且异常不会拖垮被观察的方块变化
* **玩家命令重新被记录**（三个版本都是）：开启聊天签名的客户端发的是
  `ChatCommandSignedC2SPacket`，这条路径根本不经过原钩子——在线模式服务器上玩家的命令此前
  完全查不到；**26.1.2** 版里命令与告示牌两处注入还在用 1.21 的 yarn 方法名，从未生效
* **查询不再阻塞服务器**：查询走读池；控制台/RCON 命令最多等 10 秒，超时后结果写进服务器日志；
  `/co status` 的八个计数共用 20 秒预算
* **构建与发布**：发布脚本校验 SHA-256、大小与 jar 内版本，凭证改用 git credential store，
  SQLite 驱动升级到 3.50.2.0

**同时包含 v1.9.2 的全部内容**：批量写库（**每行 578–969 微秒 → 8.3 微秒**，fsync 60 次/秒 → 4 次/秒）、
查询不再阻塞主线程、4 毫秒时间切片回档；以及 v1.9.1（俄语翻译）、v1.9.0（数据库 schema v3，
体积约减半，旧库自动迁移并保留 `coreprotect.db.bak-v2` 备份）与 v1.8.2 的全部修复。
