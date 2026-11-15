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
5. 页面自查：`check.ps1` 显示项目 42 个版本，三个 1.9.3 全部 `status: listed`、`version_type: release`、`loaders: fabric`，文件大小与本地逐字节一致；`GET /version/<id>` 的 `files[0].hashes.sha512` 与本地 `Get-FileHash -Algorithm SHA512` 完全相同（1.21 `872b4e76e362f2c738b6429f5780fced30aa0ca5bd566d353d786df39e8f22019f22f1ad60ea6ef6ab465cbd4677b967e0df003962bc92fa129e9ecdcbe64d16`、1.21.11 `dd37843c7d5c1cc6a88e7dc36260a1dc107ce65b5ce7b6b1c9db6fe4eba010df99f5981325d4c4dfb1348bea51a2f28d03f92a51c8f4c038751d84aa1e021ca1`、26.1.2 `8812d35d8f0f8dfac04129f30331c8ffaab32585bf69a5f44d16ba903f0e33ecd47628cbd557323b7ae8c235d9539521e9a72d3062dd1574a1c1de656c47cb07`）；`icon_url` 仍是 `.../6988515318bc5bcd47a6b23d003f5a18c215933f_96.webp`（**图标未被改动**，与用户口径一致）。GitHub 三个 release 正文分别为 12799 / 12817 / 12811 字符且含 `ChatCommandSignedC2SPacket`，页面 `https://github.com/CNTianCheng/coreprotect-fabric/releases/tag/v1.9.3` 抓取返回 200 且标题正确。
6. 项目级 `status` 是 `processing`，而**这是本次发布之前就存在的状态，不是 v1.9.3 造成的**：`GET /v2/project/SXIMAuWt` 返回 `queued = 2026-09-05T08:57:03.819466Z`、`requested_status = approved`、`moderator_message`（空）—— 项目自 2026-09-05 起就排在 Modrinth 的审核队列里（1.9.0/1.9.1/1.9.2 的上传日期都在它之后），期间一直保持这个状态。含义按 Modrinth 口径是 `Processing — Project is not displayed on search, and not accessible by URL (Temporary state, project under review)`（<https://www.reddit.com/r/feedthebeast/comments/12r8e7t/withheld_on_modrinth/>）：`https://modrinth.com/mod/coreprotect-fabric` 的 `<title>` 是 `Project not found`，`/v2/search?query=coreprotect fabric` 只命中 `neoprotect`；对照组 `https://modrinth.com/mod/fabric-api` 正常显示标题；`https://status.modrinth.com/` 是 "All services are online"，所以不是平台故障。**已上传的文件仍然可用**：`HEAD https://cdn.modrinth.com/data/SXIMAuWt/versions/eldLUrfn/coreprotect-fabric-1.21-1.9.3.jar` → `HTTP 200`、`Content-Length: 14537297`、`Content-Type: application/java-archive`。**只有 Modrinth 站方能推进这条队列，不要为此重复上传或重复提交审核。**
7. 教训：`git commit -m '...'` 里含双引号的正文会被 PowerShell 拆成 pathspec（`error: pathspec 'to' did not match any file(s)`）→ 改用 `git commit -F <临时文件>`；PS 5.1 的 `Invoke-RestMethod` 直连 `api.modrinth.com`（不显式设 TLS 1.2、不带 User-Agent）会拿 404，走 `.build-tools/modrinth/check.ps1` 即可；`git push` 偶发 `Recv failure: Connection was reset` / `Failed to connect to github.com:443`，等 1-2 分钟后重试就能成功。
8. 图标口径（**永不改动**）：modrinth.com 上的图标哈希 `6988515318bc5bcd47a6b23d003f5a18c215933f`；本地 `.build-tools/modrinth/icon.png` 的 SHA-256 = `5df0aacd8c27dfaea01bf9dfdf815078fc46d39eb46c2f5fb3e94fd05f3f57b1`（发布流程从不传 `-SetIcon`、从不跑 `make-icon.ps1`）。

## 9. 第二轮深度复查（v1.9.4-pre）

### 9.1 目标、方法与基线

- 用户要求（m03400）：先一次一问澄清到 95% 把握，再给完整方案；全范围重扫；主审 1.21.11、1.21 与 26.1.2 审差异；静态为主、高危点实测复现；**先出清单，用户决定后再改**；主挖新问题、抽查上轮修复；代码类遗留项一并核查。
- 本轮定位：不依赖上一轮清单的独立复查。审查视角按「数据层 / 回档引擎 / 日志采集 / 命令与权限 / 移植与工具链」五路并行，每个视角必须回答「上一轮为什么没发现」（新引入 / 上轮盲区 / 上轮判为可接受但这次判为不可接受）。
- 基线：`main` @ `57d42daa8955d849abd910f29262f65c885003c5`；三棵树工作区已跟踪文件干净；未跟踪项 16 个均为设计上不提交的产物（`.build-tools/apx-*`、`.dsh-*`、`angry-piglin-xp-1.21.11/`）。
- 规模：三棵树各 40 个 Java 文件，1.21.11 = 7188 行、1.21 = 7187 行、26.1.2 = 7198 行；三棵树 `en_us.json` 均 118 键。
- 分级沿用上轮：**P0** 数据损坏/安全/凭空物品；**P1** 功能错误或可被普通玩家触发的不良后果；**P2** 边界条件/性能/可维护性；**P3** 轻微。可信度标注：**已证实**（实测，或代码路径确定）/ **推理级**（需特定条件，未实测）。
- 约束：本轮不改产品代码、不提交、不发版；实测如需临时补丁，验完必须还原并保持工作区干净；**永不改动 Modrinth 图标、不重复上传**。

### 9.2 分级统计

**去重后共 84 条**（同一缺陷被多路报到的按一条计，映射见表下）。按级别：

| 级别 | 条数 | 说明 |
|---|---|---|
| **P0** | **2** | 都会被用户直接感知为「回档把世界改坏」：活塞坐标整体错位、`#fire` 窗口泄漏导致全员误归因 |
| **P1** | **16** | 静默丢日志 / 误伤无辜玩家 / 回档造或丢物品 / 敏感信息泄露 / 特性完全失效 / 主线程开销失控 |
| **P2** | **29** | 记录覆盖缺口（设计级，需新钩子）、`/co purge` 语义放大、文档与实际不符、发布链缺口 |
| **P3** | **37** | 脆弱写法、文档漂移、工具链卫生、低危暴露面 |
| 合计 | **84** | 其中 **11 条**是「上轮声称已修但实测/复核无效或未移植」（L1-01/L3-03、L1-02、L5-02、L5-03、L5-11、L5-06、L5-05、L4-04/L5-01、L3-07/F-9.1、F-9.2、L2-04/F-9.3） |

按来源（未去重）：主代理自查 3（F-9.1 P1 / F-9.2 P3 / F-9.3 P1）、L1 18（P1 2 / P2 6 / P3 10）、L2 13（P1 3 / P2 7 / P3 3）、L4 20（P1 2 / P2 6 / P3 11 / 误报 1）、L3 21（P0 2 / P1 8 / P2 9 / P3 2）、L5 16（P1 2 / P2 4 / P3 10）= **91 条原始，去重后 84 条**。

**去重映射（同一缺陷）**：L3-03 ≡ L1-01（`#container` 死变量）；L3-07 ≡ F-9.1（命令原文无脱敏）；L2-04 ≡ F-9.3（互斥窗口）；L4-04 ≡ L5-01（权限节点前缀不一致，同一条由两路独立报出）；L2-10 ≡ L4-03（`a:` 只过滤 `co_block`）；L2-12 ≡ L4-19（`maxBlocks` 实为每表 5000）；L1-08 ≡ L4-09（`/co status` 走无预算的 `countsSequential`）。**误报 1 条**：L4-02（见 9.4 R-9.1 的说明）。

**必须优先修（建议顺序）**
1. **P0-L3-01 活塞坐标**：`from = extending ? pos.offset(facing.getOpposite()) : pos`、`to = extending ? pos : pos.offset(facing)`；否则回档会把方块放到错位 1~2 格并清掉那里的方块。
2. **P0-L3-02 `#fire` 窗口**：`FireBlockMixin` 的 `@At("TAIL")` → `@At("RETURN")`（两处：`tick` 与 `scheduledTick`）。
3. **P1 数据可信度三连**：L1-02（行级写失败吞掉，`/co status` 应可见）、L3-09（限流丢弃不计数）、L3-08（聊天无限流）。
4. **P1 回档正确性四连**：L2-01（容器阶段需在方块恢复之后）、L2-02（双箱按 54 格回放）、L2-03（`ejectInventory` 的 undo 要放回容器）、L3-05（容器被毁时先记内容）。
5. **P1 安全**：L3-07/F-9.1（命令黑名单/脱敏）、L4-04/L5-01（权限节点前缀归一化）。
6. **P1 一致性与钩子层次**：L5-02（26.1.2 告示牌钩子下移到 `updateSignText`）、L5-03（签名命令钩子下移到执行方法）、L1-01/L3-03（`#container` 死变量）、L3-04（双箱漏斗重复记账）、L3-06（活塞销毁方块无记录）、L3-10（漏斗快照开销）。

### 9.3 发现清单

#### 9.3.0 主代理自查（与五路并行，独立复核）

**F-9.1 · P2 · 命令原文无脱敏入库：登录类明文密码可被查询**
- 位置：`coreprotect-fabric-1.21.11/src/main/java/net/coreprotect/fabric/mixin/ServerPlayNetworkHandlerMixin.java:42-51,59`（`mod.database().insertCommandAsync(TimeUtil.now(), player.getGameProfile().name(), command)`）；读取侧 `command/LookupService.java:138` → `database/DatabaseManager.java:1158`（`queryMessages("co_command", …)`）。
- 触发：任何玩家执行任何命令（含 `/login <密码>`、`/register <密码>`、`/msg <对象> <私聊>`）。
- 影响：明文密码与私聊原文长期保存在 `co_command`；默认 `permissions.lookupLevel = 1`（moderator 及以上）即可 `/co lookup a:command` 读出。全仓库无命令过滤/脱敏（grep `login|register|password|secret|token|filter|blocklist` 未命中任何过滤实现）；`config/CoreProtectConfig.java:38` 的 `logging.command = true` 只能**整体关闭**命令记录，无法只忽略敏感命令。
- 证据：代码路径确定（mixin → `insertCommandAsync` → `co_command` → lookup 直读原文）。
- 建议：新增 `logging.commandIgnore`（前缀列表，默认含 `/login`、`/l`、`/register`、`/reg`、`/changepassword`、`/msg`、`/tell`、`/w`），命中则不入库；README 显著提示此类服务器应关闭 `logging.command`。
- 可信度：**已证实**（代码路径）。
- 上轮为何没发现：上轮只审「是否漏记」（完整性），未审「是否该记」（敏感信息）。

