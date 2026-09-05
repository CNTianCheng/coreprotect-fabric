# CoreProtect Fabric 版本矩阵

本模组为 Minecraft 1.21 及其后所有受 Fabric 支持的正式版本提供专用构建。每个版本均经过：编译 → Mixin 目标签名核对 → 真实服务器启动实测（含爆炸/水流自然事件记录验证）。

## 产物总览（共 16 个版本）

| 游戏版本 | Jar（对应目录 build/libs 下） | 工程目录 | 映射 | 验证 |
|---|---|---|---|---|
| 1.21 | coreprotect-fabric-1.21-1.0.0.jar | coreprotect-fabric-1.21/ | Yarn | ✅ 全功能实测 |
| 1.21.1 | coreprotect-fabric-1.0.0.jar | coreprotect-fabric/ | Yarn | ✅ 全功能实测 |
| 1.21.2 | coreprotect-fabric-1.21.2-1.0.0.jar | coreprotect-fabric-1.21.2/ | Yarn | ✅ 全功能实测 |
| 1.21.3 | coreprotect-fabric-1.21.3-1.0.0.jar | coreprotect-fabric-1.21.3/ | Yarn | ✅ 全功能实测 |
| 1.21.4 | coreprotect-fabric-1.21.4-1.0.0.jar | coreprotect-fabric-1.21.4/ | Yarn | ✅ 全功能实测 |
| 1.21.5 | coreprotect-fabric-1.21.5-1.0.0.jar | coreprotect-fabric-1.21.5/ | Yarn | ✅ 全功能实测 |
| 1.21.6 | coreprotect-fabric-1.21.6-1.0.0.jar | coreprotect-fabric-1.21.6/ | Yarn | ✅ 全功能实测 |
| 1.21.7 | coreprotect-fabric-1.21.7-1.0.0.jar | coreprotect-fabric-1.21.7/ | Yarn | ✅ 全功能实测 |
| 1.21.8 | coreprotect-fabric-1.21.8-1.0.0.jar | coreprotect-fabric-1.21.8/ | Yarn | ✅ 全功能实测 |
| 1.21.9 | coreprotect-fabric-1.21.9-1.0.0.jar | coreprotect-fabric-1.21.9/ | Yarn | ✅ 全功能实测 |
| 1.21.10 | coreprotect-fabric-1.21.10-1.0.0.jar | coreprotect-fabric-1.21.10/ | Yarn | ✅ 全功能实测 |
| 1.21.11 | coreprotect-fabric-1.21.11-1.0.0.jar | coreprotect-fabric-1.21.11/ | Yarn | ✅ 全功能实测 |
| 26.1 | coreprotect-fabric-26.1-1.0.0.jar | coreprotect-fabric-26.1/ | Mojmap | ✅ 全功能实测 |
| 26.1.1 | coreprotect-fabric-26.1.1-1.0.0.jar | coreprotect-fabric-26.1.1/ | Mojmap | ✅ 全功能实测 |
| 26.1.2 | coreprotect-fabric-26.1.2-1.0.0.jar | coreprotect-fabric-26.1.2/ | Mojmap | ✅ 全功能实测 |
| 26.2 | coreprotect-fabric-26.2-1.0.0.jar | coreprotect-fabric-26.2/ | Mojmap | ✅ 全功能实测 |

## 关键版本适配点（跨版本差异记录）

| 版本 | 适配改动 |
|---|---|
| 1.21.2 | `Explosion` 类改为接口，爆炸入口改为 `ExplosionImpl.explode()`；`RegistryWrapper.getWrapperOrThrow` → `getOrThrow` |
| 1.21.5+ | fabric-api 需要 Loom ≥ 1.10：工具链升级为 Loom 1.17.19 + Gradle 9.7.1 |
| 1.21.9 | authlib 7（`GameProfile.name()` 替代 `getName()`）；`Entity.getWorld()` → `getEntityWorld()`；出生点改由 `server.getSpawnPoint().getPos()` 提供；`ExplosionImpl.explode()` 返回 `int`；移除经典出生点区块刻——无玩家时区块不再刻，测试命令改为强制区块加载 |
| 1.21.11 | 权限系统重构：`hasPermissionLevel(int)` 移除，改用 `CommandManager.requirePermissionLevel(..._CHECK)`（ALL/MODERATORS/GAMEMASTERS/ADMINS/OWNERS 对应 0–4 级） |
| 26.1 | yarn 不再发布，改用 Mojang 官方映射（mojmap），全部 net.minecraft 引用重写；`ResourceLocation` → `Identifier`（含 `dimension().identifier()`）；`ServerExplosion.explode()` 为爆炸入口（`getDirectSourceEntity()` 归因）；`FlowingFluid.spreadTo` 为流体破坏入口；`FireBlock.tick/checkBurnOut` 为火焰入口；`PistonMovingBlockEntity.tick`；`ServerGamePacketListenerImpl.handleChatCommand`；`Commands.hasPermission(Commands.LEVEL_*)`；`SignedMessage` → `PlayerChatMessage`（`signedContent()`）；`SignText.setMessage`；`canBeReplaced()`；`BlockStateParser.parseForBlock`；编译需要 JDK 25 |
| 26.1.1 | 爆炸需显式 `SimpleExplosionDamageCalculator`（实体视线计算器会阻止测试爆炸） |
| 26.2 | 染色方块改为 `ColorCollection`（`Blocks.WOOL.white()` 等） |

## 通用要求（所有版本）

- 服务端：Fabric Loader ≥ 0.16（构建用 0.19.3）+ 对应版本的 Fabric API
- Java：1.21–1.21.11 需 Java 21；26.x 需 **Java 25**
- 客户端无需安装本模组（纯服务端）

## 已知限制（v1）

见各工程 README.md 的「已知限制」章节（漏斗交易、掉落物记录、告示牌编辑记录等）。1.21.9 起，无玩家在线时世界不产生自然事件记录（与原版刻行为一致）；26.x 火焰蔓延受 `fire_spread_radius_around_player` 规则约束（默认 128）。
