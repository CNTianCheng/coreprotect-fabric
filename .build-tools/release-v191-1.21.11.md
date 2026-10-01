# CoreProtect Fabric v1.9.1 (Minecraft 1.21.11)

A block-level logging and rollback mod for **Fabric servers**, independently implemented with the renowned [CoreProtect](https://github.com/PlayPro/CoreProtect) plugin as its blueprint. This is the **v1.9.1 translation release** for the Minecraft 1.21.11 build; the same change ships for the other two maintained builds.

> **Identical feature set across the three maintained builds** (1.21 / 1.21.11 / 26.1.2): same commands, logging coverage, colours, database schema and crash/power-loss protection.

---

## English

### v1.9.1 — Russian translation

- Added a complete Russian (`ru_ru`) translation: **114 keys**, the same key set as `en_us`, with every `{0}` placeholder preserved — no line falls back to English and no command prints a mismatched argument
- `/co language ru_ru` switches to it manually; players whose client language is Russian get it automatically
- The language list is populated from the language folder (there is no hard-coded whitelist), so the new file needs no code change
- Fixed the mod metadata: `fabric.mod.json` pointed at a placeholder repository URL; it now links to the real homepage, source and issue tracker
- No configuration or database change — updating is a drop-in jar replacement

**Also included**: everything from v1.9.0 — database schema v3, roughly half the file size, automatic migration on first start with a `coreprotect.db.bak-v2` safety copy — and every v1.8.2 fix.

### Install

- Requirements: Minecraft **1.21.11**, Java **21**, Fabric Loader >= 0.16 + Fabric API
- Drop `coreprotect-fabric-1.21.11-1.9.1.jar` into the server's `mods/` folder; config and database are created on first launch

### Quick start

```
/co help
/co i                        # inspect: left-click block history, right-click container transactions
/co lookup u:Steve t:1h      # lookup
/co rollback t:1h r:30       # rollback (u: optional)
/co language ru_ru           # switch language
/co status                   # status
```

### Links

- Source & docs: https://github.com/CNTianCheng/coreprotect-fabric
- Bug reports: https://github.com/CNTianCheng/coreprotect-fabric/issues/new/choose
- License: MIT; bundles sqlite-jdbc (Apache License 2.0)
- Independent implementation inspired by CoreProtect; not affiliated with the CoreProtect team

---

## 中文

### v1.9.1 —— 新增俄语翻译

- 新增完整的俄语（`ru_ru`）翻译：**114 个键**与 `en_us` 完全一致，`{0}` 占位符全部保留，不会回退英文，也不会出现参数错位
- 可用 `/co language ru_ru` 手动切换；客户端语言为俄语的玩家自动生效
- 语言列表由语言文件目录动态生成（代码中没有硬编码白名单），新增文件无需改动代码
- 修正模组元数据：`fabric.mod.json` 里原本是占位仓库地址，现已改为真实的主页、源码与反馈地址
- 配置与数据库均无变化，升级就是替换 jar

**同时包含 v1.9.0 的全部内容**：数据库 schema v3、体积约减半、旧库首次启动自动迁移并保留 `coreprotect.db.bak-v2` 备份；以及 v1.8.2 的全部修复。

### 安装

- 要求：Minecraft **1.21.11**、Java **21**、Fabric Loader ≥ 0.16 + Fabric API
- 将 `coreprotect-fabric-1.21.11-1.9.1.jar` 放入服务器 `mods/` 目录，首次启动自动生成配置与数据库

### 快速上手

```
/co help
/co i                        # 检查模式：左键方块历史、右键容器交易
/co lookup u:Steve t:1h      # 查询
/co rollback t:1h r:30       # 回滚（u: 可省略）
/co language ru_ru           # 切换语言
/co status                   # 状态
```

### 相关链接

- 源码与文档：https://github.com/CNTianCheng/coreprotect-fabric
- Bug 反馈：https://github.com/CNTianCheng/coreprotect-fabric/issues/new/choose
- 许可：MIT；内置 sqlite-jdbc（Apache License 2.0）
- 本项目是受 CoreProtect 启发的独立实现，与 CoreProtect 团队无关