**F-9.2 · P3 · `database.syncMode` 看似可热改，但 `/co reload` 不生效且无提示**
- 位置：`database/DatabaseManager.java:574`（`applyPragmas` 只在建连接时执行 `PRAGMA synchronous=<config.syncMode>`）；`command/CoCommand.java:464-470`（`reload` 仅 `config().load()` + 翻译重载，不重开连接、不重设 PRAGMA）。
- 触发：管理员把 `database.syncMode` 从 `full` 改为 `normal` 后执行 `/co reload`。
- 影响：字段虽已 `volatile`（读得到新值），但写连接仍沿用旧值直到重启；`/co reload` 无任何提示，管理员会误以为已生效（持久性预期与实测不一致）。
- 建议：`reload` 后把 `PRAGMA synchronous=…` 投递到 writer 线程执行；或在 `/co reload` 输出中明确「syncMode 需重启生效」。
- 可信度：**已证实**（代码路径）。
- 上轮为何没发现：上轮把配置字段改成 `volatile` 只解决「读得到新值」，未验证「新值是否真的被使用」。

**F-9.3 · P1 · `/co rollback` 的互斥在「查询阶段」是空的：连发两次会并行执行并重复改世界**
- 位置：`rollback/RollbackManager.java:220-289`（`startRollback`）：`isRunning(operator)` 在 `:225` 检查，但任务对象要到**查询回调内**才 `runningJobs.add(job)`（`:287`），两者之间隔着一次 `mod.database().submitRead(...)` 的完整查询（`queryBlocks` + `queryContainers` + `querySigns` + `queryItems` + `queryEntities`，`:236-270`）。
- 触发：同一操作者在第一次回档的查询尚未返回时再执行一次 `/co rollback`（双击、宏、RCON 连发；控制台两次也互不阻塞，因为 `operator == null` 且都不在 `runningJobs` 里）。
- 影响：两个 Job 会同时切片执行 —— 方块改写基本幂等，但**容器增删会被应用两次**（`reverseContainer` 的 add/remove 各跑一遍 → 物品凭空复制或凭空消失）；`publishUndo` 每次覆盖同名记录（`:425-431`），`/co undo` 最终只能撤掉其中一个（可能是被截断的那个）。在真实大库上 `queryBlocks` 的耗时是数百毫秒级，容易命中。
- 证据：代码路径确定（`isRunning` → `submitRead` → 回调内才登记）；`runningJobs` 只在 `:287`/`:312` 加入、`:397`/`:323` 移除，没有「预留中」状态。
- 建议：在 `startRollback` 的**调用线程**（服务器线程）先登记一个占位（如 `pendingOperators` 集合或先把 Job 加进 `runningJobs`、失败时移除），查询失败/超限时再释放；或把 `isRunning` 检查移到回调里并在此之前不回消息。
- 可信度：**已证实**（代码路径）。
- 上轮为何没发现：上轮加的互斥只覆盖了「已开始切片」的状态，没人验证「开始之前」的窗口；`isRunning(operator)` 的注释还专门写了 console 互斥，容易让人以为已覆盖。

#### 9.3.0-b 已检查但无问题（主代理）
- `purge` 覆盖全部 8 张日志表并清理字典：`database/DatabaseManager.java:141-142`（`COUNT_TABLES`）+ `:1324-1348`（`doPurge` → `gcDictionaries` → `invalidateDictionaries`）。
- `applyPragmas` 读/写连接差异合理：读连接 `query_only=ON`、`synchronous=NORMAL`；写连接 `journal_mode=WAL` + 配置值；两路均 `busy_timeout=10000`、`temp_store=MEMORY`（`:559-579`）。
- `command/ParamParser.java` 参数边界正确：`t:` 必须为正秒、`r:`/`p:` 上限 1000、未知 key 报 `invalid_param`、裸数字=页码。
- 三棵树 `src` 内无硬编码密钥；外部端点只有 bStats 与 Modrinth（`util/BStatsMetrics.java`、`util/UpdateChecker.java`）。
- bStats 端点正确：官方 `MetricsBase.REPORT_URL = "https://bStats.org/api/v2/data/%s"`（`%s` = platform，`server-implementation` 是合法值，见 https://github.com/Bastian/bStats-Metrics/blob/master/base/src/main/java/org/bstats/MetricsBase.java）；上报内容不含玩家名/IP（`util/BStatsMetrics.java:98-135`）。
- 会话日志不记录 IP（`event/SessionEventListener.java:38-41,61-64`）；`event/MessageEventListener.java:14-21` 只订阅公共聊天。

#### 9.3.1 L1 数据层（`database/DatabaseManager.java` 全 1854 行 + 查询/命令调用侧）

| # | 级别 | 位置 | 问题与影响 | 我的复核 |
|---|---|---|---|---|
| L1-01 | **P1** | `container/ContainerTracker.java:154,163,174-180` | 「容器消失记匿名 `#container`」（v1.9.3 的 M5 修复）是**死代码**：`:163 user = "#container";` 之后 `:179` 仍传 `w.userName()`，`user` 从未被读取。容器被回档/爆炸替换、方块实体消失时，整箱差额仍记在开界面的玩家名下 → `/co lookup u:玩家`、`/co rollback u:玩家` 误伤无辜玩家（正是上轮想修的那类误判）。三棵树同源 | **✓ 亲自确认**（读 `:138-207`，`user` 只被赋值两次、无读取）；`javac` 不会告警 |
| L1-02 | **P1** | `database/DatabaseManager.java:767-781` 及其余 7 个 `insert*Async` | 行级写失败被吞：`catch (Exception e) { LOGGER.error("Database write failed: co_block", e); }` 之后该行既不回队也不计入 `droppedWrites`，同批其余行照常 `commit()` → **静默丢日志**，`/co status` 的 dropped 仍显示 0。D1 的 requeue 只覆盖批次级 `commit()` 失败 | **✓ 亲自确认**（`:767-796`）；注意 `stateId/nameId/worldId` 在 try 外，抛错会走批次 requeue |
| L1-03 | P2 | `command/CoCommand.java:450-460` + `DatabaseManager.java:1306,1485-1492` | `/co purge` 的 `execute()` 是 `worker.submit(task).get(60, SECONDS)`：超时后返回 null（**不取消任务**）→ 用户收到 `coreprotect.error.db`（文案还是 "database read failed"），但 DELETE 仍在跑/已删完；`rows == null` 使 `vacuumAsync()` 被跳过；`doPurge` 对 8 张表各自 auto-commit，中途崩溃留下「部分表已清、部分未清」 | **✓ 亲自确认机制**（`:1485-1492` + `:1324-1340`）；实际耗时取决于库大小 |
| L1-04 | P2 | `DatabaseManager.java:1324-1375,676-695` | `DELETE` / `incremental_vacuum` / `VACUUM INTO` 全压在唯一 writer 线程 → 期间所有 `insert*` 只排队，队列满 65536 即丢行；备份还需同目录再放一份等大的 `.backup.tmp` | 采信（代码结构已确认：`purgeAsync`/`vacuumAsync`/`scheduleBackups` 都是 `worker.submit`） |
| L1-05 | P2 | `DatabaseManager.java:278-291` | `open()` 失败只 `return`，`open` 保持 false，但监听器与 `/co` 命令照常注册 → 之后每行日志被丢（仅 WARN 首条与每 1000 条）、所有查询回 "database read failed"，无重试、无禁用提示、无 OP 告知 | **✓ 亲自确认**（`:278-291`） |
| L1-06 | P2 | `DatabaseManager.java:1694-1721` | `conn.rollback()` 失败被吞 → 事务仍开；`finally` 的 `setAutoCommit(true)` 按 JDBC 语义会**隐式提交**，而同一批行又被重新入队 → 重复写行（重复记录、重复还原） | 采信（推理级，需 rollback 失败） |
| L1-07 | P2 | `DatabaseManager.java:1703-1714` | 重试无上限、无降级：毒批次每秒重试一次 → 队列涨满 → 全丢；`/co status` 只能看到 queued/dropped 同涨，无「数据库不可写」状态 | 采信（推理级） |
| L1-08 | P2 | `command/LookupService.java:80-87` + `DatabaseManager.java:1261-1273` | 控制台路径在 tick 线程 `future.get(10 s)`；`countsSequential` 是 8 个**无 deadline** 的串行 `COUNT(*)`（对比 `counts()` 有 20 s 预算）→ 大库上控制台先收 "still running"、再收迟到结果，且 tick 最长卡 10 s | **✓ 机制亲自确认**（`:80-87`、`:1261`）；是否超 10 s 未测 |
| L1-09 | P3 | `DatabaseManager.java:1210,1218-1253` | 20 s 预算的 `counts()` 实际是死路径（唯一入口 `count(String)` 无调用者，`/co status` 走 `countsSequential`，见 `CoCommand.java:234`）→ `COUNTS_BUDGET_NANOS`/`cancel(true)` 形同虚设；`:1224` 的 `readPool.submit` 未捕 `RejectedExecutionException` | **✓ 亲自确认**（grep `counts(` 只有 `:1210` 内部调用，`count("co_` 无调用者） |
| L1-10 | P3 | `DatabaseManager.java:1751-1755` | 关服 drain 丢弃时只打 WARN，不累加 `droppedWrites` → 面板与日志对不上 | 采信（与 b9 的 drain 改法一致） |
| L1-11 | P3 | `DatabaseManager.java:704-744` | `PRAGMA wal_checkpoint(TRUNCATE)`（`:710`）在读池关闭（`:738`）**之前**执行；有读连接持有事务时返回 SQLITE_BUSY 并被吞 → `.db-wal` 留在磁盘，下次启动重放 | **✓ 亲自确认**（`:704-744` 顺序） |
| L1-12 | P3 | `DatabaseManager.java:1446-1467` | `b:`/`e:` 的 `%`/`_` 未转义、无 `ESCAPE`：`b:_` 命中全部方块；`e:%` 排除全部行 → 回档报「0 块」却像成功 | **✓ 亲自确认**（`:1448-1466`）；仍是参数绑定，非注入 |
| L1-13 | P3 | `DatabaseManager.java:118-119` | `co_entity` 只有 time/name 与 data 索引，**没有 `(wid_id,x,y,z)`**（其余 4 张日志表都有）→ 实体半径查询/`#kill` 全表扫 | **✓ 亲自确认**（DDL 对照） |
| L1-14 | P3 | `rollback/RollbackManager.java:720-752` | 告示牌目标解析 N+1：在读池线程内逐坐标 `querySignHistory` → 500 个位置 = 500 次 SQL，同池其它查询排队 | 采信 |
| L1-15 | P3 | `command/CoCommand.java:453-455` | purge 失败文案固定 "database read failed"，把写失败报成读失败 | **✓ 亲自确认**（同 L1-03 调用点） |
| L1-16 | P3 | `DatabaseManager.java:1239-1250` | `cancel(true)` 的中断位可能被 sqlite-jdbc 的 `checkInterrupted()` 消费到同一读池线程的下一个查询 | 采信（推理级；当前是死路径） |
| L1-17 | P3 | `DatabaseManager.java:440-452` | 迁移备份只 `Files.copy` 主库文件（checkpoint 可能 busy → 备份缺 WAL 事务），且 `if (!Files.exists(backup))` 使旧备份永不刷新 | 采信（推理级） |
| L1-18 | P3 | `DatabaseManager.java:490` vs `:311-318,544-556` | `gcDictionaries` 在 `migrateV2ToV3` 内被调用，而 `DDL_V3` 建表在其后才执行 → 旧库若缺 `co_session/co_command/co_chat`，`UNION` 会 `no such table` → 迁移失败 → 叠加 L1-05 永久降级 | **✓ 结构确认**（`:311-318` 先迁移、`:490` 内 gc）；前置条件（v1 库是否缺表）待实测 |

