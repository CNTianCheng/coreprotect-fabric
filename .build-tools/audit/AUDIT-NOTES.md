# CoreProtect Fabric 全量检测纪要（AUDIT-NOTES）

> 本文件是用户要求「每完成一个任务就把重要信息编入纪要」的载体。
> 配套纪要：`.build-tools/perf/PERF-NOTES.md`（v1.9.2 性能优化）、`CHANGELOG.md`（发布记录）。

## 1. 需求（用户口径）

- 用户原始请求（先网站后更正，m00827 答复）：
  > 「刚刚我那个说错了，对模组进行代码检测，安全性进行检测，bug检测等」
- 澄清结论（逐条问答）：
  1. 对象 = **CoreProtect Fabric 模组本体**，三个维护版本（`coreprotect-fabric-1.21` / `-1.21.11` / `-26.1.2`）。
  2. 交付方式 = **先出完整报告，等用户拍板再修**（本阶段只读，未改任何代码）。
  3. 用户没有已观察到的具体现象，要求全量自查，代码质量 / 安全 / 功能性 bug 三类不分主次。
- 沿用约束：`.build-tools/*.ps1` 纯 ASCII（`check-ascii.ps1`）；不改 Modrinth 图标；不回显 `.build-tools/modrinth/token.txt`；不同时跑两个 Gradle / 两个测试服；只杀命令行匹配 `*coreprotect*` 的 `java.exe`；本仓库**禁止 `git add -A`**。

## 2. 检测对象与范围

- 主审版本：`coreprotect-fabric-1.21.11/src/main/java/net/coreprotect/fabric/`（39 个 Java 文件）+ `src/main/resources/`。
- 两个维护版本按「同一缺陷是否存在」标注；除非另行说明，下列每条**三个版本都存在**（1.21 与 1.21.11 行号一致，26.1.2 仅 API 改名：`setBlockState`→`setBlock(...,Block.UPDATE_ALL)`、`getDefaultState`→`defaultBlockState`、`dropInventoryContents`→`dropContainerContents`、`Registries.ITEM.getId`→`BuiltInRegistries.ITEM.getKey`、`BlockArgumentParser`→`BlockStateParser`）。
- 附带审查：构建/发布流水线（`.github/workflows`、`.build-tools/*.ps1`、三份 `build.gradle`/`gradle.properties`/`fabric.mod.json`）。
- 不在范围：13 个历史 v1.0.0 目录、其它项目、`.dsh-*` 插件产物。

## 3. 方法

六个并行只读子代理（SQL/数据层、权限与安全、回档正确性、并发与生命周期、事件与 mixin、构建与发布），每个必须「引用确切代码行 + 影响 + 最小修复」且禁止改文件/跑构建；汇总后由主代理**逐条读代码复核**，剔除误报、标注级别与复核状态。

## 4. 检测结果

### 4.1 概览

- 合计 **60 条**（45 条来自前五路 + 领域⑤新增 15 条，已合并 9 条跨域重复）：**P0 立即修 6 条 / P1 高危 16 条 / P2 中危 27 条 / P3 轻微 11 条**。
- 领域⑤（事件/mixin/配置）另报 20 条，其中与已有条目重复而**合并进原条目**的 5 条：`Permissions.java:54` → B5、告示牌 HEAD → B8、`BlockEventListener.java:126` → C2（并补：先拆告示牌再放石头会永久丢失告示牌）、`ContainerTracker.java:73` 与 `HopperTracker.java:75` → C13。
- 其中 **v1.9.2 性能改造引入或放大的 5 条**：D1（批量提交失败即丢行）、A1（读线程池嵌套自锁）、A3（`closeStatements()` 落在注释里）、A2（purge 失败报成功 0 行）、D3（集成服务器开第二个世界后数据库不可用）。
- 我已**亲自读源码复核** 10 条（标 ✅）；其余标 ◻（子代理引用原文，未由我逐行复核）。
- 结论先行：**没有发现后门、密钥泄露、SQL 注入或任意代码执行**；风险集中在「权限默认过宽」「回档会造出物品/跨维度误伤」「并发下丢日志」「发布脚本可能发出错包」。

### 4.2 P0 — 建议立即修

**D1 ✅ ◻程度：已复核 · 三版本共有 · v1.9.2 引入 · blocker**
`database/DatabaseManager.java:1509-1534`（`flushWrites`，配合 `dictId` 的 `cache.put` 在 :825、`stateId` 的 `stateCache.put` 在 :1439）
问题：一批最多 1000 行在一个事务里执行，任一行抛异常或 `commit()` 失败即 `conn.rollback()`，**已从队列取出的这批行直接丢弃**（不回队、`droppedWrites` 不计数）；更糟的是 `dictId`/`stateId` 在**同一事务内**已把新字典 id 写进缓存，回滚后这些 id 指向不存在的行，之后所有引用它们的记录在 LEFT JOIN 里显示为 `NULL`（用户名/方块名变 null，回档可能用错状态）。
影响：磁盘满、I/O 错误或写锁竞争超过 `busy_timeout` 时，**静默丢日志 + 之后长期写入坏引用**（不可自愈，重启仍在）。
修复：把取出的任务放进 List，回滚时回队；回滚后清空 `nameIds`/`worldIds`/`actionIds`/`stateCache`。
- 状态：**【FIXED】** `database/DatabaseManager.java:1635-1686`（失败批次整体回队重试 + `flushRetryMs = 1000` 退避 + `invalidateDictionaries()`）+ `:1306`（清四类字典缓存）

**A1 ✅ 已复核 · 三版本共有 · v1.9.2 引入 · high（可达 blocker）**
`database/DatabaseManager.java:1377-1379` + `command/LookupService.java:62-63,96`（同样嵌套：`rollback/RollbackManager.java:124`、`inspect/Inspector.java:78`）
问题：`submitRead` 把任务**放进读线程池执行**，任务体里又调用 `queryBlocks/queryContainers` → `executeRead` → `readPool.submit(task).get(30s)`，**同一线程池嵌套等待**。池子大小 `readThreads = max(2, min(8, CPU))`，一旦 N 个外层任务占满 N 个线程，内层永远排不上 → 全部等满 30 秒后报 "database read failed"（数据库本身健康）。
影响：2 核机器上**两个玩家同时 `/co lookup`/`/co inspect`**、或一次回档叠加一次查询即可触发；玩家重试会持续占满池子。作者自己在 `countsSequential` 的注释里已描述过这个陷阱。
修复：`executeRead` 检测「已在本池线程」时直接复用 `readConnection()` 内联执行；或让 `submitRead` 用独立执行器。
- 状态：**【FIXED】** `database/DatabaseManager.java:150,1194,1459,1479-1483`（`ThreadLocal<Boolean> poolReader`：读池任务内再取读连接直接内联执行，不再同池自锁）

**C1 ✅ 已复核 · 三版本共有 · high（刷物品/经济破坏）**
`rollback/RollbackManager.java:361-378`（`removeItems` 结尾）
问题：注释写着「容器里的东西不够，就把差额发给执行者，免得数据悄悄丢失」，于是 `giveStack(operator, new ItemStack(item, remaining), …)` **凭空生成物品**；`/co undo` 走同一条路（`:302`）。
影响：玩家把 64 钻石存进箱子又取走、或箱子被苦力怕炸掉，管理员回档时箱子是空的 → **凭空造出 64 钻石**给管理员或掉在箱子旁任人拾取。
修复：删掉这个分支，容器里没有就不发。
- 状态：**【FIXED (deviating)】** `rollback/RollbackManager.java:711-721`（`removeItems` 只移除容器里实有的数量，`giveStack` 差额分支已整段删除）；偏离：连「实际取出的物品」也不再交给执行者——否则 `/co undo` 会把同样物品再加回容器，等于复制物品

**B1 ✅ 已复核 · 三版本共有 · high（权限绕过）**
`util/Permissions.java:58-62`（`hasLevel` 同样问题在 :41-45）
问题：`ServerPlayerEntity player = source.getPlayer(); if (player == null) return true;` —— **非玩家来源（命令方块、数据包函数、RCON、控制台）一律通过所有节点**，`permissions.adminLevel` 对它们完全不起作用。
影响：**权限等级 2（GAMEMASTER）就能放命令方块**，因此等级 2 的玩家可借命令方块执行 `/co purge`（不可逆删库）、`/co rollback|restore`（大规模改世界，且命令方块来源没有玩家，`RollbackManager.java:200` 不写 undo 记录 → 事后无法 `/co undo`）、`/co reload`、`/co debug natural`（强制加载 9 个区块并生成 TNT）。
修复：先算出 `required`，非玩家来源改为 `levelCheck(required).allows(source.getPermissions())`（控制台/RCON 天然是 4 级，仍然可用）。
- 状态：**【FIXED】** `util/Permissions.java:74`（非玩家来源改走 `levelCheck(required).allows(source.getPermissions())`，命令方块不再一律放行）

**E1 ✅ 已复核 · 三版本共有 · blocker（捡走物品不留记录）**
`mixin/ItemEntityPickupMixin.java:60`（HEAD 只存 `coreprotect$beforeItem`/`coreprotect$beforeCount`，:60-61 为 `int taken = beforeCount - ((ItemEntity)(Object)this).getStack().getCount(); if (taken <= 0) return;`）
问题：完全吸收时原版先把数量还原再 `discard()`（`.build-tools/mc-sources-1.21.11/net/minecraft/entity/ItemEntity.java:331-334`：`if (itemStack.isEmpty()) { this.discard(); itemStack.setCount(i); }`），于是差值为 0 直接 return，**只有背包放不下的半吸收才会写记录**。
影响：玩家走过去捡走整堆 64 颗钻石、或捡走刚拆箱子掉出的一地物品 → `co_item` 无任何行，`/co lookup u:<玩家> a:item` 查不到、`/co rollback` 也无法恢复；「谁拿走了我的东西」这个最常用场景直接失效。
修复：RETURN 处若实体已被 `discard()`（完全吸收）则记 `beforeCount`，否则记差值。三版本同改。
- 状态：**【FIXED】** `mixin/ItemEntityPickupMixin.java:70`（`taken <= 0` 时若 `isRemoved()` 则按 `beforeCount` 记录，整堆被捡走不再漏记）

**E2 ✅ 已复核 · 三版本共有 · blocker（火焰烧毁不留记录）**
`CoreProtectFabric.java:52-54`（`SET_STATE_CAUSES = Set.of("#explosion","#creeper","#tnt","#wither","#end_crystal","#enderman","#piston","#water","#lava","#fluid")` 里**没有 `#fire`**；`BREAK_CAUSES`（:55-57）里才有，而 break 钩子在 :240 会跳过火方块）
问题：原版火焰是**替换**燃料方块（`.build-tools/mc-sources-1.21.11/net/minecraft/block/FireBlock.java:195`：`world.setBlockState(mutable, this.getStateWithAge(world, mutable, r), Block.NOTIFY_ALL);`，由 :186-187 的 `getBurnChance > 0` 守卫），因此 `hookSetBlockState` 在 :206 直接返回不记录。
影响：岩浆/闪电/蔓延的火烧掉玩家木屋 → `/co lookup a:#fire` 只剩极少数草/树苗的 `removeBlock` 路径，木板/原木/羊毛一条都没有，**经典纵火破坏既看不见也回不了档**。
修复：把 `#fire` 加进 `SET_STATE_CAUSES`；**不要**顺手加 `#decay`（树叶走 `removeBlock`，会重复记录）。
- 状态：**【FIXED】** `CoreProtectFabric.java:60`（`SET_STATE_CAUSES` 加入 `#fire`）+ `:293,327`（火方块自身的状态变化仍被过滤，避免重复记录）

### 4.3 P1 — 高危

