param(
    [Parameter(Mandatory = $true)][string]$Src
)
$n = Join-Path $Src 'net\minecraft'
$ok = 0
$miss = 0

function Check([string]$Name, [string]$File, [string]$Pattern) {
    $p = Join-Path $n $File
    if (Test-Path $p) {
        $m = Select-String -Path $p -Pattern $Pattern -AllMatches | Select-Object -First 1
        if ($m) {
            Write-Host "OK    $Name"
            $script:ok++
        } else {
            Write-Host "MISS  $Name  ($File)"
            $script:miss++
        }
    } else {
        Write-Host "MISS  $Name  (file not found: $File)"
        $script:miss++
    }
}

Check 'World.setBlockState4' 'world\World.java' 'setBlockState\(BlockPos pos, BlockState state, int flags, int maxUpdateDepth\)'
Check 'World.setBlockState3' 'world\World.java' 'setBlockState\(BlockPos pos, BlockState state, int flags\)'
Check 'World.breakBlock4' 'world\World.java' 'breakBlock\(BlockPos pos, boolean drop'
Check 'World.removeBlock' 'world\World.java' 'removeBlock\(BlockPos pos, boolean move\)'
Check 'Explosion.affectWorld' 'world\explosion\Explosion.java' 'void affectWorld\(boolean particles\)'
Check 'Explosion.getEntity' 'world\explosion\Explosion.java' 'getEntity\(\)'
Check 'FireBlock.trySpreadingFire' 'block\FireBlock.java' 'trySpreadingFire\(World world, BlockPos pos, int spreadFactor, Random random, int currentAge\)'
Check 'LeavesBlock.randomTick' 'block\LeavesBlock.java' 'randomTick\(BlockState state, ServerWorld world, BlockPos pos, Random random\)'
Check 'FlowableFluid.flow' 'fluid\FlowableFluid.java' 'void flow\(WorldAccess world, BlockPos pos, BlockState state, Direction direction, FluidState fluidState\)'
Check 'PistonBlockEntity.tick' 'block\entity\PistonBlockEntity.java' 'static void tick\(World world, BlockPos pos, BlockState state, PistonBlockEntity blockEntity\)'
Check 'EndermanPickupGoal' 'entity\mob\EndermanEntity.java' 'class PickUpBlockGoal'
Check 'EndermanPlaceGoal' 'entity\mob\EndermanEntity.java' 'class PlaceBlockGoal'
Check 'SPE.onHandledScreenClosed' 'server\network\ServerPlayerEntity.java' 'void onHandledScreenClosed\(\)'
Check 'SPE.getClientOptions' 'server\network\ServerPlayerEntity.java' 'getClientOptions\(\)'
Check 'SPNH.onCommandExecution' 'server\network\ServerPlayNetworkHandler.java' 'void onCommandExecution\(CommandExecutionC2SPacket packet\)'
Check 'SPNH.getPlayer' 'server\network\ServerPlayNetworkHandler.java' 'getPlayer\(\)'
Check 'BlockArgumentParser.block' 'command\argument\BlockArgumentParser.java' 'BlockResult block\(RegistryWrapper<Block> registryWrapper, String string, boolean allowSnbt\)'
Check 'BlockArgumentParser.stringify' 'command\argument\BlockArgumentParser.java' 'String stringifyBlockState\(BlockState state\)'
Check 'SignBE.getFrontText' 'block\entity\SignBlockEntity.java' 'SignText getFrontText\(\)'
Check 'SignBE.setText' 'block\entity\SignBlockEntity.java' 'boolean setText\(SignText text, boolean front\)'
Check 'SignText.getMessage' 'block\entity\SignText.java' 'Text getMessage\(int line, boolean filtered\)'
Check 'SignText.withMessage' 'block\entity\SignText.java' 'SignText withMessage\(int line, Text message\)'
Check 'RegistryWrapper.getOrThrow' 'registry\RegistryWrapper.java' 'getOrThrow\(|getWrapperOrThrow\('
Write-Host "=== $ok OK / $miss MISS ==="