**L1 的「已检查但无问题」**（可直接引用，避免重复审）：① 全参数绑定无 SQL 注入；② 所有 `query*` 均 `ORDER BY <t>.id DESC LIMIT ? OFFSET ?`，id 唯一=全序，分页不重不漏；③ `gcDictionaries` 五组 UNION 单列对齐且全部带 `IS NOT NULL`（规避 `NOT IN (NULL)` 陷阱）；④ 字典 `INSERT … ON CONFLICT DO NOTHING` + `SELECT` 与 UNIQUE 约束匹配，缓存为 `synchronizedMap`；⑤ `readConnection()` 的 generation 失效机制正确；⑥ 读/写连接 PRAGMA 差异合理；⑦ `submitWrite` 的 `!open` 与 offer 失败都计数（唯一例外 L1-10）；⑧ writer 单线程不触碰 server 线程状态；⑨ `poolReader` 覆盖全部 `executeRead` 入口，无「池线程内再 submitRead 并阻塞」的嵌套（A1 修复完整）；⑩ `bind()` 三分支与占位符一一对应；⑪ 先删 LEGACY 索引再 `DROP COLUMN`；⑫ `-1 = n/a` 语义两端一致；⑬ `t:` 单位（秒）在 parse / 查询 / purge 三处一致；⑭ 关停有 30 s + deadline + `shutdownNow()` 兜底，不会挂死。

**L1 的「存疑未证实」**：v1 旧库是否真缺 `co_session/co_command/co_chat`（U-9.3）；`saveLastOperations()` 未见 tmp+ATOMIC_MOVE（U-9.4）；`quickCheck()` 自动恢复前是否提示管理员/是否保留 `.corrupt-*`（U-9.5）；L1-06 需真实 rollback 失败复现；417k 库上 `countsSequential` 与 `purge` 的实际耗时未测。


#### 9.3.2 L2 回档引擎（`rollback/RollbackManager.java` 1079 行 + `BlockStateUtil`/`ItemStackCodec`/查询过滤器）

| # | 级别 | 位置 | 问题与影响 | 我的复核 |
|---|---|---|---|---|
| L2-01 | **P1** | `RollbackManager.java:457-552` | **回档先回放容器、后回放方块**：同一窗口内既有该容器的存取行、又有它的破坏行（「偷完东西再拆箱」）时，容器阶段先跑，此时方块已不存在 → `getBlockEntity` 不是 `Inventory` → `skipped++`，随后方块阶段只放回一个**空**容器，库存永久丢失，用户只看到 skipped 数变大 | **✓ 亲自确认**（`:457` 容器段在 `:504` 方块段之前；`:546` 只 `setBlockState`） |
| L2-02 | **P1** | `RollbackManager.java:467,641,789` + `ContainerTracker.java:180,199` | **双箱只回放被点开那半的 27 格**：追踪侧用 `ChestBlock.getInventory(...,true)` 的合并 54 格算 delta（`CTA:199`），但写行时 pos 用玩家点开的那一半（`CTA:180`）；回放/undo 侧只取 `getBlockEntity(pos)` 的单个 `ChestBlockEntity`（27 格）→ 物品落在另一半时 rollback 的 `removeItems` 取 0 → skipped；restore/undo 的 `addItems` 塞不下就把物品掉在箱子旁 | **✓ 结构确认** |
| L2-03 | **P1** | `RollbackManager.java:539-545,690-696` | **`ejectInventory` 的 undo 语义不完整**：rollback 把被替换容器的内容掉到地上并记 `WorldItemUndo(added=true)`；`/co undo` 先恢复方块（`stepUndo` 的 blocks 段在 worldItems 段之前，得到**空**容器），再对 `added==true` 只做 `removeDroppedItems`（把地上物品删掉），**从不放回容器** → 一次 rollback+undo = 容器变空 + 掉落物消失（H1 修了记录、没修反向语义） | **✓ 亲自确认**（`:621-634` 在 `:677-696` 之前；`:690-693` 只有 remove） |
| L2-05 | P2 | `RollbackManager.java:520-538` | `#fire`/`#water` 行的 `expected` 是瞬态介质：烧成火的行 `expected=FIRE`，火熄灭后当前位置是空气，而介质消失的写入按设计被 `CoreProtectFabric:293-299` 过滤掉 → 链里没有把状态拉回来的行 → 该行永久 skipped（同一次火灾里「烧成空气」的分支可回档，「烧成火」的分支不能，行为不一致） | 采信（与 E2 的记录侧语义一致，回放侧未配） |
| L2-06 | P2 | `RollbackManager.java:546,632` | 回档/undo 用 `Block.NOTIFY_ALL`（含 `NOTIFY_NEIGHBORS`）写入 → `WorldChunk.setBlockState` 无条件 `onBlockAdded`，`FallingBlock` 会 `scheduleBlockTick` → 沙/砂砾/铁砧等按坐标乱序恢复时塌落；该 tick 在切片之外执行，`expected` 校验看不到 | 采信（vanilla 语义已核对；实际塌落结果属推理） |
| L2-07 | P2 | `RollbackManager.java:619-635` | `stepUndo` 替换方块前**没有** `ejectInventory`（C16 只加在 rollback 侧）→ undo 把当前位置的带库存方块换掉时，`WorldChunk.setBlockState` 先 `removeBlockEntity` 再 `onStateReplaced`，内容直接消失（无掉落、无记录、不计 skipped） | **✓ 亲自确认**（`:632` 之前无 eject） |
| L2-08 | P2 | `RollbackManager.java:302-309,406-427,969` | undo 记录**先 remove 后执行**：`:302 lastOperations.remove(uuid)` 后中途失败/关服 → `publishUndo` 对 undo 任务直接 return（`:427`）→ 记录无法恢复，世界停在「撤了一半」；且 `Files.writeString` 原地截断非原子，JVM 被强杀时可能留下空/半截 JSON → 下次启动**所有玩家**的 undo 记录全丢 | **✓ 亲自确认** `:969` 非原子（U-9.4 升格）；`remove` 顺序见 `:302` |
| L2-09 | P2 | `RollbackManager.java:529,546,632` | 切片内 `getBlockState`/`setBlockState` 对未加载区块会**同步加载/生成**区块（`World.getChunk(..., FULL, true)`），4 ms 预算形同虚设；半径回档可连续生成上千区块（主线程卡顿、磁盘暴涨）。全文件没有 `isPosLoaded` 判断 | 采信（vanilla 语义；未实测规模） |
| L2-10 | P2 | `DatabaseManager.java:1400-1482` + `RollbackManager.java:238-258` | 与 L4-03 同源（**合并计一条**）：`a:` 只作用于 co_block（`appendBlockAction`），容器/告示牌/物品/实体查询完全不看 action → `/co rollback a:#kill` 会把同窗口的容器存取与告示牌编辑一起回档；`a:#chat` 等对 blocks 不追加条件 = 不过滤 | **✓ 结构确认**（合并到 L4-03） |
| L2-11 | P2→P3 | `RollbackManager.java:504-552` + `CoreProtectFabric.java:346-383` | `#piston` 移动写成两条独立行（from: moved→AIR，to: AIR→moved），`r:`/`b:` 过滤可能只含其中一条 → 只回放 from 时目标位置仍留着被推过去的方块（复制），只回放 to 时凭空少一块 | 采信（机制确定；需要跨边界触发，降为 P3） |
| L2-12 | P3 | `RollbackManager.java:222,239,261` | 与 L4-19 同源（**合并**）：`maxBlocks` 实为「每表 5000」，五张表各判一次 → 单次回档最多约 5×maxBlocks 改动量 | **✓ 结构确认** |
| L2-13 | P3 | `RollbackManager.java:400,415,571` | 完成消息口径不一致：`/co undo` 只传 `blocksApplied`（不报物品）；`containerGroups` 是计划命中的位置数（全 skipped 也照报）；告示牌改动计入 `blocksApplied` | **✓ 亲自确认** |
| L2-14 | P3 | `RollbackManager.java:233,276-278` + `DatabaseManager.java:1795-1800` | 先打「Rollback started」再可能因超限被拒；关服过程中提交时查询结果被静默丢弃（只有 LOGGER warn） | 采信 |

**L2 的「已检查但无问题」**：restore/rollback 方块侧严格互逆；容器 restore 倒序（M3）正确；链式回放幂等（`current.equals(target)` 时不计 skipped）；`parseOrNull` 失败→skipped（C15）；`removeItems` 返回真实移动量不造物品（C1）；`ejectInventory` 已去掉 giveStack、控制台同样落地（H1）；物品匹配用 `areItemsAndComponentsEqual`；SNBT 往返成立（`UNCOUNTED_CODEC`/`CODEC` count 缺省 1，已核对 mc-sources `ItemStack.java:165`）；undo 持久化结构 version/UUID/四类全覆盖且读写字段对称；`SERVER_STOPPING→shutdown()` 确实接线（C6）；`submitRead` 回调经 `server.execute` 派发（无跨线程世界写入）；死亡掉落走三参 `dropItem`（`PlayerInventory.java:476`），不会被记成 `-item`；`+item` 拾取行不还原（C7）；`isRunning` 对 `operator == null` 互斥（H3）；purge 与回档无交互风险；job 内写入不产生自我污染。

**L2 的「存疑未证实」**：多格方块（门/床/双高植物）在 `NOTIFY_ALL` 下乱序恢复可能自毁（同 L2-06）；两名玩家对同一区域并发回档（`isRunning` 只按操作者判重）且后到者的 undo 记录残缺；`removeDroppedItems` 的检索盒只有 1 格（`new Box(pos)`），物品被挤出后 undo 漏删；`t:` 缺省 7 天；告示牌只留纯文本（与上游一致）。

#### 9.3.3 L4 命令 / 权限 / 配置 / i18n