| # | 复核 | 位置 | 问题与影响 | 修复 |
|---|---|---|---|---|
| A2 | ✅ | `database/DatabaseManager.java:1207-1210` + `command/CoCommand.java:419-426` | `purge` 失败被折叠成 `0L`（`return result == null ? 0L : result;`），而调用方判的是 `rows == null`（装箱后永不为 null）→ **删除失败却提示「已清理 0 行」**，DELETE 还在后台跑，随后又叠一次 VACUUM | 失败改为抛异常或返回 `Long` 并让调用方报错 **【FIXED】** `database/DatabaseManager.java:1269`（purge 返回 Long，失败为 null）+ `command/CoCommand.java:450-453`（null 报 coreprotect.error.db）|
| B2/A8 | ✅ | `command/LookupService.java:62-73` | 非玩家来源走**内联**分支，控制台/RCON／命令方块都在服务器线程 → `/co purge t:30d` 可让主线程**冻结最多 60 秒**（`:1369` 的 60s `get()`），注释却声称「不阻塞 tick」 | 非玩家也走 `submitRead`，用 `server.execute` 回投结果 **【FIXED (deviating)】** `command/LookupService.java:62,87-93`（非玩家同步等待上限 10 s，超时把结果补投服务器日志）；偏离：未改成纯异步——原版在命令返回时冲刷非玩家的输出缓冲，纯异步会让控制台/RCON 什么都不打印 |
| B3 | ✅ | `config/CoreProtectConfig.java:61-66` | `lookupLevel` 默认 **0 = 所有人**，而聊天/命令记录默认开启 → 任何普通玩家可 `/co lookup u:<某人> a:chat t:30d`（全部聊天）、`a:command`（含 `/msg` 私聊与 `/login <密码>` 之类）、`a:container`（谁在哪个坐标存取什么）、任意坐标的方块史（找隐藏基地）；`/co status` 还打印数据库路径 | 默认改 1，或把信息类命令改为显式授权 **【FIXED】** `config/CoreProtectConfig.java:66`（lookupLevel 默认 0 → 1）|
| B4 | ✅ | `command/ParamParser.java:69-80` | `r:`/`p:` 只校验 > 0，**上限 2147483647**，命令路径无任何限流 → `/co lookup p:2000000000` 让 SQLite 走完整个索引，`r:2000000000` 退化成全表扫描；循环发送可打满读线程与磁盘 | `Math.min(r, 1000)`、`Math.min(p, 1000)` 并加限流 **【FIXED】** `command/ParamParser.java:14-16,79,88`（r:/p: 上限 1000）|
| D2 | ◻ | `database/DatabaseManager.java:645-655` | `close()` 超时（5 分钟）后**由调用线程**执行 `closeStatements()`/`conn.close()`/`conn = null`，而 worker 线程仍在 `drainWrites()`/`flushWrites()` 里用同一个非 volatile 的 `conn` 和 HashMap 语句缓存 → 并发修改/已关闭语句/NPE，进而触发 D1 丢行 | 超时只记日志，绝不在非 writer 线程碰 `statements`/`conn` **【FIXED】** `database/DatabaseManager.java:698-705`（超时只记日志，不再由调用线程关连接）|
| D3 | ◻ | `database/DatabaseManager.java:219,624,1467` | `close()` 里 `worker.shutdown()`/`readPool.shutdown()` 是永久的，而 `DatabaseManager` 是 `CoreProtectFabric` 的 final 字段 → **集成服务器换世界（回标题再进存档）后 `open()` 抛 `RejectedExecutionException`**，此后所有写入静默丢弃、查询全报错、`/co rollback` 静默无效 | `open()` 时若执行器已关闭则重建 **【FIXED】** `database/DatabaseManager.java:220-233,261`（newWriter/newReadPool + ensureExecutors 在 open() 里调用）|
| C2 | ◻ | `rollback/RollbackManager.java:130-134` + `event/BlockEventListener.java:126` | 每个坐标**只应用最新一条日志**，且放置方块时 `oldData` 恒记为 air → 破坏者先打掉告示牌（sign→air）再在原位放石头（air→stone），回档只把石头变成空气，**告示牌与文字永久丢失** | 按时间倒序逐条回放同一坐标的所有行；放置时记录真实旧状态 **【FIXED】** `rollback/RollbackManager.java:435-452`（同坐标逐行成链回放）+ `event/BlockEventListener.java:131-140`（deferredPlace 记录交互前真实旧状态）|
| C3 | ✅ | `database/DatabaseManager.java:1304-1324` | `r:` 半径的 SQL **没有任何世界条件**（全库仅 `:898/:946/:1025` 三处单点查询带 `wid`）→ 主世界 `/co rollback r:10 u:X t:30m` 会**同时回档下界/末地/自定义维度中同坐标的方块**，`/co lookup r:10` 也会把多个维度混进一页 | 使用半径时补上来源世界的 `wid` 条件 **【FIXED】** `database/Criteria.java:26-30`（新增 world 字段）+ `command/CoCommand.java:423-431`（applyRadius 填入来源维度）|
| C4 | ◻ | `rollback/RollbackManager.java:127-128` | `rollback.maxBlocks`（默认 5000）只对 `co_block` 生效，`queryContainers(c, limit, 0)` 的结果**不检查长度** → 5 万条容器记录里只回档最新 5001 条，却报告「Rollback complete」 | 容器列表套用同一个上限判断 **【FIXED】** `rollback/RollbackManager.java:223,239`（容器/签名/物品/实体四类都套 maxBlocks，超出报 too_many）|
| C5 | ◻ | `container/ContainerTracker.java:97-98` + `rollback/RollbackManager.java:335,304` | 容器记录**只存物品 id + 数量总和，NBT 全部丢失** → 附魔/改名/带内容的潜影盒回档后变成白板物品、空盒子 | 按行保存序列化 `ItemStack`，用 `ItemStack.CODEC` 还原 **【FIXED】** `util/ItemStackCodec.java:31,53`（SNBT 编解码）+ `database/DatabaseManager.java:306`（co_container.nbt 列）+ `rollback/RollbackManager.java:552-578`（按 nbt 还原模板栈）|
| C6 | ◻ | `rollback/RollbackManager.java:175-179,190,200-203` + `CoreProtectFabric.java:173-183` | 任何异常退出都跳过 `finish()` → 世界已改了一半却**不写 undo 记录**；`/stop` 时被重新调度的切片不会执行，`onServerStopping` 也不收尾 → 重启后 `/co undo` 提示「没有可撤销的操作」 | 关服/异常时收尾并增量落盘 undo 数据 **【FIXED】** `rollback/RollbackManager.java:81,293-306`（SERVER_STOPPING → shutdown 收尾）+ `:799-844`（undo 落盘 coreprotect-fabric-undo.json）|
| D4 | ✅ | 同 A1 | （合并进 A1） **【FIXED】**（随 A1：`DatabaseManager.java:150,1194,1479-1483` 同池内联，不再自锁） | — |
| F1 | ✅ | `.build-tools/modrinth/probe6.ps1:81-82` | 「已验证的发布流程」**故意上传两次**以测两种 Content-Type → 每次发布都会多出一个 Modrinth 版本，删除步骤只写在注释里（v1.9.2 发布时就手工删过 `Y8M58JCT`） | 从发布路径里去掉重复上传那次 **【FIXED】** `.build-tools/modrinth/probe6.ps1:81-86`（一个 payload 只发一次，ExplicitType 分支已删）|
| F2 | ◻ | `.build-tools/modrinth/publish.ps1:449-450` | 对**非幂等**的 `POST /version` 做 3 次盲重试，重试前不复查是否已创建 → 响应丢失（正是那个 405）时产生重复版本 | 重试前重新列版本，已存在则中止 **【FIXED】** `.build-tools/modrinth/publish.ps1:510-522`（重试前 GET /project/{id}/version 复查，命中即视为成功并 break）|
| F3 | ◻ | `.build-tools/release-v192.ps1:46-49` | **先删 GitHub 上的旧 jar 再传新的**，中间无 try/catch → 中途失败会让线上 release 一个 jar 都没有 | 先传新资源，成功后再删旧的 **【FIXED】** `.build-tools/release-v192.ps1:108-123`（先上传新 asset，成功后再删同名旧 asset）|
| F4 | ◻ | `.build-tools/release-v192.ps1:26,68` | 缺 jar 只打印 `MISSING JAR` 并 `continue`，结尾照样 `publish done`、退出码 0 → **三平台少发一个也算成功** | 收集缺失项，最后 `throw` **【FIXED】** `.build-tools/release-v192.ps1:73-83`（预检收集 $missing 后 throw，不再打印 MISSING JAR 后照样成功）|
| F5 | ◻ | `.build-tools/modrinth/publish.ps1:457-477` | 某个版本失败不打断循环，前面的版本已经上线 → **半更新状态**（1.21 已发、26.1.2 缺失） | 首个失败即中止后续 **【FIXED】** `.build-tools/modrinth/publish.ps1:515-535`（首个版本失败即 throw，剩余构建不再发布）|
| F6 | ◻ | `.build-tools/modrinth/publish.ps1:228-231` | 上传前**只判断 jar 是否存在**，不算哈希、不比对内嵌版本、不查新鲜度（`$sizeMb` 算了却没用）→ `build/libs` 里的旧 jar 会被当成新版本上传 | 上传前校验 SHA-256 与 `fabric.mod.json` 版本号 **【FIXED】** `.build-tools/modrinth/publish.ps1:235-304`（SHA-256 + jar 内版本 + gradle.properties 漂移 + 源文件新鲜度四项校验）|
| F7 | ◻ | `.build-tools/release-v192.ps1:38` | 打 tag 直接指向 `main` HEAD，**不检查工作区是否干净、不校验 tag 与 jar 版本一致**，且 `draft = false`（立即公开） | 要求 `git status --porcelain` 干净并断言 jar 版本 == tag **【FIXED】** `.build-tools/release-v192.ps1:53-62,64-84,103-116`（干净工作区 + jar 内版本 == -Version + draft→上传→发布）|

| E3 | ◻ | `container/ContainerTracker.java:39-40` | 大箱子（`DoubleInventory`，共 54 格）**只在被点击的那半**建立监视并重读同一半（:73-74）→ 打开左半、把物品 shift 点进右半，或漏斗/第二个玩家动右半，**完全不留记录**；回档也只还原一半 | 监视时解析配对的另一半并一起快照/比对 **【FIXED】** `container/ContainerTracker.java:180-190`（containerAt 用 ChestBlock.getInventory 取合并的 54 格）+ `:54,115,150`（快照与比较都走它）|
| E4 | ◻ | `container/ContainerTracker.java:82-83` | 界面打开期间观察到的**所有**增减都记在看界面的人名下 → 同一时间管理员跑 `/co rollback`（`RollbackManager.java:269/342-378` 会写库存）、漏斗/发射器喂料、第二个玩家动另一半，都会写成「A 取走了 64 钻石」→ **栽赃给无辜玩家**，之后的查询与回档都指向他 | 标记内部改动（回档/漏斗/发射器）并在窗口内跳过，或改为「只在屏幕真正打开期间比对」 **【FIXED】** `container/ContainerTracker.java:90-97`（新增 rebaseline/rebaselineAt）+ `rollback/RollbackManager.java:429,583`（回档、undo 写库存后立即刷新基线）+ `HopperTracker.java:140`/`DispenserTracker.java:119`（写记录后刷新）|
| E6 | ◻ | `CoreProtectFabric.java:238` + `:214`、`util/NaturalBreakCause.java:52-54` | 末影人搬方块被记**两次**：`EndermanEntity.java:460` 的 `world.removeBlock(...)` 触发 break 钩子（记 old→AIR），内层 `setBlockState` 又以同一 old 状态进 set 钩子，而 `alreadyLoggedBreak`/`alreadyLoggedSet` 是两本互不相通的账本 | 让两本账本互相检查（break 记过的 pos+old 在 set 钩子里跳过） **【FIXED】** `CoreProtectFabric.java:276` + `util/NaturalBreakCause.java:64`（alreadyLoggedSet 跨账本判重）|
| E5 | — | 同 B5 | （合并进 B5） **【FIXED】**（随 B5：`util/Permissions.java:51-58` levelCheck fail-closed + `config/CoreProtectConfig.java:258-263` 越界夹取） | — |
| E7 | — | 同 B8 | （合并进 B8；另注：伪造的告示牌文字会被 `RollbackManager.java:270` 的 `applySignText` 写到现存告示牌上） **【FIXED】**（随 B8：`mixin/ServerPlayNetworkHandlerMixin.java:48-50` 改挂 onSignUpdate RETURN）+ 告示牌文字已可回档：`rollback/RollbackManager.java:495,627-632` | — |

### 4.4 P2 — 中危

