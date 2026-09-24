# Makes the Windows icons from asset/master/icon-1024.png: one PNG per size into asset/windows
# and desktop-app's resources (the windows' own icons), and SoundMesh.ico holding all of them
# for the exe and the installer. Run from the repository root:
#   powershell -ExecutionPolicy Bypass -File tools/windows-icons.ps1
# Halved step by step before the last resize: one bicubic jump from 1024 to 16 leaves a smudge.

Add-Type -AssemblyName System.Drawing

$sizes = 16, 20, 24, 32, 40, 48, 64, 256
$root = (Get-Location).Path
$master = [System.Drawing.Image]::FromFile((Join-Path $root 'asset\master\icon-1024.png'))
$outAsset = Join-Path $root 'asset\windows'
$outRes = Join-Path $root 'desktop-app\src\main\resources\icon'
New-Item -ItemType Directory -Force $outAsset | Out-Null
New-Item -ItemType Directory -Force $outRes | Out-Null

function Resize([System.Drawing.Image]$from, [int]$side) {
    $to = New-Object System.Drawing.Bitmap $side, $side, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($to)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $wrap = New-Object System.Drawing.Imaging.ImageAttributes
    $wrap.SetWrapMode([System.Drawing.Drawing2D.WrapMode]::TileFlipXY)
    $g.DrawImage($from, (New-Object System.Drawing.Rectangle 0, 0, $side, $side), 0, 0, $from.Width, $from.Height, [System.Drawing.GraphicsUnit]::Pixel, $wrap)
    $g.Dispose()
    return $to
}

$pngs = @()
foreach ($size in $sizes) {
    $step = $master
    while ($step.Width -ge 2 * $size * 2) { $step = Resize $step ($step.Width / 2) }
    $icon = Resize $step $size
    $name = "soundmesh-$size.png"
    $icon.Save((Join-Path $outAsset $name), [System.Drawing.Imaging.ImageFormat]::Png)
    $icon.Save((Join-Path $outRes $name), [System.Drawing.Imaging.ImageFormat]::Png)
    $pngs += , [System.IO.File]::ReadAllBytes((Join-Path $outAsset $name))
}

# ICONDIR, then one ICONDIRENTRY per image, then the images themselves as PNG.
$ico = New-Object System.IO.MemoryStream
$w = New-Object System.IO.BinaryWriter $ico
$w.Write([UInt16]0); $w.Write([UInt16]1); $w.Write([UInt16]$sizes.Count)
$offset = 6 + 16 * $sizes.Count
for ($i = 0; $i -lt $sizes.Count; $i++) {
    $side = if ($sizes[$i] -ge 256) { 0 } else { $sizes[$i] }
    $w.Write([Byte]$side); $w.Write([Byte]$side); $w.Write([Byte]0); $w.Write([Byte]0)
    $w.Write([UInt16]1); $w.Write([UInt16]32)
    $w.Write([UInt32]$pngs[$i].Length); $w.Write([UInt32]$offset)
    $offset += $pngs[$i].Length
}
foreach ($png in $pngs) { $w.Write($png) }
$w.Flush()
[System.IO.File]::WriteAllBytes((Join-Path $outAsset 'SoundMesh.ico'), $ico.ToArray())
$master.Dispose()