| # | 级别 | 位置 | 问题与影响 | 我的复核 |
|---|---|---|---|---|
| L4-01 | **P1**（原报 P0，我降级并说明理由） | `CoCommand.java:436-462` + `DatabaseManager.java:1324-1331` | **`/co purge` 静默忽略 `u:/a:/b:/e:/r:/p:`，只消费 `t:`**：`ParamParser` 解析出的这些字段从未被读取，`doPurge` 对 8 张表一律 `DELETE ... WHERE time < ?` → `/co purge u:Steve t:1d` 实际删掉**所有玩家**一天前的全部数据，不可逆、无确认、无备份，消息只报条数。降为 P1 的理由：README 只声明 `/co purge t:<时间>`、`PURGE_KEYS` 只提示 `t:`，属于「传了不支持的过滤器后被静默放大」而非文档行为失效；但后果不可逆，仍须优先修 | **✓ 亲自确认**（`:449 long before = c.time;` 之后直接 `database().purge(before)`；`PURGE_KEYS = List.of("t:")` 见 `:101`） |
| L4-02 | —（**误报**） | 我复核后否定 | 报告称 `RollbackManager.java:237` 留有 `Thread.sleep(3000L) // TEMP-REPRO F-9.3`。实测：`grep TEMP-REPRO\|Thread\.sleep` 在 `coreprotect-fabric-1.21.11/src` 只命中 `DatabaseManager.java:1742` 的 `Thread.sleep(250L)`（D1 退避），**src 里没有任何 3 秒 sleep**；`git status --porcelain` 亦干净。原因是 L4 读取该文件的时间落在我的 F-9.3 实测窗口内（我 22:10–22:16 临时插入、随后 `git checkout --` 还原）。教训已记入 §9.5 | **✓ 已证伪** |
| L4-03 | P2 | `RollbackManager.java:238-258` + `DatabaseManager.java:1469-1482` | 与 L2-10 同源（**合并**）：`a:` 只过滤 co_block；rollback 对五张表共用同一 `Criteria` 且不看 action → `a:#chat` 之类的回档会把方块、容器、物品、告示牌、实体一起回滚（各最多 maxBlocks），用户无法预期 | **✓ 结构确认** |
| L4-04 | **P1** | `Permissions.java:96-100` vs `README.md:88-100` | **权限组节点名对不上**：README 教用户写 `coreprotect.help`/`coreprotect.lookup`/…，代码用 `node2.equalsIgnoreCase(node)` 与裸名（`NODE_PURGE = "purge"` 等）比较，除 `"all"` 外**永不匹配**；`CoreProtectConfig.load()` 对组内未知节点零校验零告警 → `permissionGroups` 功能整体静默失效（fail-closed，无提权） | **✓ 亲自确认**（读 `Permissions.java:62-102` 与 `README.md:82-102`） |
| L4-05 | P2 | `CoCommand.java:392-400` + `ParamParser.java:42-44` | 「必须给过滤条件」守卫是死代码：`ParamParser` 在解析前就预填 `c.time = now - defaultTimeSeconds`，故 `c.time > 0` 恒真 → `coreprotect.error.missing_user` 不可达 → `/co rollback b:stone`、`/co restore e:Steve` 静默对**全体玩家**在默认 7 天窗口生效 | 采信（与我 b16 记录的「至少一个过滤」实现吻合，但未注意预填导致守卫失效） |
| L4-06 | P2 | `Permissions.java:31-32,67` | 单个 `lookupLevel` 同时覆盖 help/status/inspect/lookup/online/language，且 `a:#chat`、`a:#command` 与方块同权限 → 无法「只给看方块、禁读聊天/命令」（而命令记录含 `/login` 明文密码，见 F-9.1）；默认 1 时普通玩家连 `/co help`、`/co language` 都拿不到；`coreprotect.error.permission` 是死键（无权限时 Brigadier 直接不匹配节点，玩家只看到原版 "Unknown or incomplete command"） | 采信 |
| L4-07 | P2 | `CoCommand.java:464-470` + `CoreProtectConfig.java:298-309` | `/co reload` 无 try/catch、无条件报成功；`load()` 的 catch 只打 "... using defaults" 而赋值都在 `fromJson` 之后 → 实际保留旧值，日志撒谎；只有 `database.file` 有「需重启」提示，`cacheSizeMB/syncMode/batchIntervalMs/batchMaxRows/dataRetention` 改了无任何提示（其中 `syncMode` 确实只在建连接时生效，见 F-9.2） | 采信（与我 b16 的 F-9.2 一致） |
| L4-08 | P2 | `CoCommand.java:511-576` | `/co debug natural` 在出生点造火/水/TNT 真炸，并对 9 个区块 `setChunkForced(..., true)` **只加不减** → forced chunks 随存档持久化；无清理命令；仅 adminLevel=4 门槛 | 采信（`setChunkForced` 是否持久化属存疑，见下） |
| L4-09 | P2 | `CoCommand.java:234` + `DatabaseManager.java:1261-1273` | 与 L1-08/L1-09 同源（**合并到 L1-08**）：`/co status` 走无预算的 `countsSequential`，带 20 s 预算的 `counts()` 已成死代码 | **✓ 结构确认** |
| L4-10 | P3 | `CoreProtectConfig.java:31,215` | D10 只修一半：`database` 字段加了 `volatile`，但 `load()` 是**整对象替换**（`volatile` 保护的是引用本身）；`lookup/rollback/permissions/permissionGroups/logging` 同理 | 采信 |
| L4-11 | P3 | `CoreProtectConfig.java:194-215,306-309` | Gson 未知键静默忽略，拼错键零告警（`lookupLevel` 写到顶层、`permission_groups` 下划线写法等）；语法错误时保留旧值却打 "using defaults" | 采信 |
| L4-12 | P3 | `LookupService.java:50-53` | 任意 `#xxx` 都当合法 action → `a:#contaner` 走 `#` 分支返 0 行并显示 "No results found"，与「真的没数据」不可区分 | 采信 |
| L4-13 | P3 | `CoCommand.java:40-89,211-226` | 裸 `/co`、`/co rollback`、`/co purge`、`/co debug` 都回原版 "Incomplete command"（父节点无 `.executes`）；help 固定 12 行，不列 `/co i`、`/coreprotect`、`debug natural`，也不显示权限等级 | 采信 |
| L4-14 | P3 | `DatabaseManager.java:997,1077,1179-1186` | `b:`/`e:`/`r:` 在容器/告示牌/聊天/命令/会话查询被静默忽略（`queryMessages` 只有时间+用户名，连 `r:` 都没有）；`e:` 一词两义（排除用户 vs 排除方块名），README 却并列 | 采信 |
| L4-15 | P3 | `DatabaseManager.java:126,932,1387` | 语言偏好按玩家名存（`co_user(user TEXT PRIMARY KEY, language TEXT)`）→ 改名丢设置、离线模式同名继承 | 采信 |
| L4-16 | P3 | `LookupService.java:89-90,155` + `RollbackManager.java:274,379` + `CoCommand.java:454` | 硬编码英文塞进 `{0}`（中文玩家看到「数据库错误：database read failed」）；`:89-90` 承诺「结果会写进服务器日志」对 RCON 不实；`Translator.format` 失败时回退当前语言的坏 pattern 而非 en_us | 采信 |
| L4-17 | P3 | `ParamParser.java:79,88` | `r:`/`p:` 超限静默夹到 1000，无提示 → 管理员以为清过整个区域 | **✓ 与我们 v1.9.3 的 MAX_RADIUS/MAX_PAGE 实现一致** |
| L4-18 | P3 | `CoCommand.java:252-270` | `/co status` 向 `lookupLevel`（默认 1）玩家暴露数据库绝对路径、11 个 logging 开关、权限组数量等运维信息 | 采信 |
| L4-19 | P3 | `CoreProtectConfig.java:266-269` | 与 L2-12 同源（**合并**）：`rollback.maxBlocks` 只夹下界无上界，且是「单表」上限 | **✓ 结构确认** |
| L4-20 | P3 | `README.md:218`、`OVERVIEW.md:126`、`OVERVIEW_EN.md:126`、`README.md:102` | 文档脱节：三处仍写「大型回滚在主线程执行（受 maxBlocks 上限保护）」，与 README:31 的「读线程池 + 4 ms 切片」自相矛盾；`README:102`「命令方块默认无权」只对 admin 级成立（命令方块 level 2 可跑 `lookupLevel=1` 的 `/co lookup`，含 `a:#chat`） | 采信 |

**L4 的「已检查但无问题」**：`t:` 全边界（`TimeUtil.parseSeconds` 用 `Math.*Exact`，0/负/空→invalid_time）；`r:`/`p:` 非法值报错（除静默夹取）；未知键报 `invalid_param`；lookup 侧 `a:` 12 种写法分派正确；页脚 `p:` 封顶 + 仅满页输出；控制台/RCON 10 s 上限与补投路径；`sendRows` 用 `result.failed()` 区分失败与空；SQL 全参数化；权限基础行为（控制台/RCON level4、命令方块按自身权限、离线模式不按名字、`levelCheck` fail-closed、越界回落）；5 个语言文件各 118 键集合一致且占位符编号逐一匹配；`plain()` 剥 `§`/控制符覆盖 chat/command/sign；补全只查内存；`languageSet` 白名单；undo busy/empty 提示；`/co online` 行为。

**L4 的「存疑未证实」**：MessageFormat 缺参行为（按 JDK 语义推断）；RCON 超时补投是否真丢；`setChunkForced` 是否持久化到 `level.dat`（若不持久化则 L4-08 降级）；`countsSequential` 真实耗时；命令方块实际 permission level；`co_user` 改名后旧记录清理；`maxBlocks` 很大时内存峰值；`buildLookupCommand` 用 `t:<secondsAgo>` 重建页脚导致翻页窗口随时间平移（与上游同行为）。
#### 9.3.4 L3 日志采集（event / container / mixin / `CoreProtectFabric` 的 hook）

