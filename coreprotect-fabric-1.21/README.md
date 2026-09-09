# CoreProtect Fabric（Minecraft 1.21 版）

一个以 **CoreProtect** 插件为蓝本制作的 Fabric 服务器模组（Minecraft 1.21）：方块/容器/击杀/聊天/命令/会话全记录，支持回滚（Rollback）、恢复（Restore）、查询（Lookup）、检查模式（Inspect）与多语言切换。

> 本目录为 **Minecraft 1.21** 专用构建；Minecraft 1.21.1 请使用 `coreprotect-fabric/` 目录构建的 `coreprotect-fabric-1.0.0.jar`。

> 本项目是受 [CoreProtect](https://github.com/PlayPro/CoreProtect) 启发的独立实现，与 CoreProtect 团队无关。

## ✨ 功能

- **方块记录**：玩家放置/破坏、火焰蔓延、水/熔岩冲毁、爆炸（TNT/苦力怕/凋灵/末影水晶）、活塞推拉、末影人搬运、树叶凋零（自然事件记录为 `#fire`、`#water`、`#lava`、`#creeper`、`#tnt`、`#piston`、`#enderman`、`#decay` 等）
- **容器交易**：打开箱子/木桶/熔炉/潜影盒等容器时的物品存取差异记录（每个物品类型一行）
- **漏斗交易**：漏斗每次刻对自身、上方来源容器与朝向目标容器的物品变化逐项记录，记为用户 `#hopper`（`/co lookup u:#hopper a:#container`）
- **投掷器/发射器**：每次发射物品（取走 1 个）与外部存入都按容器交易逐项记录，记为用户 `#dropper` / `#dispenser`（`/co lookup u:#dropper a:#container`、`u:#dispenser a:#container`）
- **掉落物**：玩家丢弃/捡起物品记录（`/co lookup a:item`，动词 dropped / picked up）
- **告示牌编辑**：玩家修改告示牌文字记录（`/co lookup a:#sign`），检查模式下也会显示该位置的文字历史
- **实体击杀**：玩家击杀生物、玩家死亡
- **聊天 / 命令 / 会话**：聊天消息、执行的命令、登录/登出
- **回滚 / 恢复**：`/co rollback u:<玩家> t:<时间> [r:<半径>]` 撤销该玩家的方块改动与容器物品交易；`/co restore` 反向重放
- **撤销**：`/co undo` 撤销自己最近一次回滚/恢复
- **查询**：`/co lookup` 按玩家、时间、操作、半径、方块类型过滤
- **检查模式**：`/co inspect`（简写 `/co i`，支持 `on`/`off`）左键查看方块历史、右键查看容器/相邻方块历史、手持方块右键查询该方块类型（不放置方块）
- **在线记录**：`/co online` 列出当前在线玩家；`/co online <玩家> [t:<时间>] [p:<页>]` 按"几点几分"（`yyyy-MM-dd HH:mm:ss`）列出该玩家的上线/下线记录，并显示当前是否在线
- **权限组**：按玩家名单精细授权（`coreprotect.help`、`coreprotect.inspect`、`coreprotect.lookup`、`coreprotect.rollback` 等节点），未启用时回退到 OP 等级判断
- **数据库位置**：`databaseFile` 支持相对路径（相对于游戏目录，父目录自动创建）与绝对路径
- **数据保存限制**：`dataRetention` 开关 + 最长保存天数，启用后服务器启动时自动删除过期数据
- **bStats 统计**：按官方协议匿名上报使用人数（需 bstats.org 注册的服务 ID，可随时退出）
- **新版本通知**：自动检查 Modrinth，控制台 + 管理员登录时提示新版本
- **多语言**：en_us / zh_cn / zh_tw / ja_jp，默认自动跟随客户端语言，也可用 `/co language <code>` 手动切换（按玩家持久化）
- **数据清理**：`/co purge t:<时间>`
- **SQLite 数据库**：异步写入、游戏线程零阻塞；读取走独立多线程连接池（多核并行）；**压缩存储**：方块状态/物品字符串字典化（v2 结构，旧库首次启动自动迁移备份并压缩）；`/co purge` 后自动压缩文件；mmap 内存映射 + 可调缓存（`database.cacheSizeMB`，默认 128MB）
- **崩溃/断电保护**：WAL 每次提交刷盘（`syncMode: full`）；异常关闭自动检测并在启动时校验（quick_check）；定期热备份 `coreprotect.db.backup`；检测到损坏时**自动从备份恢复**（原件另存为 `.corrupt-<时间>`）
- **CoreProtect 同款配色**（v22+ 样式）：通用消息前缀为深青色 `CoreProtect - ` + 白色文字；查询标题 `----- CoreProtect | Lookup Results -----`；查询行按"灰色时间 + 绿色`+`/红色`-`标记 + 深青色玩家 + 白色动作 + 深青色对象"着色；状态行为深青色标签 + 白色数值

## 📥 安装

1. 需要 **Java 21** 和 **Fabric Loader 0.16+**（服务端），并安装 **Fabric API**。
2. 将 `coreprotect-fabric-1.21-1.8.1.jar` 放入服务端 `mods/` 文件夹。
3. 启动服务器。数据库默认创建在游戏目录下的 `coreprotect.db`，配置在 `config/coreprotect-fabric.json`。

## 🛠 自行构建

```bash
# 需要 JDK 21
./gradlew build
# 产物位于 build/libs/coreprotect-fabric-1.21-1.8.1.jar
```

## 🎮 命令

| 命令 | 说明 | 权限 |
|---|---|---|
| `/co help` | 显示帮助 | lookup 级 |
| `/co inspect [on\|off]` | 切换检查模式 | lookup 级 |
| `/co i [on\|off]` | `/co inspect` 的简写（CoreProtect 同款） | lookup 级 |
| `/co lookup [u:<玩家>] [t:<时间>] [a:<操作>] [r:<半径>] [b:<方块>] [e:<排除>] [p:<页>]` | 查询记录 | lookup 级 |
| `/co rollback u:<玩家> [t:<时间>] [r:<半径>] [a:<操作>] [b:<方块>] [e:<排除>]` | 回滚 | admin 级 |
| `/co restore ...` | 恢复（回滚的反向操作） | admin 级 |
| `/co undo` | 撤销最近一次回滚/恢复 | admin 级 |
| `/co purge t:<时间>` | 删除旧数据 | admin 级 |
| `/co reload` | 重新加载配置与语言 | admin 级 |
| `/co status` | 数据库统计 | lookup 级 |
| `/co online` | 列出当前在线玩家 | lookup 级 |
| `/co online <玩家> [t:<时间>] [p:<页>]` | 查询玩家几点几分在线（登录/登出时间 + 当前是否在线） | lookup 级 |
| `/co language [list\|<代码>]` | 查看/切换语言（`auto` 恢复跟随客户端） | lookup 级 |
| `/co debug natural` | 在出生点附近放置火焰/水流/爆炸/漏斗/投掷器/发射器测试场景，验证自然事件与机械容器记录 | admin 级 |

/coreprotect 是 /co 的别名。/co lookup、/co rollback、/co restore、/co purge、/co online 输入参数时会自动提示 u: 	: : : : e: p: 等键，u: 后会自动补全玩家名；/co language 提示可用语言代码。

- **时间格式**：`2w5d10h30m15s`（周/天/小时/分钟/秒），纯数字按秒计。
- **操作类型 `a:`**：`block`（全部方块）、`+block`（仅放置）、`-block`（仅破坏）、`#container`（容器）、`item`/`#item`（掉落物）、`#sign`（告示牌编辑）、`#kill`（击杀）、`#chat`、`#command`、`#session`、`+session`、`-session`，以及自然事件 `#fire` `#water` `#lava` `#explosion` `#creeper` `#tnt` `#wither` `#end_crystal` `#enderman` `#piston` `#decay`。
- 权限等级：`config/coreprotect-fabric.json` 中 `permissions.lookupLevel`（默认 0 = 所有人）与 `permissions.adminLevel`（默认 4 = OP）。开启 `permissionGroups.enabled` 后，按玩家名单授予细粒度节点，OP 等级仅作为未匹配到组时的回退。

## 🔐 权限组（permissionGroups）

在 `config/coreprotect-fabric.json` 中把 `permissionGroups.enabled` 设为 `true` 即可启用。每个组可配置玩家名单与权限节点列表：

```jsonc
"permissionGroups": {
  "enabled": true,
  "groups": {
    "admin": {
      "players": ["Steve"],
      "permissions": ["all"]              // "all" 授予全部权限
    },
    "builder": {
      "players": ["Alex", "Notch"],
      "permissions": [
        "coreprotect.help",
        "coreprotect.status",
        "coreprotect.inspect",
        "coreprotect.lookup",
        "coreprotect.rollback"
      ]
    }
  }
}
```

可用节点：`coreprotect.help`、`coreprotect.status`、`coreprotect.inspect`、`coreprotect.lookup`、`coreprotect.online`、`coreprotect.language`（lookup 级），`coreprotect.rollback`、`coreprotect.restore`、`coreprotect.undo`、`coreprotect.purge`、`coreprotect.reload`、`coreprotect.debug`（admin 级）。

规则：玩家名与节点名不区分大小写；一个玩家属于多个组时取并集；未匹配到任何组的玩家回退到 `permissions.lookupLevel`/`adminLevel` 判断；控制台与命令方块始终拥有全部权限。`/co status` 会显示权限组的开关状态与组数量。

## ⚙️ 配置（config/coreprotect-fabric.json）

```jsonc
{
  "language": "en_us",          // 默认语言（玩家可单独切换）
  "databaseFile": "coreprotect.db", // 相对游戏目录；可用子目录（如 "data/coreprotect.db"）或绝对路径
  "logging": {                   // 各类记录的开关
    "block": true, "container": true, "entity": true,
    "chat": true, "command": true, "session": true, "natural": true,
    "hopper": true, "dispenser": true, "item": true, "signEdit": true
  },
  "lookup": {
    "defaultTimeSeconds": 604800, // 查询默认时间窗口（7 天）
    "maxLines": 10,               // 每页行数
    "inspectLines": 8             // 检查模式显示行数
  },
  "rollback": { "maxBlocks": 5000 }, // 单次回滚的安全上限
  "permissions": { "lookupLevel": 0, "adminLevel": 4 },
  "permissionGroups": {
    "enabled": false,
    "groups": {
      "admin": { "players": [], "permissions": ["all"] }
    }
  },
  "database": {                  // 数据库调优与安全
    "cacheSizeMB": 128,          // 每连接页面缓存（越大查询越快，占用内存越多）
    "syncMode": "full",          // full=每次提交刷盘（断电也不丢已提交数据）；normal=更快
    "checkpointMinutes": 10,     // WAL 检查点间隔（越小崩溃恢复越快）
    "backupMinutes": 360,        // 在线热备份间隔（写 coreprotect.db.backup）；0=关闭
    "autoRestoreBackup": true    // 检测到损坏时自动从备份恢复
  },
  "dataRetention": {             // 数据保存时间限制
    "enabled": false,            // 开启后，服务器启动时自动删除过期数据
    "maxDays": 30                // 最长保存天数
  },
  "metrics": {                   // bStats 使用人数统计
    "enabled": true,
    "serviceId": 33739,          // bStats 服务编号（已内置，可改为 0 关闭上报）
    "endpoint": "https://bstats.org/api/v2/data/server-implementation"
  },
  "updateCheck": {               // 新版本通知
    "enabled": true,
    "slug": "coreprotect-fabric", // Modrinth 项目 slug
    "url": "",                   // 可选：自定义 JSON API 地址（Modrinth 版本列表格式）
    "intervalHours": 12          // 检查间隔（小时）
  }
}
```

## 📊 bStats 使用人数统计

- 按官方 bStats v2 协议实现：每 30 分钟向 bstats.org 匿名上报一次（在线人数、是否正版验证、MC/加载器/Java/系统版本、CPU 核数等），启动后首次上报有 3-6 分钟随机延迟。
- 服务编号已内置（`metrics.serviceId: 33739`，平台 `server-implementation`），默认开箱即用；改为 0 或关闭 `metrics.enabled` 即不上报。
- 完全匿名，无性能影响；也可通过 `config/bstats/config.txt` 中的 `enabled: false` 一键退出。

## 🆕 新版本通知

- 服务器启动 30 秒后自动检查 Modrinth API（`updateCheck.slug`，可用 `url` 换成自定义接口），之后每 `intervalHours` 小时复查一次。
- 发现新版本时，**服务器控制台**会输出：`A new version of CoreProtect Fabric is available: vX.X.X (current: vY.Y.Y). Download: <链接>`。
- 拥有 admin 级权限的玩家**登录时**会收到同款通知（跟随玩家语言）。

## 📅 在线记录（/co online）

- `/co online`：列出当前在线玩家。
- `/co online Steve`：显示 Steve 当前是否在线，并列出其登录/登出时间（服务器本地时间 `yyyy-MM-dd HH:mm:ss`）。
- `/co online Steve t:1h p:2`：只查最近 1 小时，显示第 2 页。
- 依赖会话记录（`logging.session: true`，默认开启）。

## 🕑 数据保存限制（dataRetention）

- `enabled: false`：不限制（默认）。
- `enabled: true` + `maxDays: 30`：每次服务器启动时，自动删除超过 30 天的全部记录（方块/容器/掉落物/告示牌/实体/会话/命令/聊天），删除条数会写入服务器日志。
- `/co status` 末尾显示当前限制状态；`/co purge t:<时间>` 仍可随时手动清理。

## 🗃 数据库

SQLite（WAL 模式），默认位于游戏目录：`coreprotect.db`（可用 `databaseFile` 改为相对子目录或绝对路径），表结构接近 CoreProtect：

- `co_block`：方块记录（旧/新方块状态、操作、告示牌文字）
- `co_container`：容器与漏斗物品交易（物品 ID + 数量 + 方向）
- `co_item`：掉落物记录（丢弃/捡起）
- `co_sign`：告示牌编辑记录（文字快照）
- `co_entity`：击杀记录
- `co_session` / `co_command` / `co_chat`：会话、命令、聊天
- `co_user`：玩家语言偏好

## 📖 检查模式（Inspect）

1. 执行 `/co inspect` 或简写 `/co i` 开启；`/co inspect off` / `/co i off` 关闭，`/co inspect on` / `/co i on` 强制开启。
2. **左键点击方块**：显示该位置最近 8 条方块历史（+ 容器交易）。
3. **右键点击容器**：显示该容器的物品交易历史；右键普通方块显示相邻方块历史。
4. **手持方块右键方块**：不放置方块，直接对该方块类型执行一次 `lookup`；点击空气则不做任何操作（与 CoreProtect 插件一致）。
5. 检查模式下无法破坏方块（防止误操作）。

## 🌍 多语言

- 语言文件位于 `assets/coreprotect/lang/*.json`，按玩家语言自动选择：`/co language` 覆盖 → 客户端语言 → 配置默认语言 → `en_us`。
- 添加新语言：复制 `en_us.json` 为 `<区域码>.json`（如 `ko_kr.json`）翻译后放入 mods 打包即可。

## ⚠️ 已知限制（v1 路线图）

- 漏斗（`#hopper`）、投掷器（`#dropper`）、发射器（`#dispenser`）交易均已记录
- 掉落物记录覆盖玩家丢弃/捡起；方块被破坏产生的掉落物不做独立记录（已含在方块破坏记录中）
- 告示牌编辑已记录；回滚仅还原放置/破坏时的文字，编辑历史暂不参与回滚
- 击杀记录不回滚（与原版 CoreProtect 一致）
- 大型回滚在主线程执行（受 `maxBlocks` 上限保护）
- 回滚后的容器物品找回策略：被取走的物品优先从容器中扣除并交还执行者，缺失部分会补发（简化版）
- 自然事件通过 Mixin 挂钩实现，采用 best-effort 策略（`defaultRequire: 0`）：若未来 MC 版本改动导致个别挂钩失效，只会失去对应自然事件记录，不影响其他功能

## 📜 许可

MIT License。`sqlite-jdbc` 版权归其作者（Apache License 2.0）。
