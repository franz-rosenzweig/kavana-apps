# Chataltul.ico, from the Mac's own icon (mac/Chataltul/Resources/AppIcon.icns, whose biggest picture is a
# 1024-pixel PNG): every size Windows asks for, each a PNG inside the .ico. Run by windows\build.ps1 and the
# Windows workflow; git leaves the result out.
#
#   powershell -File windows\tools\make-icon.ps1 [-Source mac\Chataltul\Resources\AppIcon.icns] [-Out windows\Chataltul\Assets\Chataltul.ico]
param(
    [string]$Source = "mac\Chataltul\Resources\AppIcon.icns",
    [string]$Out = "windows\Chataltul\Assets\Chataltul.ico"
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

# the .icns: "icns", its length, then pieces of a four-letter type and a length each (big-endian)
$icns = [System.IO.File]::ReadAllBytes((Resolve-Path $Source))
function BigEndian([byte[]]$b, [int]$at) { ([uint32]$b[$at] -shl 24) -bor ([uint32]$b[$at + 1] -shl 16) -bor ([uint32]$b[$at + 2] -shl 8) -bor [uint32]$b[$at + 3] }
$best = $null
$i = 8
while ($i + 8 -le $icns.Length) {
    $length = [int](BigEndian $icns ($i + 4))
    if ($length -lt 8) { break }
    $isPng = $icns[$i + 8] -eq 0x89 -and $icns[$i + 9] -eq 0x50 -and $icns[$i + 10] -eq 0x4E -and $icns[$i + 11] -eq 0x47
    if ($isPng -and ($null -eq $best -or $length -gt $best.Length)) { $best = @{ At = $i + 8; Length = $length - 8 } }
    $i += $length
}
if ($null -eq $best) { throw "no PNG picture in $Source" }
$picture = New-Object byte[] $best.Length
[System.Array]::Copy($icns, $best.At, $picture, 0, $best.Length)
$stream = New-Object System.IO.MemoryStream (, $picture)
$image = [System.Drawing.Image]::FromStream($stream)

$sizes = 16, 20, 24, 32, 40, 48, 64, 128, 256
$pngs = foreach ($size in $sizes) {
    $bitmap = New-Object System.Drawing.Bitmap $size, $size
    $g = [System.Drawing.Graphics]::FromImage($bitmap)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $g.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
    $g.DrawImage($image, 0, 0, $size, $size)
    $g.Dispose()
    $s = New-Object System.IO.MemoryStream
    $bitmap.Save($s, [System.Drawing.Imaging.ImageFormat]::Png)
    $bitmap.Dispose()
    , $s.ToArray()
}
$image.Dispose()

New-Item -ItemType Directory -Force (Split-Path $Out) | Out-Null
$file = [System.IO.File]::Create((Join-Path (Resolve-Path (Split-Path $Out)) (Split-Path $Out -Leaf)))
$w = New-Object System.IO.BinaryWriter $file
# the header, one entry per size, then the pictures
$w.Write([uint16]0); $w.Write([uint16]1); $w.Write([uint16]$sizes.Count)
$offset = 6 + 16 * $sizes.Count
for ($k = 0; $k -lt $sizes.Count; $k++) {
    $side = if ($sizes[$k] -ge 256) { 0 } else { $sizes[$k] }      # 0 means 256
    $w.Write([byte]$side); $w.Write([byte]$side); $w.Write([byte]0); $w.Write([byte]0)
    $w.Write([uint16]1); $w.Write([uint16]32)
    $w.Write([uint32]$pngs[$k].Length); $w.Write([uint32]$offset)
    $offset += $pngs[$k].Length
}
foreach ($png in $pngs) { $w.Write([byte[]]$png) }
$w.Close()
"made $Out from the $($best.Length)-byte picture in $Source"
