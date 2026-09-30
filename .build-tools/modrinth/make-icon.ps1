# Generates the Modrinth project icon (512x512 PNG).
# Pure ASCII script (see check-ascii.ps1). Draws with System.Drawing.
param(
    [string]$Out = (Join-Path $PSScriptRoot 'icon.png')
)

Add-Type -AssemblyName System.Drawing

$size = 512
$bmp = New-Object System.Drawing.Bitmap($size, $size)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic

# --- background: rounded square with a vertical gradient -------------------
$rect = New-Object System.Drawing.Rectangle(0, 0, $size, $size)
$bg = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
    $rect,
    [System.Drawing.Color]::FromArgb(255, 38, 46, 58),
    [System.Drawing.Color]::FromArgb(255, 18, 22, 28),
    [System.Drawing.Drawing2D.LinearGradientMode]::Vertical)
$g.FillRectangle($bg, $rect)

# --- faint block grid (the "logged blocks" motif) -------------------------
$gridPen = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(38, 120, 138, 160), 2)
$cell = 64
for ($i = 1; $i -lt ($size / $cell); $i++) {
    $p = $i * $cell
    $g.DrawLine($gridPen, $p, 0, $p, $size)
    $g.DrawLine($gridPen, 0, $p, $size, $p)
}

# --- accent bar -----------------------------------------------------------
$accent = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 63, 185, 80))
$g.FillRectangle($accent, 96, 118, 320, 10)

# --- monogram "CP" --------------------------------------------------------
$fontFamily = $null
foreach ($name in @('Bahnschrift SemiBold', 'Segoe UI Bold', 'Arial Bold', 'Arial')) {
    try {
        $candidate = New-Object System.Drawing.FontFamily($name)
        $fontFamily = $candidate
        break
    } catch { }
}
if ($null -eq $fontFamily) { $fontFamily = [System.Drawing.FontFamily]::GenericSansSerif }

$monoFont = New-Object System.Drawing.Font($fontFamily, 190, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
$white = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 240, 244, 248))
$fmt = New-Object System.Drawing.StringFormat
$fmt.Alignment = [System.Drawing.StringAlignment]::Center
$fmt.LineAlignment = [System.Drawing.StringAlignment]::Center
$monoBox = New-Object System.Drawing.RectangleF(0, 120, $size, 240)
$g.DrawString('CP', $monoFont, $white, $monoBox, $fmt)

# --- caption --------------------------------------------------------------
$capFamily = $null
foreach ($name in @('Bahnschrift', 'Segoe UI', 'Arial')) {
    try {
        $capFamily = New-Object System.Drawing.FontFamily($name)
        break
    } catch { }
}
if ($null -eq $capFamily) { $capFamily = [System.Drawing.FontFamily]::GenericSansSerif }

$capFont = New-Object System.Drawing.Font($capFamily, 42, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
$grey = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 150, 163, 178))
$capBox = New-Object System.Drawing.RectangleF(0, 356, $size, 60)
$g.DrawString('FABRIC', $capFont, $grey, $capBox, $fmt)

# --- rounded corners ------------------------------------------------------
$path = New-Object System.Drawing.Drawing2D.GraphicsPath
$r = 72
$d = $r * 2
$path.AddArc(0, 0, $d, $d, 180, 90)
$path.AddArc($size - $d, 0, $d, $d, 270, 90)
$path.AddArc($size - $d, $size - $d, $d, $d, 0, 90)
$path.AddArc(0, $size - $d, $d, $d, 90, 90)
$path.CloseFigure()

$rounded = New-Object System.Drawing.Bitmap($size, $size)
$rg = [System.Drawing.Graphics]::FromImage($rounded)
$rg.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$rg.Clear([System.Drawing.Color]::Transparent)
$brush = New-Object System.Drawing.TextureBrush($bmp)
$rg.FillPath($brush, $path)
$rg.Dispose()
$g.Dispose()

if (Test-Path $Out) { Remove-Item $Out -Force }
$rounded.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$rounded.Dispose()
$bmp.Dispose()

$info = Get-Item $Out
Write-Output ("icon written: {0} ({1} bytes, {2})" -f $info.FullName, $info.Length, $fontFamily.Name)