| # | 复核 | 位置 | 问题与影响 | 修复 |
|---|---|---|---|---|
| A3 | ✅ | `database/DatabaseManager.java:1243` | 源码里那一行是**一个字面 `\n` 写在注释中**，`closeStatements();` 被吞进注释 → 该方法从未执行，`incremental_vacuum` 在语句缓存仍打开时运行（全库仅这一处出现字面 `\n`；v1.9.2 我加「修复」时引入） | 拆成三行真代码 **【FIXED】** `database/DatabaseManager.java:1317`（`closeStatements();` 已是真代码行，VACUUM 前先关语句缓存）|
| A4 | ◻ | `database/DatabaseManager.java:1236,813-814,1428-1429` | purge 的字典 GC 会删除无引用的 `co_state/co_name/co_world` 行，但写入端缓存**从不失效**且命中后不复查存在性 → 之后写入的行指向已删除的字典 id（显示空白/过滤匹配不到，重启仍在） | GC 后清空四个缓存 **【FIXED】** `database/DatabaseManager.java:1301,1306`（purge 删字典后 invalidateDictionaries 清空 nameIds/worldIds/actionIds/stateCache）|
| B5 | ✅ | `util/Permissions.java:48-56` + `config/CoreProtectConfig.java` | `levelCheck` 对 1..4 之外的值一律返回 `ALWAYS_PASS_CHECK`，而 `load()` 会夹取很多数值字段却**从不校验 `lookupLevel`/`adminLevel`** → 写 `adminLevel: 5`（或 0）等于把 `/co purge`、`/co rollback` 发给所有人 | 校验范围：adminLevel 1..4（越界回落 4），lookupLevel 0..4 **【FIXED】** `util/Permissions.java:51-58`（case 0 → ALWAYS_PASS、越界 default → OWNERS_CHECK）+ `config/CoreProtectConfig.java:258-263`（加载时夹取）|
| B6 | ✅ | `util/Permissions.java:70-79` | 权限组按**玩家名**（`equalsIgnoreCase`）匹配而非 UUID → 离线模式服务器上，任何人用配置里成员的名字登录即继承该组，而默认 `admin` 组权限是 `all` | 改用 UUID，并在非正版模式下忽略权限组 **【FIXED】** `util/Permissions.java:84-90,106`（sameUuid 匹配；名字匹配仅在 online-mode 生效）|
| B7 | ◻ | `event/SessionEventListener.java:40-49` + `inspect/Inspector.java:33-37,64` | 退出时只清容器追踪，**不清 `Inspector.active`**，使用时也不再复核权限 → 玩家 `/co inspect` 后重登仍持续看到方块/容器记录；`/co reload` 提高 `lookupLevel` 后同样不受影响；UUID 集合只增不减 | 断线时 `inspector().disable(player)`，并在使用处复核节点 **【FIXED】** `inspect/Inspector.java:41,74`（复核 `Permissions.NODE_INSPECT`）+ `event/SessionEventListener.java:59`（断线 disable）|
| B8 | ✅ | `mixin/ServerPlayNetworkHandlerMixin.java:21,30` | 命令与告示牌都在 `@At("HEAD")` 记录**未经校验的原始包内容**、且无限流 → **伪造审计记录**（任意「命令」记在自己名下；任意坐标写告示牌文字，哪怕那里没有告示牌），可栽赃或污染日志 | 改 `@At("RETURN")` 只记录真正执行成功的动作，并按玩家限流 **【FIXED (deviating)】** `mixin/ServerPlayNetworkHandlerMixin.java:38,48-50`（executeCommand/onSignUpdate 均改 RETURN）+ `CoreProtectFabric.java:222`（allowPacketLog 限流 60 条/分钟）；偏离：限流是事后丢弃——RETURN 处无法阻止执行，被拒绝的命令与蜡封告示牌仍会进记录 |
| C7 | ◻ | `rollback/RollbackManager.java:125,128` | 回档**只读 `co_block` 与 `co_container`**，实体击杀（`EntityEventListener.java:53`）与物品掉落（`PlayerEntityDropMixin.java:39`）虽然记录了却永不恢复，命令仍报成功 | 把 `co_entity`/`co_item` 纳入回档与 undo 计划 **【FIXED (deviating)】** `rollback/RollbackManager.java:233-248`（co_item/co_entity 已进计划，undo 同路径）+ `:523,536`（物品行找不到实体栈、实体击杀行一律 job.skipped++）；偏离：co_entity 的击杀行故意不复活白板实体，只计数不还原 |
| C9 | ◻ | `mixin/ServerPlayNetworkHandlerMixin.java:36-43` + `rollback/RollbackManager.java:270` | 告示牌编辑只写 `co_sign`，回档只读 `co_block.meta` → **被改写的告示牌文字永远回不来**（`/co lookup a:sign` 却看得到） | 把 `co_sign` 纳入回档计划 **【FIXED】** `rollback/RollbackManager.java:495,627-632`（resolveSignTargets 按坐标成链）+ `util/BlockStateUtil.java:141`（replaceSignText 返回旧文本，undo 可还原）|
| C10 | ◻ | `rollback/RollbackManager.java:230-234,329-332` | 目标位置已不是容器时直接跳过，**不计入 `job.skipped`** → 输出「containers: 0, skipped: 0」但该位置的物品其实没恢复 | 跳过前 `job.skipped++` **【FIXED】** `rollback/RollbackManager.java:400,410,424,448,456,466,491,511,517,523,536`（包括「目标已不是容器」的 400/410 分支在内，各跳过分支都计数）|
| C11 | ◻ | `container/ContainerTracker.java:40,92-101` | 大箱子只快照**被点击的那半（27 格）**，GUI 却能操作全部 54 格 → 只动另一半时**完全不写记录**，事后无法查询/回档 | 快照时解析配对的另一半 **【FIXED】** `container/ContainerTracker.java:180-190`（containerAt 合并双箱 54 格）+ `:54,115,150`（监视、比较、基线全用合并库存）|
| C12 | ◻ | `rollback/RollbackManager.java:41,200-203` | undo 记录**只在内存、且每人一份**：重启后 `/co undo` 失效；慢回档 + 快回档叠加时后写覆盖先写 → 撤错操作 | 落盘 undo 记录，且同一玩家有进行中的任务时拒绝覆盖 **【FIXED】** `rollback/RollbackManager.java:205,309`（isRunning 拒绝同一玩家的新任务）+ `:799-844`（undo 落盘 coreprotect-fabric-undo.json）|
| C13 | ◻ | `container/ContainerTracker.java:73-74` + `container/HopperTracker.java:74-78` | 关闭 GUI 时容器已消失（被炸/被拆）→ `return` 直接丢掉整份差异；漏斗/发射器跨区块重载或超过 `MAX_SNAPSHOTS` 后只重设基线 → 这些转移**不留记录** | 保留上次已知内容，只在真正卸载时重设基线 **【FIXED】** `container/ContainerTracker.java:138,150`（容器消失时按最后已知内容记 withdraw）+ `HopperTracker.java:148-151`/`DispenserTracker.java:127-130`（过期优先淘汰到 90%，不再 clear）|
| C14 | ◻ | `command/CoCommand.java:389-391` | 非玩家来源使用 `r:` 时中心点**静默取 (0,0,0)** → 控制台 `/co rollback r:10 …` 打的是原点周围（再叠加 C3，会打到所有维度） | 无玩家时拒绝 `r:`，或要求显式坐标 **【FIXED】** `command/CoCommand.java:423-431`（applyRadius 对非玩家来源报 `coreprotect.error.radius_needs_player`）|
| D6 | ◻ | `event/SessionEventListener.java:23` | 玩家**进服时同步阻塞读取语言**（`executeRead` → `get(30s)`）→ 读池被占满或磁盘卡顿时，JOIN 处理把服务器线程挂住最多 30 秒 | 语言改为缓存 + 异步刷新 **【FIXED】** `i18n/Translator.java:113-118`（cachedUserLanguage/cacheUserLanguage）+ `event/SessionEventListener.java:27,31`（进服先读缓存，再用 submitRead 异步刷新）|
| D7 | ◻ | `database/DatabaseManager.java:1601` | 异步回调 `server.execute(() -> callback.accept(result))` **没有 try/catch** → 回调里抛异常不再被命令框架兜住（内联分支是包了 try/catch 的），管理员看不到红色报错只见控制台噪声 | 回调包 try/catch 并记日志 **【FIXED】** `database/DatabaseManager.java:1730-1750`（server == null 记 WARN「Query result discarded…」，回调体包 try/catch）|
| D8/B10 | ✅ | `database/DatabaseManager.java:1466-1477` | `!open` 直接 `return`、`scheduleFlush` 遇 `RejectedExecutionException` 只把标志复位 → 关服窗口内的行**既不写入也不计数**；`droppedWrites` 只统计队列满，`writeStats()` 全项目无人调用 → 运维看不到丢失 | 这些路径也累加 `droppedWrites` 并记日志 **【FIXED】** `database/DatabaseManager.java:1582,1586,1593`（未 open 与队列满都走 countDropped）+ `:1720`（writeStats）+ `command/CoCommand.java:273`（/co status 新增 coreprotect.status.writes 行）|
| F8 | ◻ | `coreprotect-fabric-1.21/build.gradle:2` | Loom 固定成 `1.8-SNAPSHOT`（另两个版本用正式版 1.17.19）→ 主推版本**构建不可复现**，上游清理快照后直接构建失败 | 固定为正式版本 **【FIXED】** `coreprotect-fabric-1.21/build.gradle:2`（`fabric-loom` 1.8-SNAPSHOT → 1.8.13）|
| F9 | ◻ | `coreprotect-fabric-1.21/build.gradle:24`（三版本同） | 内嵌 `sqlite-jdbc 3.46.1.0`（SQLite 3.46.1）：CVE-2025-6965（内存破坏，3.50.2 修复）、CVE-2025-29087（`concat_ws()` 整数溢出，3.49.1 修复）→ 每个装了本模组的服务器都带着这份原生库；可达性取决于是否存在攻击者可影响的 SQL（本次未证实） | 三版本升级到 ≥ 3.50.2.0（需实测兼容） **【FIXED】** `coreprotect-fabric-1.21/build.gradle:24-25`、`coreprotect-fabric-1.21.11/build.gradle:24-25`、`coreprotect-fabric-26.1.2/build.gradle:23-24`（sqlite-jdbc 3.46.1.0 → 3.50.2.0）；SQLite 兼容性未在服务器实测（见 §7）|
| F10 | ◻ | `.build-tools/modrinth/versions.json:9` | 版本号在 `versions.json` 里**重复一份**，`bump-version.ps1` 不会更新它 → 下次改版本后要么报「缺 jar」，要么**把旧 jar 当新版本上传** | 从 `mod_version` 生成，或纳入 bump 脚本 **【FIXED】** `.build-tools/bump-version.ps1:37-58`（同一轮同步 `modrinth/versions.json`，残留 `version_number` 直接 throw）|
| F11 | ◻ | `.build-tools/test-full.ps1:15,24` | 按「命令行包含 `$WorkDir`」杀 java 进程，并对该参数拼出的路径做 `Remove-Item -Recurse -Force`，参数未校验 → 传短路径会**误杀别的 JVM、误删目录**（本机正好有其它会话在跑） | 校验 `$WorkDir` 必须在仓库内，进程按完整路径匹配 **【FIXED】** `.build-tools/test-full.ps1:2,9-17`（解析后必须位于 `$root` 下，否则 throw）+ `:23-25`（按完整路径 `IndexOf` 匹配 java.exe，不用通配）|
| F12 | ◻ | `.build-tools/new-version.ps1:10-11` | 用未校验的 `-Version` 拼路径后 `Remove-Item -Recurse -Force`，只判 `Test-Path` → 脚本被复制到别处运行时可能删掉无关目录 | 删除前断言目标在 `$root` 下且名字符合预期 **【FIXED】** `.build-tools/new-version.ps1:12-23`（`-Version` 正则校验，`-Src`/`$dest` 必须解析到 `$root` 内否则 throw）|
| F13 | ✅ | `.build-tools/check-ascii.ps1:17-22` | 失败分支只打印、**没有 `exit 1`** → 这个「发布前必过」的 ASCII 门禁**永远退出 0**，只能靠 CI 里另写的 grep 兜底 | 失败分支加 `exit 1` **【FIXED】** `.build-tools/check-ascii.ps1:23`（失败分支现在真的 exit 1）|
| F14 | ◻ | `.gitignore:57` | 只按精确路径忽略 `.build-tools/modrinth/token.txt`，而脚本用 `$PSScriptRoot` 相对查找 → 工具被复制/改名后 token 变成「未跟踪但未忽略」，一次 `git add -A` 即泄露 PAT | 全深度忽略 `token.txt`、`*.pat` **【FIXED】** `.gitignore:60-61`（改为裸名 `token.txt` / `*.pat`，任意深度匹配）|
| F15 | ◻ | `.build-tools/release-v192.ps1:11-12`（另 6 个脚本同） | 用 `git credential fill` 把 GitHub PAT **明文落到 %TEMP%**，清理是 best-effort（`-ErrorAction SilentlyContinue`）→ 删除失败就长期留在磁盘 | 改走管道读取（`publish-bilingual.ps1:3` 已是正确写法） **【FIXED】** `.build-tools/release-v192.ps1:37-46`（`git credential fill` 走管道，`%TEMP%\ghcred-*` 落盘代码已删；其余 11 个脚本同样处理，全仓 grep 只剩注释里的历史说明）|
| F16 | ◻ | `.build-tools/release-pairs.json:23`、`bump-version.ps1:2` | 工具脚本里硬编码的版本停在一两个版本前，且老发布脚本仍可运行 → 误跑 `release-v191.ps1` 会用当前 `build/libs` 里的 jar **重传旧 release**；bump 脚本默认 `1.8.2→1.9.0`，失败只打印 `MISSING` 仍报 `VERSION_BUMP_DONE` | 版本改为参数、必需化；废弃脚本删除或加保护 **【FIXED】** `.build-tools/bump-version.ps1:6-7`（`-From`/`-To` 均为 Mandatory）+ `release-v191.ps1:3-6`（v181-mc/v182/v190 同：默认 throw，只有显式 `-AllowHistoricalRerun` 才能重跑旧发布）|
| F17 | ◻ | `.github/workflows/build.yml:28,31,37,46` | 所有 action 按**可变 tag** 引用（`@v4`）而非 commit SHA → 上游被劫持/重打 tag 时会在构建里执行（影响面被 `contents: read` 限制，但产物不受保护） | 固定到 SHA **【FIXED】** `.github/workflows/build.yml:30,33,39,48,60`（checkout / setup-java / setup-gradle / upload-artifact 全部固定到 commit SHA 并附版本注释）|
| F18 | ◻ | `.github/workflows/build.yml:50` | 没产出 jar 也 `if-no-files-found: warn` → **构建失败仍然绿**，直到发布才发现 | 改为 `error` **【FIXED】** `.github/workflows/build.yml:53`（`if-no-files-found: error`）|
| D9 | ◻ | `database/DatabaseManager.java:677-686` | `closeReadConnections()` 会关闭**正在使用**的连接（查询中途失败），且 `generation` 检查与 `add`、`clear` 之间存在竞态 → Windows 上连接未释放会锁住文件，`quickCheck()` 的损坏恢复可能失败（"logging is suspended"） | 加锁重查 generation，由读线程自己移除/关闭连接 **【FIXED (deviating)】** `database/DatabaseManager.java:712-730`（close 先 `readPool.shutdown()` + `awaitTermination(5 s)`，再 `generation.incrementAndGet()` 后按代关闭读连接）；偏离：没有引入锁 + 读线程自移除，仍存在「单条查询超过 5 s」的窄窗口（见 §7）|
| D10 | ◻ | `config/CoreProtectConfig.java:198` + `command/CoCommand.java:435` | `/co reload` 就地改非 volatile 的配置对象（写线程同时在读 `batchIntervalMs`/`batchMaxRows`，无 happens-before），且**改 `databaseFile` 静默无效**（`dbPath` 只在 `open()` 时解析，写入仍进旧库） | 用 volatile/不可变快照发布配置；`databaseFile` 变更需重开或明确告警 **【FIXED】** `config/CoreProtectConfig.java:130-147`（Database 的数值/字符串/布尔字段全部 `volatile`）+ `:302`（改 `database.file` 记 WARN「restart 才生效」）|

