# Security Policy

中文说明见下方 [中文](#中文)。

## Supported versions

| Build | Version | Supported |
|---|---|---|
| Minecraft 1.21 | **1.9.1** | ✅ |
| Minecraft 1.21.11 | **1.9.1** | ✅ |
| Minecraft 26.1.2 | **1.9.1** | ✅ |
| all other builds (1.21.2 – 1.21.10, 26.1, 26.1.1, 26.2, 1.21.1) | 1.0.0 | ❌ archived |

Only the newest release of the three in-sync builds receives security fixes.

## Reporting a vulnerability

**Please do not open a public issue for security problems.**

Use GitHub's private reporting channel:

1. Open the repository's **Security** tab.
2. Click **Report a vulnerability**.
3. Describe the problem, or attach a patch/reproduction if you already have one.

If that channel is unavailable, open a regular issue that only asks a maintainer to contact
you — without any details of the vulnerability.

Helpful details:

* mod version (`/co status` prints it) and Minecraft / Fabric Loader version
* whether the server runs with `online-mode=true` and whether players hold OP
* exact steps or commands to reproduce, and the expected vs. actual behaviour
* relevant log lines, and whether a database file was modified or lost

## What is in scope

| Area | Examples |
|---|---|
| Data integrity | corrupting or losing log data, a rollback that damages the world in a way the mod cannot undo, database files left locked/corrupted after a crash |
| Privilege | running `/co rollback`, `/co restore`, `/co purge` or `/co reload` without the configured permission level |
| Injection | SQL injection through lookup parameters, command injection through player names or chat content, path traversal through the configured database file name |
| Availability | a player able to hang or crash the server through the mod (unbounded memory use, blocking the server thread, an exception thrown on the server thread, log flooding) |
| Exposure | leaking data (player IPs, chat, private coordinates) to players who are not allowed to see it |

## Out of scope

* Bugs in Minecraft, Fabric Loader, Fabric API or `sqlite-jdbc` themselves — report those
  upstream (tell us as well if the mod is affected).
* Anything that requires an already-compromised server, filesystem access or OP-level
  access that the operator did not intend to give.
* Moderation/administrative misuse by a server owner (e.g. an admin reading the logs).
* Denial of service through normal, intentional use of the mod's own commands
  (`/co purge` on a huge database is slow by design).
* Social engineering, physical access, or attacks against the repository/CI only.

## Response

Reports are handled on a best-effort basis by the maintainer:

* acknowledgement within about 7 days,
* an assessment (accepted / not in scope) with reasoning,
* a fix released as a new patch version, with credit in the release notes unless you prefer
  to stay anonymous.

Please give us reasonable time to ship a fix before publishing details.

## Hardening advice for server owners

* Keep `permissions.lookupLevel` / `adminLevel` as high as your staff structure allows;
  `/co rollback` and `/co purge` are destructive.
* Keep the database outside any web-served directory — it contains chat and command history.
* Leave `database.autoRestoreBackup` and `database.syncMode: full` enabled unless you have a
  specific reason not to; they are what protects the log against a crash or power loss.
* Back up `coreprotect.db` (or at least `coreprotect.db.backup`) off the machine.

---

## 中文

### 支持范围

| 构建 | 版本 | 是否支持 |
|---|---|---|
| Minecraft 1.21 | **1.9.1** | ✅ |
| Minecraft 1.21.11 | **1.9.1** | ✅ |
| Minecraft 26.1.2 | **1.9.1** | ✅ |
| 其余构建（1.21.2 – 1.21.10、26.1、26.1.1、26.2、1.21.1） | 1.0.0 | ❌ 已归档 |

只有三个同步构建的最新版本会获得安全修复。

### 如何报告漏洞

**请不要为安全问题开公开 Issue。** 请使用 GitHub 的私密报告通道：

1. 打开仓库的 **Security** 标签页；
2. 点击 **Report a vulnerability**；
3. 描述问题，或直接附上补丁/复现方式。

若该通道不可用，可开一个不含任何漏洞细节的 Issue，仅请求维护者与你私下联系。

建议提供的信息：

* 模组版本（`/co status` 会显示）与 Minecraft / Fabric Loader 版本
* 服务端是否 `online-mode=true`，玩家是否拥有 OP
* 可复现的确切步骤或命令，以及预期行为与实际行为
* 相关日志，以及数据库文件是否被修改或丢失

### 属于受理范围

| 类别 | 示例 |
|---|---|
| 数据完整性 | 日志数据损坏或丢失；回滚造成无法撤销的世界破坏；崩溃后数据库文件被锁死或损坏 |
| 权限 | 未达到配置权限等级却执行 `/co rollback`、`/co restore`、`/co purge`、`/co reload` |
| 注入 | 查询参数导致的 SQL 注入；玩家名/聊天内容导致的命令注入；配置的数据库文件名导致的路径穿越 |
| 可用性 | 玩家可通过模组卡死或崩服（内存无上限、阻塞服务器线程、服务器线程抛异常、日志刷屏） |
| 信息泄露 | 向无权查看的玩家泄露数据（IP、聊天、私密坐标） |

### 不属于受理范围

* Minecraft、Fabric Loader、Fabric API、`sqlite-jdbc` 自身的缺陷（请向对应上游反馈；若模组受影响也欢迎告知）。
* 需要已失陷的服务器、文件系统权限或服主本不打算给出的 OP 权限才能触发的问题。
* 服主自身的管理行为（例如管理员查看日志）。
* 正常使用模组自带命令造成的性能问题（例如对超大数据库执行 `/co purge` 本来就慢）。
* 社会工程、物理接触，以及仅针对仓库/CI 的攻击。

### 处理流程

由维护者尽力处理：

* 约 7 天内确认收到；
* 给出结论（受理 / 不受理）及理由；
* 以补丁版本发布修复，并在更新说明中致谢（如你希望匿名请说明）。

在修复发布前，请给我们合理的处理时间，不要提前公开细节。

### 给服主的加固建议

* `permissions.lookupLevel` / `adminLevel` 按实际管理结构尽量设高；`/co rollback` 与 `/co purge` 都是破坏性操作。
* 数据库不要放在任何对外提供 Web 服务的目录下——它包含聊天与命令历史。
* 除非确有理由，请保持 `database.autoRestoreBackup` 开启、`database.syncMode: full`，它们正是崩溃/断电时的保障。
* 定期把 `coreprotect.db`（至少 `coreprotect.db.backup`）备份到本机之外。
