$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$src = Join-Path $root 'coreprotect-fabric-1.21\src\main'
$dst = Join-Path $root 'coreprotect-fabric-26.1.2\src\main'

Write-Host "Copying java sources from 1.21 -> 26.1.2 (mojmap)"
Copy-Item -Path (Join-Path $src 'java\*') -Destination (Join-Path $dst 'java') -Recurse -Force
Copy-Item -Path (Join-Path $src 'resources\assets\*') -Destination (Join-Path $dst 'resources\assets') -Recurse -Force

# Order matters: longer / more specific strings first.
$pairs = @(
    # --- protect identifiers that must keep their (mixin) names ---
    @('WorldMixin', '@@WM@@'),
    @('ServerPlayerEntityMixin', '@@SPEM@@'),
    @('PlayerEntityDropMixin', '@@PEDM@@'),
    @('ItemEntityPickupMixin', '@@IEPM@@'),
    @('PistonBlockEntityMixin', '@@PBEM@@'),
    @('HopperBlockEntityMixin', '@@HBEM@@'),
    @('SignText', '@@SIGNTEXT@@'),

    # --- imports ---
    @('import net.minecraft.util.math.BlockPos;', 'import net.minecraft.core.BlockPos;'),
    @('import net.minecraft.util.math.Direction;', 'import net.minecraft.core.Direction;'),
    @('import net.minecraft.util.math.Vec3d;', 'import net.minecraft.world.phys.Vec3;'),
    @('import net.minecraft.util.math.random.Random;', 'import net.minecraft.util.RandomSource;'),
    @('import net.minecraft.util.math.ChunkPos;', 'import net.minecraft.world.level.ChunkPos;'),
    @('import net.minecraft.util.hit.BlockHitResult;', 'import net.minecraft.world.phys.BlockHitResult;'),
    @('import net.minecraft.util.Hand;', 'import net.minecraft.world.InteractionHand;'),
    @('import net.minecraft.util.ActionResult;', 'import net.minecraft.world.InteractionResult;'),
    @('import net.minecraft.util.Formatting;', 'import net.minecraft.ChatFormatting;'),
    @('import net.minecraft.util.Identifier;', 'import net.minecraft.resources.Identifier;'),
    @('import net.minecraft.block.BlockState;', 'import net.minecraft.world.level.block.state.BlockState;'),
    @('import net.minecraft.block.Block;', 'import net.minecraft.world.level.block.Block;'),
    @('import net.minecraft.block.Blocks;', 'import net.minecraft.world.level.block.Blocks;'),
    @('import net.minecraft.block.HopperBlock;', 'import net.minecraft.world.level.block.HopperBlock;'),
    @('import net.minecraft.block.FireBlock;', 'import net.minecraft.world.level.block.FireBlock;'),
    @('import net.minecraft.block.LeavesBlock;', 'import net.minecraft.world.level.block.LeavesBlock;'),
    @('import net.minecraft.block.PistonBlock;', 'import net.minecraft.world.level.block.piston.PistonBaseBlock;'),
    @('import net.minecraft.block.FluidBlock;', 'import net.minecraft.world.level.block.LiquidBlock;'),
    @('import net.minecraft.block.AbstractFireBlock;', 'import net.minecraft.world.level.block.BaseFireBlock;'),
    @('import net.minecraft.block.DispenserBlock;', 'import net.minecraft.world.level.block.DispenserBlock;'),
    @('import net.minecraft.block.DropperBlock;', 'import net.minecraft.world.level.block.DropperBlock;'),
    @('import net.minecraft.block.entity.BlockEntity;', 'import net.minecraft.world.level.block.entity.BlockEntity;'),
    @('import net.minecraft.block.entity.HopperBlockEntity;', 'import net.minecraft.world.level.block.entity.HopperBlockEntity;'),
    @('import net.minecraft.block.entity.DispenserBlockEntity;', 'import net.minecraft.world.level.block.entity.DispenserBlockEntity;'),
    @('import net.minecraft.block.entity.DropperBlockEntity;', 'import net.minecraft.world.level.block.entity.DropperBlockEntity;'),
    @('import net.minecraft.block.entity.PistonBlockEntity;', 'import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;'),
    @('import net.minecraft.block.entity.SignBlockEntity;', 'import net.minecraft.world.level.block.entity.SignBlockEntity;'),
    @('import net.minecraft.block.entity.SignText;', 'import net.minecraft.world.level.block.entity.SignText;'),
    @('import net.minecraft.entity.Entity;', 'import net.minecraft.world.entity.Entity;'),
    @('import net.minecraft.entity.LivingEntity;', 'import net.minecraft.world.entity.LivingEntity;'),
    @('import net.minecraft.entity.ItemEntity;', 'import net.minecraft.world.entity.item.ItemEntity;'),
    @('import net.minecraft.entity.TntEntity;', 'import net.minecraft.world.entity.item.PrimedTnt;'),
    @('import net.minecraft.entity.player.PlayerEntity;', 'import net.minecraft.world.entity.player.Player;'),
    @('import net.minecraft.entity.decoration.EndCrystalEntity;', 'import net.minecraft.world.entity.boss.enderdragon.EndCrystal;'),
    @('import net.minecraft.entity.mob.CreeperEntity;', 'import net.minecraft.world.entity.monster.Creeper;'),
    @('import net.minecraft.entity.boss.WitherEntity;', 'import net.minecraft.world.entity.boss.wither.WitherBoss;'),
    @('import net.minecraft.entity.projectile.WitherSkullEntity;', 'import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;'),
    @('import net.minecraft.fluid.FlowableFluid;', 'import net.minecraft.world.level.material.FlowingFluid;'),
    @('import net.minecraft.fluid.FluidState;', 'import net.minecraft.world.level.material.FluidState;'),
    @('import net.minecraft.fluid.LavaFluid;', 'import net.minecraft.world.level.material.LavaFluid;'),
    @('import net.minecraft.fluid.WaterFluid;', 'import net.minecraft.world.level.material.WaterFluid;'),
    @('import net.minecraft.inventory.Inventory;', 'import net.minecraft.world.Container;'),
    @('import net.minecraft.item.ItemStack;', 'import net.minecraft.world.item.ItemStack;'),
    @('import net.minecraft.item.Item;', 'import net.minecraft.world.item.Item;'),
    @('import net.minecraft.item.Items;', 'import net.minecraft.world.item.Items;'),
    @('import net.minecraft.item.BlockItem;', 'import net.minecraft.world.item.BlockItem;'),
    @('import net.minecraft.registry.Registries;', 'import net.minecraft.core.registries.BuiltInRegistries;'),
    @('import net.minecraft.registry.RegistryKey;', 'import net.minecraft.resources.ResourceKey;'),
    @('import net.minecraft.registry.RegistryKeys;', 'import net.minecraft.core.registries.Registries;'),
    @('import net.minecraft.registry.RegistryWrapper;', 'import net.minecraft.core.RegistryAccess;'),
    @('import net.minecraft.server.command.ServerCommandSource;', 'import net.minecraft.commands.CommandSourceStack;'),
    @('import net.minecraft.server.network.ServerPlayerEntity;', 'import net.minecraft.server.level.ServerPlayer;'),
    @('import net.minecraft.server.world.ServerWorld;', 'import net.minecraft.server.level.ServerLevel;'),
    @('import net.minecraft.text.Text;', 'import net.minecraft.network.chat.Component;'),
    @('import net.minecraft.text.MutableText;', 'import net.minecraft.network.chat.MutableComponent;'),
    @('import net.minecraft.text.ClickEvent;', 'import net.minecraft.network.chat.ClickEvent;'),
    @('import net.minecraft.text.HoverEvent;', 'import net.minecraft.network.chat.HoverEvent;'),
    @('import net.minecraft.world.WorldAccess;', 'import net.minecraft.world.level.LevelAccessor;'),
    @('import net.minecraft.world.World;', 'import net.minecraft.world.level.Level;'),
    @('import net.minecraft.command.argument.BlockArgumentParser;', 'import net.minecraft.commands.arguments.blocks.BlockStateParser;'),
    @('import net.minecraft.server.filter.FilteredMessage;', ''),
    @('import net.minecraft.world.explosion.Explosion;', 'import net.minecraft.world.level.ServerExplosion;'),
    @('import net.minecraft.network.packet.c2s.play.CommandExecutionC2SPacket;', 'import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;'),
    @('import net.minecraft.network.packet.c2s.play.UpdateSignC2SPacket;', 'import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;'),
    @('import net.minecraft.server.network.ServerPlayNetworkHandler;', 'import net.minecraft.server.network.ServerGamePacketListenerImpl;'),
    @('import static net.minecraft.server.command.CommandManager.argument;', 'import static net.minecraft.commands.Commands.argument;'),
    @('import static net.minecraft.server.command.CommandManager.literal;', 'import static net.minecraft.commands.Commands.literal;'),

    # --- registry placeholder dance ---
    @('RegistryKeys.', '@@REGKEYS@@.'),
    @('Registries.', '@@REGISTRIES@@.'),
    @('net.minecraft.core.registries.BuiltInRegistries;', '@@BIRIMPORT@@;'),
    @('@@REGKEYS@@.', 'Registries.'),
    @('@@REGISTRIES@@.', 'BuiltInRegistries.'),
    @('@@BIRIMPORT@@;', 'net.minecraft.core.registries.BuiltInRegistries;'),

    # --- type usages ---
    @('ServerCommandSource', 'CommandSourceStack'),
    @('ServerWorld', 'ServerLevel'),
    @('ServerPlayerEntity', 'ServerPlayer'),
    @('PlayerEntity', 'Player'),
    @('TntEntity', 'PrimedTnt'),
    @('PistonBlockEntity', 'PistonMovingBlockEntity'),
    @('Inventory', 'Container'),
    @('WorldAccess', 'LevelAccessor'),
    @('MutableText', 'MutableComponent'),
    @('Formatting.', 'ChatFormatting.'),
    @('ActionResult.', 'InteractionResult.'),
    @('Text.literal', 'Component.literal'),
    @('Text.empty', 'Component.empty'),
    @('Text.translatable', 'Component.translatable'),
    @('Hand.MAIN_HAND', 'InteractionHand.MAIN_HAND'),
    @('Hand.OFF_HAND', 'InteractionHand.OFF_HAND'),

    # --- method renames ---
    @('.getUuid()', '.getUUID()'),
    @('.getServerWorld()', '.level()'),
    @('.getWorld()', '.level()'),
    @('.getBlockPos()', '.blockPosition()'),
    @('.getRegistryKey().getValue()', '.dimension().identifier()'),
    @('.getStackInHand(', '.getItemInHand('),
    @('.formatted(', '.withStyle('),
    @('.up()', '.above()'),
    @('.up(', '.above('),
    @('.down()', '.below()'),
    @('.offset(', '.relative('),
    @('inv.size()', 'inv.getContainerSize()'),
    @('inv.getStack(', 'inv.getItem('),
    @('inv.setStack(', 'inv.setItem('),
    @('.getCommandSource()', '.createCommandSourceStack()'),
    @('server.getPlayerManager()', 'server.getPlayerList()'),
    @('.getPlayerList()', '.getPlayers()'),
    @('server.getCurrentPlayerCount()', 'server.getPlayerCount()'),
    @('source.sendFeedback(', 'source.sendSuccess('),
    @('source.sendError(', 'source.sendFailure('),
    @('.setBlockState(', '.setBlock('),
    @('.breakBlock(', '.destroyBlock('),
    @('.getRegistryManager()', '.registryAccess()'),
    @('getWrapperOrThrow(', 'lookupOrThrow('),
    @('SharedConstants.getGameVersion().getName()', 'SharedConstants.getCurrentVersion().name()'),
    @('.getClientOptions()', '.clientInformation()'),
    @('.getContent().getString()', '.signedContent()'),
    @('World.ExplosionSourceType', 'Level.ExplosionInteraction'),
    @('.getDefaultState()', '.defaultBlockState()'),
    @('.setStack(', '.setItem('),
    @('.getStack()', '.getItem()'),
    @('.styled(', '.withStyle('),
    @('Block.NOTIFY_ALL', 'Block.UPDATE_ALL'),
    @('.spawnEntity(', '.addFreshEntity('),
    @('.createExplosion(', '.explode('),
    @('.getInventoryAt(', '.getContainerAt('),
    @('server.getSpawnPoint().getPos()', 'server.getRespawnData().pos()'),
    @('world.getSpawnPos()', 'CoreProtectFabric.instance().server().getRespawnData().pos()'),
    @('onPlayerCollision', 'playerTouch'),
    @('onCommandExecution', 'handleChatCommand'),
    @('onSignUpdate', 'handleSignUpdate'),
    @('.getText()', '.getLines()'),

    # --- restore protected names ---
    @('@@WM@@', 'WorldMixin'),
    @('@@SPEM@@', 'ServerPlayerEntityMixin'),
    @('@@PEDM@@', 'PlayerEntityDropMixin'),
    @('@@IEPM@@', 'ItemEntityPickupMixin'),
    @('@@PBEM@@', 'PistonBlockEntityMixin'),
    @('@@HBEM@@', 'HopperBlockEntityMixin'),
    @('@@SIGNTEXT@@', 'SignText')
)

$files = Get-ChildItem -Path (Join-Path $dst 'java') -Recurse -File -Filter *.java
foreach ($f in $files) {
    $text = [System.IO.File]::ReadAllText($f.FullName, [System.Text.Encoding]::UTF8)
    $orig = $text
    foreach ($p in $pairs) { $text = $text.Replace($p[0], $p[1]) }
    if ($text -ne $orig) {
        [System.IO.File]::WriteAllText($f.FullName, $text, (New-Object System.Text.UTF8Encoding($false)))
    }
}
Write-Host ("Applied mojmap mapping to " + $files.Count + " files")