| E8 | ◻ | `container/ContainerTracker.java:83,92-101` | 快照只按物品 id 计数（`Registries.ITEM.getId(stack.getItem())`）→ 同 id 同数量的**互换**（附魔/改名/耐久不同的剑）差值为 0，`continue` 跳过：偷换完全查不到，也永远回不了附魔 | 快照键改为序列化后的 `ItemStack`（组件+NBT+耐久） **【FIXED】** `container/ContainerTracker.java:47`（Tally 以组件敏感 SNBT 串为键）+ `util/ItemStackCodec.java:31,53` + `database/DatabaseManager.java:61,306`（co_container.nbt 列）+ `rollback/RollbackManager.java:552-578`（按 nbt 还原模板栈）|
| E9 | ◻ | `container/HopperTracker.java:57,80-89` | 玩家自己打开漏斗存取物品时，漏斗自身库存的每 tick 差分会记一条 `#hopper`，关界面时又记一条玩家名 → 同一动作两条归属矛盾的记录（`/co lookup u:#hopper` 虚增、`/co rollback u:<玩家>` 漏掉） | 该位置存在 ContainerTracker 监视时跳过自身库存比对 **【FIXED】** `container/HopperTracker.java:73,110`（watchedAt 命中时 suppressLog，仅刷新基线）+ `container/DispenserTracker.java:89`（同）|
| E10 | ◻ | `container/HopperTracker.java:66` + `container/DispenserTracker.java:62` | 只把 `HopperBlockEntity` 从「外部输出」里排除 → 漏斗喂**发射器/投掷器**时 `#hopper` 与 `#dropper`/`#dispenser` 各记一条，物品被重复计数 | 输出类型排除 `DispenserBlockEntity`/`DropperBlockEntity`，交给 DispenserTracker **【FIXED】** `container/HopperTracker.java:77,83,94-99`（trackedSeparately 同时排除漏斗与发射器/投掷器）|
| E11 | ◻ | `mixin/WorldMixin.java:21`（`removeBlock` 同 :42-45） | 在 `@At("HEAD")` 记录且**从不看返回值** → 原版拒绝的写入（`World.java:249-263` 的 `!isInLoadLimit`、`blockState == null`）也写成日志行（幽灵记录，回档时什么都不改） | 改为 RETURN 注入并用 `cir.getReturnValue() == true` 守卫 **【FIXED (deviating)】** `CoreProtectFabric.java:268,322`（钩子仍留在 HEAD，但内部复刻原版拒绝条件 `newState == null || !world.isInLoadLimit(pos) || world.isDebugWorld()`）；偏离：不做 HEAD→RETURN——RETURN 时 `getBlockState` 已返回新状态，会让每条自然破坏记录都带上错误状态 |
| E12 | ◻ | `event/BlockEventListener.java:99` | 只跟踪手持 `BlockItem` 的放置 → 水桶/岩浆桶/打火石/骨粉/刷怪蛋等放置**都不记录**（谁倒的岩浆烧了家查不到，只能看到随后自然蔓延的 `#lava`） | 在 onUse 捕获候选位置状态，让 :114-134 的延迟判定对任意手持物生效 **【FIXED】** `event/BlockEventListener.java:99-121`（去掉 `instanceof BlockItem` 限制，记录交互前状态后由 `deferredPlace` 判定）+ `:131-140`（逐格比对 before[]）|
| E14 | ◻ | `event/BlockEventListener.java:118` | 只比较「结果方块类型相同」来判定放置成功 → 站在石墙边右键箱子（原版只开 GUI）也会写一条假 `+stone`，之后回档会删掉玩家没放过的方块 | 改用 onUse 时捕获的旧状态判断（旧状态为空气/可替换才算放置） **【FIXED】** `event/BlockEventListener.java:115-116,131-140`（用交互前捕获的 `before[]` 旧状态判定；now 与旧状态相同或同方块类型则跳过）|
| E16 | ◻ | `util/NaturalBreakCause.java:79` + `CoreProtectFabric.java:227,245` | cause 窗口与其中的数据库调用**没有异常保护**：中途抛异常 → TAIL 不执行、`CURRENT` 长期残留（后续玩家破坏被记成 `#fire`/`#piston` 等自然原因），且异常会穿出 mixin **中止原版的世界改动** | hook 体包 try/catch（记日志并吞掉），并在 finally 里清理 cause（或改 HEAD+RETURN） **【FIXED】** `CoreProtectFabric.java:245-247,306-310,346-348`（三个入口 try/catch(Throwable) 并记日志，不再把异常抛进原版世界代码）+ `util/NaturalBreakCause.java:34,85`（`MAX_WINDOW_NANOS` = 5 s 的 pruneStale 看门狗，泄漏的 cause 自动丢弃）|

### 4.5 P3 — 轻微

| # | 复核 | 位置 | 问题与影响 | 修复 |
|---|---|---|---|---|
| A5 | ◻ | `database/DatabaseManager.java:1341-1347` | `e:<动作>` 第一个条件把原值直接喂给 `LIKE`（未加通配）→ `e:fire`/`e:tnt` **过滤不掉** `#fire`/`#tnt` | 第一条也用 `like` **【FIXED】** `database/DatabaseManager.java:1422-1428`（排除过滤器三处都传带 `%` 的 like；`:1426` 注释保留了原 bug 说明）|
| A6 | ◻ | `database/DatabaseManager.java:1155-1177` | 计数失败被吞成 0 → `/co status` 把有百万行的表显示成「0 rows」，看起来像数据没了 | 失败标记出来，显示「不可用」 **【FIXED】** `database/DatabaseManager.java:1212,1232`（计数失败返回 `-1L`「未知」，不再折算成 0）；`/co status` 对 -1 显示 `n/a` |
| A7 | ◻ | `database/DatabaseManager.java:1595-1601` | `server == null` 时读任务直接返回、**回调从不触发** → 玩家既没结果也没报错（仅启动/关服窗口，未证实） | 该分支也 `callback.accept(null)` **【FIXED (deviating)】** `database/DatabaseManager.java:1736-1738`（`server == null` 时记 WARN「Query result discarded - no server to deliver it to.」）；偏离：未改成 `callback.accept(null)`——此时没有 server 线程可投递回调，只做告警 + 日志 |
| A8b | ◻ | `command/LookupService.java:148` | `c.page == Integer.MAX_VALUE` 时页脚 `c.page + 1` 溢出成负数，提示的下一页命令是负页码 | 用 long 或封顶 **【FIXED】** `command/LookupService.java:181`（`nextPage` 封顶 `Integer.MAX_VALUE`，不再溢出成负数）|
| B9 | ◻ | `command/LookupService.java:332` + `event/MessageEventListener.java:17-20` | 聊天/命令/告示牌原文**原样输出**，`§` 与换行可伪造「CoreProtect - ...」样式行或隐藏真实内容（欺骗审计者；非代码执行，`Translator.format` 已吞 `IllegalArgumentException`） | 存储或渲染时剥掉 `§` 与控制字符 **【FIXED】** `command/LookupService.java:375`（新增 `plain()`：剥 `§` 及其后一个字符、`\u007f`、除 `\n`/`\t` 外的控制符）+ `:366,440`（聊天/命令/告示牌渲染都过它）|
| B11 | ◻ | `rollback/RollbackManager.java:177` | 异常原文回显给命令来源（含 JDBC 路径/SQL 状态），命令方块来源还会进服务器日志 | 日志记详细、给用户通用错误 **【FIXED】** `rollback/RollbackManager.java:251,329`（用户只收 `coreprotect.error.db`，异常原文只进 LOGGER）|
| C15 | ◻ | `util/BlockStateUtil.java:28-45` | 未知方块状态解析失败即当**空气**，`stringify` 兜底会写出无法解析的 Java `toString` → 卸载方块模组后，某些 restore 行会把现存的方块删掉而不是跳过 | 解析失败返回 null 并计入 skipped **【FIXED】** `util/BlockStateUtil.java:44`（`parseOrNull` 返回 null，`stringify` 兜底同样改 null）+ `rollback/RollbackManager.java:451-456`（解析失败 `job.skipped++`，绝不当作空气）|
| C16 | ◻ | `rollback/RollbackManager.java:266-269,394-404` | 只在目标是空气时倒出容器内容，且**直接掉在地上**（谁都能捡）；目标非空气时不倒（可达性未证实） | 只要被替换状态含方块实体就倒出，最好直接给执行者 **【FIXED (deviating)】** `rollback/RollbackManager.java:474,740-747`（替换任何带库存的方块前先 `ejectInventory`，优先 `giveStack` 给执行者、放不下才落地）；偏离：不再限定「目标为空气」，改为无条件先倒出 |
| F19 | ◻ | `coreprotect-fabric-26.1.2/src/main/resources/fabric.mod.json:26-27` | 运行期依赖宽到无意义（`fabricloader >=0.16.0`、`fabric-api: *`），与各版本实际编译依赖（loader 0.19.3）不一致 → 玩家可加载从未编译验证过的组合 | 每棵树下限按实际编译版本声明 **【FIXED】** 三棵 `src/main/resources/fabric.mod.json:26-27`（1.21 → loader >=0.16.9 / api >=0.102.0+1.21；1.21.11 与 26.1.2 → loader >=0.19.3，26.1.2 api >=0.155.2+26.1.2）|

