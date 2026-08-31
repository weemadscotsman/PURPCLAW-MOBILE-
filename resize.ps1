Add-Type -AssemblyName System.Drawing
param([string]$Source = 'screen0.png')
$src = (Resolve-Path $Source).Path
$img = [System.Drawing.Image]::FromFile($src)
$w = $img.Width
$h = $img.Height
Write-Host "Original: ${w}x${h}"
$targetW = 600
$targetH = [int]($h * ($targetW / $w))
if ($targetH -gt 1500) { $targetH = 1500 }
$bmp = New-Object System.Drawing.Bitmap $img, $targetW, $targetH
$outName = [System.IO.Path]::GetFileNameWithoutExtension($src) + '_tiny.png'
$bmp.Save($outName, [System.Drawing.Imaging.ImageFormat]::Png)
$img.Dispose()
$bmp.Dispose()
Write-Host "Saved: ${targetW}x${targetH}"