# Makes the Windows icons from asset/master/icon-1024.png: a square master (icon-1024-square.png),
# one PNG per size into asset/windows and desktop-app's resources (the windows' own icons), and
# SoundMesh.ico holding all of them for the exe and the installer. Run from the repository root:
#   powershell -ExecutionPolicy Bypass -File tools/windows-icons.ps1
#
# Square because the master's rounded corners are not see-through but opaque white, with a faint
# white line round the rounded edge: on a dark taskbar that is four white specks. Everything
# outside the rounded square, and the band the line is drawn in, is painted over with the master's
# own background - the SVG's radial gradient, worked out per pixel - so the result is the icon as
# if it had never been clipped. Nothing else reaches that band: the beams end well inside it.
#
# Halved step by step before the last resize: one bicubic jump from 1024 to 16 leaves a smudge.

Add-Type -AssemblyName System.Drawing
Add-Type -ReferencedAssemblies System.Drawing -TypeDefinition @'
using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;

public static class SquareIcon {
    // icon-master.svg: rect rx 229.1 on 1024; radialGradient cx 0.5 cy 0.46 r 0.85, three stops.
    const double Side = 1024, Round = 229.1, Band = 5;
    static readonly double[] At = { 0, 0.6, 1 };
    static readonly int[,] Stop = { { 0x0C, 0x10, 0x17 }, { 0x07, 0x0A, 0x0F }, { 0x04, 0x06, 0x0A } };

    // How far a point is inside the rounded square; negative outside it.
    static double Inside(double x, double y) {
        double cx = Math.Min(Math.Max(x, Round), Side - Round);
        double cy = Math.Min(Math.Max(y, Round), Side - Round);
        bool corner = (x < Round || x > Side - Round) && (y < Round || y > Side - Round);
        if (corner) return Round - Math.Sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy));
        return Math.Min(Math.Min(x, Side - x), Math.Min(y, Side - y));
    }

    static int Ground(double x, double y, int channel) {
        double t = Math.Min(1, Math.Sqrt((x - 512) * (x - 512) + (y - 471.04) * (y - 471.04)) / 870.4);
        int i = t <= At[1] ? 0 : 1;
        double k = (t - At[i]) / (At[i + 1] - At[i]);
        return (int)Math.Round(Stop[i, channel] + (Stop[i + 1, channel] - Stop[i, channel]) * k);
    }

    public static Bitmap Make(string masterPath) {
        var master = new Bitmap(masterPath);
        var square = new Bitmap(1024, 1024, PixelFormat.Format32bppArgb);
        using (var g = Graphics.FromImage(square)) g.DrawImage(master, 0, 0, 1024, 1024);
        master.Dispose();
        var data = square.LockBits(new Rectangle(0, 0, 1024, 1024), ImageLockMode.ReadWrite, PixelFormat.Format32bppArgb);
        var px = new byte[data.Stride * 1024];
        Marshal.Copy(data.Scan0, px, 0, px.Length);
        for (int y = 0; y < 1024; y++) {
            for (int x = 0; x < 1024; x++) {
                double inside = Inside(x + 0.5, y + 0.5);
                if (inside >= Band + 1) continue;
                // A pixel of blend at the band's inner edge, so the seam cannot show.
                double keep = Math.Max(0, Math.Min(1, inside - Band));
                int o = y * data.Stride + x * 4;
                for (int c = 0; c < 3; c++) {
                    int ground = Ground(x + 0.5, y + 0.5, 2 - c); // BGRA in memory
                    px[o + c] = (byte)Math.Round(px[o + c] * keep + ground * (1 - keep));
                }
                px[o + 3] = 255;
            }
        }
        Marshal.Copy(px, 0, data.Scan0, px.Length);
        square.UnlockBits(data);
        return square;
    }
}
'@

$sizes = 16, 20, 24, 32, 40, 48, 64, 256
$root = (Get-Location).Path
$outAsset = Join-Path $root 'asset\windows'
$outRes = Join-Path $root 'desktop-app\src\main\resources\icon'
New-Item -ItemType Directory -Force $outAsset | Out-Null
New-Item -ItemType Directory -Force $outRes | Out-Null
$master = [SquareIcon]::Make((Join-Path $root 'asset\master\icon-1024.png'))
$master.Save((Join-Path $outAsset 'icon-1024-square.png'), [System.Drawing.Imaging.ImageFormat]::Png)

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
