# CoreProtect Fabric v1.8.2 (Minecraft @VER@)

A block-level logging and rollback mod for **Fabric servers**, independently implemented with the renowned [CoreProtect](https://github.com/PlayPro/CoreProtect) plugin as its blueprint. This is the **v1.8.2 bug-fix release** for the Minecraft @VER@ build; the same fixes ship for 1.21 and 26.1.2.

> **Feature set identical to the 1.21 flagship build**: same commands, logging coverage, colours, database schema v2 with crash/power-loss protection, bStats metrics and update checks.

---

## English

### v1.8.2 — bug fixes

**Crash / data safety**

- **Rollback safety limit applied before touching the world**: a rollback refused for exceeding `rollback.maxBlocks` no longer half-applies container changes, and the SQL query is capped (`LIMIT -1` meant *unlimited* in SQLite, so a wide rollback could load the entire table and run the server out of memory)
- **Corrupt-database recovery fixed**: every pooled read connection is closed before the files are moved (Windows kept them locked, which aborted the recovery), and a failed recovery can no longer leave the mod in a state where every write throws
- **Guaranteed shutdown flush**: the WAL checkpoint + close can no longer be lost to a timeout, and the crash marker is deleted only after a clean stop — so a crash during startup or shutdown is detected again
- **Writes arriving after shutdown are dropped** instead of throwing on the server thread; session-leave rows and open-container diffs are no longer lost (the database now closes on `SERVER_STOPPED`)
- **Read failures are reported** instead of surfacing as a bare `NullPointerException` or as a silent "No results found"; reader connections are closed on restore/close, so no file handles leak
- `/co purge t:0` no longer deletes the entire database, and absurd durations (`t:99999999999999999w`) are rejected instead of overflowing or throwing

**Rollback / restore / undo**

- Container item operations are recorded and **inverted by `/co undo`** (previously reported as undone while the items stayed reversed)
- The undo record is always replaced, so `/co undo` can no longer revert an unrelated older rollback
- Restored stacks never exceed the maximum stack size; items missing from a container are handed back instead of being silently dropped
- `/co rollback` and `/co restore` no longer require `u:` — like the plugin, a time/radius/block filter alone is enough

**Logging correctness**

- **`#fire` logging fixed**: the mixin targeted a method that no longer exists, so fire damage was never recorded
- Sign edits are stored **for the correct face** (front/back) and restored to that face
- Item drops and pickups are only logged when items really moved (no more phantom rows from pickup delay, a full inventory or an empty stack)
- Natural-cause nesting is a stack again: fire → explosion no longer leaves a stale marker that mis-attributes later changes
- Change deduplication is state-based, so a second real change at the same position is logged again
- State-only changes (water level, piston extension, dropper `triggered`) are no longer stored as block changes
- Hopper/dispenser snapshots are keyed per dimension and expire, so a chunk reload or another world's container no longer produces phantom transactions

**Lookups & commands**

- CoreProtect-compatible action names: `a:container`, `+container`, `-container`, `a:kill`, `a:death`, `a:chat`, `a:command`, `a:session`, `a:sign`, `a:item`, `+item`, `-item`
- `a:#kill` returns only kills, `a:#death` only deaths; `+item`/`-item` really filter drops vs pickups
- `e:` now excludes the matching block/action as documented (it was compared against the user column, so a block exclusion filtered nothing)
- `a:#kill` lookups honour `r:`, `t:`, `u:` and `e:`; `u:` matches player names case-insensitively
- Command suggestions work again (`u:<name>` player completion and key filtering — the token cursor was always empty)
- A failed database read is shown as an error instead of "No results found"

**Config, metrics & update check**

- Corrected configuration values are written back to disk (they used to be re-clamped on every start)
- A `"language": null` entry no longer breaks the config
- bStats honours the official `config/bstats/config.yml` opt-out and no longer rewrites `config.txt` (a failed write used to reset the server UUID)
- Update check: an HTTP 404 from Modrinth is reported once instead of warning every interval; versions are compared properly (pre-releases ignored, all candidates scanned)
- Metric/update schedulers are cancelled on shutdown

**Test utility**

- `/co debug natural` force-loads the test chunks (1.21.9+/26.x no longer tick chunks without players) and places the fire scenario away from the TNT, so the built-in self-test works again

### Install

- Requirements: Minecraft **@VER@**, Java **@JAVA@**, Fabric Loader >= @LOADER@ + Fabric API
- Drop `coreprotect-fabric-@VER@-1.8.2.jar` into the server's `mods/` folder; config and database are created on first launch

### Quick start

```
/co help
/co i                        # inspect: left-click block history, right-click container transactions
/co lookup u:Steve t:1h      # lookup
/co rollback t:1h r:30       # rollback (u: optional)
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

### v1.8.2 —— 缺陷修复

**崩溃与数据安全**

- **回滚安全上限提前生效**：超过 `rollback.maxBlocks` 的回滚不再"已经改了一半容器才报错"；查询也改为带上限（SQLite 里 `LIMIT -1` 等于不限制，大范围回滚会把整张表读进内存导致 OOM）
- **数据库损坏恢复修复**：移动文件前会关闭全部读取连接（Windows 上连接占用会导致恢复被跳过）；恢复失败也不会再留下"每次写入都报错"的状态
- **关库必定刷盘**：WAL 检查点 + 关闭不再因超时被丢弃；崩溃标记只在完全正常停止后删除，启动/关闭过程中崩溃也能被检出
- **关服后的写入直接丢弃**，不再在服务器线程抛异常；玩家断线产生的会话/容器记录不再丢失（数据库改到 `SERVER_STOPPED` 关闭）
- **读取失败会明确报错**，不再表现为空指针或"没有结果"；恢复/关闭时读取连接会真正关闭，不再泄漏文件句柄
- `/co purge t:0` 不再清空整库；超大时间（`t:99999999999999999w`）会被拒绝而不是溢出或抛异常

**回滚 / 恢复 / 撤销**

- 容器物品操作会被记录，**`/co undo` 会一并撤销**（以前会提示成功但物品仍是反的）
- 撤销记录每次都会被替换，`/co undo` 不会再把更早的一次回滚撤销掉
- 恢复进容器的物品不会超过单组上限；容器里不足的数量会补发，不再静默丢失
- `/co rollback`、`/co restore` 不再强制要求 `u:`（与插件一致，只给时间/半径/方块条件即可）

**记录正确性**

- **修复 `#fire` 记录**：mixin 指向的方法早已不存在，火焰破坏从来没有被记录
- 告示牌编辑按**正确的正/反面**记录，回滚时写回同一面
- 掉落/拾取只在物品真的移动时记录（不再出现拾取延迟、背包满、空栈造成的幽灵记录）
- 自然事件归因改为栈式嵌套：火焰→爆炸 不再残留标记导致后续方块改动被错误归因
- 去重改为按"状态对"判断，同一位置的真实二次改动仍会记录
- 仅状态变化（水位、活塞伸出、发射器 triggered 等）不再记录为方块改动
- 漏斗/发射器快照按维度区分并带过期时间，区块重载或其它世界的容器不再产生幽灵交易

**查询与命令**

- 兼容 CoreProtect 的写法：`a:container`、`+container`、`-container`、`a:kill`、`a:death`、`a:chat`、`a:command`、`a:session`、`a:sign`、`a:item`、`+item`、`-item`
- `a:#kill` 只查击杀、`a:#death` 只查死亡；`+item`/`-item` 真正区分拾取与丢弃
- `e:` 现在按文档排除对应方块/动作（以前只比对用户名，方块排除形同虚设）
- `a:#kill` 查询支持 `r:`/`t:`/`u:`/`e:`；`u:` 匹配玩家名不再区分大小写
- 命令补全恢复可用（`u:<玩家名>` 补全与键名过滤，之前取到的 token 恒为空）
- 数据库读取失败会提示错误，而不是显示"没有结果"

**配置 / 统计 / 更新检查**

- 被修正的配置值会写回文件（以前每次启动都会重新修正一遍）
- `"language": null` 不再破坏配置
- bStats 支持官方 `config/bstats/config.yml` 退出开关，并且不再重写 `config.txt`（写入失败曾导致服务器 UUID 变化）
- 更新检查：Modrinth 返回 404 只提示一次，不再每个周期刷警告；版本比较更严谨（忽略预览版、扫描全部条目）
- 关服时取消统计与更新检查的定时任务

**测试工具**

- `/co debug natural` 会强制加载测试区块（1.21.9+/26.x 无玩家时区块不再 tick），并把火焰场景移出 TNT 范围，自带自检重新可用

### 安装

- 要求：Minecraft **@VER@**、Java **@JAVA@**、Fabric Loader ≥ @LOADER@ + Fabric API
- 将 `coreprotect-fabric-@VER@-1.8.2.jar` 放入 `mods/`，首次启动自动生成配置与数据库

### 快速上手

```
/co help
/co i                        # 检查模式：左键方块历史、右键容器交易
/co lookup u:Steve t:1h      # 查询
/co rollback t:1h r:30       # 回滚（u: 可省略）
/co online Steve             # 在线记录
/co status                   # 状态
```

### 相关链接

- 源码与文档：https://github.com/CNTianCheng/coreprotect-fabric
- Bug 反馈：https://github.com/CNTianCheng/coreprotect-fabric/issues/new/choose
- 许可：MIT；内置 sqlite-jdbc（Apache License 2.0）
- 本项目是受 CoreProtect 启发的独立实现，与 CoreProtect 团队无关