| # | 级别 | 位置 | 问题与影响 | 我的复核 |
|---|---|---|---|---|
| L3-01 | **P0** | `CoreProtectFabric.java:369-370` | **活塞记录坐标整体错位**：`PistonBlockEntity` 的 `pos` 是**目标格**（`PistonBlock.java:300-304`：`blockPos3 = blockPos3.offset(direction); world.addBlockEntity(PistonExtensionBlock.createBlockEntityPiston(blockPos3, …))`），而 `from = extending ? pos.offset(facing) : pos.offset(facing, 2)`、`to = extending ? pos.offset(facing, 2) : pos.offset(facing)` 把 `pos` 当活塞本体 → 延伸偏 +2 格、回缩偏 +1 格。真实源格写入在 `PistonBlock.move()`（`onBlockEvent` 路径，不在 `#piston` 窗口内，`hookSetBlockState` 因 `cause == null` 直接 return），目标格写入的旧状态是 `MOVING_PISTON` 又被 `isPistonPart` 过滤 → **没有任何正确记录兜底**。后果：`/co lookup` 指错位置；`/co rollback` 会把方块放到错误位置并把那里的**无罪方块**清成空气 | **✓ 亲自确认坐标语义**（mc-sources `PistonBlock.java:300,303-304`）；修复公式：`from = extending ? pos.offset(facing.getOpposite()) : pos`、`to = extending ? pos : pos.offset(facing)` |
| L3-02 | **P0** | `mixin/FireBlockMixin.java:29` | **`@At("TAIL")` 挡不住正常 return**：mixin 的 `TAIL` 只匹配方法**最后一个** return，`RETURN` 才匹配全部。`FireBlock.scheduledTick`（mc-sources `FireBlock.java:131-163`）在 `:157`（周围无可燃方块，最常见）、`:162`（age==15 且下方不可燃）各有一条正常 return → `NaturalBreakCause.clear("#fire")` 不执行；`set()` 同类只 `depth++`，之后每次合法 clear 只减 1、**永远回不到 0**，直到 5 s 看门狗（`NaturalBreakCause.pruneStale`）整窗丢弃。窗口内所有方块变化（含玩家破坏/放置）都被记成 `#fire` 自然行为 → 与玩家事件行**双记 + 栽赃** | **✓ 亲自确认机制**（`FireBlockMixin.java:26/33` 为 HEAD/TAIL；`NaturalBreakCause.set` 的 depth 逻辑见 `NaturalBreakCause.java:100-107`）；修复：改成 `at = @At("RETURN")` |
| L3-03 | P1 | `container/ContainerTracker.java:154,163,179` | 与 **L1-01 同源（合并）**：M5 的 `#container` 署名修复是死变量，`insertContainerAsync` 仍传 `w.userName()` | 与 L1-01 一致 |
| L3-04 | P1 | `container/HopperTracker.java:60-62,76-85` | **双箱被两个漏斗各记一份**：HopperTracker 用查询坐标做 key（`wid + "|" + x,y,z`），而 `HopperBlockEntity.getInventoryAt` 对双箱返回**合并 54 格**（mc-sources `HopperBlockEntity.java:373`）→ 两半各有一条基线、内容相同，同一笔变动被两个 key 各写一条 `#hopper` 行 → `/co rollback` 按行累加后物品凭空多/少（红石农场成倍错账） | 采信（机制清楚；C11 只修了玩家侧 `containerAt`，漏斗侧没处理） |
| L3-05 | P1 | `event/BlockEventListener.java:43-56` + `CoreProtectFabric.java:332` | **容器被破坏时内容不上账**：苦力怕/TNT 炸掉、玩家挖掉、活塞推掉装有物品的箱子/木桶/潜影盒/熔炉时只写方块行（`insertBlockAsync(new BlockLog(…))` / `naturalLog(…)`），没有任何 `co_container` 行；`ContainerTracker` 只在 `onScreenClosed`/`onDisconnect`/`onDeath`/`finalizeAll` 时 diff → `/co rollback` 只能恢复出**空箱**，掉落物被岩浆/漏斗吃掉则彻底无痕 | 采信 |
| L3-06 | P1 | 无钩子（原版 `PistonBlock.java:283-295`） | 活塞推不动的方块（花/红石线/铁轨/告示牌）被就地销毁（`dropStacks` + `setBlockState(pos, AIR, NOTIFY_LISTENERS \| FORCE_STATE)`），发生在 `PistonBlock.move()` 里、不在 `#piston` 窗口内 → 无任何记录，`/co rollback` 救不回 | 采信 |
| L3-07 | P1 | `mixin/ServerPlayNetworkHandlerMixin.java:59` + `DatabaseManager.java:859` | 与 **F-9.1 同源（合并）**：命令原文（含 `/login` 明文密码）直接 `INSERT INTO co_command(time, name_id, message)`，无黑名单/脱敏；`lookupLevel` 默认 1 即可读别人的密码 | 与 F-9.1 一致 |
| L3-08 | P1 | `event/MessageEventListener.java:17-20` | 聊天**没有限流**（`allowPacketLog` 只挂在命令与告示牌上）→ 刷屏客户端可每 tick 一条把写队列（65536）打满，`countDropped` 丢的是**所有**记录；也没有长度截断 | 采信 |
| L3-09 | P1 | `CoreProtectFabric.java:222-239` | 包钩子限流（60 条/分钟/人）**静默丢弃**：`return false` 后调用方直接不记，不计入 `droppedWrites` → `/co status` 看不到，事后无法区分「没做过」与「被限流丢了」；且命令与告示牌共享同一配额（疯狂改告示牌会挤掉命令记录） | **✓ 亲自确认**（`CoreProtectFabric.java:222-239` 的 `allowPacketLog` 只 WARN 一次，无计数） |
| L3-10 | P1 | `container/HopperTracker.java:76,82,100` + `ContainerTracker.java:216` | **每漏斗每 tick 最多 3 次全量快照 × 每栈 SNBT 编码**：`HopperTracker` 每 tick 调三次 `diffAndLog`（每次 `snapshot` 全容器遍历），`ContainerTracker.snapshot` 每个非空栈调一次 `ItemStackCodec.encodeIdentity`（内部每次新建 `RegistryOps.of(...)` + `UNCOUNTED_CODEC.encodeStart` + `toNbtProviderString`），写入后还要 `rebaseline` 再全量快照一次。满格 54 格双箱贴两个漏斗 ≈ 每 tick 324 次编码，全在主线程 → MSPT/GC 放大器 | 采信（代码路径确定；MSPT 未测） |
| L3-11 | P2 | `util/BlockStateUtil.java:68-78` + `CoreProtectFabric.java:385-392` + `RollbackManager.java:547` | 告示牌只存**正面**（`sign.getFrontText()`），背面丢弃；自然破坏的 `naturalLog(..., meta=null)` → 火烧/炸毁的告示牌回档成空白牌；而 `signLinesToJson` 已支持 `"side"` 字段，写入侧没用 | 采信 |
| L3-12 | P2 | 无钩子（原版 `FallingBlockEntity.java:96,194`） | 重力方块（沙/砾/混凝土粉/铁砧/龙蛋）掉落：`spawnFromBlock` 清源格与落地 `setBlockState` 都在任何 cause 窗口之外 → 源格与落点均无记录 | 采信 |
| L3-13 | P2 | 无钩子（`BedBlock.java:95,98` / `RespawnAnchorBlock.java:148`） | 床两格、重生锚自身那格在 `createExplosion` **之前**就被 `removeBlock`（不在爆炸窗口内）→ 爆炸回档救不回这几格 | 采信 |
| L3-14 | P2 | 无钩子（原版 `FarmlandBlock.java:104-119`） | 玩家/生物落地踩踏耕地 → `setToDirt` 直接 `setBlockState`，无 cause、无事件 → 常见 grief 无法回滚（连带被顶走的作物也无记录） | 采信 |
| L3-15 | P2 | 双格结构另一半（`TallPlantBlock.java:126`、`BedBlock.java:189`、`DoorBlock`） | 破坏事件只报被点那格，另一半由 `setBlockState`/邻居更新移除（`SKIP_DROPS`）→ 高草/门/床回档后剩半格 | 采信 |
| L3-16 | P2 | 事件注册全集（仅 9 处） | 无 `UseEntityCallback`/`AttackEntityCallback`：箱子矿车/漏斗矿车/船/驴驮箱/展示框/盔甲架/讲台里的物品，以及这些实体被拆/点燃，全部无痕 | 采信 |
| L3-17 | P2 | `mixin/ItemEntityPickupMixin.java:75-79` | 拾取行（`+item`）不带 NBT（10 参构造器，nbt 缺省 null；对比 `PlayerEntityDropMixin.java:40-45` 写了 `encodeIdentity`）→ 回档「同 id 白板物品」，附魔书/改名物品/潜影盒内容丢失 | 采信（与 C5/E8 的改造不一致） |
| L3-18 | P2 | 无钩子（15 项 mixin 里只有 `LeavesBlockMixin` 注入 randomTick） | 冰/雪融化、铜氧化、珊瑚失活、草方块退化等随机刻变化无记录 → 回档后无法复原，也难与人为破坏区分 | 采信（未逐类核对 randomTick） |
| L3-19 | P2 | `container/ContainerTracker.java:131-142` | 多人同开一个容器：A 先关时 `diffAndLog` 汇总了「开界面以来」的全部变化（含 B 做的），随后 `rebaselineAt` 把 B 的基线刷成当前值 → B 关界面时自己的操作不再产生记录（错记到 A + B 无记录） | 采信 |
| L3-20 | P3 | `mixin/ServerPlayNetworkHandlerMixin.java:42-51` | 非玩家来源命令（控制台/RCON/命令方块/datapack）**完全不记录**：`coreprotect$logCommand` 从 `ServerPlayNetworkHandler.getPlayer()` 取玩家，控制台路径不经此 mixin → `/op`、`/ban`、`/co rollback` 的发起者不可查 | 采信 |
| L3-21 | P3 | `DatabaseManager.java:859,872` | `co_command`/`co_chat` 没有 world 列 → 多世界服务器无法按维度检索聊天/命令，与 `co_block` 的 `wid_id` 设计不一致 | 采信 |

**L3 的「已检查但无问题」**：`WorldMixin` 4 个注入点覆盖 1.21.11 全部写方块入口（2 个 `setBlockState` 重载 + `removeBlock` + `breakBlock`，低阶重载都委托高阶）；`ExplosionImpl.explode()`、`FlowableFluid.flow`、末影人两个 Goal、`PistonBlockEntity.tick`、`LeavesBlock.randomTick` 均为单出口/顺序体，`#explosion`/`#water`/`#enderman`/`#piston`/`#decay` 窗口不泄漏（`#decay` 走 `hookBreakBlock`，可记录）；`PISTON_LOGGED` 的 WeakHashMap 去重有效；`insert*Async` 主线程只构造对象 + `trackUser`（纯内存、跳过 `#` 前缀、上限 100），字典 id 在写线程解析；`flushWrites` 失败整批回滚 + drain 回队不丢行；玩家侧双箱已由 `containerAt` 合并 54 格 + `rebaselineAt` 的 `DoubleInventory.isPart` 映射处理。

**L3 的「存疑未证实」**：三棵树未 diff（L3-01/02/17 需在 1.21 与 26.1.2 复验，同源极可能同病）；26.1.2 的 `setBlock` 重载数未核；`PistonBlock.onBlockEvent` 与 BE tick 是否可能重叠只是源码推断；L3-06/L3-13 未构造运行用例；`lookupLevel` 调 0 时的泄露面未实测；`pruneStale` 会掩盖「泄漏发生频率」这一诊断信号。

#### 9.3.5 L5 移植差异 / 工具链 / 文档一致性

