# CoreProtect Fabric

以 Bukkit/Spigot 生态久负盛名的 [CoreProtect](https://github.com/PlayPro/CoreProtect) 插件为蓝本，仓库地址 [github.com/CNTianCheng/coreprotect-fabric](https://github.com/CNTianCheng/coreprotect-fabric)，为 **Fabric 服务器**独立实现（非移植、非官方作品）的方块级操作记录与回滚模组：记录一切、可查可回滚。覆盖 **Minecraft 1.21 至 26.2 共 16 个游戏版本**。

[English overview](./OVERVIEW_EN.md) · [中文总览](./OVERVIEW.md) · [MIT License](./LICENSE)

## ✨ 功能

- **全量记录**：方块放置/破坏、火焰/水/熔岩/爆炸/TNT/凋灵/活塞/末影人/树叶凋零等自然事件、容器与漏斗/投掷器/发射器交易、掉落物、告示牌编辑、击杀、聊天、命令、登录会话
- **回滚 / 恢复 / 撤销**：`/co rollback`、`/co restore`、`/co undo`，按玩家/时间/半径精确回滚
- **查询**：`/co lookup` 组合过滤（玩家/时间/操作/半径/方块/排除）+ 分页，输出配色与 CoreProtect 插件 v22+ 一致
- **检查模式**：`/co i` 左键方块历史、右键容器交易、手持方块右键查询类型（不放置方块）
- **在线记录**：`/co online <玩家>` 查询几点几分在线
- **权限组**：按玩家名单授予 12 个细粒度节点，未启用时回退到 OP 等级
- **数据维护**：`/co purge`、`dataRetention` 启动自动清理（可设保存天数）、数据库位置可配
- **性能**：SQLite（WAL）异步写入 + 多线程并行读取（最多 8 线程）、mmap 内存映射、方块状态/物品字符串**字典化压缩存储**
- **bStats 统计**：使用人数/玩家数匿名统计（服务编号 `33739`）
- **新版本通知**：启动后自动检查，控制台 + 管理员登录提示
- **多语言**：English / 简体中文 / 繁體中文 / 日本語，自动跟随客户端语言

## 📦 版本矩阵

| Minecraft | 模组版本 | 工程目录 | Java |
|---|---|---|---|
| **1.21（旗舰）** | **1.8.2** | [`coreprotect-fabric-1.21/`](./coreprotect-fabric-1.21) | 21 |
| 1.21.1 | 1.0.0 | [`coreprotect-fabric/`](./coreprotect-fabric) | 21 |
| 1.21.2 ~ 1.21.10 | 1.0.0 | `coreprotect-fabric-1.21.x/` | 21 |
| **1.21.11** | **1.8.2** | [`coreprotect-fabric-1.21.11/`](./coreprotect-fabric-1.21.11) | 21 |
| 26.1 / 26.1.1 / 26.2 | 1.0.0 | `coreprotect-fabric-26.x/` | 25 |
| **26.1.2** | **1.8.2** | [`coreprotect-fabric-26.1.2/`](./coreprotect-fabric-26.1.2) | 25 |

> **1.21、1.21.11、26.1.2 为 v1.8.2 完整功能版**（功能一致）；其余版本为核心功能集 v1.0.0。1.21.2~1.21.11 使用 yarn 映射，26.x 使用 mojmap。所有构建要求 **Fabric Loader ≥ 0.16 + Fabric API**。

## 📥 安装

1. 选择与服务器 **Minecraft 版本严格对应**的 Jar（1.21.x 需 Java 21，26.x 需 Java 25）。
2. 放入服务端 `mods/`，首次启动自动生成 `config/coreprotect-fabric.json` 与数据库。
3. 常用：`/co help`、`/co i`、`/co lookup u:<玩家> t:<时间>`、`/co rollback u:<玩家> t:<时间>`。

## 🛠 自行构建

```bash
# 进入对应版本目录（示例：1.21）
cd coreprotect-fabric-1.21
./gradlew build            # Windows: gradlew.bat build
# 产物在 build/libs/coreprotect-fabric-1.21-<版本>.jar
```

## 🐛 提交 Bug

请使用 [Bug 报告模板](https://github.com/CNTianCheng/coreprotect-fabric/issues/new/choose)（中文），包含：游戏版本、模组版本、复现步骤、`/co status` 输出与服务器日志片段。

## 📄 开源许可

MIT License。灵感与功能对标 [CoreProtect](https://github.com/PlayPro/CoreProtect)（MIT），内置 [sqlite-jdbc](https://github.com/xerial/sqlite-jdbc)（Apache License 2.0）。本模组为独立实现，与 CoreProtect 团队无关。
