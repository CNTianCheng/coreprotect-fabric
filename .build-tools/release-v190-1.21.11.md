# CoreProtect Fabric v1.9.0 (Minecraft 1.21.11)

A block-level logging and rollback mod for **Fabric servers**, independently implemented with the renowned [CoreProtect](https://github.com/PlayPro/CoreProtect) plugin as its blueprint. This is the **v1.9.0 storage release** for the Minecraft 1.21.11 build; the same change ships for 1.21 and 26.1.2.

> **Feature set identical to the 1.21 flagship build**: same commands, logging coverage, colours, crash/power-loss protection, bStats metrics and update checks, now on database schema v3.

---

## English

### v1.9.0 — the database is about half the size

**How the space is saved**

- Player names, world ids and action/cause names move into dictionary tables (`co_name`, `co_world`, `co_action`); every log row keeps a small integer id instead of repeating the same text millions of times
- Each log table now carries two indexes instead of three: one position index `(world, x, y, z)` and one composite `(time, user)` index that also answers "this player, this time range" lookups
- Ids are cached on the writer thread, so logging the same player/block does not add a query
- `/co purge` also drops dictionary rows that nothing references any more

**Measured on a 417,000-row survival-style database**

| | v1.8.2 (schema v2) | v1.9.0 (schema v3) |
|---|---|---|
| File size | 46.38 MB | **24.32 MB (−47.6 %)** |
| Bytes per row | 116.6 | **61.2** |

- Largest objects: `co_block` 9.50 MB, position index 4.94 MB, time index 4.27 MB, containers 0.86 MB, items 0.59 MB
- A real in-place migration of the same data through the mod: 48,635,904 → 26,464,256 bytes (−45.6 %)
- For reference the finished file still gzips to 12.2 MB, but lookups need a live SQLite database, so no external compression is used

**Upgrading is automatic**

- On the first start after updating, the v2 database is converted inside a single transaction; a `coreprotect.db.bak-v2` copy is written first (the write-ahead log is checkpointed so the copy is complete) and the file is compacted in the background afterwards
- Older v1 databases are migrated in the same start (v1 → v2 → v3)
- The log tells you when the backup can be deleted. To roll back: stop the server and replace `coreprotect.db` with `coreprotect.db.bak-v2`
- No command or configuration changes, and nothing to do after updating

**Also included**: every v1.8.2 fix — rollback safety limit applied before touching the world, corrupt-database recovery, `#fire` logging, sign front/back faces, container item undo, item drop/pickup accuracy, CoreProtect-compatible action names, and the rest of the v1.8.x list.

### Install

- Requirements: Minecraft **1.21.11**, Java **21**, Fabric Loader >= 0.16 + Fabric API
- Drop `coreprotect-fabric-1.21.11-1.9.0.jar` into the server's `mods/` folder; config and database are created on first launch

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

### v1.9.0 —— 数据库体积约减半

**空间是怎么省下来的**

- 玩家名、世界 id、动作/原因名移入字典表（`co_name`、`co_world`、`co_action`），每条记录只存一个整数 id，不再把同样的字符串重复存几百万遍
- 每张日志表的索引从 3 个减到 2 个：位置索引 `(世界, x, y, z)` 与复合索引 `(时间, 玩家)`（后者同时服务"某玩家 + 时间段"的查询）
- id 在写入线程内缓存，记录同一个玩家/方块不会额外产生查询
- `/co purge` 之后会顺带删除没有任何记录引用的字典行

**实测（41.7 万行生存服式数据）**

| | v1.8.2（schema v2） | v1.9.0（schema v3） |
|---|---|---|
| 文件大小 | 46.38 MB | **24.32 MB（−47.6%）** |
| 单行占用 | 116.6 字节 | **61.2 字节** |

- 占用明细：`co_block` 9.50 MB、位置索引 4.94 MB、时间索引 4.27 MB、容器 0.86 MB、物品 0.59 MB
- 通过 mod 对同一批数据做真实迁移：48,635,904 → 26,464,256 字节（−45.6%）
- 参考：成品文件再 gzip 是 12.2 MB，但查询需要实时 SQLite 文件，因此没有采用外部压缩

**升级是自动的**

- 更新后首次启动会在**一个事务内**完成 v2 → v3 转换；转换前先写出 `coreprotect.db.bak-v2` 备份（先做 WAL checkpoint，保证备份完整），随后后台压缩文件
- 更老的 v1 数据库会在同一次启动里完成 v1 → v2 → v3
- 日志会提示备份何时可以删除。需要回退时：停服后把 `coreprotect.db.bak-v2` 覆盖回 `coreprotect.db` 即可
- 命令与配置没有变化，升级后无需任何操作

**同时包含 v1.8.2 的全部修复**：回滚安全上限在改动世界前生效、数据库损坏恢复、`#fire` 记录、告示牌正反面、容器物品撤销、掉落/拾取准确性、兼容 CoreProtect 的动作名等。

### 安装

- 要求：Minecraft **1.21.11**、Java **21**、Fabric Loader ≥ 0.16 + Fabric API
- 将 `coreprotect-fabric-1.21.11-1.9.0.jar` 放入服务器 `mods/` 目录，首次启动自动生成配置与数据库

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
