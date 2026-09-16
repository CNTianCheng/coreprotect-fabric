# Contributing to CoreProtect Fabric

Thanks for your interest in improving this mod! This guide explains how to report
problems, propose changes, and get a pull request merged.

中文说明见下方 [中文](#中文)。

---

## English

### Ways to help

* **Bug reports** — use the [bug report form](https://github.com/CNTianCheng/coreprotect-fabric/issues/new/choose). Include the mod version, Minecraft version, Fabric Loader version, the exact command you ran, and the relevant server log lines.
* **Feature requests** — open an issue describing the Minecraft behaviour you want logged, plus how the CoreProtect plugin handles it if you know.
* **Translations** — language files live in `src/main/resources/assets/coreprotect/lang/` (`en_us`, `zh_cn`, `zh_tw`, `ja_jp`). All files must keep the same key set; a missing key falls back to `en_us`.
* **Code** — fixes and features are welcome, but please read the rules below first: they come from real bugs that shipped in this project.

### Which build should I edit?

The repository holds one project per Minecraft version. Only these three carry the
complete feature set and must be kept **in sync**:

| Directory | Minecraft | Java | Mappings |
|---|---|---|---|
| `coreprotect-fabric-1.21/` | 1.21 / 1.21.1 | 21 | yarn |
| `coreprotect-fabric-1.21.11/` | 1.21.11 | 21 | yarn |
| `coreprotect-fabric-26.1.2/` | 26.1.2 | 25 | official (mojmap) |

The remaining directories (`coreprotect-fabric-1.21.2` … `-1.21.10`, `coreprotect-fabric-26.1`,
`-26.1.1`, `-26.2`, `coreprotect-fabric/`) are archived v1.0.0 builds; changes there are
usually not needed.

When you change behaviour, change it in **all three** in-sync directories — the code is the
same logic with different mappings, so the edit is almost always portable.

### Development setup

```bash
# 1.21 / 1.21.11  ->  JDK 21
# 26.1.2          ->  JDK 25  (Loom 1.17.19, Gradle 9.7.1)
cd coreprotect-fabric-1.21
./gradlew build          # jar ends up in build/libs/
./gradlew runServer      # starts a local dev server (run/ directory)
```

* SQLite is bundled as a nested jar (`sqlite-jdbc`); do not add a `shadowJar`.
* The mod is server-side only; `environment` in `fabric.mod.json` stays `*`.

### Hard-won rules (please follow)

1. **Never put non-ASCII text into a `.ps1`/`.bat` script.** Windows PowerShell reads
   BOM-less scripts as ANSI and the text silently turns into mojibake (this once corrupted
   GitHub release bodies). Keep tool scripts pure ASCII and read translated strings from
   UTF-8 data files. `pwsh -File .build-tools/check-ascii.ps1` must pass.
2. **Verify every mixin target against the mapped jar.** `coreprotect.mixins.json` uses
   `defaultRequire: 0`, so an injection that matches nothing fails *silently*. Check with
   `javap -p -c -cp <minecraft-merged-deobf jar> <class>` before committing a mixin change.
3. **Keep `compatibilityLevel` in step with the Java version** (`JAVA_21` for 1.21/1.21.11,
   `JAVA_25` for 26.x). A wrong level makes some injections fail without an error.
4. **Bump the version in both places**: `MOD_VERSION` in `CoreProtectFabric.java` and
   `mod_version` in `gradle.properties`, plus the jar name in the docs of every in-sync build.
5. **Never commit build output, run directories, databases or secrets** (`build/`, `run/`,
   `*.db*`, `apikey.txt`). The `.gitignore` files already cover these — keep it that way.
6. **Database schema changes need a migration.** Bump `PRAGMA user_version`, migrate old
   databases on startup, and keep the automatic `.bak` backup before dropping columns.

### Testing before you open a pull request

* `./gradlew build` must succeed for every in-sync directory you touched.
* Start a real server and exercise the change. The repository ships an RCON harness:
  * `pwsh -File .build-tools/test-full.ps1 -WorkDir <project> -Gradle <gradle.bat> -Fresh`
    runs a headless server, places fire/water/TNT/piston/hopper/dropper/dispenser
    scenarios (`/co debug natural`) and checks lookups, rollback, restore and purge.
  * `pwsh -File .build-tools/test-quick.ps1` is a shorter smoke test.
* Rollback/restore correctness matters most: verify blocks really return to the right
  state (`execute if block …` in RCON) instead of trusting the summary line.

### Pull requests

* One logical change per pull request; describe **what** changed and **why**.
* Fill in the pull request template completely — especially "builds affected" and
  "how it was tested".
* Keep the existing code style: 4-space indentation, no wildcard imports, no new
  dependencies without a good reason, comments only where the code is not self-evident.
* User-visible strings belong in the language files, not in Java code.
* CoreProtect compatibility is a goal: keep command syntax, colour scheme
  (`§3CoreProtect §f- `, `----- CoreProtect | Lookup Results -----`) and row layout stable.

### Commit messages

Short imperative subject in English, optionally a body explaining the reasoning:
`fix: only log item pickups when the stack really shrank`.

---

## 中文

### 你可以怎样参与

* **Bug 反馈** —— 使用 [Bug 表单](https://github.com/CNTianCheng/coreprotect-fabric/issues/new/choose)，请附上模组版本、Minecraft 版本、Fabric Loader 版本、执行的命令，以及相关服务器日志。
* **功能建议** —— 提 Issue 说明希望记录的游戏行为；如果知道 CoreProtect 插件的处理方式，请一并说明。
* **翻译** —— 语言文件位于 `src/main/resources/assets/coreprotect/lang/`（`en_us`、`zh_cn`、`zh_tw`、`ja_jp`），所有文件必须保持相同的键集合，缺失的键会回退到 `en_us`。
* **代码** —— 欢迎修 bug 与提交功能，但请先阅读下面的规则：它们都是这个项目里真实发布过的缺陷换来的。

### 应该改哪个工程目录？

仓库里每个 Minecraft 版本一个工程目录。只有下面三个是**完整功能版**，且必须保持同步：

| 目录 | Minecraft | Java | 映射 |
|---|---|---|---|
| `coreprotect-fabric-1.21/` | 1.21 / 1.21.1 | 21 | yarn |
| `coreprotect-fabric-1.21.11/` | 1.21.11 | 21 | yarn |
| `coreprotect-fabric-26.1.2/` | 26.1.2 | 25 | 官方 mojmap |

其余目录（`coreprotect-fabric-1.21.2` … `-1.21.10`、`coreprotect-fabric-26.1`、`-26.1.1`、`-26.2`、`coreprotect-fabric/`）是归档的 v1.0.0 构建，通常无需改动。

改动行为时请**同步修改这三个目录**：它们逻辑相同、只是映射不同，改动基本可以直接照搬。

### 开发环境

```bash
# 1.21 / 1.21.11  ->  JDK 21
# 26.1.2          ->  JDK 25（Loom 1.17.19、Gradle 9.7.1）
cd coreprotect-fabric-1.21
./gradlew build          # 产物在 build/libs/
./gradlew runServer      # 启动本地开发服务器（run/ 目录）
```

* SQLite 以嵌套 jar（`sqlite-jdbc`）方式内置，请勿引入 shadowJar。
* 模组为纯服务端，`fabric.mod.json` 的 `environment` 保持 `*`。

### 必须遵守的几条规则

1. **不要在 `.ps1`/`.bat` 脚本里写非 ASCII 字符。** Windows PowerShell 按 ANSI 读取无 BOM 脚本，中文会静默变成乱码（曾导致 GitHub Release 说明乱码）。工具脚本保持纯 ASCII，中文放到 UTF-8 数据文件里读取；`pwsh -File .build-tools/check-ascii.ps1` 必须通过。
2. **每个 mixin 目标都要对着映射后的 jar 验证。** `coreprotect.mixins.json` 使用 `defaultRequire: 0`，匹配不到的注入会**静默失效**。提交前用 `javap -p -c -cp <minecraft-merged-deobf jar> <类名>` 核对。
3. **`compatibilityLevel` 要与 Java 版本一致**（1.21/1.21.11 用 `JAVA_21`，26.x 用 `JAVA_25`），写错会导致部分注入失败且不报错。
4. **两处都要升版本号**：`CoreProtectFabric.java` 的 `MOD_VERSION` 与 `gradle.properties` 的 `mod_version`，并同步各完整功能版文档里的 jar 名。
5. **不要提交构建产物、运行目录、数据库或密钥**（`build/`、`run/`、`*.db*`、`apikey.txt`），`.gitignore` 已覆盖，请勿移除。
6. **改数据库结构必须写迁移**：提升 `PRAGMA user_version`、启动时迁移旧库，并在删除列前保留自动 `.bak` 备份。

### 提交 PR 前请自测

* 改动到的每个完整功能版目录都要 `./gradlew build` 通过。
* 起一个真实服务端验证改动。仓库自带 RCON 测试脚本：
  * `pwsh -File .build-tools/test-full.ps1 -WorkDir <工程目录> -Gradle <gradle.bat> -Fresh`：启动无头服务端，放置火焰/水/TNT/活塞/漏斗/投掷器/发射器场景（`/co debug natural`），并检查查询、回滚、恢复与清理。
  * `pwsh -File .build-tools/test-quick.ps1`：更快的冒烟测试。
* 回滚/恢复的正确性最重要：请用 RCON 的 `execute if block …` 确认方块真的回到正确状态，不要只看汇总行。

### 关于 Pull Request

* 一个 PR 只做一件事，说明**改了什么**与**为什么**。
* 完整填写 PR 模板，尤其是「影响哪些构建」与「如何测试」。
* 保持现有代码风格：4 空格缩进、不用通配符导入、非必要不新增依赖、注释只写在不易理解处。
* 面向用户的文案放进语言文件，不要硬编码在 Java 里。
* 兼容 CoreProtect 是目标：命令语法、配色（`§3CoreProtect §f- `、`----- CoreProtect | Lookup Results -----`）与行布局请保持稳定。

### 提交信息

英文祈使句短标题，必要时补充说明原因，例如：
`fix: only log item pickups when the stack really shrank`