| # | 级别 | 位置 | 问题与影响 | 我的复核 |
|---|---|---|---|---|
| L5-01 | P1 | 三树 `util/Permissions.java`（1.21:92 / 1.21.11:97 / 26.1.2:97）+ 根与三树 `README.md`/`OVERVIEW*.md` | **文档要求的权限节点名前缀与代码不一致**：文档教写 `"coreprotect.help"`、`"coreprotect.rollback"`，代码只匹配裸名（`NODE_HELP = "help"`，`node2.equalsIgnoreCase(node)`）→ 按文档配置的权限组一个节点都不匹配，玩家静默回退到 OP 等级判定；只有默认 `"all"` 生效，所以问题从未暴露 | **✓ 亲自确认**（`Permissions.java:18-29` 全裸名 + `:97` 比较；`README.md:90-94,101` 全带前缀）；建议在 `has()` 里 strip `coreprotect.` 前缀（一处改动修三树，不必改 6 份文档） |
| L5-02 | P1 | `coreprotect-fabric-26.1.2/src/main/java/net/coreprotect/fabric/mixin/ServerPlayNetworkHandlerMixin.java:60-62` | **告示牌钩子挂错层**：26.1.2 钩的是包入口 `handleSignUpdate(ServerboundSignUpdatePacket)` 的 RETURN，而该方法只做 `filterTextPacket(...).thenAcceptAsync(...)` 排队后立即返回；真正写入在私有 `updateSignText`（`ServerGamePacketListenerImpl.java:2006`，内含 `hasChunkAt`/`instanceof SignBlockEntity` 守卫）。后果：未加载区块、方块实体不是告示牌也各记一条 `co_sign`，且记录早于写入；该文件 javadoc 还自称「only a sign the server really rewrote are recorded」→ **上轮 B8 在 C 树被回退** | 采信（L5 附了 mc-sources 26.1.2 的 `:2001`/`:2006` 行号）；修复：改钩 `updateSignText` |
| L5-03 | P2 | A/B `ServerPlayNetworkHandlerMixin.java:47-49`、C `:45-47` | **签名命令钩子也挂错层**：三树都钩包入口（yarn `onChatCommandSigned` / mojmap `handleSignedChatCommand`）的 RETURN，真正执行在 `handleCommandExecution(ChatCommandSignedC2SPacket, LastSeenMessageList)` / `performSignedChatCommand(...)`；入口 RETURN 时 last-seen 校验失败会 `disconnect(CHAT_VALIDATION_FAILED)`、或 `tryHandleChat` 只是 `server.execute(...)` 排队 → **幻影 `co_command` 行**（未执行的命令被记录）且与命令自身日志乱序。旁证：未签名路径三树都钩动作方法（yarn `executeCommand`、mojmap `performUnsignedChatCommand:1491`），只有签名路径错层 → 上轮 §8.5 的补丁方向错 | 采信；修复：改钩动作方法 |
| L5-04 | P2 | 根 `README.md:25,28,30,32`、根 `OVERVIEW.md:49,69,78,89,92,95`、根 `OVERVIEW_EN.md` 同；根因 `.build-tools/bump-version.ps1:13-23` | 仓库根三份文档版本停在 **1.9.1**（树内已是 1.9.3），因为 bump 脚本只改树内文档 → 每版必漂移；Modrinth `wiki_url` 正指向该 README | 采信（发布时未核对根文档） |
| L5-05 | P2 | `coreprotect-fabric-1.21.11/{README.md:37,OVERVIEW.md:107,OVERVIEW_EN.md:107}` + 根文档同段 + `.build-tools/modrinth/body.md:88,191` | 文档写 Loader **≥0.16**，而 `fabric.mod.json` 要求 **≥0.19.3**（26.1.2 树文档已正确写 0.19）→ 按文档装旗舰 jar 会被 loader 硬拒；F19 只改了 manifest，文档没跟上 | 采信 |
| L5-06 | P2 | `.build-tools/test-cp.ps1:16`（`test-migrate:14` / `test-probe:12` / `test-quick:13` / `test-russian:16` 同）vs `test-full.ps1:12-17,26` | **F11 只修了 `test-full.ps1`**：其余 5 个测试脚本仍用未校验的 `-WorkDir` + 通配 `-like "*$WorkDir*"` 杀 java（`Stop-Process -Force`）→ 传错参数可能杀掉无关进程 | 采信 |
| L5-07 | P3 | `.build-tools/modrinth/body.md:80-82,183-185` | Modrinth 项目页正文仍写 `-1.9.2.jar`；`publish.ps1 -UpdateProject` 会把它推到线上 | 采信 |
| L5-08 | P3 | 三树 `OVERVIEW.md:105,128`、`OVERVIEW_EN.md:105,128`、树 `README.md:221` | 文档称 `defaultRequire: 0`（best-effort），而三树 `coreprotect.mixins.json` 实为 `1` → 事实相反（钩子失效＝开服失败）；旧树 1.21.2/26.2 确是 0，文档没跟上 | 采信 |
| L5-09 | P3 | `coreprotect-fabric-26.1.2/.../mixin/FireBlockMixin.java:18,25` | 26.1.2 里唯一不带方法描述符的 `@Inject(method = "tick")`（当前 `FireBlock` 只有一个 tick，能命中；将来加重载会 `defaultRequire:1` 直接开服失败） | 采信 |
| L5-10 | P3 | 三树 `README.md:22,205` vs `OVERVIEW.md:49`/`OVERVIEW_EN.md:49` | 同树自相矛盾：README 说「手持方块右键（不放置方块）」（=代码行为），OVERVIEW 说「放置方块」→ 用户可能以为放置已被记录 | 采信 |
| L5-11 | P3 | `DatabaseManager.close()`：1.21 `:709→712`、26.1.2 `:709→712` vs 1.21.11 `:708→710` | §8.4 记为「已知残留」的 WAL 顺序修复（先 `closeStatements()` 再 `wal_checkpoint(TRUNCATE)`）**未移植到 1.21 与 26.1.2** | 采信（与 E/A3 同源） |
| L5-12 | P3 | `.build-tools/modrinth/publish.ps1:289-304` | 只校验 version_number / jar 内版本 / 源文件新鲜度 / SHA，**不校验 `game_versions` 与 jar 内 `fabric.mod.json` 的 `minecraft` 范围一致** → 可能给 1.21 的 jar 标 1.21.11 | 采信 |
| L5-13 | P3 | `.github/workflows/build.yml:66` | `if grep -rlP …`：grep 退出码 2（自身错误）被当作「无匹配」→ ASCII 门禁 **fail-open**；另未加 gradle wrapper-validation | 采信 |
| L5-14 | P3 | `.build-tools/` 无 `release-v193.ps1`（最新仍是 `release-v192.ps1`） | v1.9.3 已发布但没有对应脚本；v181/v182/v190/v191 都有 `-AllowHistoricalRerun` 保护，唯独 v192 是「活」脚本 → 下个版本发布者容易改错 | 采信 |
| L5-15 | P3 | `.build-tools/modrinth/versions.json` 的 `environment` 字段 | `publish.ps1` 从不发送 environment（注释：带了会 405）→ 死数据，读者误以为生效 | 采信 |
| L5-16 | P3 | `.build-tools/modrinth/probe5.ps1:88`、`probe6.ps1` | 脚本会**真的在 Modrinth 上创建版本**（仅注释提醒手工删），属发布链之外的活跃危险面 | 采信 |

**L5 的三树等价性清单（40 文件全量 numstat + 逐 hunk 语义核对）**
- 完全等价（纯改名）：`RollbackManager`（含 M6 `setItem` 槽位写回）、`LookupService`（1.21 与 1.21.11 逐字节相同）、`ContainerTracker`、`Inspector`、`BlockEventListener`、`Dispenser/HopperTracker`、`EntityEventListener`、`SessionEventListener`、`Translator`、`Messages`、`BlockStateUtil`、`TimeUtil`、`Criteria`、`NaturalBreakCause`、`BStatsMetrics`、`UpdateChecker`、`ParamParser`、`CoreProtectConfig`、`ItemStackCodec`。
- 必要差异（版本 API）：`isInBuildLimit`(1.21)/`isInLoadLimit`(1.21.11)/`isInValidBounds`(26.1.2) 强度相同（**§8.4 说「语义略弱」不准确**，可更正）；`Permissions` 三种写法等价；`ExplosionMixin` `affectWorld(Z)` vs `explode()I`；`PlayerEntityDropMixin` 描述符 3 形态；`ItemStackCodec` 的 `CODEC`+`copyWithCount(1)`（`count:1` 不影响解码/匹配，数量一律取 DB `amount`）；`CoCommand.debugNatural` 在 C 树改为下一 tick 放置（仅调试命令）；26.1.2 用 `implementation` + `release=25`/`JAVA_25`（mojmap + Java 25 必需）。
- 疑似遗漏已单独立项：C 树告示牌钩子（L5-02）、三树签名命令钩子（L5-03）、A/C 的 `close()` 顺序（L5-11）、C 树 `FireBlockMixin` 描述符（L5-09）。

**L5 已检查且无问题**：40 文件 numstat 全表与全部 B-C hunk 语义；15 个 mixin 注入点与 1.21.11/26.1.2 源码逐一对齐（`DispenserBlockMixin:23,31`/`DropperBlockMixin:20`/`LeavesBlockMixin:18,25`/`PistonBlockEntityMixin:23,30`/`WorldMixin:28,35,42,49`/`HopperBlockEntityMixin:18`/`ServerPlayerEntityMixin:14`/`ItemEntityPickupMixin:38,53`/`PlayerEntityDropMixin:27` 描述符全部正确）；`coreprotect.mixins.json` 三树 15 项一致；`MOD_VERSION="1.9.3"` 三树一致；5 语言 × 3 树 = 918 键全 118 且占位符零不匹配；`gradle.properties`/jar 命名/`versions.json` 三方一致；`release-v192.ps1` 的 draft→上传→删旧 asset→发布顺序可重入；`new-version.ps1` 路径守卫；`.gitignore` 覆盖（`token.txt`/`*.pat`/`coreprotect.db*`/`payload-*`/zip/jdk）；63 个被跟踪脚本零非 ASCII；`README.md:14` 的 12 个节点与 `Permissions.java:18-29` 一致；bStats 33739、`batchIntervalMs=250`/`batchMaxRows=1000`、schema v3 索引、config 全部键名/默认值/clamp 与树 README §105-153 一致、命令表与 `CoCommand:41-92` 注册项及分级一致。

**L5 的 F1–F19 回归核查**：仍正确的 17 条（F1/F2/F3/F4/F5/F6/F7/F8/F9/F10/F12/F13/F14/F15/F16/F17/F18/F19 的 manifest 部分）；**不完整或被回退**：F11（只修 1/6 脚本 → L5-06）、F19 的文档部分（→ L5-05）、B8 在 26.1.2（→ L5-02）、§8.5 签名命令补丁（→ L5-03）、§8.4 WAL 顺序（→ L5-11）。无「校验过严拦正常发布」的证据。

**L5 的存疑未证实**：26.1.2 `FireBlockMixin` 无描述符当前能命中（`FireBlock.java:134` 只有一个 tick），未来重载增多未实测；5 个 `test-*.ps1` 用固定端口 25575/25577 + `rcon.password=testpass`，并行会端口冲突（未实测）；`versions.json.environment` 与 Modrinth 审核队列的关系（既存队列，未复核线上）；1.21 树 `isInBuildLimit` 的 ±30M 与 `isInLoadLimit` 的 `ChunkPos.isLoadable` 边界相同，1.21 多一道过滤属 over-filter（未实测）。


### 9.4 实测复现记录

- **R-9.1 · F-9.3 的实测确认（复现成功，1.21.11，2026-11-14 22:16）**：临时在 `rollback/RollbackManager.java` 的 `submitRead` lambda 首行插入 `Thread.sleep(3000L)`（标记 `TEMP-REPRO F-9.3`），用 `gradle runServer`（Gradle 9.7.1 + jdk-21.0.12.1+1、`run/` 复用旧库、RCON 25575/`testpass`）连发两条 `co rollback t:2h`（间隔约 1 s）。**结果**：两条都回 `CoreProtect - Rollback in progress, this may take a moment...`，**没有出现 `coreprotect.error.busy`** → 证明第一次命令还停在查询阶段时，第二次没有被互斥拦住；随后用 `git checkout -- <RollbackManager.java>` 还原、`git status --porcelain --untracked-files=no` 只剩 `.build-tools/audit/AUDIT-NOTES.md`。
  - 说明：测试库当时为空（`co status` 全 0 行），所以看不到「容器物品被应用两次」的直接后果；该后果由 `RollbackManager.java:236-289` 的代码路径推得（两 Job 各自 `stepRollback`）。
  - 复现脚本：`.dsh-scratch/repro-race.ps1`（未跟踪，不入库）；日志 `.dsh-scratch/repro-race-log.txt`。
  - **过程中的副作用（重要）**：L4 子代理恰好在我保持着这段临时代码时读取了 `RollbackManager.java`，于是把 `Thread.sleep(3000L) // TEMP-REPRO F-9.3` 报成「发布版留有调试脚手架」（L4-02）。复核：`grep TEMP-REPRO|Thread\.sleep` 在 `coreprotect-fabric-1.21.11/src` 只命中 `DatabaseManager.java:1742` 的 `Thread.sleep(250L)`（D1 退避），src 无任何 3 秒 sleep，`git status --porcelain` 干净 → **L4-02 是误报**。教训：做实测时要么在独立 worktree/副本里改，要么事先告知并行的审查者「这段时间的 src 不代表发布状态」。