| E18 | ◻ | `src/main/resources/coreprotect.mixins.json`（三版本同） | `injectors.defaultRequire: 0` 而 `required: true` 只管 mixin apply → 版本更新导致某个钩子方法改名时**注入失败只警告**，模组照常启动但方块/火/流体记录静默失效，服主看不到任何错误 | 改为 `defaultRequire: 1`（或逐注入器 `require = 1`） **【FIXED】** `coreprotect-fabric-1.21.11/src/main/resources/coreprotect.mixins.json`（`injectors.defaultRequire` 0 → 1，15 个注入目标逐个核对描述符）|
| E19 | ◻ | `i18n/Translator.java:120,143` | 缺键时直接打印键名（更新后新增的键对老语言包全部裸露）；且**所有**单引号一律转义成 `''`，含引号的译文被 MessageFormat 当引用文本原样输出（占位符不替换） | 缺键记一次警告；只转义不平衡的单引号 **【FIXED (deviating)】** `i18n/Translator.java:42,143`（缺键只 WARN 一次，`warnedKeys` 去重）+ `:183`（`escapeQuotes` 保留全量转义，只把译者已写的 `''` 当字面引号）；偏离：没有改成「只转义不配对引号」——那样 `"player's name's {0}"` 这类多引号译文仍会被吞掉 |
| E20 | ◻ | `config/CoreProtectConfig.java:226-236` | `maxLines`/`defaultTimeSeconds` 只有下限没有上限（`/co reload` 同样接受）→ 配置写 `maxLines: 2000000000` 后每次 `/co lookup`/`/co inspect`（`CoCommand.java:303`、`LookupService.java:85`、`Inspector.java:69,121`）都构造百万行页面并全库扫描，主线程卡死 | 上限夹取（maxLines 1..1000，defaultTimeSeconds 不超过保留窗口） **【FIXED】** `config/CoreProtectConfig.java:243-253`（`MAX_ROWS` = 1000 夹取 maxLines、`MAX_DEFAULT_TIME_SECONDS` 夹取 defaultTimeSeconds）+ `:49,51`（默认 604800 s / 10 行）|

### 4.6 我的复核记录与局限

- **已亲自读源码确认（✅，12 条）**：`Permissions.java`（B1/B5/B6）、`Config.java:61-66`（B3）、`ParamParser.java:69-80`（B4）、`DatabaseManager.java:1377-1379`（A1）、`:1207-1210` + `CoCommand.java:419-426`（A2）、`:1243`（A3）、`:1509-1534`（D1）、`:1466-1477`（D8）、`:1304-1324` + 全库 `wid` 检索（C3）、`ServerPlayNetworkHandlerMixin.java:21,30`（B8）、`RollbackManager.java:361-378`（C1）、`check-ascii.ps1:17-22`（F13）、`ItemEntityPickupMixin.java` 全文（E1）、`CoreProtectFabric.java:52-58` + `FireBlock.java:195`（E2）。
- 领域⑤子代理的覆盖声明：读完 14 个 mixin、`event/*`、`container/*Tracker`、`util/{NaturalBreakCause,Permissions}`、`config/CoreProtectConfig`、`i18n/Translator`、`util/Messages` 调用点、`CoreProtectFabric` 钩子与 cause 集合、三棵树的 `fabric.mod.json`/`coreprotect.mixins.json`、五个语言文件，并对照原版 `World`/`FireBlock`/`LeavesBlock`/`FlowableFluid`/`ExplosionImpl`/`EndermanEntity`/`PistonBlock`/`ItemEntity`/`ChestBlock`/`ServerPlayNetworkHandler`；仍**未**检查 `EntityEventListener` 的各回调（实体死亡、载具放置/破坏、物品展示框、漏斗矿车）、`BlockStateUtil` 内部解析、聊天渲染与 SQLite schema/purge/索引行为。
- 「已复核」= 我读了被引用的代码行并确认行为；「◻」= 子代理引用了原文但我未逐行复核（修之前会再复核一次）。
- **未覆盖**：未编译、未启动服务器（全部为静态审查）→ 所有并发/回档结论都未在运行时复现；未审查 13 个历史版本目录、`.build-tools` 里 `probe4/5/7.ps1`、`make-icon.ps1` 等少数脚本、以及其它项目的代码。
- **明确判定为「没问题」的部分**（避免误伤）：无后门、无硬编码密钥（`apikey.txt`、`github-recovery-codes.txt`、`token.txt` 均未被 git 跟踪）、无 `git add -A`/强推/`Invoke-Expression`、工作流 `permissions: contents: read`；**SQL 全部参数化**（玩家名/世界名/所有过滤器都走 `bind`），未发现注入；写队列 FIFO 且字典 id 在行任务内解析（顺序正确）；`Translator.format` 有 try/catch（无格式化字符串漏洞）；更新检查器/bStats 只发匿名聚合数据且有超时。

### 4.7 领域⑤中没有条目正文的 3 条（E13/E15/E17）

- **E13：NOT PLANNED (no spec)** —— 全文检索确认 §4.2–4.5 没有 E13 的条目正文，只有 §5 把它写进子代理 C 的写入范围（位置与已并入 C2 的告示牌链式回放重叠）；没有可依据的原始建议，故不做（§6.5 同结论）。
- **E15：FIXED** —— `container/HopperTracker.java:148-151`、`container/DispenserTracker.java:127-130`（`prune()` 改为过期优先、快照超过 `MAX_SNAPSHOTS` = 20000 时淘汰最旧到 90%，不再整表 `clear()`）。本条没有 §4 条目正文，只按 §5 分工与 §6.3 的记录核对代码。
- **E17：FIXED** —— 与 E15 同一处 `prune()` 逻辑（同上两处行号）。本条同样没有 §4 条目正文。

## 5. 状态与后续

- 阶段：**用户已拍板「全修」**（m00961），开始实施：60 条全修，改 `coreprotect-fabric-1.21.11`（主版本）→ 移植 1.21 / 26.1.2 → 三版本编译 → `test-full.ps1` 定向回归 → 发布 v1.9.3 并自查 GitHub / Modrinth 页面。
- 实施分工（互不重叠的写入范围）：
  - 主代理：`database/DatabaseManager.java`（D1/A2/A3/A4/D2/D3/D7/D8/D9/A5/A6/A7）、`command/{LookupService,CoCommand,ParamParser}.java`（A1/B2/A8b/B9/C14/D10）、`util/Permissions.java`（B1/B5）、`config/CoreProtectConfig.java`（B3/B5/D10/E20）。
  - 子代理 A（回档）：`rollback/RollbackManager.java`、`util/BlockStateUtil.java`（C1/C2/C4/C6/C7/C9/C10/C12/C16/B11）。
  - 子代理 B（容器）：`container/{Container,Hopper,Dispenser}Tracker.java`（E3/E9/E10/E17/C11/C13/E15 + 为 E4 提供 `rebaseline` API）。
  - 子代理 C（事件/mixin）：`mixin/*`、`event/*`、`CoreProtectFabric.java`、`util/NaturalBreakCause.java`、`i18n/Translator.java`、`inspect/Inspector.java`、`resources/coreprotect.mixins.json`（E1/E2/E6/E11/E12/E13/E14/E16/E18/E19/B7/B8/D6）。
  - 子代理 D（工具链）：`.build-tools/*.ps1`、`.github/workflows/build.yml`、`.gitignore`、三份 `build.gradle`、三份 `fabric.mod.json`（F1–F19）。
  - 第二轮（主代理）：C5/E8（容器 NBT 序列化，含 `co_container` 列与回档读取）、E4（内部写入时 `rebaseline`），跨文件故放最后统一做。
- 给用户的三选一（m00918 报告）：①只修 P0 六条 ②P0 + 全部高危（22 条）③60 条全修 —— **用户选 ③**。
- 建议修复顺序（供拍板参考）：P0 六条（D1、A1、C1、B1、E1、E2）→ P1 里与「玩家能滥用/数据会丢」相关的（A2、B2、B3、B4、C2、C3、C4、C5、C6、D2、D3、E3、E4、E6）→ 发布脚本类（F1-F7）→ 其余。
- 修复后的验证计划（沿用既有约定）：三版本 `compileJava`/`build` → `test-full.ps1` 无头服务器 RCON 全量回归（含伪造记录、命令方块权限、跨维度半径、并发查询、批量失败回滚等定向用例）→ 视情况发布 v1.9.3 并自查 GitHub / Modrinth 页面。

## 6. 修复记录（v1.9.3 实施中）

统一针对 `coreprotect-fabric-1.21.11/`（1.21 与 26.1.2 的移植在本表完成后统一做）。所有改动**尚未编译、尚未实机验证**。

### 6.1 主代理批次（21 条，已改完）

| ID | 文件 | 改动 |
|---|---|---|
| D1 | `database/DatabaseManager.java` `flushWrites` | 提交失败不再丢行：取出的任务回队（含期间新到的），`invalidateDictionaries()` 清空字典缓存，失败后退避 1 s 重试；成功则清零退避 |
| A1 | `DatabaseManager.executeRead` | 新增 `ThreadLocal<Boolean> poolReader`，读池任务内再调用读查询（`counts` 等）直接内联执行，不再同池自锁 |
| A2 | `DatabaseManager.purge` + `CoCommand` | 签名改为 `Long purge(long)`（null = 失败），调用方检查 `rows == null` 并报 `coreprotect.error.db`，不再把失败报成「0 行」 |
| A3 | `DatabaseManager.incrementalVacuum` | 修掉注释里字面 `\n` 吞掉 `closeStatements();` 的写法，语句缓存现在真的在维护语句前关闭（VACUUM 不再报 `SQL statements in progress`） |
| A4 | `DatabaseManager.invalidateDictionaries` | 新增；purge 删字典后清空 `nameIds/worldIds/actionIds/stateCache`，不再复用已删除的 id |
| A5 | `DatabaseManager.appendBlockFilters` | `e:` 排除过滤器原来把原值（无 `%`）传给 `NOT LIKE`，`e:fire` 永远匹配不上 `#fire`；改为传 `like` |
| A6 | `DatabaseManager.counts/countsSequential` + `CoCommand.status` | 失败计数由 0 改为 -1，`/co status` 显示 `n/a`，不再把查询失败显示成「0 条」 |
| A7 | `DatabaseManager.submitRead` | `server == null` 时记 WARN「Query result discarded - no server to deliver it to.」；回调加 try/catch；`RejectedExecutionException` 记 WARN |
| D2 | `DatabaseManager.close` | 关库超时分支不再由调用线程硬关连接（只记日志），避免「连接已关、写入继续」 |
| D3 | `DatabaseManager.open`/`ensureExecutors` | `worker`/`readPool` 改为可重建（`newWriter()`/`newReadPool()`），`ensureExecutors()` 在 `open()` 里执行；关库后再开可用，`writeQueue` 非空时立即排空 |
| D7 | 同上 A7 | 回调异常不再静默 |
| D8 | `DatabaseManager.submitWrite/countDropped` + `CoCommand.status` | 未 open 或队列满都计入 `droppedWrites` 并限频告警；`/co status` 新增 `coreprotect.status.writes` 行（已提交批次 / 排队 / 丢弃） |
| D9 | `DatabaseManager.close`/`closeReadConnections` | 先关读池（`shutdown` + 等待 5 s）再关闭读连接，避免关掉正在使用的连接 |
| D10 | `config/CoreProtectConfig.java` | `Database` 字段全部 `volatile`（`/co reload` 与数据库 worker 之间建立可见性）；改 `database.file` 现在会告警「重启才生效」 |
| B1 | `util/Permissions.java` | 非玩家来源不再一律通过：走 `levelCheck(required).allows(source.getPermissions())`，命令方块/控制台按各自权限判定 |
| B5 | `Permissions.levelCheck` + `CoreProtectConfig.load` | `levelCheck` 显式 `case 0 -> ALWAYS_PASS`、越界 `default -> OWNERS_CHECK`（fail closed）；`load()` 里 `adminLevel`/`lookupLevel` 越界回落 4 |
| B6 | `Permissions.has` + `CoreProtectConfig.permissionGroups` | 权限组按 UUID 匹配（带/不带横线均可），纯名字只在 online-mode 服务器生效，离线模式不再能冒名进组 |
| B3 | `CoreProtectConfig.Permissions` | `lookupLevel` 默认 0 → 1（默认不再允许所有玩家查询全部聊天/命令/容器史） |
| B4 | `command/ParamParser.java` | `r:` 上限 1000、`p:` 上限 1000，避免全库扫描与页脚整数溢出 |
| B9 | `command/LookupService.java` | 新增 `plain(...)`：渲染聊天/命令/告示牌记录前剥掉 `§` 与其后字符、`\u007f` 及除 `\n`/`\t` 外的控制字符，杜绝伪造「CoreProtect - …」样式行 |
| A8b | `LookupService` 页脚 | `c.page + 1` 溢出改为封顶 `Integer.MAX_VALUE` |
| C3 | `DatabaseManager.appendCommonFilters` + `Criteria` + `CoCommand` | `Criteria` 新增 `world` 字段；带半径的查询补来源维度条件（原本跨维度误伤）；由 `CoCommand.applyRadius` 从玩家所在世界填入 |
| C14 | `command/CoCommand.applyRadius` | 非玩家来源使用 `r:` 时直接报错 `coreprotect.error.radius_needs_player`，不再静默以 (0,0,0) 为中心 |
| B2 | `command/LookupService.query` | 控制台/RCON/命令方块：同步等待上限 10 s，超时提示「查询仍在运行，结果会写入服务器日志」并把结果补投到控制台（既不冻结 60 s，也不丢输出） |

