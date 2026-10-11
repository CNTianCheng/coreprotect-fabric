# CoreProtect Fabric v1.9.3 (Minecraft 26.1.2)

A block-level logging and rollback mod for **Fabric servers**, independently implemented with the renowned [CoreProtect](https://github.com/PlayPro/CoreProtect) plugin as its blueprint. This is the **v1.9.3 audit release** for the Minecraft 26.1.2 build; the same fixes ship for the other two maintained builds.

> **Identical feature set across the three maintained builds** (1.21 / 1.21.11 / 26.1.2): same commands, logging coverage, colours, database schema and crash/power-loss protection.

---

## English

### v1.9.3 — security, data-integrity and logging fixes (60 findings)

A complete self-audit of the mod (code quality, security, functional bugs) produced 60 findings; this release fixes them. **Nothing is removed from the log**, and an existing database is upgraded in place (`co_container` and `co_item` gain an `nbt` column). A second, adversarial pass over the fixes themselves is included as well: it found one case where `/co undo` could duplicate items, a container whose contents could be deleted by a console-issued rollback, a rollback/undo race, and a shutdown path that could block the server thread.

**Permissions and information exposure**

- Non-player sources (command blocks in particular) no longer bypass every permission level: `/co purge`, `/co rollback`, `/co restore` and `/co undo` now require `adminLevel`, the `coreprotect.*` nodes use the same check, and an out-of-range `permissions.adminLevel`/`lookupLevel` fails closed instead of allowing everyone
- `permissions.lookupLevel` now defaults to **1** — only moderators can read chat, command, container and block history unless you configure otherwise
- Permission groups are matched by player **UUID**; a name in `permissionGroups` is honoured only on an online-mode server, so an offline-mode player can no longer inherit the admin group by taking that name
- Chat, command and sign text is stripped of `§` colour codes and control characters before printing, so a logged message can no longer forge a `CoreProtect - ...` line or hide content
- `r:` and `p:` are capped at 1000 instead of accepting `2147483647`, which turned one command into a full-table scan

**Rollback / restore correctness**

- A rollback no longer creates items: what it takes out of a container is removed, and `/co undo` cannot duplicate it
- Restoring an item restores its **data components** — an enchanted, renamed or damaged stack is no longer restored as a plain one, and a swap between two different stacks of the same item is now logged instead of silently ignored
- Block and sign chains are replayed per position (newest → oldest for a rollback, oldest → newest for a restore), so the result is the oldest recorded state — container rows follow the same order, so a stack that was stored and taken out again ends up where the log says it was; positions in another dimension are skipped and counted instead of being written into the wrong world
- Limits apply to every log type, a rollback that throws still writes its undo record, the undo record survives a restart (`coreprotect-fabric-undo.json`, now written by a background thread instead of the server thread), and a second `/co rollback`, `/co restore` or `/co undo` from the same player is refused while one of their operations is running (console, RCON and command blocks each count as one operator)
- An emptied container no longer loses its contents: every stack a rollback ejects is dropped next to the container **and** written into the undo record with its components, so even a console-issued rollback can be undone instead of deleting the items
- A container a rollback actually changed is now written back through the inventory API, so the change is marked dirty: it survives a save/reload and reaches the players who have the container open. Editing only the stack object could make the rollback appear to have done nothing once the screen was reopened
- Unknown block states are skipped instead of treated as air, a container is emptied before its block is replaced, and only dropped item entities are restored

**Logging completeness**

- A pick-up that takes the whole stack is logged (a `taken == 0` result used to be swallowed)
- Fire is attributed again (`#fire`): burning, fire spread and extinguished blocks are recorded, while fire's own block changes are filtered out
- No more double rows when `removeBlock` delegates to `setBlockState` (endermen, pistons); right-click interactions are decided by comparing the block before and after, so buckets, flint and steel, bone meal, hoes, axes, shovels and shears are recorded and the fake `+stone` rows are gone
- Double chests are tracked as one 54-slot container, a container broken while its screen is open still logs its contents, hoppers/dispensers that would log a transfer twice are excluded, and the snapshot map drops its oldest entries instead of everything when it overflows
- Command and sign hooks target the validated execution path and are limited to 60 per player per minute; a failing hook is caught so it cannot break the world change it observes
- Player commands sent with chat signing enabled are recorded again: the hook covered only the unsigned path (`CommandExecutionC2SPacket`), while a client with signing enabled sends `ChatCommandSignedC2SPacket` and never reaches `executeCommand`, so on an online-mode server player commands were missing from `/co lookup` altogether
- On the **26.1.2** build both injections still used the yarn method names and therefore never applied - command and sign logging had been dead on that build. With `"defaultRequire"` now at `1` a stale target stops the server at startup instead of being ignored silently

**Concurrency and database**

- A failed write batch is re-queued instead of lost, dropped rows are warned about, and `/co status` reports committed batches, queued rows and dropped rows
- Nested read-pool queries no longer deadlock, a failed count shows as `n/a` instead of `0` (the eight counts share one 20 s budget, so `/co status` cannot stall the command for minutes), and `/co purge` reports an error instead of a successful "0 rows" when the query fails
- Console/RCON commands wait at most 10 s for a query and the result goes to the server log if it is still running, instead of the command silently printing nothing (an interrupted query now says so instead of returning without a word)
- Shutting down drains the queued rows with a 30 s deadline and then reports — and drops — what could not be written, instead of hanging the server thread for five minutes
- A container whose contents cannot be read (unloaded chunk, block entity gone) is no longer reported as fully emptied by the player who had its screen open; only a container that really disappeared is logged, and then as an anonymous `#container` change
- Executor pools are recreated when the database is reopened, `/co reload` publishes its changes safely and warns that `database.file` needs a restart, and `maxLines`, `inspectLines` and `defaultTimeSeconds` are bounded
- `VACUUM`/checkpoint no longer collide with cached statements; `a:sign` and entity filters no longer fail with `ambiguous column name: id` / `no such column: e.action`; a radius lookup stays inside the dimension it was issued in

**Build, packaging and release tooling**

- `publish.ps1` checks SHA-256, size and the version inside `fabric.mod.json` before uploading, re-checks a failed upload, cross-checks `versions.json` and refuses stale jars
- Release scripts upload the new asset before deleting the old one, refuse a dirty working tree, and need an explicit flag to re-run a historical release
- GitHub Actions are pinned to fixed versions and fail when no artifact is produced, `check-ascii.ps1` exits non-zero on failure, credentials come from the git credential store instead of a file on disk, the SQLite driver moves to 3.50.2.0 and the `fabric.mod.json` loader/API minimums match each build

**Also included**: everything from v1.9.2 (batched writes: **578–969 µs → 8.3 µs per row**, fsyncs 60/s → 4/s; non-blocking lookups; 4 ms sliced rollback), v1.9.1 (Russian translation) and v1.9.0 — database schema v3, roughly half the file size, automatic migration on first start with a `coreprotect.db.bak-v2` safety copy — plus every v1.8.2 fix.

### Install

- Requirements: Minecraft **26.1.2**, Java **25**, Fabric Loader >= 0.16 + Fabric API
- Drop `coreprotect-fabric-26.1.2-1.9.3.jar` into the server's `mods/` folder; config and database are created on first launch

### Quick start

```
/co help
/co i                        # inspect: left-click block history, right-click container transactions
/co lookup u:Steve t:1h      # lookup
/co rollback t:1h r:30       # rollback (u: optional)
/co language ru_ru           # switch language
/co status                   # status
```

### Links

- Source & docs: https://github.com/CNTianCheng/coreprotect-fabric
- Bug reports: https://github.com/CNTianCheng/coreprotect-fabric/issues/new/choose
- License: MIT; bundles sqlite-jdbc (Apache License 2.0)
- Independent implementation inspired by CoreProtect; not affiliated with the CoreProtect team

---

## 中文

### v1.9.3 —— 安全、数据完整性与记录完整性修复（60 条）

对模组做了一次完整的自查（代码质量 / 安全 / 功能性 bug），共 60 条，本次全部修复。**记录范围没有减少**，旧数据库原地升级（`co_container` 与 `co_item` 增加 `nbt` 列）。此外还对修复本身做了一轮对抗式复查，其中的问题也一并修掉：`/co undo` 可能凭空造物品、控制台发起的回档可能删掉容器内容、回档与 undo 的并发竞态、关服路径可能卡住服务器线程。

**权限与信息暴露**

- 非玩家来源（尤其是命令方块）不再绕过所有权限等级：`/co purge`、`/co rollback`、`/co restore`、`/co undo` 现在同样要求 `adminLevel`，`coreprotect.*` 权限节点走同一判定；`permissions.adminLevel`/`lookupLevel` 配成越界值是**拒绝**而不是对所有人放行
- `permissions.lookupLevel` 默认改为 **1**——默认只有管理员能查看聊天、命令、容器与方块历史
- 权限组按玩家 **UUID** 匹配；`permissionGroups` 里的名字只在正版（online-mode）服务器上生效，离线模式服务器上冒用配置名不再能继承管理组
- 聊天、命令、告示牌文本在输出前剥掉 `§` 颜色代码与控制字符，伪造的 `CoreProtect - ...` 行与隐藏内容不再可能出现
- `r:` 与 `p:` 上限改为 1000（原来可填 `2147483647`，一条命令就会全表扫描）

**回档 / 还原正确性**

- 回档不再凭空造物品：从容器里取出的物品就是取出，`/co undo` 也无法复制
- 还原物品会一并还原**数据组件**：附魔、改名、耐久不同的物品不再被还原成白板，同物品不同组件之间的互换也会被记录
- 方块与告示牌按坐标成链回放（回档从最新到最旧、还原从最旧到最新），最终落到最旧一条记录的状态；容器行也按同样顺序回放，同一堆物品存取多次后落在记录中的位置；维度不符的位置计为 skipped，不再写进错误的世界
- 方块与物品上限对所有记录类型生效；回档中途异常也会写入 undo 记录；undo 记录重启后仍在（`coreprotect-fabric-undo.json`，改由后台线程写盘）；同一玩家在自己有任务运行时不能再开第二个 `/co rollback`、`/co restore` 或 `/co undo`（控制台、RCON、命令方块各算一个操作者）
- 被倒空的容器不再丢内容：回档倒出的每一栈都会掉在容器旁**并**带组件写进 undo 记录，控制台发起的回档也能被 undo 找回来
- 回档对容器内容的改动会通过容器 API 写回并标脏：存档/重载后仍然生效，也会同步给正开着该容器的玩家（此前只改物品对象，玩家重开界面后可能“变回原样”）
- 无法解析的方块状态一律跳过（不再当作空气），替换容器方块前先倒空内容，掉落物之外的物品实体不再被还原（避免复制）

**记录完整性**

- 整栈被捡走现在会记录（`taken == 0` 的结果原来被吞掉）
- 火焰重新可归因（`#fire`）：燃烧、蔓延、熄灭都会记录，火焰自身方块变化不会刷屏
- `removeBlock` 委派给 `setBlockState` 时不再记两遍（末影人、活塞）；右键交互改为比较交互前后方块，水桶/岩浆桶、打火石、骨粉、锄斧锹剪刀都能记录，虚假的 `+stone` 行消失
- 双箱按 54 格合并容器记录；GUI 打开期间容器被破坏仍记录内容；会重复记录的漏斗/发射器被排除；快照表超限时淘汰最旧条目而不是整表清空
- 命令与告示牌钩子挂在已校验的执行路径上，并限流 60 条/分钟/人；钩子内部异常被捕获，不再拖垮它正在观察的原版方块变化
- 开启聊天签名的客户端发出的命令重新被记录：原钩子只覆盖未签名路径（`CommandExecutionC2SPacket`），签名客户端发的 `ChatCommandSignedC2SPacket` 根本不经过 `executeCommand`，在线模式服务器上玩家的命令此前完全查不到
- **26.1.2** 版里两处注入仍在用 yarn 方法名，从未生效，该版本上的命令与告示牌记录一直是死的；`"defaultRequire"` 现在为 `1`，目标写错会在开服时直接失败而不是被静默忽略

**并发与数据库**

- 批量写入失败的行重新入队而不是丢弃，丢行会告警，`/co status` 显示已提交批次数、排队行数与丢弃行数
- 读池嵌套查询不再自锁，查询失败计数显示 `n/a` 而不是 `0`（八个计数共用 20 秒预算，`/co status` 不会再卡住命令几分钟），`/co purge` 查询失败时报错而不是报“成功删除 0 行”
- 控制台/RCON 命令最多等待 10 秒，仍未完成则把结果写进服务器日志，不再静默无输出（查询被中断时会在日志里说明）
- 关服时以 30 秒为限清空待写队列，写不进去的行告警后丢弃，不再让服务器线程卡满五分钟
- 读不到内容的容器（区块未加载、方块实体消失）不再被记成“被开界面的玩家搬空”；只有真正消失的容器才记录，且署名为匿名的 `#container`
- 数据库重开后执行器池会重建，`/co reload` 安全发布配置并提示 `database.file` 需重启生效，`maxLines`、`inspectLines`、`defaultTimeSeconds` 都有上限
- `VACUUM`/checkpoint 不再与语句缓存冲突；`a:sign` 与实体过滤不再报 `ambiguous column name: id` / `no such column: e.action`；半径查询限定在发起命令的维度内

**构建、打包与发布工具**

- `publish.ps1` 上传前校验 SHA-256、大小与 `fabric.mod.json` 内的版本，失败后复核，交叉校验 `versions.json`，并拒绝过期 jar
- 发布脚本先上传新附件再删除旧附件，工作区不干净时拒绝运行，历史版本重跑需要显式开关
- GitHub Actions 固定到具体版本、没有产物即失败，`check-ascii.ps1` 失败返回非零，凭据改从 git 凭据存储读取而不再落盘，SQLite 驱动升级到 3.50.2.0，`fabric.mod.json` 的 loader/API 下限与各版本对应

**同时包含 v1.9.2 的全部内容**：批量写库（**每行 578–969 微秒 → 8.3 微秒**，fsync 60 次/秒 → 4 次/秒）、查询不再阻塞主线程、4 毫秒时间切片回档；以及 v1.9.1（俄语翻译）、v1.9.0（数据库 schema v3，体积约减半，旧库首次启动自动迁移并保留 `coreprotect.db.bak-v2` 备份）与 v1.8.2 的全部修复。

### 安装

- 要求：Minecraft **26.1.2**、Java **25**、Fabric Loader ≥ 0.16 + Fabric API
- 将 `coreprotect-fabric-26.1.2-1.9.3.jar` 放入服务器 `mods/` 目录，首次启动自动生成配置与数据库

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
