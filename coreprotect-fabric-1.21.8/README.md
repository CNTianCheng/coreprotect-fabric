# CoreProtect Fabric

一个以 **CoreProtect** 插件为蓝本制作的 Fabric 服务器模组（Minecraft 1.21.1）：方块/容器/击杀/聊天/命令/会话全记录，支持回滚（Rollback）、恢复（Restore）、查询（Lookup）、检查模式（Inspect）与多语言切换。

> 本项目是受 [CoreProtect](https://github.com/PlayPro/CoreProtect) 启发的独立实现，与 CoreProtect 团队无关。

## ✨ 功能

- **方块记录**：玩家放置/破坏、火焰蔓延、水/熔岩冲毁、爆炸（TNT/苦力怕/凋灵/末影水晶）、活塞推拉、末影人搬运、树叶凋零（自然事件记录为 `#fire`、`#water`、`#lava`、`#creeper`、`#tnt`、`#piston`、`#enderman`、`#decay` 等）
- **容器交易**：打开箱子/木桶/熔炉/潜影盒等容器时的物品存取差异记录（每个物品类型一行）
- **实体击杀**：玩家击杀生物、玩家死亡
- **聊天 / 命令 / 会话**：聊天消息、执行的命令、登录/登出
- **回滚 / 恢复**：`/co rollback u:<玩家> t:<时间> [r:<半径>]` 撤销该玩家的方块改动与容器物品交易；`/co restore` 反向重放
- **撤销**：`/co undo` 撤销自己最近一次回滚/恢复
- **查询**：`/co lookup` 按玩家、时间、操作、半径、方块类型过滤
- **检查模式**：`/co inspect` 左键查看方块历史、右键查看容器/相邻方块历史、放置方块查询该方块类型
- **多语言**：en_us / zh_cn / zh_tw / ja_jp，默认自动跟随客户端语言，也可用 `/co language <code>` 手动切换（按玩家持久化）
- **数据清理**：`/co purge t:<时间>`
- **SQLite 数据库**：异步写入，游戏线程零阻塞

## 📥 安装

1. 需要 **Java 21** 和 **Fabric Loader 0.16+**（服务端），并安装 **Fabric API**。
2. 将 `coreprotect-fabric-1.0.0.jar` 放入服务端 `mods/` 文件夹。
3. 启动服务器。数据库默认创建在游戏目录下的 `coreprotect.db`，配置在 `config/coreprotect-fabric.json`。

## 🛠 自行构建

```bash
# 需要 JDK 21
./gradlew build
# 产物位于 build/libs/coreprotect-fabric-1.0.0.jar
```

## 🎮 命令

| 命令 | 说明 | 权限 |
|---|---|---|
| `/co help` | 显示帮助 | lookup 级 |
| `/co inspect` | 切换检查模式 | lookup 级 |
| `/co lookup [u:<玩家>] [t:<时间>] [a:<操作>] [r:<半径>] [b:<方块>] [e:<排除>] [p:<页>]` | 查询记录 | lookup 级 |
| `/co rollback u:<玩家> [t:<时间>] [r:<半径>] [a:<操作>] [b:<方块>] [e:<排除>]` | 回滚 | admin 级 |
| `/co restore ...` | 恢复（回滚的反向操作） | admin 级 |
| `/co undo` | 撤销最近一次回滚/恢复 | admin 级 |
| `/co purge t:<时间>` | 删除旧数据 | admin 级 |
| `/co reload` | 重新加载配置与语言 | admin 级 |
| `/co status` | 数据库统计 | lookup 级 |
| `/co language [list\|<代码>]` | 查看/切换语言（`auto` 恢复跟随客户端） | lookup 级 |
| `/co debug natural` | 在出生点附近放置火焰/水流/爆炸测试场景，验证自然事件记录 | admin 级 |

`/coreprotect` 是 `/co` 的别名。

- **时间格式**：`2w5d10h30m15s`（周/天/小时/分钟/秒），纯数字按秒计。
- **操作类型 `a:`**：`block`（全部方块）、`+block`（仅放置）、`-block`（仅破坏）、`#container`（容器）、`#kill`（击杀）、`#chat`、`#command`、`#session`、`+session`、`-session`，以及自然事件 `#fire` `#water` `#lava` `#explosion` `#creeper` `#tnt` `#wither` `#end_crystal` `#enderman` `#piston` `#decay`。
- 权限等级：`config/coreprotect-fabric.json` 中 `permissions.lookupLevel`（默认 0 = 所有人）与 `permissions.adminLevel`（默认 4 = OP）。

## ⚙️ 配置（config/coreprotect-fabric.json）

```jsonc
{
  "language": "en_us",          // 默认语言（玩家可单独切换）
  "databaseFile": "coreprotect.db",
  "logging": {                   // 各类记录的开关
    "block": true, "container": true, "entity": true,
    "chat": true, "command": true, "session": true, "natural": true
  },
  "lookup": {
    "defaultTimeSeconds": 604800, // 查询默认时间窗口（7 天）
    "maxLines": 10,               // 每页行数
    "inspectLines": 8             // 检查模式显示行数
  },
  "rollback": { "maxBlocks": 5000 }, // 单次回滚的安全上限
  "permissions": { "lookupLevel": 0, "adminLevel": 4 }
}
```

## 🗃 数据库

SQLite（WAL 模式），位于游戏目录：`coreprotect.db`，表结构接近 CoreProtect：

- `co_block`：方块记录（旧/新方块状态、操作、告示牌文字）
- `co_container`：容器物品交易（物品 ID + 数量 + 方向）
- `co_entity`：击杀记录
- `co_session` / `co_command` / `co_chat`：会话、命令、聊天
- `co_user`：玩家语言偏好

## 📖 检查模式（Inspect）

1. 执行 `/co inspect` 开启。
2. **左键点击方块**：显示该位置最近 8 条方块历史（+ 容器交易）。
3. **右键点击容器**：显示该容器的物品交易历史；右键普通方块显示相邻方块历史。
4. **放置方块**：对该方块类型执行一次 `lookup`。
5. 检查模式下无法破坏方块（防止误操作）。

## 🌍 多语言

- 语言文件位于 `assets/coreprotect/lang/*.json`，按玩家语言自动选择：`/co language` 覆盖 → 客户端语言 → 配置默认语言 → `en_us`。
- 添加新语言：复制 `en_us.json` 为 `<区域码>.json`（如 `ko_kr.json`）翻译后放入 mods 打包即可。

## ⚠️ 已知限制（v1 路线图）

- 漏斗/投掷器等非玩家容器交易暂不记录
- 掉落物拾取/丢弃暂不记录
- 告示牌**编辑**（不破坏重放）暂不记录，回滚仅还原放置/破坏时的文字
- 击杀记录不回滚（与原版 CoreProtect 一致）
- 大型回滚在主线程执行（受 `maxBlocks` 上限保护）
- 回滚后的容器物品找回策略：被取走的物品优先从容器中扣除并交还执行者，缺失部分会补发（简化版）
- 自然事件通过 Mixin 挂钩实现，采用 best-effort 策略（`defaultRequire: 0`）：若未来 MC 版本改动导致个别挂钩失效，只会失去对应自然事件记录，不影响其他功能

## 📜 许可

MIT License。`sqlite-jdbc` 版权归其作者（Apache License 2.0）。