新增 3 个语言键（5 个语言文件全部补齐，均为合法 UTF-8 JSON）：`coreprotect.status.writes`、`coreprotect.error.radius_needs_player`、`coreprotect.error.db`（后者此前**完全缺失**但有 4+ 处调用）。

### 6.2 子代理 A（回档，`rollback/RollbackManager.java` 930 行 + `util/BlockStateUtil.java` 174 行）

C1（不再凭空造物品）、C2（按坐标成链回放，最终落到最旧匹配行的 `oldData`）、C4（容器套用 `rollback.maxBlocks`）、C6（异常/关服收尾写 undo，新增 `shutdown()` 注册 `SERVER_STOPPING`，每 5 s 增量落盘）、C7（`co_item` 只还原「掉落」行）、C9（签名编辑按坐标成链 + `BlockStateUtil.replaceSignText`）、C10（跳过容器计入 skipped）、C12（undo 记录持久化到 `coreprotect-fabric-undo.json`，跨重启可用；同玩家任务在跑时拒绝新任务）、C16（替换方块前先倒出库存，优先交执行者）、B11（异常细节只进日志，用户只收通用错误）、C15（新增 `parseOrNull`，不可解析状态记 skipped，绝不当作空气）。

**两处主动偏离，已确认接受**：① C1 顺带不再把「实际取出的物品」交给执行者（否则 `/co undo` 会把同样物品加回容器 → 复制物品）；② C4 的 `maxBlocks` 上限主动扩到 `co_sign`/`co_item`/`co_entity`。**已知语义变化**：完成消息的 `blocks` 计入签名恢复、`skipped` 计入 4 类不可还原行（实体击杀、拾取等），比原来宽。

### 6.3 子代理 B（容器，`container/{Container,Hopper,Dispenser}Tracker.java`）

E3（新增 `containerAt`，双箱取合并的 54 格库存）、C11（快照与比较都基于合并容器）、E9（玩家自己开漏斗/发射器界面时的存取不再记成 `#hopper`/`#dispenser`，但仍刷新基线）、E10（漏斗/发射器互相转移不再双重记录）、C13（容器被破坏时把最后已知内容记为 withdraw，不再整份丢弃）、E15/E17（基线重置改为过期优先 + 最旧淘汰到 90%，不再 `clear()`）、E4（新增 `public static void rebaseline(ServerWorld, BlockPos)`，供回档/undo 写入容器后刷新基线；漏斗/发射器仅在实际写记录后调用）。

### 6.4 子代理 D（工具链，F1–F19）

发布/回档脚本加固：F1 上传去重、F2 非 2xx 后复核、F3 先传新 asset 再删旧、F4/F5 缺 jar 或版本失败即 throw、F6 上传前校验 SHA-256/大小/jar 内版本/不比源文件旧、F7 release 要求干净工作区且 draft→上传→发布、F8/F9 依赖版本修正（`1.21/build.gradle` SNAPSHOT→1.8.13；sqlite-jdbc 3.46.1.0→3.50.2.0）、F10 版本文件交叉校验、F11–F13 测试/新建脚本/ASCII 检查加固、F14 `.gitignore` 补 `token.txt`/`*.pat`、F15 12 个脚本改用 `git credential fill`（PAT 不落盘）、F16 旧脚本默认拒绝重跑、F17/F18 workflow 固定 action SHA 且缺文件即失败、F19 三份 `fabric.mod.json` loader/API 下限修正。验证：53 个 `.ps1` 语法 OK、5 个 JSON OK、`check-ascii.ps1` 全绿。

**两条运维结论**：① `publish.ps1 -DryRun` 现在会因「jar 比源文件旧」而拒绝发布 → 发布前三棵树必须重新构建；② release 脚本要求工作区干净 → 必须先提交再发布。

### 6.5 子代理 C（事件/mixin，已回报）

E1（满吸收判定改 `isRemoved()`）、E2（`SET_STATE_CAUSES` 加 `#fire` + 火块自身变化全部过滤）、E6（set 钩子跨账本判重 `alreadyLoggedBreak`）、E11（**偏离**：不做 HEAD→RETURN，改为在 HEAD 内复刻 `newState == null || !world.isInLoadLimit(pos) || world.isDebugWorld()` 的拒绝判定，理由：RETURN 时 `getBlockState` 已是新值，会毁掉全部自然记录）、E12（去掉 `instanceof BlockItem`，改为交互前后状态对比 + `world.getServer().execute(...)` 延后判定）、E14（成败改按前后状态变化判定、旧态取交互前真实状态）、E16（三个 mixin 入口 `try/catch(Throwable)` + `NaturalBreakCause` 5 秒看门狗）、E18（`injectors.defaultRequire` 0→1，15 个注入目标逐个核对）、E19（**偏离**：保留全量引号转义，只把译者已写的 `''` 视为字面引号）、B7（`isInspecting` 加 `Permissions.has(source, NODE_INSPECT)`；DISCONNECT 清 inspector 与翻译覆盖）、B8（命令钩子改挂私有 `executeCommand(Ljava/lang/String;)V` 的 RETURN、告示牌改挂 `onSignUpdate(UpdateSignC2SPacket;Ljava/util/List;)V` 的 RETURN；新增 `CoreProtectFabric.allowPacketLog(UUID)` 限流 60 条/分钟/人）、D6（进服立即应用 `cachedUserLanguage(name)`，再用 `submitRead` 异步刷新）。

- **E13 无规格未做**（AUDIT-NOTES §4.2–4.5 无该条目，此前已并入 C2）；**C9 事件侧未做**，由子代理 A 在 `rollback/RollbackManager.java` 完成。
- 风险自述：E16 降低失败可见性（每个失败事件一条 `[CoreProtect] Failed to log ...`）；B8 限流（常量在 `CoreProtectFabric.java:209-215`）；`defaultRequire:1` 让注入目标改名变成启动硬失败 → 移植到 1.21/26.1.2 时必须重校验描述符。

### 6.6 主代理第二轮（C5/E8：容器内容 NBT 序列化，已改完）

新增 `util/ItemStackCodec.java`（`encodeIdentity(ItemStack, RegistryWrapper.WrapperLookup)` / `decode(String, RegistryWrapper.WrapperLookup)`，走 `ItemStack.UNCOUNTED_CODEC` + `RegistryOps.of(NbtOps.INSTANCE, registries)` + `NbtHelper.toNbtProviderString/fromNbtProviderString`）；`ContainerTracker` 的 `Watch` 快照由 `Map<String,Integer>` 改为 `Map<String,Tally>`（`record Tally(ItemStack sample, int count)`），以组件敏感的序列化串为键，比较与记录都带上样本栈；`DatabaseManager` 的 `ContainerLog` 增加第 11 个字段 `String nbt`（保留 10 参兼容构造器）、`co_container` 建表语句与 `initSchema` 的 `ALTER TABLE ... ADD COLUMN nbt TEXT` 兼容旧库；`queryContainers`/`queryContainerHistory` 返回该列；`RollbackManager` 的 `ItemUndo` 记录 `nbt`，还原时按 nbt 解码模板栈（失败回退 `new ItemStack(item)`），容器增删改用 `ItemStack.areItemsAndComponentsEqual` 匹配并把完整栈交给执行者；undo 记录 JSON 兼容读写 `nbt`。

### 6.7 移植（1.21 / 26.1.2）

1.21.11 树上全部改动完成后统一移植；`defaultRequire:1` 与 mixin 描述符、`ItemStack` API 在 1.21（无 `DataComponent` 迁移差异）与 26.1.2（mojmap 命名）必须逐个复核。

### 6.8 仍未完成

- 三棵树编译 → `test-full.ps1` 定向回归 → 更新 `CHANGELOG.md` → 提交 → 重建 jar → 发布 v1.9.3（GitHub + Modrinth）→ 自查页面。**绝不改动 Modrinth 图标**（`6988515318bc5bcd47a6b23d003f5a18c215933f`）。

## 7. Fix round (v1.9.3)

本节是 v1.9.3 修复轮的**核实记录**：§4 的每一条 finding 都在自己的条目里加了状态标记（`【FIXED】` / `【FIXED (deviating)】` / `【OPEN】` / `【NOT PLANNED (no spec)】`）与 `path:line` 证据。核实对象只有 `coreprotect-fabric-1.21.11` 一棵树；本节汇总改动文件、计数与已知缺口。核实的是「代码里是否真的这么改」，不等于「修复已生效」——生效要等编译与 `test-full.ps1` 回归。

### 7.1 本轮改动的文件（`coreprotect-fabric-1.21.11`，33 改 + 1 新增，`git diff --stat` 合计 +1941/−351）

Java（`src/main/java/net/coreprotect/fabric/`）：

| 文件 | 改动 |
|---|---|
| `CoreProtectFabric.java` (+107) | `#fire` 进 `SET_STATE_CAUSES`、三个 hook 包 try/catch、`allowPacketLog` 命令/告示牌限流 |
| `command/CoCommand.java` (+48) | purge 失败报错、非玩家用 `r:` 拒绝、`/co status` 增加写入统计行 |
| `command/LookupService.java` (+85) | 非玩家查询 10 s 上限并补投日志、`plain()` 剥 `§`/控制符、页脚封顶 |
| `command/ParamParser.java` (+13) | `r:`/`p:` 上限 1000 |
| `config/CoreProtectConfig.java` (+66) | lookupLevel 默认 1、权限等级越界夹取、Database 字段 volatile、database.file 变更告警 |
| `container/ContainerTracker.java` (+168) | 合并双箱库存快照、Tally 组件敏感键、rebaseline API、容器消失记 withdraw |
| `container/HopperTracker.java` (+131) | 排除发射器/投掷器、被监视位置抑制自身记录、prune 改为过期优先 |
| `container/DispenserTracker.java` (+101) | 同上的 prune 与记录抑制逻辑 |
| `database/Criteria.java` (+6) | 新增 `world` 字段，半径查询限定维度 |
| `database/DatabaseManager.java` (+282) | 失败批次回队重试、读池内联、purge 返回 Long、字典缓存失效、计数 -1、执行器可重建、`co_container`/`co_item` 加 nbt 列 |
| `event/BlockEventListener.java` (+74) | 放置判定改为交互前后状态比对，任意手持物都生效 |
| `event/SessionEventListener.java` (+13) | 语言读缓存 + 异步刷新、断线清 inspector |
| `i18n/Translator.java` (+59) | 语言缓存、缺键只警告一次、引号转义处理 |
| `inspect/Inspector.java` (+10) | 使用处复核 `coreprotect.inspect` 权限 |
| `mixin/ItemEntityPickupMixin.java` (+13) | 整堆被捡走时按 `beforeCount` 记录 |
| `mixin/PlayerEntityDropMixin.java` (+3) | 掉落物记录附带 ItemStack 的 SNBT |
| `mixin/ServerPlayNetworkHandlerMixin.java` (+34) | 命令/告示牌钩子改挂 RETURN |
| `mixin/WorldMixin.java` (+7) | 复刻原版拒绝条件（E11） |
| `rollback/RollbackManager.java` (+711) | 同坐标成链回放、不再凭空造物品、容器/签名/物品/实体入计划、undo 落盘、关服收尾 |
| `util/BlockStateUtil.java` (+67) | `parseOrNull` 失败返 null、`replaceSignText` 返回旧文本 |
| `util/ItemStackCodec.java`（新增） | ItemStack ↔ SNBT 编解码，库存与掉落物的组件敏感身份 |
| `util/NaturalBreakCause.java` (+51) | 跨账本判重、5 s 看门狗丢弃泄漏的 cause |
| `util/Permissions.java` (+41) | 非玩家走 levelCheck、levelCheck fail-closed、权限组按 UUID 匹配 |