### 9.5 未证实 / 推理级条目

- **U-9.1 · bStats `serviceId` 归属存疑**：`config/CoreProtectConfig.java:114` 默认 `serviceId = 33739`、`metrics.enabled = true`。若该 id 并非本项目在 bstats.org 注册的 id，则本模组的统计会记在别的项目名下（数据归属错误），且服务器会在不知情的情况下持续上报。离线无法核实（bStats 无公开按 id 反查的接口）；建议作者确认，或在无法确认时默认 `0`（即关闭）。
- **U-9.2 · `NaturalBreakCause` 去重记忆只保留最近一次**：`util/NaturalBreakCause.java:51-77` 的 `alreadyLoggedBreak`/`alreadyLoggedSet` 只记 `lastBreakPos/lastBreakOld` 与 `lastSetPos/lastSetOld/lastSetNew`。同一 cause 窗口内、同一坐标若出现两次 `setBlockState(pos, air)` 且中间未经过 break 钩子（`lastBreakOld` 仍停留在第一次的旧状态），第二次会被误判为重复而漏记。未构造出真实 vanilla 调用序列，标记为推理级。
- **U-9.3 · v1 旧库是否缺表**（L1）：`migrate()` 只从 v2 迁到 v3；v1 库缺 `co_container`/`co_sign`/`co_entity` 等表时是否会被 `CREATE TABLE IF NOT EXISTS` 补齐、旧数据能否读，未实测。
- **U-9.4 · undo JSON 非原子写**（L1，已由我复核确认写法）：`RollbackManager.saveLastOperations()` 用 `Files.writeString(path, root.toString())`，进程被 kill 于写入中途可留半个 JSON；下次启动 `loadLastOperations()` 解析失败的处理分支未实测（`RollbackManager.java` 的 `readLastOperations`）。
- **U-9.5 · `quickCheck()` 自动恢复**（L1）：`DatabaseManager` 启动时的 `PRAGMA quick_check` 若判定损坏并自动恢复，是否会保留 `.corrupt-*` 备份文件并告知管理员，未实测。
- **U-9.6 · 三棵树未逐条 diff L3 的钩子类条目**（L3）：L3-01/L3-02/L3-17 在 1.21 与 26.1.2 上「同源极可能同病」，但未逐文件确认（L5 的三树 numstat 显示这些文件等价，间接支持）。
- **U-9.7 · 26.1.2 `FireBlockMixin` 无方法描述符**（L5）：当前 `FireBlock` 只有一个 `tick` 能命中；将来 MC 增加重载时的失败形态（`defaultRequire:1` → 开服崩溃）未实测。
- **U-9.8 · `test-*.ps1` 端口固定**（L5）：5 个测试脚本都用 25575/25577 与 `rcon.password=testpass`，两脚本并行会端口冲突，未实测。
- **U-9.9 · `publish.ps1` 的 stale 检测对 `-P` 覆盖参数无效**（L5）：比较 `src/**`+`build.gradle`+`gradle.properties` 与 jar 时间戳，若构建时用 `-P` 覆盖版本则检测可能失效，未验证。
- **U-9.10 · 1.21 树 `isInBuildLimit` 的 over-filter**（L5）：1.21 原生 `setBlockState` 不拒水平越界，该树多一道 ±30M 过滤，属「行为等价但更严」，边界用例未跑。
- **U-9.11 · 自然变化覆盖率**（L3）：`randomTick` 类变化（冰/雪融化、铜氧化、珊瑚失活）未逐类核对，L3-18 只给了类目而非穷举。

### 9.6 与上一轮对照：为什么上次没发现

上一轮修完 60 条 + 复查轮 15 条后，这一轮又找出 84 条。归纳成 6 个系统性原因（每条都对应本轮的条目）：

1. **只审「有没有记录」，没审「记的对不对、该不该记」**。上轮 P0/E1/E2 全在抓「漏记」，于是没人看 `hookSetBlockState` 的**坐标算术**（L3-01 活塞 `pos` 语义）、也没人问「窗口的关闭点是不是覆盖了所有 return」（L3-02 的 `TAIL` vs `RETURN`）、更没人问「命令原文该不该原样入库」（L3-07/F-9.1）。这三条都是「记录点选错」而非「没有记录点」。
2. **修复的验收口径是「存在一个实现」，不是「所有同类实例都排查过」**。F11 只加固了 `test-full.ps1`（还有 5 个同形脚本 → L5-06）；F19 只改了三份 manifest（文档仍写 0.16 → L5-05）；B8 只在 yarn 树改了告示牌钩子（26.1.2 回退 → L5-02）；§8.5 给签名命令补了钩子但没核「钩在入口还是动作点」（→ L5-03）；§8.4 把 WAL 顺序列为「已知残留」后就再没进待修清单（→ L5-11）。
3. **文档与代码从未做「字符串值级」对照**。配置键名/默认值/范围都核过（所以 118 键、clamp 区间全对），但**权限节点是字符串值**：文档教 `coreprotect.help`、代码只认 `help` → 权限组特性整体静默失效（L4-04/L5-01）。根目录三份文档的版本号停在 1.9.1（L5-04）、`defaultRequire` 文档写 0 代码是 1（L5-08）也属同类：文档被当成「叙述」而不是「契约」。
4. **只验证「新值读得到」，不验证「新值真的被使用」**。D10 把 `Database` 字段改成 `volatile` 解决了可见性，但没人验证 `PRAGMA synchronous` 是否随 `/co reload` 重设（F-9.2）。
5. **互斥只覆盖了「已经开始的」状态**。上轮加的 `runningJobs` 去重只在切片启动后生效，`startRollback` 与首次 `runningJobs.add` 之间隔着 5 个查询（F-9.3）；同理 M5 的 `#container` 署名修复写了却没人检查变量是否被读取（L1-01/L3-03 的死代码）。
6. **并行审查被我的临时补丁污染**。我为 F-9.3 做实测时在 `RollbackManager.java` 里插了 `Thread.sleep(3000)`，L4 恰好在这段时间读文件，于是报了「发布版留有调试脚手架」（L4-02 误报）。教训已记入 9.4：实测要么在独立 worktree，要么先声明时间窗。

**结论**：上一轮是「按清单修」，这一轮是「按不变量查」。凡是「一个函数的所有出口」「一个字符串值在文档与代码两侧」「一个修复的所有同类实例」这类**全称命题**，上一轮都没有系统化覆盖。

### 9.7 代码类遗留项核查

用户在澄清阶段点名要求「代码类遗留项一并核查」（v1.9.3 §8.5 的 known gaps）。逐项结论：

| 遗留项 | 核查结论 | 证据 |
|---|---|---|
| sqlite-jdbc 3.50.2.0 兼容 | **未发现不兼容**：三棵树本轮的 `test-full.ps1` 全绿（建库、迁移、批量写、`/co purge`、`/co rollback`、`/co undo`、`co status` 全过），未见 `SQLException`/`UnsupportedOperationException`；但没有针对 JDBC 的专项用例，判定为「弱证据通过」 | 三棵树 `FULL_TEST_DONE`、`MIXIN_FAILURES: no` |
| B8 包钩子限流 | **仍有缺陷**：`allowPacketLog` 的丢弃既不计数也不写进 `/co status` → 已升级为 **L3-09（P1）**；且聊天路径完全没有限流 → **L3-08（P1）** | `CoreProtectFabric.java:222-239` |
| 关库 / 超时语义 | **仍有缺陷**：`execute()` 超时返回 null 却不取消任务（**L1-03**）；`drainWrites()` 的有界重试只覆盖批次级失败，行级失败仍被吞（**L1-02**）；1.21 与 26.1.2 的 `close()` 里 `wal_checkpoint` 仍早于 `closeStatements()`（**L5-11**） | `DatabaseManager.java:704-744, 1485-1492`；L1-02/L1-03/L5-11 |
| C7（`co_item` 只还原掉落行）/ C16（替换方块前倒出内容）语义 | **C16 引入了新问题**：`ejectInventory` 倒出的物品不进 undo（rollback 后再 undo 会净丢物品）→ **L2-03（P1）**；C7 的「拾取行不还原」设计不变，但拾取行现在**不带 NBT**（回档后白板物品）→ **L3-17（P2）** | `RollbackManager.java` 的 `ejectInventory`/`removeDroppedItems`；`ItemEntityPickupMixin.java:75-79` |
| D7（无服务器时丢弃查询结果）/ D9（`close()` 里读池顺序） | **未见新问题**：D7 的丢弃路径只发生在服务器停止时且已有 WARN；D9 的读池关闭已在 1.21.11 调整为早于 checkpoint（1.21/26.1.2 见 L5-11） | `DatabaseManager.java:1670-1690`、`:704-744` |
| MSPT 前后对比 | **仍未测**（本轮不阻塞）。最小测法：`run/` 用同一世界，`co lookup a:block` 打开 inspector 面板并保持，`/debug stopwatch` 采样 60 s 取 `tickServer`；再用 100 个加载中的漏斗（`L3-10` 的场景）重复一次，对比 P95 | 未执行 |

### 9.8 修复进度（「全修」轮，v1.9.4）

用户 2026-11-14 指令：**全修**。按文件所有权拆成 6 条并行工作流，全部先只在旗舰树 `coreprotect-fabric-1.21.11` 落地，编译通过后再移植到 `-1.21` 与 `-26.1.2`。

| 工作流 | 负责文件 | 覆盖 findings | 状态 |
|---|---|---|---|
| W1 数据库层 | `database/DatabaseManager.java`、`database/Criteria.java` | L1-02、L1-04…L1-13、L1-16…L1-18、L4-03/L2-10、L4-09、L4-14、L4-15、L3-21、F-9.2、L5-11 | **已落地**（旗舰树编译通过） |
| W2 回档引擎 | `rollback/RollbackManager.java`、`util/BlockStateUtil.java`、`util/ItemStackCodec.java` | L2-01、L2-03、L2-05…L2-14、L1-14、L3-11、L4-03、L4-16 | **已落地**，另有主代理追加 2 处 |
| W3 日志采集 | `CoreProtectFabric.java`、`mixin/*`、`event/*`、`util/NaturalBreakCause.java`、`coreprotect.mixins.json` | **L3-01(P0)**、**L3-02(P0)**、L3-05…L3-09、L3-12…L3-18、L3-20、L5-03、L2-11 | **已落地**（8 个新 mixin，15→23） |
| W4 容器追踪 | `container/{Container,Hopper,Dispenser}Tracker.java` | L1-01/L3-03、L3-04、L3-10、L3-19、L2-02 | **已落地**，另有主代理补 2 处 |
| W5 命令/权限/配置 | `command/*`、`config/CoreProtectConfig.java`、`util/Permissions.java`、`i18n/*`、lang JSON | L4-01、L4-04/L5-01、L4-05…L4-13、L4-17…L4-19、L1-03、L1-15、F-9.1、F-9.2 | **已落地**（lang 5×133 键） |
| W6 工具链/文档 | `.build-tools/**`、`.github/workflows/build.yml`、根与三树 README/OVERVIEW、`body.md` | L5-04…L5-08、L5-10、L5-12…L5-16、L4-20 | **已落地**（`check-ascii` 58 文件绿） |

