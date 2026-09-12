$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$dir = Join-Path $root 'coreprotect-fabric-26.1.2\src\main\java'

$pairs = @(
    @('PistonBlockEntityMixin', '@@PBEM@@'),
    @('.getPlayerManager()', '.getPlayerList()'),
    @('.getGameProfile().getName()', '.getGameProfile().name()'),
    @('.getCurrentPlayerCount()', '.getPlayerCount()'),
    @('.isOnlineMode()', '.usesAuthentication()'),
    @('Registries.WORLD', 'Registries.DIMENSION'),
    @('RegistryKey', 'ResourceKey'),
    @('server.getWorld(', 'server.getLevel('),
    @('BuiltInRegistries.ITEM.get(id)', 'BuiltInRegistries.ITEM.getOptional(id).orElse(null)'),
    @('BlockPos.ORIGIN', 'BlockPos.ZERO'),
    @('hit.blockPosition()', 'hit.getBlockPos()'),
    @('hit.getSide()', 'hit.getDirection()'),
    @('.isSneaking()', '.isShiftKeyDown()'),
    @('.isReplaceable()', '.canBeReplaced()'),
    @('ground.add(', 'ground.offset('),
    @('pos().add(0, 1, 20)', 'pos().offset(0, 1, 20)'),
    @('state.get(HopperBlock.FACING)', 'state.getValue(HopperBlock.FACING)'),
    @('state.get(PistonBlock.FACING)', 'state.getValue(PistonBaseBlock.FACING)'),
    @('AbstractFireBlock', 'BaseFireBlock'),
    @('FluidBlock', 'LiquidBlock'),
    @('ActionResult', 'InteractionResult'),
    @('Hand hand', 'InteractionHand hand'),
    @('.isSource()', '.isSourcePiston()'),
    @('.getPushedBlock()', '.getMovedState()'),
    @('dispenser.size()', 'dispenser.getContainerSize()'),
    @('dispenser.getStack(', 'dispenser.getItem('),
    @('ItemStack.areItemsEqual(', 'ItemStack.isSameItem('),
    @('.getMaxCount()', '.getMaxStackSize()'),
    @('slot.increment(', 'slot.grow('),
    @('remaining.decrement(', 'remaining.shrink('),
    @('operator.getContainer().insertStack(stack)', 'operator.getInventory().add(stack)'),
    @('slot.isOf(item)', 'slot.getItem() == item'),
    @('PistonBlock', 'PistonBaseBlock'),
    @('@@PBEM@@', 'PistonBlockEntityMixin')
)

$files = Get-ChildItem -Path $dir -Recurse -File -Filter *.java
foreach ($f in $files) {
    $text = [System.IO.File]::ReadAllText($f.FullName, [System.Text.Encoding]::UTF8)
    $orig = $text
    foreach ($p in $pairs) { $text = $text.Replace($p[0], $p[1]) }
    if ($text -ne $orig) {
        [System.IO.File]::WriteAllText($f.FullName, $text, (New-Object System.Text.UTF8Encoding($false)))
        Write-Host ("  patched " + $f.Name)
    }
}
Write-Host "mojmap fix pass 2 done"