资源与构建：

| 文件 | 改动 |
|---|---|
| `src/main/resources/coreprotect.mixins.json` | `injectors.defaultRequire` 0 → 1 |
| `src/main/resources/fabric.mod.json` | loader / Fabric API 下限按实际编译版本声明 |
| `assets/coreprotect/lang/{en_us,ja_jp,ru_ru,zh_cn,zh_tw}.json` | 每个语言文件补 3 个新键（`status.writes`、`error.db`、`error.radius_needs_player`） |
| `build.gradle` | sqlite-jdbc 3.46.1.0 → 3.50.2.0 |
| `README.md` / `OVERVIEW.md` / `OVERVIEW_EN.md` | 文档同步 lookupLevel 默认值、命令方块权限判定与批处理配置项 |

同一轮里树外还改了（详见 §6.4）：`.build-tools/*.ps1`（发布/测试/新建脚本加固）、`.build-tools/modrinth/{publish,probe6}.ps1`、`.build-tools/release-pairs.json`、`.github/workflows/build.yml`、`.gitignore`、`coreprotect-fabric-1.21/build.gradle` 与 `coreprotect-fabric-26.1.2/build.gradle`（sqlite-jdbc）、另两棵树的 `fabric.mod.json`。

### 7.2 计数

- §4 合计 **60 条**（§4.1 口径：前五路 45 条 + 领域⑤ 15 条，9 条跨域重复已合并）；逐条标记实际覆盖 **78 个 id 行**（含 `B2/A8`、`D8/B10` 这类双 id 行与 5 条「合并进…」行）。
- **FIXED：60 条 / 60 条（100%）**，其中 **FIXED (deviating)：9 条** —— C1、B2/A8、B8、C7、C16、A7、D9、E11、E19。
- **OPEN：0 条**。**NOT PLANNED (no spec)：1 条** —— E13（该 id 在 §4 里没有条目正文）。
- 另外 2 个领域⑤ id（E15/E17）同样没有 §4 条目正文，按 §6.3 的记录与代码核对为 FIXED（见 §4.7）。
- **P0：6 条，全部 FIXED**（D1、A1、C1、B1、E1、E2）。

### 7.3 known gaps（故意留下、或本次无法核实的部分）

- **没有服务器实测**：三棵树都还没编译、没跑 `test-full.ps1`（§6.8）；v1.9.2 的 MSPT before/after 也没有在服务器上量过，性能结论仍只有静态审查（§4.6）。
- **1.21 与 26.1.2 是分开移植的**：本节所有证据行号只对 `coreprotect-fabric-1.21.11` 有效。**1.21 已移植并编译通过、26.1.2 进行中**，详情与真适配差异见 §8.4。
- **B8 的限流是事后丢弃**：钩子在 RETURN，无法阻止执行；被拒绝的命令与蜡封告示牌仍会进记录（只是不再无限量）。
- **D9 仍有窄窗口**：采用「关读池 + `awaitTermination(5 s)` + generation」，单条查询超过 5 s 时仍可能被关连接（没有改成锁 + 读线程自移除）。
- **F9 的兼容性未实测**：sqlite-jdbc 已升到 3.50.2.0，但没在服务器上跑过实际 SQL 与旧库迁移。
- **C7 的 `co_entity` 击杀行不还原**：只 `job.skipped++`，故意不复活白板实体（`co_item` 掉落物已带 SNBT，可正常还原）。
- **F16 只是加保护**：历史发布脚本默认 throw，但带 `-AllowHistoricalRerun` 仍可重跑。
- **E13 无规格未做**（NOT PLANNED）；E15/E17 无条目正文，只能按 §6.3 的记录核对。
- **§6.5 有一处过期说明**：其中「子代理 C 尚未回报、C5/E8 未做」与现状不符——C 已回报（§6.5 正文），C5/E8 已在 §6.6 完成。
- **未复核范围**：`event/MessageEventListener` 的渲染侧、`EntityEventListener` 各回调、SQLite schema/purge/索引在运行时的影响都未验证（§4.6 原文）。

## 8. 评审轮（对抗式复查）与移植落地

独立复查只读审查了 `coreprotect-fabric-1.21.11` 的 v1.9.3 工作树，报出 1 blocker + 3 high + 5 medium + 5 low。**全部已改**；下表行号是**改动前**的位置（改完会漂移）。

### 8.1 已改内容（`coreprotect-fabric-1.21.11`）

| 编号 | 位置（改动前） | 问题 | 改法 |
|---|---|---|---|
| B1 | `rollback/RollbackManager.java:682`（连带 `:711-721`、`:417-422`、`:577-581`） | `reverseContainer` 无条件 `return 1`，`removeItems` 没取满时仍按整额记 undo → `/co undo` 凭空造物品 | `removeItems`/`addItems` 返回真实移动量，`reverseContainer` 返回 `done`，`done > 0` 才写 `ItemUndo`（amount 用 `done`） |
| H1 | `:470-475`、`:740-747` | `ejectInventory` 把内容交给执行者或掉地却不进 undo（控制台回档时物品直接消失） | 删除 `giveStack`；一律掉在容器旁并逐栈记 `WorldItemUndo(..., added=true, SNBT)` |
| H2 | `:110`、`:526`、`:596-606`、`:774-782` | `WorldItemUndo` 只存 item id → 掉落物的附魔/内容在回档 + undo 后丢失 | undo 与 `co_item` 都存身份 SNBT；匹配改 `ItemStack.areItemsAndComponentsEqual` |
| H3 | `:271-286` | `startUndo` 没有 `isRunning` 检查且先消耗记录 → 回档还在切片时能并发 undo | 先守卫（拒绝发 `coreprotect.error.busy`）再取记录；`operator == null`（控制台/RCON/命令方块）单独一类，同类并发也拒绝 |
| M1 | `database/DatabaseManager.java:1692-1696`、`:1657-1674`、`:698` | 持续失败的批次让 `drainWrites` 死循环；`close()` 卡满 5 分钟；`shutdown()` 不打断 | 有界 drain（30 s 截止 + 连续失败 3 次 + 250 ms 间隔）后告警并 `writeQueue.clear()`；`close()` 超时 30 s 后 `shutdownNow()` |
| M2 | `RollbackManager.java:348-351`、`:834-848` | 每 5 s 在**服务器线程**同步重写整个 undo JSON | 拆 `publishUndo`/`commitUndo`，落盘走单线程守护执行器（`saveLastOperationsAsync` + `AtomicBoolean` CAS，永不写比内存更旧的快照） |
| M3 | `RollbackManager.java:395-433` | 容器行在 restore 时也按「最新优先」重放 → 同一物品多次存取终态不同 | `int slot = job.isRestore ? list.size() - 1 - job.entryIndex : job.entryIndex;` |
| M4 | `command/LookupService.java:100-102` | `InterruptedException` 直接 return，控制台/RCON 什么都不打印 | 重新置中断位 + 告警 + `callback.accept(null)` |
| M5 | `container/ContainerTracker.java:150-159` | 容器读不到时把记忆里每条都记成全量 withdraw 并归咎开界面的玩家（伪造日志） | 区块未加载 → 跳过；方块实体仍在或方块仍带实体 → 跳过；真的没了才记，署名 `#container` |
| L1 | `RollbackManager.java:340-343` | `job.server == null` 时静默计 skipped | 先 `LOGGER.warn` 再 `Messages.error(..., "coreprotect.error.db", "server unavailable")` |
| L2 | `:685-702` | `addItems` 不看 `inv.isValid` | 空槽先查 `inv.isValid(i, remaining)` |
| L3 | `DatabaseManager.java:1652-1674` | commit 后失败会重复入队 | 接受现状（复查者自认无法验证，保留重试入队） |
| L4 | `DatabaseManager.java:1193-1216` | `counts()` 最坏阻塞 8 × 30 s | 共享 20 s 预算 + 每个 future 最多 5 s + 超时 `cancel(true)` |
| L5 | `command/CoCommand.java:402` | 被拒绝时重复打印 start 消息 | 删除预打印，改由 `RollbackManager` 在守卫通过/拒绝时各打印一次 |
| M6（本轮自查追加） | `RollbackManager.java` 的 `addItems`（`slot.increment` 后未写回）、`removeItems`（`slot.decrement` 后未写回） | 只改 `ItemStack` 对象、不调 `inv.setStack` → 容器不会被标脏：回档结果在玩家重开界面或服务器存档/重载后可能回到旧值（回档「看起来没生效」） | 合并分支 `inv.setStack(i, slot)`；取空槽 `inv.setStack(i, ItemStack.EMPTY)`、部分取用 `slot.decrement(take)` 后 `inv.setStack(i, slot)`；三棵树同步 |

`mixin/PlayerEntityDropMixin.java` 里的 `ItemStackCodec` 调用漏了 import，由首次 `compileJava` 发现（`找不到符号 变量 ItemStackCodec`）并补上。

### 8.2 HopperTracker 为何不再改

复查把 `container/HopperTracker.java:127-137` 列为 M5 同形，但该路径每次都用**刚取到的** `inv` 计算 `after`，且 `previous == null || previous.container != container` 会重建基线，记录一律署名 `#hopper`，记录后还会 `ContainerTracker.rebaseline(...)`。M5 的失败模式（读不到 → 全量 withdraw + 栽赃开界面的玩家）在这里不存在，故不改，只留结论。

### 8.3 语言文件

新键 `coreprotect.error.busy` 加入三棵树 × 5 语言（脚本 `.build-tools/audit/add-busy-key.ps1`，数据 `.build-tools/audit/lang-busy.tsv`）；1.21 与 1.21.11 每文件 118 键，26.1.2 在移植时补齐。全部文件 `ConvertFrom-Json` 校验通过。

### 8.4 移植状态与编译

- `coreprotect-fabric-1.21.11`：`compileJava` **BUILD SUCCESSFUL**（Gradle 9.7.1、JDK 21.0.12.1+1、`--offline --no-daemon -Xmx1400m`）。
- `coreprotect-fabric-1.21`：按 1.21.11 工作树移植（23 java = 22 改 + 新增 `util/ItemStackCodec.java`、5 lang、`coreprotect.mixins.json` 的 `defaultRequire` 改 1），`compileJava` **BUILD SUCCESSFUL**（Gradle 8.10.2）。
- `coreprotect-fabric-26.1.2`：本轮移植进行中（mojmap），完成后同样跑 `compileJava` 验证。

1.21 树真正需要适配的地方（其余只是改名）：

| 文件 | 1.21 的写法 | 影响 |
|---|---|---|
| `CoreProtectFabric.java` | 1.21 没有 `World.isInLoadLimit(BlockPos)` → 用 `!world.isInBuildLimit(pos)` | **语义略弱**：只挡 ±3000 万与高度，不挡远处未加载区块，等于回到 vanilla 1.21 的判定 |
| `util/Permissions.java` | 1.21 没有 `CommandManager.*_CHECK` / `PermissionCheck` → `hasPermissionLevel(levelCheck(level))` | 结果等价（1.21 版 `hasLevel` 暂未被调用，保留 API 一致） |
| `mixin/PlayerEntityDropMixin.java` | 1.21 的 drop 方法描述符是 `dropItem(Lnet/minecraft/item/ItemStack;ZZ)` | 行为等价 |
| `database/DatabaseManager.java` | 保留 1.21 已有的 `closeStatements()` 位置（WAL checkpoint 之后） | 顺序差异，无功能影响 |
| 其余 7 个文件 | `.name()`→`.getName()`、`getEntityWorld()`→`getWorld()`/`getServerWorld()`、`getOrThrow`→`getWrapperOrThrow` | 纯改名 |

