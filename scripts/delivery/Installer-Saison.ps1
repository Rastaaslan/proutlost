[CmdletBinding()]
param(
 [Parameter(Mandatory=$true)][string]$MondeSource,
 [Parameter(Mandatory=$true)][string]$ProfilDirector,
 [string]$AdresseEcoute='127.0.0.1',
 [ValidateRange(1024,65535)][int]$Port=25579,
 [ValidateRange(1,100)][int]$MaxJoueurs=25
)
$ErrorActionPreference='Stop'
$pack=Split-Path -Parent $PSScriptRoot
$world=(Resolve-Path -LiteralPath $MondeSource).Path
$profile=(Resolve-Path -LiteralPath $ProfilDirector).Path
if (!(Test-Path -LiteralPath (Join-Path $world 'level.dat'))) { throw 'World source has no level.dat.' }
$manifestPath=Join-Path $profile 'manifest.json'
if (!(Test-Path -LiteralPath $manifestPath)) { throw 'Missing authored Director manifest.json.' }
$manifest=Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($manifest.status -ne 'APPROVED' -or $manifest.production_approved -ne $true) {
 throw 'The season profile needs an explicit author approval. No test profile is promoted automatically.'
}
foreach ($name in @('approval_id','approved_by','approved_at')) {
 if ([string]::IsNullOrWhiteSpace([string]$manifest.$name)) { throw "Missing author field: $name" }
}
$descriptors=@($manifest.profiles)+@($manifest.primitives,$manifest.references)+@($manifest.documents)
foreach ($entry in $descriptors) {
 $rel=[string]$entry.path
 if ($rel -notmatch '^[a-z0-9_./-]+\.json$' -or $rel.StartsWith('/') -or $rel.Contains('..')) { throw 'Invalid profile file path.' }
 $file=Join-Path $profile $rel
 if (!(Test-Path -LiteralPath $file -PathType Leaf)) { throw "Missing profile input: $rel" }
 if ((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -ne $entry.sha256) { throw "Profile hash differs: $rel" }
}
$physical=Join-Path $world 'director-physical-catalogs'
$pairs=@(Get-ChildItem -LiteralPath $physical -Directory -ErrorAction SilentlyContinue | Where-Object {
 (Test-Path -LiteralPath (Join-Path $_.FullName 'catalog.json')) -and
 (Test-Path -LiteralPath (Join-Path $_.FullName 'adapters.json'))
})
if ($pairs.Count -eq 0) { throw 'Missing authentic Contra physical export: director-physical-catalogs/<name>/catalog.json and adapters.json. G0 geometry alone is insufficient.' }
$target=Join-Path $pack 'world'
$existingProfile=Join-Path $world 'proutlost/director-profile/manifest.json'
if ((Test-Path -LiteralPath $existingProfile) -and ((Get-FileHash -LiteralPath $existingProfile -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash)) { throw 'Source world has a different Director profile. Preserve it and resolve the profile choice first.' }
if (Test-Path -LiteralPath $target) { throw 'Destination world already exists; keep it and install into a fresh extracted server directory.' }
$lock=Join-Path $world 'session.lock'
$handle=$null
try {
 if (Test-Path -LiteralPath $lock) { $handle=[IO.File]::Open($lock,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::None) }
 New-Item -ItemType Directory -Path $target | Out-Null
 Get-ChildItem -LiteralPath $world -Force | Where-Object { $_.Name -ne 'session.lock' } | Copy-Item -Destination $target -Recurse
} finally { if ($null -ne $handle) { $handle.Dispose() } }
$profileTarget=Join-Path $target 'proutlost/director-profile'
if (!(Test-Path -LiteralPath $profileTarget)) {
 New-Item -ItemType Directory -Path $profileTarget -Force | Out-Null
 Get-ChildItem -LiteralPath $profile -Force | Copy-Item -Destination $profileTarget -Recurse
}
New-Item -ItemType Directory -Path (Join-Path $target 'serverconfig') -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $pack 'season-templates/proutlost-quest-server.toml') -Destination (Join-Path $target 'serverconfig/proutlost-quest-server.toml')
# WorldPrep preparation remains a separate explicit operator operation.
Copy-Item -LiteralPath (Join-Path $pack 'season-templates/proutlost-server.toml') -Destination (Join-Path $target 'serverconfig/proutlost-server.toml')
$properties=Get-Content -LiteralPath (Join-Path $pack 'server.properties') -Encoding UTF8
$settings=@{'level-name'='world';'server-ip'=$AdresseEcoute;'server-port'=[string]$Port;'max-players'=[string]$MaxJoueurs;'online-mode'='true';'enable-rcon'='false'}
foreach ($key in $settings.Keys) {
 $properties=@($properties | Where-Object { $_ -notmatch ('^'+[regex]::Escape($key)+'=') })
 $properties+=($key+'='+$settings[$key])
}
[IO.File]::WriteAllLines((Join-Path $pack 'server.properties'),$properties,(New-Object Text.UTF8Encoding($false)))
$receipt=@{schema_version=1;source_world=$world;approval_id=$manifest.approval_id;director_manifest_sha256=(Get-FileHash -LiteralPath (Join-Path $profileTarget 'manifest.json') -Algorithm SHA256).Hash.ToLowerInvariant();installed_utc=[DateTime]::UtcNow.ToString('o');physical_catalogue_directories=@($pairs.Name);fullstack_accepted=$false}
[IO.File]::WriteAllText((Join-Path $pack 'SEASON_INSTALLATION.json'),($receipt | ConvertTo-Json -Depth 5),(New-Object Text.UTF8Encoding($false)))
Write-Output "Season installed in $target. Start Lancer-Saison.cmd. The source world was preserved."
