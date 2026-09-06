# CoreProtect Fabric v1.7.2 (Minecraft 1.21 · Flagship)

A block-level logging and rollback mod for **Fabric servers**, independently implemented with the renowned [CoreProtect](https://github.com/PlayPro/CoreProtect) plugin as its blueprint. This release is the **Minecraft 1.21 flagship build** (most features, actively updated). Other Minecraft versions (1.21.1 / 1.21.2~1.21.11 / 26.1~26.2) are available in their own releases.

---

## English

### Changelog

| Version | Highlights |
|---|---|
| **v1.0.0** | Core feature set: block/container/entity/chat/command/session logging, natural-event attribution (`#fire` `#water` `#lava` `#tnt` `#creeper` `#wither` `#end_crystal` `#piston` `#enderman` `#decay`), rollback/restore/undo, combined lookups, inspection mode, four languages (en/zh_cn/zh_tw/ja), async SQLite (WAL) |
| **v1.1.0** | Hopper transactions (`#hopper`), item drop/pickup logging (`a:item`), sign-edit logging (`a:#sign`), CoreProtect v22+ output colors |
| **v1.2.0** | `/co i` inspect shorthand (with `on`/`off`); permission groups (12 fine-grained nodes per player name list, OP-level fallback) |
| **v1.3.0** | `/co online` online records (clock-time join/leave history); `databaseFile` custom database location; `dataRetention` retention limit (automatic startup pruning); fixed session queries |
| **v1.3.1** | Fixed inspect mode placing blocks on right-click (now cancels placement and runs a lookup); output colors fully aligned with the plugin's v22+ style |
| **v1.4.0** | bStats usage metrics (official v2 protocol, opt-out supported); automatic update checks with console + admin join notifications |
| **v1.4.1** | bStats service id built in (33739), works out of the box |
| **v1.5.0** | Dropper (`#dropper`) / dispenser (`#dispenser`) item transaction logging |
| **v1.5.1** | Reworked piston records (no more "piston changed/destroyed air" noise; moved blocks recorded correctly); no lookup when clicking air in inspect mode; clickable next-page hints |
| **v1.6.0** | Command parameter suggestions (`u:` `t:` `a:` `r:` `b:` `e:` `p:`, player-name completion after `u:`); faster lookups: separate parallel read pool (multi-core), session/command/chat indexes, index-backed radius queries, parallel `/co status` counts |
| **v1.7.0** | Database space compression: dictionary-encoded block states/items (schema v2; legacy DBs auto-migrate with backup + VACUUM, measured 12.55MB -> 8.3MB); auto-compact after `/co purge`; faster queries: mmap memory mapping, configurable cache (`database.cacheSizeMB`), read pool up to 8 threads |
| **v1.7.1** | Fixed bStats registration: reporting platform corrected to server-implementation (the platform service 33739 is registered under); old configs migrate automatically |
| **v1.7.2** | bStats player-count reporting (a `players` single-line chart; add a Single Line Chart with id `players` on your bstats.org service page to display the online-player curve) |

### Install

- Requirements: Minecraft **1.21**, Java **21**, Fabric Loader >= 0.16 + Fabric API
- Drop `coreprotect-fabric-1.21-1.7.2.jar` into the server's `mods/` folder; config and database are created on first launch

### Quick start

```
/co help
/co i                        # inspect: left-click block history, right-click container transactions
/co lookup u:Steve t:1h      # lookup
/co rollback u:Steve t:1h    # rollback
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

### 各版本更新特点

| 版本 | 更新内容 |
|---|---|
| **v1.0.0** | 核心功能：方块/容器/实体/聊天/命令/会话记录，自然事件归因，回滚/恢复/撤销，组合查询，检查模式，四种语言，SQLite（WAL）异步写入 |
| **v1.1.0** | 漏斗交易记录（`#hopper`）、掉落物记录（`a:item`）、告示牌编辑记录（`a:#sign`）、CoreProtect v22+ 同款输出配色 |
| **v1.2.0** | `/co i` 检查模式简写（支持 `on`/`off`）；权限组（按玩家名单授予 12 个细粒度节点，未匹配回退 OP 等级） |
| **v1.3.0** | `/co online` 在线记录（几点几分上线/下线）；`databaseFile` 自定义数据库位置；`dataRetention` 保存时间限制（启动自动清理）；修复会话查询 |
| **v1.3.1** | 修复检查模式手持方块右键被放置；输出配色完全对齐插件 v22+ |
| **v1.4.0** | bStats 使用人数统计（官方 v2 协议，可退出）；新版本自动检查，控制台与管理员登录时通知 |
| **v1.4.1** | bStats 服务编号内置（33739），开箱即用 |
| **v1.5.0** | 投掷器（`#dropper`）/发射器（`#dispenser`）物品交易记录 |
| **v1.5.1** | 重做活塞记录（消除"改变/摧毁空气"噪音，正确记录移动方块）；检查模式点击空气不再查询；翻页提示可点击 |
| **v1.6.0** | 命令参数自动提示（`u:` `t:` `a:` `r:` `b:` `e:` `p:`，`u:` 后补全玩家名）；查询提速：读写线程分离（多核并行）、补索引、半径包围盒、并行统计 |
| **v1.7.0** | 数据库压缩：方块状态/物品字符串字典化存储（v2 schema，旧库自动迁移+备份+VACUUM，实测 12.55MB→8.3MB）；purge 后自动压缩；mmap、可配缓存、8 线程读池 |
| **v1.7.1** | 修复 bStats 无法注册（平台更正为 server-implementation，旧配置自动迁移并写回） |
| **v1.7.2** | bStats 玩家数上报（`players` 单线图；在 bstats.org 服务页添加 id 为 `players` 的 Single Line Chart 即可显示在线人数曲线） |

### 安装

- 要求：Minecraft **1.21**、Java **21**、Fabric Loader ≥ 0.16 + Fabric API
- 将 `coreprotect-fabric-1.21-1.7.2.jar` 放入 `mods/`，首次启动自动生成配置与数据库

### 快速上手

```
/co help
/co i                        # 检查模式：左键方块历史、右键容器交易
/co lookup u:Steve t:1h      # 查询
/co rollback u:Steve t:1h    # 回滚
/co online Steve             # 在线记录
/co status                   # 状态
```

### 相关链接

- 源码与文档：https://github.com/CNTianCheng/coreprotect-fabric
- Bug 反馈：https://github.com/CNTianCheng/coreprotect-fabric/issues/new/choose
- 许可：MIT；内置 sqlite-jdbc（Apache License 2.0）
- 本项目是受 CoreProtect 启发的独立实现，与 CoreProtect 团队无关