### 8.5 发布清单（v1.9.3 已完成）

已完成：

- 代码：1.21.11、1.21、26.1.2 三棵树全部改动（含 §8.1 的 M6 槽位写回）落盘，三棵树 `compileJava` / `build` 成功。
- 26.1.2 移植（mojmap 子代理，唯一非 1:1 适配）：26.1.2 没有 `ItemStack.UNCOUNTED_CODEC`，改用 `ItemStack.CODEC` 编码 `stack.copyWithCount(1)`（往返一致，库里 SNBT 多一个 `count:1`、键顺序可能不同）；其余全是改名（`NbtHelper.toNbtProviderString`→`CompoundTag.toString`、`NbtHelper.fromNbtProviderString`→`TagParser.parseCompoundFully`、`RegistryOps.of`→`create`、`RegistryWrapper.WrapperLookup`→`HolderLookup.Provider`、`DoubleInventory.isPart`→`CompoundContainer.contains`、`Inventory.isValid`→`Container.canPlaceItem`、`isChunkLoaded`→`hasChunk`、`toImmutable`→`immutable`、`BlockState.isOf(Block)`→`state.getBlock() == block`、`ChestBlock.getInventory`→`getContainer`、`getOtherEntities(null,…)`→`getEntities((Entity) null, …)`、`areItemsAndComponentsEqual`→`isSameItemSameComponents`、`getRegistryKey().getValue().toString()`→`dimension().identifier().toString()`、`isInLoadLimit`/`isDebugWorld`/`Box`/`getUuidAsString`/`isOnlineMode`/`getPlayerManager().getPlayer`、`Commands.hasPermission(f).test(source)`→`f.allows(source.getPermissions())`）。`coreprotect.mixins.json` 的 `defaultRequire` 0→1；5 个语言文件各 118 键，键集合/顺序/取值与 1.21.11 完全一致。
- 26.1.2 保留的一处顺序差异：`database/DatabaseManager.java` 的 `close()` 仍是 WAL checkpoint 先、`closeStatements()` 后（1.21 与 26.1.2 的 v1.9.2 本来就如此，v1.9.3 diff 未触及；后果：有缓存语句时 truncating checkpoint 可能返回 SQLITE_BUSY 且异常被忽略，`-wal` 留在非空）。
- 我在子代理收尾后补齐的两处（它启动早于这两次改动，被旧快照覆盖）：① `CoreProtectFabric.java` 的 `MOD_VERSION` 曾回到 `1.9.2` → 已改回 `1.9.3`；② M6 槽位写回未移植（`slot.grow(move)` / `slot.shrink(take)` 后没有写回）→ 已按 mojmap 补 `inv.setItem(i, slot)` 与 `inv.setItem(i, ItemStack.EMPTY)`，与另两棵树语义一致。
- 三棵树 `build` 均 `BUILD SUCCESSFUL`，产物 `coreprotect-fabric-<mc>-1.9.3.jar`（加签名命令钩子前的体积：1.21 = 14,537,102 B、1.21.11 = 14,535,449 B、26.1.2 = 14,535,994 B；最终发布体积见本节末尾），jar 内 `fabric.mod.json` 的 version 三棵树都是 `1.9.3`；`javap -c` 复核三棵树 `RollbackManager` 的槽位写回调用点各 6 处（一致）。
- 文档：`CHANGELOG.md`（英文 + 中文，含 M6）、`.build-tools/release-v193-template.md`、三份 `release-v193-*.md`（11945 / 11963 / 11957 字符，无占位符）、`.build-tools/modrinth/changelog-1.9.3.md`。
- 版本号：`bump-version.ps1 -From 1.9.2 -To 1.9.3` 已改三棵树的源码/README/OVERVIEW 与 `.build-tools/modrinth/versions.json`。
- 工具链：`check-ascii.ps1` 报 55 个脚本全部纯 ASCII；`test-full.ps1` 已加入 `co lookup u:#container` 与 v1.9.3 并发用例。
- 三棵树定向回归（`test-full.ps1 -Fresh`，一次一棵树）：
  - `coreprotect-fabric-1.21.11`：`FULL_TEST_DONE`、exit 0、`MIXIN_FAILURES: no`、`CORE_ENABLED: yes`，游戏内 `co status` 显示 `Version: CoreProtect Fabric v1.9.3` 与 `Writes: 40 batch(es) committed | 0 queued | 0 dropped`；唯一 WARN 是新增诊断网的 `Dropped a '#fire' cause window that was never closed`（合成场景，预期）。
  - `coreprotect-fabric-1.21`：同样 `FULL_TEST_DONE`、exit 0、`MIXIN_FAILURES: no`、`CORE_ENABLED: yes`（WARN 为 `'#piston'` 窗口）。
  - `coreprotect-fabric-26.1.2`：首轮**开服即崩**，修复后重跑 `FULL_TEST_DONE`、exit 0、`MIXIN_FAILURES: no`、`CORE_ENABLED: yes`。
  - 三棵树都验证：控制台 `co rollback t:5m r:80` 回 `A radius (r:) needs a player position - run this command as a player.`（预期）；`co purge t:1s` 后 `co status` 归零、`co lookup a:#tnt` 变 `No results found`。

本轮（复查轮之后）又发现并修复的两个真实缺陷：

1. **26.1.2 的 mixin 从未生效** —— `coreprotect-fabric-26.1.2/src/main/java/net/coreprotect/fabric/mixin/ServerPlayNetworkHandlerMixin.java` 里两处 `@Inject(method = ...)` 仍是 yarn 描述符（`executeCommand(Ljava/lang/String;)V`、`onSignUpdate(...;Ljava/util/List;)V`），注入一直静默失败（该版本上命令与告示牌根本没记录）；`"defaultRequire": 1` 之后它变成开服失败：`Mixin apply for mod coreprotect failed ... InvalidInjectionException Critical injection failure: @Inject annotation on coreprotect$onCommand could not find any targets matching 'executeCommand(Ljava/lang/String;)V' in net.minecraft.server.network.ServerGamePacketListenerImpl`，随后 `Failed to start the minecraft server`。已按 mojmap 改写为共用 helper `coreprotect$logCommand(String)` + `performUnsignedChatCommand(Ljava/lang/String;)V` + `handleSignedChatCommand(Lnet/minecraft/network/protocol/game/ServerboundChatCommandSignedPacket;)V`（取 `packet.command()`）+ `handleSignUpdate(Lnet/minecraft/network/protocol/game/ServerboundSignUpdatePacket;)V`；26.1.2 其余全部 mixin 目标用 javap 对着 `minecraft-merged.jar` 核对 missing 0。
2. **1.21 / 1.21.11 漏记带聊天签名的命令** —— 只挂了 `executeCommand(Ljava/lang/String;)V`，而签名客户端走 `public void onChatCommandSigned(ChatCommandSignedC2SPacket)` → `private void handleCommandExecution(ChatCommandSignedC2SPacket, LastSeenMessageList)`；`javap -p -c` 反汇编证实该私有方法体内**没有** `executeCommand` 调用（它直接 `CommandManager.execute(ParseResults, String)`），所以在线模式下玩家命令此前完全没有记录。两棵树已加 `@Inject(method = "onChatCommandSigned(Lnet/minecraft/network/packet/c2s/play/ChatCommandSignedC2SPacket;)V", at = @At("RETURN"))` 并共用 `coreprotect$logCommand`；`ChatCommandSignedC2SPacket` 在 1.21 与 1.21.11 均为 record 且 `command()` 存在（javap 确认）。
- 上述两个改动后三棵树重新 `build` 成功，jar：1.21 = 14,537,297 B、1.21.11 = 14,535,601 B、26.1.2 = 14,536,067 B；三份 notes 由 `make-notes-193.ps1` 重新生成（12799 / 12817 / 12811 字符，无占位符）；`CHANGELOG.md` 中英各加对应条目。

已完成（发布）：

1. 三棵树回归全绿：`coreprotect-fabric-1.21.11`、`coreprotect-fabric-1.21`、`coreprotect-fabric-26.1.2` 各 `FULL_TEST_DONE`、exit 0、`MIXIN_FAILURES: no`、`CORE_ENABLED: yes`（开服成功即证明 `executeCommand` 与 `onChatCommandSigned` 两个注入都命中目标）。
2. 提交（显式路径 138 项，**未用 `git add -A`**）：`0ca0f9b` = `v1.9.3: security, data-integrity and logging fixes (60 findings)`；随后 `a1a031c` = `release script: read the GitHub credential through a real stdin file`。远端 `main` 与 `refs/tags/v1.9.3`、`v1.9.3-mc1.21.11`、`v1.9.3-mc26.1.2` 现在都指向 `a1a031c`。
3. GitHub 发布（`.build-tools/release-v192.ps1 -Version 1.9.3 -PreviousTag v1.9.2`）：首次失败 `git : fatal: refusing to work with credential missing protocol field`——Windows PowerShell 5.1 没有 `<` 重定向，管道又给不了 `git credential fill` 可用的 stdin（.NET `ProcessStartInfo` + `RedirectStandardInput` 也一样）。修法：把请求写进一行临时文件，用 `cmd /c "git credential fill < 文件" 2>NUL`，`finally` 里删除。之后 API 复核：三个 release `draft=False`、`prerelease=False`，各带一个资产且与本地 jar 同大小（1.21 = `coreprotect-fabric-1.21-1.9.3.jar` 14,537,297 B、1.21.11 = 14,535,601 B、26.1.2 = 14,536,067 B）；正文 12799 / 12817 / 12811 字符，都含 `ChatCommandSignedC2SPacket`。脚本用 `target_commitish=main` 建 tag，首跑时远端 `main` 还停在 `f1fd4a3` → 已 `git push origin main` 并 `git push --force` 三个 tag 指向 `a1a031c`。sha256：1.21 `06F2401E…4707`、1.21.11 `17A2FFDC…377A`、26.1.2 `5B9EEEAC…2E7C`。
4. Modrinth 发布走已验证的两步流程（`POST /version` 的 `HTTP 405` 已知问题）：`publish.ps1 -DumpPayload`（三个 jar sha256 前缀 `06F2401E5E85` / `17A2FFDC8DC8` / `5B9EEEAC2A66`，`JarOk True`；脚本照旧不碰项目正文与图标）→ `probe6.ps1` 三次 `HTTP 200 : CREATED`，版本 id = `eldLUrfn`（1.21）、`n9YO8cwx`（1.21.11）、`vchfPTj6`（26.1.2）。
5. 页面自查：`check.ps1` 显示项目 42 个版本，三个 1.9.3 全部 `status: listed`、`version_type: release`、`loaders: fabric`，文件大小与本地逐字节一致，`icon_url` 仍是 `.../6988515318bc5bcd47a6b23d003f5a18c215933f_96.webp`（**图标未被改动**，与用户口径一致）。发布期间项目级 `status` 显示 `processing`（新文件上传后的扫描状态；三个版本本身已 `listed`，项目 `updated` 时间等于 26.1.2 版本发布时间）。
6. 教训：`git commit -m '...'` 里含双引号的正文会被 PowerShell 拆成 pathspec（`error: pathspec 'to' did not match any file(s)`）→ 改用 `git commit -F <临时文件>`；PS 5.1 的 `Invoke-RestMethod` 直连 `api.modrinth.com` 会拿 404，走 `.build-tools/modrinth/check.ps1`（它显式设 TLS 1.2 并带 User-Agent）即可。
7. 图标口径（**永不改动**）：modrinth.com 上的图标哈希 `6988515318bc5bcd47a6b23d003f5a18c215933f`；本地 `.build-tools/modrinth/icon.png` 的 SHA-256 = `5df0aacd8c27dfaea01bf9dfdf815078fc46d39eb46c2f5fb3e94fd05f3f57b1`（发布流程从不传 `-SetIcon`、从不跑 `make-icon.ps1`）。
