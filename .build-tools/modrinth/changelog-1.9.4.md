# v1.9.4 — second audit round (84 findings)

Minecraft **1.21**, **1.21.11**, **26.1.2** — all three maintained builds.

## English

A second, adversarial audit of the mod — this time checking not only whether an event is logged but
whether it is logged *correctly* — produced 84 new findings (2 critical, 16 high, 29 medium,
37 low). This release fixes all of them. An existing database is upgraded in place on the next
start (`co_command`/`co_chat` gain `wid_id`, `co_user` gains `uuid`, `co_entity` gains a position
index, and a new `co_user_uuid` table keeps the language per account).

* **Piston records had the wrong coordinates.** An extension was written one block too far (+2) and
  a retraction one block too close (+1), because a moving block entity's position is its
  *destination*. A rollback therefore placed blocks at the wrong place and cleared innocent ones.
* **The `#fire` cause window never closed** when the fire tick returned early (nothing flammable
  around, burn-out) — `@At("TAIL")` does not run on a normal `return`. Every later block change was
  attributed to fire and recorded twice for the rest of the session.
* **Rollback correctness**: containers replay *after* the block phase (a rollback used to empty a
  container into a block it then replaced, losing the inventory); double chests replay as the merged
  54 slots that were logged, and the tracker resolves both halves to one canonical position, so two
  hoppers no longer log the same transaction twice; replacing half a double chest ejects only that
  half; everything taken out of a container is written to the undo record and put back by `/co undo`
  (rollback + undo is a net zero again); undo records are written atomically; a second
  rollback/restore/undo from the same operator is refused while one runs (console, RCON and command
  blocks each count as their own operator).
* **Data integrity**: a row that fails to insert is counted as dropped and shown by `/co status`;
  `/co purge` runs in one transaction, honours `u:`, `a:`, `b:`, `e:` and `r:`, and no longer
  reports "0 rows" when the result is unknown; backups use `VACUUM INTO` with a free-space check;
  a failed batch retries with a 1 s -> 30 s back-off; `close()` stops the write queue, then the read
  pool, then checkpoints; a failed first open retries in the background and says logging is
  disabled; `b:`/`e:` escape `%` and `_`; `/co status` counts share a 20 second budget.
* **Logging completeness**: signed chat commands are logged again (the vanilla path bypasses the
  plain command hook) and console/RCON/command-block commands go through the command manager; added
  random ticks, falling blocks, farmland trampling, beds and respawn anchors destroyed by
  explosions, killed entities, both halves of double blocks, both sides of signs, and picked-up loot
  with its full item data; chat text is sanitised and every record kind has its own rate limit;
  `logging.commandBlacklist` (default `/login /register /l /reg /changepassword /newpassword`)
  keeps credentials out of `co_command`; an unticked piston, a pushed block and a container removed
  by an explosion are recorded so a rollback restores them.
* **Commands and configuration**: `/co purge` accepts the documented filters, `/co lookup` lists the
  valid actions for an invalid one, slow queries say so and deliver later, `coreprotect.` prefixes
  are accepted in permission nodes, a group can grant `chat`/`command` separately, `/co help` and
  `/co language` are public, `/co reload` reports unknown keys and applies `database.syncMode`, and
  radius/page limits are clamped with a notice.
* **Main-thread cost**: the container tracker compares a 64-bit digest first and does one deep
  comparison per container per tick.

Also included: everything from v1.9.3, v1.9.2 (batched writes: 578-969 µs -> 8.3 µs per row),
v1.9.1 (Russian), v1.9.0 (schema v3, ~half the disk use) and v1.8.2.

## 中文

对模组做了第二轮对抗式复查 —— 不只问「有没有记录」，还问「记得对不对」—— 共 84 条新问题
（严重 2 / 高危 16 / 中危 29 / 轻微 37），本次全部修复。旧库下次启动原地升级（`co_command`/`co_chat`
增加 `wid_id`，`co_user` 增加 `uuid`，`co_entity` 增加位置索引，新增 `co_user_uuid` 表按账号记语言）。

* **活塞记录坐标错误**：延伸偏 +2、回缩偏 +1（移动的方块实体坐标是终点），回档会把方块放到错位处并
  清掉无辜方块。
* **`#fire` 窗口未关闭**：火焰 tick 两条正常返回时 `@At("TAIL")` 不执行，窗口一直开着，之后所有方块
  变化都被记成火焰并重复记录。
* **回档正确性**：容器改为在方块之后回放（此前会把内容倒进即将被替换的方块里，库存回不来）；双箱按
  合并的 54 格回放、两半归一为同一位置（两个漏斗不再重复记账）；替换半边双箱只倒出该格内容；回档取出
  的物品带完整数据写入 undo 并可被 `/co undo` 放回（回档+撤销净零）；undo 原子写盘；同一执行者的第二个
  回档/还原/撤销在运行期间被拒绝。
* **数据完整性**：单行写失败计入 dropped 并显示在 `/co status`；`/co purge` 单事务、支持全部过滤条件、
  不再谎报「删除 0 行」；备份用 `VACUUM INTO` 并检查磁盘空间；失败批按 1 秒→30 秒退避重试；`close()`
  顺序改为停写队列→停读池→checkpoint；首次打开失败后台重试并明确提示；`b:`/`e:` 转义；`/co status`
  计数共用 20 秒预算。
* **记录完整性**：带签名命令重新被记录，控制台/RCON/命令方块统一记录；新增随机 tick、下落方块、耕地
  踩踏、床与重生锚被炸、实体被击杀、双格方块另一半、告示牌双面、拾取带完整数据；聊天文本净化并按类型
  限流；新增 `logging.commandBlacklist` 防密码入库；未推动的活塞、被推的方块、被炸的容器都会记录。
* **命令与配置**：`/co purge` 支持文档里的过滤条件，`/co lookup` 非法动作列出合法动作，慢查询会告知并
  稍后投递结果，权限节点接受 `coreprotect.` 前缀，权限组可分开授予 `chat`/`command`，`/co help` 与
  `/co language` 公开，`/co reload` 报告未知配置项并应用 `database.syncMode`，半径与页码钳制并提示。
* **主线程开销**：容器追踪先比 64 位指纹，每容器每 tick 只做一次深比较。

同时包含 v1.9.3、v1.9.2（批量写库 每行 578–969 微秒 → 8.3 微秒）、v1.9.1（俄语）、v1.9.0
（schema v3，体积约减半）与 v1.8.2 的全部内容。