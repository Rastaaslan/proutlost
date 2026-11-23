[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$pack=Split-Path -Parent $PSScriptRoot
if (Test-Path (Join-Path $pack 'world/level.dat')) { return }
$name='PROUTLOST_S2_R56_MONDE_CONTRA.zip'
$archive=@((Join-Path $pack $name),(Join-Path (Split-Path -Parent $pack) $name)) | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (!$archive) { throw "Placer $name dans le dossier serveur ou juste a cote, puis relancer run.bat." }
if (Test-Path (Join-Path $pack 'world')) { throw 'Un dossier world incomplet existe : le conserver et terminer son installation avant lancement.' }
$expected=(Get-Content -LiteralPath (Join-Path $pack 'MONDE_CONTRA_SHA256.txt') -Raw).Trim()
$actual=(Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actual -ne $expected) { throw 'Archive Contra incomplete ou differente du serveur R56.' }
Write-Host 'Installation du monde Contra conserve et du profil approuve...'
Expand-Archive -LiteralPath $archive -DestinationPath $pack -Force
if (!(Test-Path (Join-Path $pack 'world/level.dat'))) { throw 'Installation Contra incomplete.' }
