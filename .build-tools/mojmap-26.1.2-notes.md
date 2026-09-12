# 26.1.2 port notes (CoreProtect Fabric, mojmap)

## Goal
`G:\桌面\deepseek-Harness\coreprotect-fabric-26.1.2` must compile and behave like
`G:\桌面\deepseek-Harness\coreprotect-fabric-1.21` (version 1.8.1). The 1.21 sources were already
copied into the 26.1.2 project and mechanically rewritten from yarn names to mojmap names by
`.build-tools\port-26.1.2.ps1`. Remaining work = fix compile errors / mixin target descriptors
until `gradle build` succeeds, then verify at runtime.

## Build command
```
$env:JAVA_HOME='C:\Program Files\Java\graalvm-jdk-25.0.2+10.1'   # JDK 25 (required, release 25)
cd G:\桌面\deepseek-Harness\coreprotect-fabric-26.1.2
& 'G:\桌面\deepseek-Harness\.build-tools\gradle\gradle-9.7.1\bin\gradle.bat' build --no-daemon --console=plain
```
IMPORTANT: use `--no-daemon`; the machine has little free RAM, so never run two gradle builds at once.

## Mapped Minecraft jar (for javap checks) - deobfuscated, mojmap names
`C:\Users\whyx2\.gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-merged-deobf\26.1.2\minecraft-merged-deobf-26.1.2.jar`
Use javap from JDK 25:
`& 'C:\Program Files\Java\graalvm-jdk-25.0.2+10.1\bin\javap.exe' -cp <jar> <class>`

## Already-verified mojmap API facts (do not re-derive)
Types:
- `net.minecraft.world.level.Level` (yarn World), `net.minecraft.server.level.ServerLevel` (ServerWorld)
- `net.minecraft.server.level.ServerPlayer` (ServerPlayerEntity), `net.minecraft.commands.CommandSourceStack`
- `net.minecraft.core.BlockPos`, `net.minecraft.core.Direction`, `net.minecraft.util.RandomSource`
- `net.minecraft.world.level.block.state.BlockState`, `net.minecraft.world.Container` (Inventory)
- `net.minecraft.world.item.ItemStack`, `net.minecraft.world.item.BlockItem`, `net.minecraft.world.item.Items`
- `net.minecraft.core.registries.BuiltInRegistries` (Registries), `net.minecraft.core.registries.Registries` (RegistryKeys)
- `net.minecraft.resources.ResourceKey` (RegistryKey), `net.minecraft.resources.Identifier` (Identifier)
- `net.minecraft.network.chat.Component` (Text), `net.minecraft.network.chat.MutableComponent` (MutableText)
- `net.minecraft.ChatFormatting`, `net.minecraft.world.InteractionResult`, `net.minecraft.world.InteractionHand`
- `net.minecraft.world.level.block.piston.PistonMovingBlockEntity` (PistonBlockEntity)
- `net.minecraft.world.entity.item.PrimedTnt` (TntEntity), `net.minecraft.world.entity.player.Player`
- `net.minecraft.world.entity.boss.wither.WitherBoss`, `net.minecraft.world.entity.monster.Creeper`
- `net.minecraft.world.entity.boss.enderdragon.EndCrystal`
- `net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull`
- `net.minecraft.world.level.material.FlowingFluid` / `FluidState` / `LavaFluid` / `WaterFluid`
- `net.minecraft.world.level.LevelAccessor` (WorldAccess), `net.minecraft.world.level.ServerExplosion` (Explosion)

Methods:
- `Entity.level()`, `Entity.getUUID()`, `Entity.blockPosition()`, `Entity.getDisplayName()`
- `ServerPlayer.level()` returns ServerLevel; `ServerPlayer.createCommandSourceStack()`, `doCloseContainer()`
- `Level.dimension()` returns `ResourceKey<Level>`; `.dimension().identifier()`
- `Level.setBlock(BlockPos, BlockState, int[, int])`, `destroyBlock(BlockPos, boolean, Entity, int)`,
  `removeBlock(BlockPos, boolean)`, `getBlockEntity(BlockPos)`, `addFreshEntity(Entity)`
- `Level.explode(Entity, double,double,double, float, Level.ExplosionInteraction)` (was createExplosion)
- `Block.UPDATE_ALL` (was NOTIFY_ALL), `BlockState.defaultBlockState()` (was getDefaultState)
- `Container.getContainerSize()/getItem(int)/setItem(int,ItemStack)` (was size()/getStack()/setStack())
- `ServerboundSignUpdatePacket.getLines()` (was getText())
- `SharedConstants.getCurrentVersion().name()`
- `MinecraftServer.getPlayerCount()`, `getPlayerList()`, `getRespawnData().pos()`
- `PlayerList.getPlayers()`, `getPlayerByName(String)`
- `Commands.hasPermission(PermissionCheck)` -> returns a `Predicate`; constants `Commands.LEVEL_ALL`,
  `LEVEL_MODERATORS`, `LEVEL_GAMEMASTERS`, `LEVEL_ADMINS`, `LEVEL_OWNERS` (class `net.minecraft.server.permissions.PermissionCheck`)
