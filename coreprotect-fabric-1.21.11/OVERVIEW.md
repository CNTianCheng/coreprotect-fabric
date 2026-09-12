# CoreProtect Fabric 模组概述（v1.8.1）

## 一句话介绍

**CoreProtect Fabric** 是一款面向 Minecraft 1.21.11 Fabric 服务器的方块级操作记录与回滚模组，以 Bukkit/Spigot 生态中久负盛名的 **CoreProtect** 插件为蓝本独立实现，将"记录一切、可查可回滚"的服务器治理能力带入 Fabric 生态。方块、容器、漏斗、掉落物、告示牌编辑、实体、聊天、命令、在线会话九大类行为全量记录，并提供回滚（Rollback）、恢复（Restore）、查询（Lookup）、检查模式（Inspect）、在线记录查询与多语言切换，输出配色与 CoreProtect 同款。

> 本模组是受 [CoreProtect](https://github.com/PlayPro/CoreProtect) 启发的**独立实现**（非移植、非官方作品），功能与命令风格向其看齐，数据表结构也尽量贴近，方便熟悉 CoreProtect 的服主快速上手。

## 设计目标

- **防熊与取证**：任何破坏、偷窃、恶意操作都有据可查，纠纷处理不再靠猜
- **可逆性**：误操作、被熊破坏的建筑可以按玩家/时间/范围精确回滚
- **低侵入**：SQLite 异步写入，游戏主线程零阻塞；无权限节点的 Fabric 环境用权限等级即可管理，也可开启细粒度权限组
- **开箱即用**：放入 mods 即工作，配置与语言均有合理默认值

## 核心功能

### 1. 全量行为记录

| 类别 | 记录内容 | 示例 |
|---|---|---|
| 方块 | 玩家放置/破坏（含完整方块状态与告示牌文字快照） | `Steve 放置了 stone` |
| 自然事件 | 火焰蔓延、水/熔岩冲毁、爆炸（TNT/苦力怕/凋灵/末影水晶）、活塞推拉、末影人搬运、树叶凋零 | `#fire 摧毁了 wool`、`#water 冲毁了 torch` |
| 容器交易 | 打开容器到关闭期间的物品存取差异，逐物品类型记录数量 | `Steve 取出了 5x diamond` |
| 漏斗交易 | 漏斗每次刻对自身、上方来源与朝向目标容器的物品变化逐项记录，记为虚拟用户 #hopper | `#hopper 存入了 3x diamond` |
| 投掷器/发射器 | 每次发射取走物品与外部存入按容器交易逐项记录，记为虚拟用户 #dropper / #dispenser | `#dropper 取出了 1x emerald` |
| 掉落物 | 玩家丢弃/捡起物品（动作 dropped / picked up） | `Steve 丢出了 1x stone` |
| 告示牌编辑 | 玩家直接编辑告示牌文字的历史快照（无需破坏重放） | `Steve 编辑了告示牌文字 [...]` |
| 实体 | 玩家击杀生物、玩家死亡（含击杀者） | `Steve 击杀了 zombie` |
| 行为 | 聊天消息、执行的命令、登录/登出会话 | `Steve: /give ...` |

### 2. 回滚 / 恢复 / 撤销

- `/co rollback u:<玩家> t:<时间> [r:<半径>] [b:<方块>] [a:<操作>] [e:<排除>]` —— 撤销目标玩家的方块改动与容器物品交易（取走的东西按原逻辑补回、放入的移除并交还操作者）
- `/co restore` —— 与回滚相反，重放被回滚的改动
- `/co undo` —— 一键撤销自己最近一次回滚/恢复
- 安全保护：单次回滚超过 `maxBlocks`（默认 5000）自动拒绝，防止误操作冻结服务器

### 3. 查询系统

`/co lookup [u:<玩家>] [t:<时间>] [a:<操作>] [r:<半径>] [b:<方块>] [e:<排除>] [p:<页码>]`，支持分页与组合过滤：

- 操作类型 `a:`：`block`（全部方块）、`+block`（仅放置）、`-block`（仅破坏）、`#container`（容器）、`item`/`#item`（掉落物）、`#sign`（告示牌编辑）、`#kill`（击杀）、`#chat`、`#command`、`#session` 及自然事件 `#fire` `#water` `#lava` `#tnt` `#creeper` `#piston` `#enderman` `#decay` 等
- 时间格式：`2w5d10h30m15s`（周/天/小时/分钟/秒）
- 输出采用 CoreProtect 同款配色（v22+ 样式）：通用消息前缀深青色 `CoreProtect - ` + 白色文字；查询标题 `----- CoreProtect | Lookup Results -----`；查询行"灰色时间 + 绿色`+`/红色`-`标记 + 深青色玩家 + 白色动作 + 深青色对象"；状态行深青色标签 + 白色数值

### 4. 检查模式（Inspect）

`/co inspect` 或简写 `/co i`（支持 `on`/`off`）开启后：**左键**查看方块历史、**右键**查看容器交易或相邻方块、**放置方块**对该方块类型执行查询；检查模式下无法破坏方块，防止取证时误操作。

### 5. 在线记录（Online）

- `/co online` —— 列出当前在线玩家
- `/co online <玩家> [t:<时间>] [p:<页码>]` —— 显示该玩家当前是否在线，并按"几点几分"（`yyyy-MM-dd HH:mm:ss`）列出登录/登出记录

### 6. 权限系统

- **等级制（默认）**：`permissions.lookupLevel`（默认 0 = 所有人）/ `adminLevel`（默认 4 = OP），无权限插件的 Fabric 环境零配置可用
- **权限组（可选）**：开启 `permissionGroups.enabled` 后按玩家名单授予细粒度节点（`coreprotect.help`、`coreprotect.inspect`、`coreprotect.lookup`、`coreprotect.online`、`coreprotect.rollback` 等 12 个节点），多组取并集、名字不分大小写，未匹配的玩家回退到等级制判断

### 7. 数据维护与多语言

- `/co purge t:<时间>` 手动清理旧数据；`dataRetention` 开启后服务器**启动时自动删除**超过 `maxDays` 天的全部记录（条数写入服务器日志，`/co status` 显示开关状态）
- 数据库位置可配：`databaseFile` 支持相对游戏目录的子路径或绝对路径，父目录自动创建
- `/co reload` 热重载配置与语言、`/co status` 查看数据库统计
- **四种语言**：English / 简体中文 / 繁體中文 / 日本語
  - 自动跟随客户端语言，也可 `/co language <代码>` 手动切换（按玩家持久化，`auto` 恢复自动）
  - 添加新语言只需在 `assets/coreprotect/lang/` 增加一份 JSON 翻译文件

### 8. bStats 统计与新版本通知

- **bStats 使用人数统计**：按官方 bStats v2 协议匿名上报（在线人数、正版验证、MC/加载器/Java/系统信息），每 30 分钟一次；bStats 服务编号已内置（33739），`config/bstats/config.txt` 可一键退出
- **新版本通知**：启动后自动检查 Modrinth API（slug/接口可配置），发现新版本时在**服务器控制台**提示，admin 级玩家**登录时**收到同款通知（跟随玩家语言）

## 命令速览

| 命令 | 功能 | 默认权限 |
|---|---|---|
| `/co help` | 帮助 | 所有人（lookup 级） |
| `/co inspect [on\|off]`、`/co i [on\|off]` | 切换检查模式 | lookup 级 |
| `/co lookup [参数]` | 查询记录 | lookup 级 |
| `/co online [玩家] [t:<时间>]` | 查询在线记录 / 列出在线玩家 | lookup 级 |
| `/co rollback u:<玩家> [参数]` | 回滚 | OP（admin 级） |
| `/co restore u:<玩家> [参数]` | 恢复 | admin 级 |
| `/co undo` | 撤销上次回滚/恢复 | admin 级 |
| `/co purge t:<时间>` | 清理旧数据 | admin 级 |
| `/co reload` | 重载配置与语言 | admin 级 |
| `/co status` | 数据库统计 | lookup 级 |
| `/co language [list\|<代码>]` | 查看/切换语言 | lookup 级 |
| `/co debug natural` | 放置火焰/水流/爆炸场景，验证记录功能 | admin 级 |

`/coreprotect` 为 `/co` 的别名。权限等级与权限组均可在 `config/coreprotect-fabric.json` 中调整。

## 技术架构

- **事件采集**：Fabric API 事件（破坏/放置/右键/击杀/聊天/会话/掉落物）+ Mixin 挂钩自然事件与漏斗、掉落物拾取、告示牌编辑，通过线程局部"原因归因"机制把无玩家参与的世界变化归因到 `#fire`、`#water`、`#hopper` 等虚拟用户
- **存储**：SQLite（WAL 模式），单工作线程异步写入，游戏主线程不接触磁盘；表结构贴近 CoreProtect：`co_block` / `co_container` / `co_item` / `co_sign` / `co_entity` / `co_session` / `co_command` / `co_chat` / `co_user`
- **回滚引擎**：同一位置取最新记录，"回滚取旧态、恢复取新态"，按 BlockState 对象级比较，避免属性顺序/格式差异导致误判；容器物品操作先行，保证拆除容器前物品不丢失
- **翻译**：服务端翻译引擎按玩家解析语言，回退链：`/co language` 覆盖 → 客户端语言 → 配置默认 → `en_us`
- **配置**：`config/coreprotect-fabric.json`（语言、数据库位置、各类记录开关、查询行数、回滚上限、权限等级/权限组、数据保存限制）

## 安装与版本

| 项目 | 要求 |
|---|---|
| Minecraft | **1.21.11** |
| 服务端 | Fabric Loader ≥ 0.16 + Fabric API |
| Java | 21 |
| 安装 | 将 `coreprotect-fabric-1.21.11-1.8.1.jar` 放入 `mods/` 即可，首次启动自动生成配置与数据库 |

> 本模组另提供 1.21.2 ~ 1.21.11 与 26.1 ~ 26.2 全系列的对应构建（位于各自的工程目录），功能保持一致。

## 适用场景

- **生存服防熊**：查谁拆的家、谁偷的箱子，一键回滚
- **建筑服保护**：误拆、误炸、水流冲毁都有记录可回溯
- **纠纷取证**：配合检查模式快速定位现场历史；`/co online` 核对玩家上下线时间
- **服务器运维**：会话/命令/聊天记录 + 自动数据清理辅助管理审计

## 已知限制（v1.8.1）

- 漏斗（`#hopper`）、投掷器（`#dropper`）、发射器（`#dispenser`）交易均已记录
- 掉落物记录覆盖玩家丢弃/捡起；方块被破坏产生的掉落物不做独立记录（已含在方块破坏记录中）
- 告示牌编辑已记录；回滚仅还原放置/破坏时的文字，编辑历史暂不参与回滚
- 击杀记录不回滚（与原版 CoreProtect 一致）
- 大型回滚在主线程执行（受 `maxBlocks` 上限保护）
- 容器回滚的物品找回采用简化策略：被取走的物品优先从容器扣除并交还操作者，缺失部分补发
- 自然事件等挂钩采用 best-effort 策略（`defaultRequire: 0`）：若未来游戏版本改动导致个别挂钩失效，仅失去对应记录，不影响其他功能

## 许可与致谢

- 本模组以 **MIT License** 开源
- 灵感与功能对标 [CoreProtect](https://github.com/PlayPro/CoreProtect)，向其开发团队致敬
- 内置 [sqlite-jdbc](https://github.com/xerial/sqlite-jdbc)（Apache License 2.0）