**不计入修复**：L4-02（误报，见 9.4 R-9.1）。**跨文件契约**（已在各工作流中固定）：`DatabaseManager.purge(Criteria)` / `countDroppedRow()` / `droppedRows()` / `applySyncMode()`；`CoreProtectConfig.shouldLogCommand(String)`；`ContainerTracker.captureBeforeRemoval(ServerWorld, BlockPos)`。

#### 9.8.1 主代理在六路之外的追加（均已编译）

| # | 文件:行 | 内容 | 对应 |
|---|---|---|---|
| A1 | `rollback/RollbackManager.java:1253` 新增 `ownedInventory(ServerWorld,BlockPos)`，替换 `:653` 与 `:901` 的 `containerAt` | 回档/撤销替换半边双箱时只倒出**该格自己的 27 格**，不再把幸存的那半也撒到地上 | L2-02 补充 |
| A2 | `rollback/RollbackManager.java:83` + `startRollback` | `CONSOLE_TIMEOUT_MS = 10_000L`；`operator == null`（控制台/RCON/命令方块）时 `future.get(...)` 后**同步** `applyPlan` | 修复「控制台 `/co rollback` 什么都不打印」（vanilla 在命令返回时刷输出缓冲） |
| A3 | `container/ContainerTracker.java:308` | `if (!mod.config().logging.container) return;` | 尊重 `logging.container` 开关 |
| A4 | lang ×5 | `coreprotect.error.db` 去占位符；`coreprotect.undo/rollback/restore.complete` 改 5 占位符（blocks/containers/item ops/skipped/signs） | 中文下不再出现「数据库错误：database read failed」；完成行能显示告示牌与跳过数 |
| A5 | `CoreProtectFabric.java`、`event/MessageEventListener.java`、`event/SessionEventListener.java`、`command/CoCommand.java` | 命令/聊天行写入 `wid_id`；语言先按 uuid 查、再回落按名字；`/co language` 写 `(name,uuid,lang)` | 打通 W1 预留的 schema 字段（`co_user_uuid`、`co_command/co_chat.wid_id`） |
| A6 | `.build-tools/test-full.ps1` | `co rollback/restore t:5m r:80` → 去掉 `r:80` | 控制台无玩家位置，C14 会拒绝；否则回归里回档路径完全空跑 |

#### 9.8.3 发布前追加的三项（2026-11-15 14:10）

这些是回归与实测过程中新发现的问题，**不在 84 条之内**，但因为都属于本轮改动直接暴露出来的行为/体验缺陷，一并修掉；控制台 undo 一项只记录不改（超出范围，留待用户决定）。

| 编号 | 级别 | 位置 | 问题 | 处置 |
|---|---|---|---|---|
| R-9.2 | P3 | 三棵树 `command/LookupService.java:289`、`:306` | 容器行与物品行用 `Text.literal(l.amount() + "x ")` 再与物品名拼接、`row(...)` 又以空格连接 → 控制台/Discord 侧出现 `1x  diamond` 双空格（游戏内看不出来） | 已修：两处改为 `l.amount() + "x"`，探针实测输出 `1x diamond` |
| R-9.3 | P2 | 三棵树 `command/CoCommand.java` 的 `debugNatural` | `/co debug natural` 只强制 `cz -1..1` 九个区块且**放完立即释放**：①活塞场景在 `ground.add(0,1,20)`（`baseZ+1` 区块）从来没被强制加载，放置被静默丢弃；②1.21.9+ 无玩家时区块不 tick，火靠 scheduledTick 才蔓延，释放后 `#fire` 永远不出现 —— 结果是官方自检命令在“无玩家服务器”上验证不了三类自然事件 | 已修（三棵树）：新增 `forceChunks(world, minX, maxX, minZ, maxZ, forced)`，范围改 `cx -1..1` × `cz -1..2`（覆盖活塞），并用 `CompletableFuture.delayedExecutor(20L, TimeUnit.SECONDS)` + `server().execute(...)` 在 20 秒后释放（仍满足 L4-08「不永久强制区块」） |
| O-9.1 | P2（观察，未改） | 三棵树 `rollback/RollbackManager.java:583-589`（`publishUndo`）+ `:110`/`:424`/`:428` | 控制台/RCON/命令方块执行 `/co rollback|restore` 后 `/co undo` 永远回 `There is nothing to undo.`：`publishUndo` 第一行 `if (job.undo \|\| job.operator == null) return;`，而 `lastOperations` 是 `Map<UUID, …>`、`operator == null` 时 id 为 null | **v1.9.3 固有行为**（`git show HEAD:…RollbackManager.java` 同一位置同为 `:426` 处提前 return），非本轮引入；已记入本条，是否修由用户决定 |

实测依据（`.dsh-scratch/probe-natural.ps1`，旗舰树 `runServer` + RCON）：第一次探针 `Blocks: 1` 且 `#tnt`/`#fire`/`#piston` 全空；临时插桩（已全部删除、残留 0 处）后 `#tnt` 出现 **10 行**（`TNT destroyed dirt` / `grass_block`）、`Blocks: 47`、`Containers: 16` ⇒ **TNT 路径本身正常**，空白是区块窗口/时序造成的。

#### 9.8.2 验证状态（2026-11-15 14:20 更新）

- 旗舰树 `compileJava`：**BUILD SUCCESSFUL**（含 A1–A6）。
- 旗舰树 `test-full.ps1 -Fresh`：**FULL_TEST_DONE**、`MIXIN_FAILURES: no`、`CORE_ENABLED: yes`、exit 0。
  - A2 已在回归里**实测确认**：日志出现 `CoreProtect - Rollback in progress, this may take a moment...` /
    `Restore in progress...` / `CoreProtect - No matching data found.` —— 控制台不再是空输出。
  - `/co status` 含 `Writes:` 行；`/co purge t:1s` 报 `Purge complete. Deleted 33 row(s)`；非法 action 列出 43 个合法动作。
- 语言文件：三份构建各自 5 个 JSON，旗舰树 `ConvertFrom-Json` 通过，**各 133 键**；`src` 内无 `TEMP-REPRO`/`FIXME`/`System.out` 残留。
- 关机前的两个移植子代理（`c031d154`/`06cc6b15`）工作**作废**并已重新派发：`aab5a855-c611-4455-971e-3daeba9ad0dc`（1.21，重查 16 个半途文件 + 补 8 个新 mixin）与 `242e9ab3-ecd9-4c12-bf6e-03ef25751c14`（26.1.2，从零移植 37 项）。两者均已收尾并编译通过。
- **移植缺口（已补齐）**：1.21 首次编译 2 类错误（`isPosLoaded`→`isChunkLoaded` 9 处、`getSpawnPoint()`→`world.getSpawnPos()`）、26.1.2 首次编译 14 个错误（`source.level()`→`getLevel()`、`.add(`→`.offset(`、`piston.PistonBaseBlock`、`hasWorld`、缺 `import HolderLookup`、`BlockState.isOf(Block)`→`getBlock() ==`、`getEntities` 歧义、`ResourceKey.create`）全部由我逐条修复。
- **三树构建（2026-11-15 14:13，含 R-9.2/R-9.3）**：`1.21.11` = 14,578,811 B、`1.21` = 14,581,083 B、`26.1.2` = 14,579,035 B，三份 `fabric.mod.json` 的 `version` 均 `1.9.4`；1.21 必须联网（`--offline` 会因 fabric-loom 不在缓存而失败）。
- 语言文件：三份构建各自 5 个 JSON，**各 133 键**，`ConvertFrom-Json` 全通过；`src` 内无 `TEMP-REPRO`/`TEMP-PROBE`/`FIXME`/`System.out` 残留（探针插桩已删除并核对 0 处）。
- 发布材料已就绪：`CHANGELOG.md` 中英 v1.9.4 两节（英文标题 `:10`、中文标题 `:338`，含新增的「Self-test and display / 自检命令与显示」分组）、`.build-tools/release-v194-template.md` 与三份 `release-v194-<mc>.md`、`.build-tools/modrinth/changelog-1.9.4.md`、`.build-tools/make-notes-194.ps1`（纯 ASCII）、`modrinth/project.json` 的 `changelog_file` 已指向 `changelog-1.9.4.md`。
- 版本号：`bump-version.ps1 -From 1.9.3 -To 1.9.4` 已跑（三树 `MOD_VERSION`、`gradle.properties`、三树与根目录 README/OVERVIEW/OVERVIEW_EN、`modrinth/versions.json`）。
- **回归结果（三棵树，2026-11-15 14:26 / 14:31 / 14:36，均为 `-Fresh`）**：`coreprotect-fabric-1.21.11`、`coreprotect-fabric-1.21`、`coreprotect-fabric-26.1.2` 全部 `FULL_TEST_DONE`、exit 0、`MIXIN_FAILURES: no`、`CORE_ENABLED: yes`；日志里 CoreProtect 的 ERROR/WARN 只剩 vanilla 的 offline-mode 提示。上一轮出现的 `[CoreProtect] Missing translation key: coreprotect.cause.random_tick` 已随 15 个语言文件补 6 个 `coreprotect.cause.*` 键（`server`/`entity`/`container`/`gravity`/`random_tick`/`trample`，各文件 133 → **139 键**）而消失。三棵树 jar 同步重建：`1.21.11` = 14,579,149 B、`1.21` = 14,581,465 B、`26.1.2` = 14,579,373 B。
- **`/co debug natural` 实测（旗舰树，`.dsh-scratch/probe-natural.ps1`）**：R-9.3 修复后 `a:#piston` 从 0 变 **2-4 行**、`a:#tnt` 10 行、`a:#water` 5 行、`u:#hopper a:#container` 与 `a:container` 各 10 行、`co rollback t:5m` 回 `Rollback complete. Blocks: 5, containers: 4, item operations: 11, signs: 0, skipped: 1` ⇒ 自然事件与容器链路在旗舰树**全部可验证**。`a:#fire` 在**无玩家**的 1.21.9+ 服务器上仍为空：火焰只通过 scheduledTick 蔓延，无玩家时即使强制加载 60 秒也可能不烧穿白羊毛（1.21 树因出生点区块长期 tick 才出现 `Fire changed white_wool`）——属环境限制，已在 §9.8.3 记明，不再是日志代码缺陷。