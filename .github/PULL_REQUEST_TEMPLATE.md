## What does this pull request change?

<!-- One or two sentences. Link the issue it fixes: "Fixes #123". -->

## Why?

<!-- The behaviour before/after, and why the change is needed. -->

## Builds affected

<!-- The three builds below carry the full feature set and must stay in sync. -->

- [ ] `coreprotect-fabric-1.21/`
- [ ] `coreprotect-fabric-1.21.11/`
- [ ] `coreprotect-fabric-26.1.2/`
- [ ] other / archived build (please say which):

## Type of change

- [ ] Bug fix
- [ ] New feature
- [ ] Database schema / migration change
- [ ] Translation or documentation only
- [ ] Tooling / CI

## How was it tested?

<!-- A checked box means you actually did it, not that it should work. -->

- [ ] `./gradlew build` succeeds for every build listed above
- [ ] Runtime tested on a real server (RCON or in-game), steps:
- [ ] Rollback / restore verified at block level (`execute if block …`), not just by the summary line
- [ ] Every new/changed mixin target verified against the mapped jar with `javap`
- [ ] `pwsh -File .build-tools/check-ascii.ps1` passes (no non-ASCII text inside scripts)

## Checklist

- [ ] `MOD_VERSION` in `CoreProtectFabric.java` **and** `mod_version` in `gradle.properties` are bumped
- [ ] `compatibilityLevel` in `coreprotect.mixins.json` matches the build (`JAVA_21` / `JAVA_25`)
- [ ] New user-visible strings were added to **all four** language files with the same key set
- [ ] `README.md` / `OVERVIEW.md` / `OVERVIEW_EN.md` updated if behaviour or versions changed
- [ ] No build output, `run/` directory, database file, jar or secret is included in the diff
- [ ] Database change includes a migration path for existing databases (if applicable)

---

## 这个 PR 改了什么？

<!-- 一两句话，并关联 Issue："Fixes #123"。 -->

## 为什么？

<!-- 改动前后的行为，以及为什么需要改。 -->

## 影响哪些构建

<!-- 以下三个是完整功能版，必须保持同步。 -->

- [ ] `coreprotect-fabric-1.21/`
- [ ] `coreprotect-fabric-1.21.11/`
- [ ] `coreprotect-fabric-26.1.2/`
- [ ] 其他/归档构建（请注明）：

## 改动类型

- [ ] 缺陷修复
- [ ] 新功能
- [ ] 数据库结构 / 迁移
- [ ] 仅翻译或文档
- [ ] 工具链 / CI

## 如何测试的？

<!-- 勾选代表你确实做了，而不是“应该没问题”。 -->

- [ ] 上述每个构建目录 `./gradlew build` 均通过
- [ ] 在真实服务端实测（RCON 或游戏内），步骤：
- [ ] 回滚 / 恢复已在方块层面验证（用 `execute if block …`），而不是只看汇总行
- [ ] 新增/修改的 mixin 目标都用 `javap` 对着映射后的 jar 核对过
- [ ] `pwsh -File .build-tools/check-ascii.ps1` 通过（脚本内没有非 ASCII 文本）

## 检查清单

- [ ] `CoreProtectFabric.java` 的 `MOD_VERSION` **和** `gradle.properties` 的 `mod_version` 都已升级
- [ ] `coreprotect.mixins.json` 的 `compatibilityLevel` 与构建匹配（`JAVA_21` / `JAVA_25`）
- [ ] 新增的面向用户文案已加入**全部四个**语言文件且键集合一致
- [ ] 行为或版本变化时已更新 `README.md` / `OVERVIEW.md` / `OVERVIEW_EN.md`
- [ ] diff 中没有构建产物、`run/` 目录、数据库文件、jar 或密钥
- [ ] 若改动数据库结构，已为既有数据库提供迁移路径
