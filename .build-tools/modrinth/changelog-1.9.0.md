# v1.9.0 — a much smaller database

## English

### Storage rewritten around dictionaries (schema v3)

- `user`, `world` and `action` are no longer stored as text on every row: they live in the new `co_name` / `co_world` / `co_action` dictionaries (block states stay in `co_state`), and the log tables keep integer ids only.
- Each log table now carries exactly two indexes — `(wid_id, x, y, z)` for radius queries and a composite `(time, name_id)` that also covers per-player lookups.
- Measured on a 417k-row fixture: **46.38 MB → 24.32 MB (−47.6 %)**, i.e. **116.6 → 61.2 bytes per row**.
- `/co purge` now also garbage-collects dictionary rows that are no longer referenced.

### Upgrading is automatic

- An existing v1 or v2 database is migrated in place on the first server start; `coreprotect.db.bak-v2` is kept next to it as a safety net.
- The migration needs roughly **2.2× the database size in free disk space** (the backup copy plus the pre-VACUUM file).
- Once the new database looks correct you can delete the `.bak-v2` file; restoring it is just a matter of replacing `coreprotect.db` while the server is stopped.

### Also in this release

- Every fix from v1.8.2 (`#fire` logging, sign front/back faces, phantom item drop/pickup rows, rollback safety limit and container undo, `/co undo`, CoreProtect-compatible `a:container` and friends, `e:`/radius/case-insensitive filters, crash-marker and shutdown ordering, bStats and update-check behaviour).
- The mod still writes asynchronously on a single worker thread: the game thread never touches the disk.

**Upgrading from v1.8.x is a drop-in replacement** — replace the jar in `mods/`, start the server, and the database is migrated on first launch.

---

## 中文

### 数据库改为字典化存储（schema v3）

- `user`、`world`、`action` 不再逐行保存文本，而是存入新增的 `co_name` / `co_world` / `co_action` 字典表（方块状态仍在 `co_state`），日志表只保留整数 id。
- 每张日志表现在只有两个索引：用于半径查询的 `(wid_id, x, y, z)`，以及同时覆盖按玩家查询的复合索引 `(time, name_id)`。
- 41.7 万行测试数据实测：**46.38 MB → 24.32 MB（−47.6%）**，即**每行 116.6 → 61.2 字节**。
- `/co purge` 现在会一并回收不再被引用的字典行。

### 自动升级

- 首次启动服务端时会原地迁移旧版 v1 / v2 数据库，并在旁边保留 `coreprotect.db.bak-v2` 作为保险。
- 迁移大约需要**数据库体积 2.2 倍的可用磁盘空间**（备份副本 + VACUUM 前的文件）。
- 确认新库无误后可删除 `.bak-v2`；需要还原时，停服后用该文件替换 `coreprotect.db` 即可。

### 本版还包含

- v1.8.2 的全部修复（`#fire` 记录、告示牌正反面、掉落/拾取幽灵记录、回滚安全上限与容器撤销、`/co undo`、CoreProtect 兼容的 `a:container` 等写法、`e:`/半径/大小写过滤、崩溃标记与关库时序、bStats 与更新检查行为）。
- 写入仍然在单一异步工作线程上进行：游戏主线程不接触磁盘。

**从 v1.8.x 升级即为替换 jar** —— 更换 `mods/` 中的文件并启动服务端，首次启动会自动完成数据库迁移。