- `Component.literal(...).withStyle(ChatFormatting.X)`, `MutableComponent.withStyle(UnaryOperator<Style>)`
- `new ClickEvent.RunCommand(String)`, `new HoverEvent.ShowText(Component)`
- `CommandSourceStack.sendSuccess(Supplier<Component>, boolean)`, `sendFailure(Component)`, `sendSystemMessage(Component)`
- `HopperBlockEntity.getContainerAt(Level, BlockPos)` (was getInventoryAt)
- `Player.getItemInHand(InteractionHand)`, `Player.drop(ItemStack, boolean)`, `Player.clientInformation()`

## Verified mixin target descriptors for 26.1.2
- WorldMixin (`Level`):
  - `setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z`
  - `setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z`
  - `destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;I)Z`
  - `removeBlock(Lnet/minecraft/core/BlockPos;Z)Z`
- ExplosionMixin: `@Mixin(net.minecraft.world.level.ServerExplosion.class)`, method `explode()I`,
  entity via `getDirectSourceEntity()` (was getEntity)
- FlowableFluidMixin -> `@Mixin(net.minecraft.world.level.material.FlowingFluid.class)`,
  `spreadTo(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Lnet/minecraft/world/level/material/FluidState;)V`
- FireBlockMixin: `tick(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V`
- LeavesBlockMixin: `randomTick(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V`
- EndermanPickupMixin: `@Mixin(targets = "net.minecraft.world.entity.monster.EnderMan$EndermanTakeBlockGoal")`, `tick()V`
- EndermanPlaceMixin: `@Mixin(targets = "net.minecraft.world.entity.monster.EnderMan$EndermanLeaveBlockGoal")`, `tick()V`
- PistonBlockEntityMixin: `tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/piston/PistonMovingBlockEntity;)V` (static)
- ServerPlayerEntityMixin: `doCloseContainer`
- ServerPlayNetworkHandlerMixin (`ServerGamePacketListenerImpl`):
  - `handleChatCommand` with `ServerboundChatCommandPacket`; player field = `((ServerGamePacketListenerImpl) (Object) this).player`
  - `handleSignUpdate` with `ServerboundSignUpdatePacket` (single parameter, no List arg)
- HopperBlockEntityMixin: `pushItemsTick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/HopperBlockEntity;)V` (static)
- DispenserBlockMixin / DropperBlockMixin: `dispenseFrom(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)V`
- PlayerEntityDropMixin: `drop(Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/entity/item/ItemEntity;`
- ItemEntityPickupMixin: `playerTouch(Lnet/minecraft/world/entity/player/Player;)V`

## Known remaining manual work (not covered by the script)
1. `util/Permissions.java`: `hasPermissionLevel` no longer exists. Use
   `Commands.hasPermission(levelCheck(level)).test(source)` where `levelCheck(int)` returns the
   `Commands.LEVEL_*` constant (see `coreprotect-fabric-1.21.11/.../util/Permissions.java` for the
   1.21.11 shape). `event/SessionEventListener.java` also used `hasPermissionLevel`.
2. `mixin/ServerPlayNetworkHandlerMixin.java`: sign logging uses `packet.getLines()`; drop the
   `FilteredMessage` import and the extra parameter.
3. `mixin/PistonBlockEntityMixin.java`: 1.21.11 version calls `CoreProtectFabric.logPistonMove(...)`
   from a `finalTick`/`finish` hook - port the same behaviour using `finalTick()`.
4. `util/BStatsMetrics.java`: `SharedConstants.getCurrentVersion().name()`.
5. `util/BlockStateUtil.java`: sign text + `BlockStateParser.parse(...)` signatures changed; compare
   with the v1.0.0 file (`git show HEAD:coreprotect-fabric-26.1.2/src/...`) which already compiled.
6. `command/CoCommand.java` debug scenario: `Level.ExplosionInteraction.TNT`, `Block.UPDATE_ALL`,
   `hopper.setItem(0, ...)`, `world.addFreshEntity(tnt)`, spawn position from `getRespawnData().pos()`.
7. Features must stay identical to 1.21 v1.8.1: schema v2 database with crash/power-loss protection,
   hopper/dropper/dispenser/piston/item/sign/command/session/chat logging, `/co online`,
   permission groups, bStats, update checker, data retention, colors and `/co i` inspect mode.

## Reference for previously-compiling mojmap code
The original v1.0.0 26.1.2 sources are in git:
`git show HEAD:coreprotect-fabric-26.1.2/src/main/java/...`
(the 26.1.2 project was committed at v1.0.0 and compiled successfully then).
