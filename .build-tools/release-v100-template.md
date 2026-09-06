# CoreProtect Fabric {mc} (v1.0.0)

A block-level logging and rollback mod for **Fabric servers**, independently implemented with the renowned [CoreProtect](https://github.com/PlayPro/CoreProtect) plugin as its blueprint. This release is the dedicated build for **Minecraft {mc}**.

> For the full feature evolution, see the flagship release (Minecraft 1.21): https://github.com/CNTianCheng/coreprotect-fabric/releases/tag/v1.7.2

---

## English

### Features (v1.0.0 core feature set)

- **Block logging**: player placements/breaks (full block states plus sign text snapshots)
- **Natural events**: fire spread, water/lava washouts, explosions (TNT/Creeper/Wither/End Crystal), piston movement, enderman pickup/placement, leaf decay (logged as `#fire` `#water` `#lava` `#tnt` `#creeper` `#wither` `#end_crystal` `#piston` `#enderman` `#decay`)
- **Container transactions**: item deltas between opening and closing a container, logged per item type
- **Entities & activity**: player kills, player deaths, chat, executed commands, join/leave sessions
- **Rollback / restore / undo**: `/co rollback u:<user> t:<time> [r:<radius>]`, `/co restore`, `/co undo`
- **Lookup**: `/co lookup` with combined filters (user/time/action/radius/block/exclude) and pagination
- **Inspection mode**: `/co inspect` — left-click block history, right-click container transactions, place a block to look it up
- **Languages**: English / 简体中文 / 繁體中文 / 日本語, following the client language automatically
- **SQLite (WAL)**: asynchronous writes, zero main-thread blocking

### Install

- Requirements: Minecraft **{mc}**, Java **{java}**, Fabric Loader >= 0.16 + Fabric API{mapping_note_en}
- Drop `{jar}` into the server's `mods/` folder; config and database are created on first launch

### Commands

```
/co help                    # help
/co inspect                 # inspection mode
/co lookup [params]         # lookup
/co rollback u:<user> ...   # rollback
/co restore u:<user> ...    # restore
/co undo                    # undo
/co purge t:<time>          # prune old data
/co status                  # status
/co language <code>         # switch language
```

`/coreprotect` is an alias of `/co`; permission levels are configurable in `config/coreprotect-fabric.json` (lookupLevel/adminLevel).

### Links

- Source & docs: https://github.com/CNTianCheng/coreprotect-fabric
- Bug reports: https://github.com/CNTianCheng/coreprotect-fabric/issues/new/choose
- License: MIT; bundles sqlite-jdbc (Apache License 2.0)
- Independent implementation inspired by CoreProtect; not affiliated with the CoreProtect team

---

## 中文

### 功能（v1.0.0 核心功能集）

- **方块记录**：玩家放置/破坏（含方块状态与告示牌文字快照）
- **自然事件**：火焰蔓延、水/熔岩冲毁、爆炸（TNT/苦力怕/凋灵/末影水晶）、活塞推拉、末影人搬运、树叶凋零（记录为 `#fire` `#water` `#lava` `#tnt` `#creeper` `#wither` `#end_crystal` `#piston` `#enderman` `#decay`）
- **容器交易**：打开容器到关闭期间的物品存取差异逐项记录
- **实体/行为**：玩家击杀、玩家死亡、聊天、执行的命令、登录/登出会话
- **回滚 / 恢复 / 撤销**：`/co rollback u:<玩家> t:<时间> [r:<半径>]`、`/co restore`、`/co undo`
- **查询**：`/co lookup` 按玩家/时间/操作/半径/方块/排除组合过滤 + 分页
- **检查模式**：`/co inspect` 左键方块历史、右键容器交易、放置方块查询类型
- **多语言**：English / 简体中文 / 繁體中文 / 日本語，自动跟随客户端语言
- **SQLite（WAL）**：异步写入，游戏主线程零阻塞

### 安装

- 要求：Minecraft **{mc}**、Java **{java}**、Fabric Loader ≥ 0.16 + Fabric API{mapping_note_zh}
- 将 `{jar}` 放入服务端 `mods/`，首次启动自动生成配置与数据库

### 命令速览

```
/co help                    # 帮助
/co inspect                 # 检查模式
/co lookup [参数]            # 查询
/co rollback u:<玩家> [参数] # 回滚
/co restore u:<玩家> [参数]  # 恢复
/co undo                    # 撤销
/co purge t:<时间>           # 清理旧数据
/co status                  # 状态
/co language <代码>          # 切换语言
```

`/coreprotect` 为 `/co` 的别名；权限等级见 `config/coreprotect-fabric.json`（lookupLevel/adminLevel）。

### 相关链接

- 源码与文档：https://github.com/CNTianCheng/coreprotect-fabric
- Bug 反馈：https://github.com/CNTianCheng/coreprotect-fabric/issues/new/choose
- 许可：MIT；内置 sqlite-jdbc（Apache License 2.0）
- 本项目是受 CoreProtect 启发的独立实现，与 CoreProtect 团队无关
