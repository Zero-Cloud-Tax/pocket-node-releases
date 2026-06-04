param(
    [Parameter(Mandatory = $true)]
    [string]$TextPath,
    [Parameter(Mandatory = $true)]
    [string]$OutputPath,
    [Parameter(Mandatory = $true)]
    [string]$Title,
    [int]$FontSize = 18,
    [int]$Padding = 24,
    [int]$MaxWidth = 1800
)

$ErrorActionPreference = "Stop"

Add-Type -AssemblyName System.Drawing

$textPathResolved = (Resolve-Path -LiteralPath $TextPath).Path
$lines = Get-Content -LiteralPath $textPathResolved
if ($lines.Count -eq 0) {
    $lines = @("")
}

$outputDir = Split-Path -Parent $OutputPath
if (-not (Test-Path -LiteralPath $outputDir)) {
    New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
}
$outputPathResolved = [System.IO.Path]::GetFullPath($OutputPath)

$font = New-Object System.Drawing.Font("Consolas", $FontSize, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
$measureBitmap = New-Object System.Drawing.Bitmap 1, 1
$measureGraphics = [System.Drawing.Graphics]::FromImage($measureBitmap)
$measureGraphics.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::ClearTypeGridFit

$lineHeight = [int][Math]::Ceiling($font.GetHeight($measureGraphics) + 6)
$maxLineWidth = 0
foreach ($line in $lines) {
    $size = $measureGraphics.MeasureString($line, $font, $MaxWidth)
    $lineWidth = [int][Math]::Ceiling($size.Width)
    if ($lineWidth -gt $maxLineWidth) {
        $maxLineWidth = $lineWidth
    }
}
$measureGraphics.Dispose()
$measureBitmap.Dispose()

$bitmapWidth = [Math]::Min($MaxWidth, [Math]::Max(900, $maxLineWidth + ($Padding * 2)))
$bitmapHeight = ($lines.Count * $lineHeight) + ($Padding * 2) + 10

$bitmap = New-Object System.Drawing.Bitmap $bitmapWidth, $bitmapHeight
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
$graphics.Clear([System.Drawing.Color]::FromArgb(12, 12, 12))
$graphics.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::ClearTypeGridFit

$titleBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(155, 213, 255))
$textBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(214, 214, 214))
$subtleBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(68, 68, 68))

$graphics.FillRectangle($subtleBrush, 0, 0, $bitmapWidth, 6)
$graphics.DrawString($Title, $font, $titleBrush, $Padding, 10)

$y = $Padding + 32
foreach ($line in $lines) {
    $graphics.DrawString($line, $font, $textBrush, $Padding, $y)
    $y += $lineHeight
}

$bitmap.Save($outputPathResolved, [System.Drawing.Imaging.ImageFormat]::Png)

$titleBrush.Dispose()
$textBrush.Dispose()
$subtleBrush.Dispose()
$graphics.Dispose()
$bitmap.Dispose()
$font.Dispose()
