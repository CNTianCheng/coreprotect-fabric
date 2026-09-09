# CoreProtect Fabric 模组总简介（全版本）

## 一句话介绍

**CoreProtect Fabric** 是一款以 Bukkit/Spigot 生态久负盛名的 **CoreProtect** 插件为蓝本独立实现的 Fabric 服务器模组，把"记录一切、可查可回滚"的服务器治理能力带给 Fabric 生态。本仓库提供 **Minecraft 1.21 至 26.2 共 16 个游戏版本**的专用构建：方块/容器/实体/聊天/命令/会话全量记录，回滚（Rollback）、恢复（Restore）、查询（Lookup）、检查模式（Inspect）与多语言切换开箱即用。

> 本模组是受 [CoreProtect](https://github.com/PlayPro/CoreProtect) 启发的**独立实现**（非移植、非官方作品），功能与命令风格向其看齐，数据表结构尽量贴近，熟悉 CoreProtect 的服主可快速上手。

## 设计目标

- **防熊与取证**：任何破坏、偷窃、恶意操作都有据可查，纠纷处理不再靠猜
- **可逆性**：误操作、被熊破坏的建筑可以按玩家/时间/范围精确回滚
- **低侵入**：SQLite 异步写入，游戏主线程零阻塞；Fabric 无权限插件时用权限等级即可管理
- **全版本覆盖**：每个游戏版本一份独立构建，映射（yarn / mojmap）与 API 适配各自完成，互不影响
- **开箱即用**：放入 `mods/` 即工作，配置与语言均有合理默认值

## 全系列共有功能

### 1. 行为记录

| 类别 | 记录内容 | 示例 |
|---|---|---|
| 方块 | 玩家放置/破坏（含完整方块状态与告示牌文字快照） | `Steve 放置了 stone` |
| 自然事件 | 火焰蔓延、水/熔岩冲毁、爆炸（TNT/苦力怕/凋灵/末影水晶）、活塞推拉、末影人搬运、树叶凋零 | `#fire 摧毁了 wool`、`#water 冲毁了 torch` |
| 容器交易 | 打开容器到关闭期间的物品存取差异，逐物品类型记录数量 | `Steve 取出了 5x diamond` |
| 实体 | 玩家击杀生物、玩家死亡（含击杀者） | `Steve 击杀了 zombie` |
| 行为 | 聊天消息、执行的命令、登录/登出会话 | `Steve: /give ...` |

### 2. 回滚 / 恢复 / 撤销

- `/co rollback u:<玩家> t:<时间> [r:<半径>] [b:<方块>] [a:<操作>] [e:<排除>]` —— 撤销目标玩家的方块改动与容器物品交易
- `/co restore` —— 与回滚相反，重放被回滚的改动
- `/co undo` —— 一键撤销自己最近一次回滚/恢复
- 安全保护：单次回滚超过 `maxBlocks`（默认 5000）自动拒绝

### 3. 查询系统

`/co lookup [u:<玩家>] [t:<时间>] [a:<操作>] [r:<半径>] [b:<方块>] [e:<排除>] [p:<页码>]`，支持分页与组合过滤。操作类型 `a:` 包括 `block`、`+block`、`-block`、`#container`、`#kill`、`#chat`、`#command`、`#session` 及自然事件 `#fire` `#water` `#lava` `#tnt` `#creeper` `#piston` `#enderman` `#decay` 等。时间格式：`2w5d10h30m15s`。

### 4. 检查模式（Inspect）

`/co inspect` 开启后：**左键**查看方块历史、**右键**查看容器交易或相邻方块、**放置方块**对该方块类型执行查询；检查模式下无法破坏方块。

### 5. 数据维护与多语言

- `/co purge t:<时间>` 清理旧数据、`/co reload` 热重载、`/co status` 查看统计
- **四种语言**：English / 简体中文 / 繁體中文 / 日本語，自动跟随客户端语言，可 `/co language <代码>` 手动切换（按玩家持久化）

## 1.21 版增强功能（v1.8.1 旗舰版）

`coreprotect-fabric-1.21/` 工程持续迭代，在共有功能之上新增：

| 版本 | 新增内容 |
|---|---|
| v1.1.0 | 漏斗交易记录（`#hopper`）、掉落物记录（丢弃/捡起，`a:item`）、告示牌编辑记录（`a:#sign`）、CoreProtect 同款输出配色 |
| v1.2.0 | `/co i` 检查模式简写（含 `on`/`off`）、权限组功能（按玩家名单授予 12 个细粒度节点） |
| v1.3.0 | `/co online` 在线记录查询、`databaseFile` 自定义数据库位置、`dataRetention` 数据保存时间限制（启动自动清理） |
| v1.3.1 | 修复检查模式手持方块右键被放置的问题（改为不放置、只查询该方块类型）；输出配色完全对齐插件 v22+ 样式 |
| v1.4.0 | bStats 使用人数统计（官方 v2 协议）；新版本自动检查并在服务器控制台与管理员登录时通知 |
| v1.5.0 | 投掷器（#dropper）/发射器（#dispenser）物品交易记录 |
| v1.5.1 | 修复活塞记录噪音（不再出现"活塞改变/摧毁空气"）、检查模式点击空气不再弹出查询、查询翻页提示可直接点击 |
| v1.6.0 | 命令参数自动提示（u:/t:/a:/r:/b:/e:/p: 与玩家名补全）；查询提速：读/写线程分离（多核并行读取）、会话/命令/聊天表补索引、半径查询走索引包围盒、/co status 统计并行执行 |
| v1.7.0 | 数据库空间压缩：方块状态/物品字符串字典化存储（v2 schema，旧库自动迁移+备份+VACUUM 压缩，实测 12.55MB→8.3MB）；/co purge 后自动压缩文件；查询再提速：mmap 内存映射、可配缓存（database.cacheSizeMB）、读取池扩至 8 线程 |
| v1.7.1 | 修复 bStats 无法注册：上报平台由 fabric 更正为 server-implementation（服务 33739 实际注册平台），旧配置自动迁移 |
| v1.7.2 | bStats 增加玩家数上报（customCharts 的 players 单线图，配合 bstats.org 服务页的 players 图表显示在线人数） |
| v1.8.0 | 崩溃防护：WAL 提交每次刷盘（synchronous=full）、异常关闭检测 + 启动时 quick_check 完整性校验、周期性 WAL 检查点 |
| v1.8.1 | 断电级防护：定期在线热备份（VACUUM INTO，默认 6 小时）、检测到损坏时自动从备份恢复（损坏原件另存），读连接代际自动重开 |

## 版本矩阵（16 个构建）

全部构建要求 **Fabric Loader ≥ 0.16 + Fabric API**；Java 要求分两档（1.21.x 为 21，26.x 为 25）。

| Minecraft | 模组版本 | Jar 文件名 | 工程目录 | Java |
|---|---|---|---|---|
| 1.21 | 1.8.1（旗舰） | `coreprotect-fabric-1.21-1.8.1.jar` | `coreprotect-fabric-1.21/` | 21 |
| 1.21.1 | 1.0.0 | `coreprotect-fabric-1.0.0.jar` | `coreprotect-fabric/` | 21 |
| 1.21.2 | 1.0.0 | `coreprotect-fabric-1.21.2-1.0.0.jar` | `coreprotect-fabric-1.21.2/` | 21 |
| 1.21.3 | 1.0.0 | `coreprotect-fabric-1.21.3-1.0.0.jar` | `coreprotect-fabric-1.21.3/` | 21 |
| 1.21.4 | 1.0.0 | `coreprotect-fabric-1.21.4-1.0.0.jar` | `coreprotect-fabric-1.21.4/` | 21 |
| 1.21.5 | 1.0.0 | `coreprotect-fabric-1.21.5-1.0.0.jar` | `coreprotect-fabric-1.21.5/` | 21 |
| 1.21.6 | 1.0.0 | `coreprotect-fabric-1.21.6-1.0.0.jar` | `coreprotect-fabric-1.21.6/` | 21 |
| 1.21.7 | 1.0.0 | `coreprotect-fabric-1.21.7-1.0.0.jar` | `coreprotect-fabric-1.21.7/` | 21 |
| 1.21.8 | 1.0.0 | `coreprotect-fabric-1.21.8-1.0.0.jar` | `coreprotect-fabric-1.21.8/` | 21 |
| 1.21.9 | 1.0.0 | `coreprotect-fabric-1.21.9-1.0.0.jar` | `coreprotect-fabric-1.21.9/` | 21 |
| 1.21.10 | 1.0.0 | `coreprotect-fabric-1.21.10-1.0.0.jar` | `coreprotect-fabric-1.21.10/` | 21 |
| 1.21.11 | 1.0.0 | `coreprotect-fabric-1.21.11-1.0.0.jar` | `coreprotect-fabric-1.21.11/` | 21 |
| 26.1 | 1.0.0 | `coreprotect-fabric-26.1-1.0.0.jar` | `coreprotect-fabric-26.1/` | 25 |
| 26.1.1 | 1.0.0 | `coreprotect-fabric-26.1.1-1.0.0.jar` | `coreprotect-fabric-26.1.1/` | 25 |
| 26.1.2 | 1.0.0 | `coreprotect-fabric-26.1.2-1.0.0.jar` | `coreprotect-fabric-26.1.2/` | 25 |
| 26.2 | 1.0.0 | `coreprotect-fabric-26.2-1.0.0.jar` | `coreprotect-fabric-26.2/` | 25 |

> 说明：1.21.2 ~ 1.21.11 使用 yarn 映射，26.x 使用官方 mojmap 映射。除 1.21 旗舰版外，其余版本为 v1.0.0 核心功能集；各工程目录内均附有对应版本的 `OVERVIEW.md` / `OVERVIEW_EN.md` 与 `README.md`。

## 安装

1. 按上表选择与你服务器 **Minecraft 版本严格对应**的 Jar（1.21.x 需要 **Java 21**，26.x 需要 **Java 25**）。
2. 服务端安装 **Fabric Loader ≥ 0.16** 与 **Fabric API**。
3. 将 Jar 放入 `mods/`，启动服务器。首次启动自动在 `config/coreprotect-fabric.json` 生成配置、在游戏目录创建 SQLite 数据库。

## 技术架构（全系列共享）

- **事件采集**：Fabric API 事件（破坏/放置/右键/击杀/聊天/会话）+ Mixin 挂钩自然事件，通过线程局部"原因归因"把无玩家参与的世界变化归因到 `#fire`、`#water` 等虚拟用户（best-effort，`defaultRequire: 0`）
- **存储**：SQLite（WAL 模式）单工作线程异步写入，主线程零阻塞；表结构贴近 CoreProtect：`co_block` / `co_container` / `co_entity` / `co_session` / `co_command` / `co_chat` / `co_user`（1.21 版另有 `co_item` / `co_sign`）
- **回滚引擎**：同一位置取最新记录，"回滚取旧态、恢复取新态"，BlockState 对象级比较；容器物品操作先行，防止拆除容器时物品丢失
- **翻译**：服务端按玩家解析语言，回退链：`/co language` 覆盖 → 客户端语言 → 配置默认 → `en_us`

## 已知限制

- 击杀记录不回滚（与原版 CoreProtect 一致）；大型回滚在主线程执行（受 `maxBlocks` 保护）
- 容器回滚采用简化物品找回策略：被取走的物品优先从容器扣除并交还操作者，缺失部分补发
- 自然事件挂钩为 best-effort：若未来游戏版本改动导致个别挂钩失效，仅失去对应记录，不影响其他功能
- 1.21 版已额外记录漏斗（`#hopper`）、投掷器/发射器（`#dropper`/`#dispenser`）、掉落物与告示牌编辑；**其余版本暂未包含**这四类记录，且暂无 `/co i` 简写、权限组、`/co online`、数据保存限制（可按需将 1.21 版改动移植）

## 许可与致谢

- 全系列以 **MIT License** 开源
- 灵感与功能对标 [CoreProtect](https://github.com/PlayPro/CoreProtect)，向其开发团队致敬
- 内置 [sqlite-jdbc](https://github.com/xerial/sqlite-jdbc)（Apache License 2.0）
