# v1.9.1 — Russian translation

Minecraft **1.21**, **1.21.11**, **26.1.2** — all three maintained builds.

## English

* Added a complete Russian (`ru_ru`) translation: **114 keys**, the same key set as `en_us`, with every `{0}` placeholder preserved — no output falls back to English and no command prints a mismatched argument
* `/co language ru_ru` switches to it manually; players whose client language is Russian get it automatically
* The language list is generated from the language folder (no hard-coded whitelist), so the file needed no code change
* No configuration or database change — updating is a drop-in jar replacement

**Also included**: everything from v1.9.0 (database schema v3 — roughly half the file size, automatic migration on first start with a `coreprotect.db.bak-v2` safety copy) and every v1.8.2 fix.

## 中文

* 新增完整的俄语（`ru_ru`）翻译：**114 个键**与 `en_us` 完全一致，`{0}` 占位符全部保留，不会回退英文，也不会出现参数错位
* 可用 `/co language ru_ru` 手动切换；客户端语言为俄语的玩家自动生效
* 语言列表由语言文件目录动态生成（代码中没有硬编码白名单），新增文件无需改动代码
* 配置与数据库均无变化，升级就是替换 jar

**同时包含 v1.9.0 的全部内容**：数据库 schema v3、体积约减半、旧库首次启动自动迁移并保留 `coreprotect.db.bak-v2` 备份；以及 v1.8.2 的全部修复。
