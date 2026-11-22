[CmdletBinding()]
param([string]$Java='')
$ErrorActionPreference='Stop'
$pack=Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $pack
if ($Java -eq '') {
 if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) { $Java=Join-Path $env:JAVA_HOME 'bin/java.exe' }
 else { $Java=(Get-Command java.exe -ErrorAction Stop).Source }
}
$info=New-Object Diagnostics.ProcessStartInfo
$info.FileName=$Java
$info.Arguments='-version'
$info.UseShellExecute=$false
$info.RedirectStandardError=$true
$p=[Diagnostics.Process]::Start($info)
$version=$p.StandardError.ReadToEnd()
if (!$p.WaitForExit(15000)) { $p.Kill(); throw 'Java version check timed out.' }
if ($p.ExitCode -ne 0 -or $version -notmatch 'version "21[.+"]') { throw "Java 21 x64 required. Observed: $version" }
if (!(Test-Path -LiteralPath 'SEASON_INSTALLATION.json')) { throw 'Season is not installed. See INSTALLATION.md; Contra and the approved Director profile are required.' }
$installed=Get-Content -LiteralPath 'SEASON_INSTALLATION.json' -Raw -Encoding UTF8 | ConvertFrom-Json
$manifest='world/proutlost/director-profile/manifest.json'
if (!(Test-Path -LiteralPath $manifest)) { throw 'Missing installed Director manifest.' }
$profile=Get-Content -LiteralPath $manifest -Raw -Encoding UTF8 | ConvertFrom-Json
if ($profile.status -ne 'APPROVED' -or $profile.production_approved -ne $true) { throw 'Author-approved season profile required.' }
$pin=(Get-FileHash -LiteralPath $manifest -Algorithm SHA256).Hash.ToLowerInvariant()
if ($pin -ne $installed.director_manifest_sha256) { throw 'Director profile changed since installation; review and install its matching version.' }
$argsFile=Get-Content -LiteralPath 'user_jvm_args.txt' -Raw
if ($argsFile -match 'proutlost\.(director\.(nativeLab|productionRuntimeQa|cohortQa)|qa\.)') { throw 'QA arguments are incompatible with the season launch.' }
if (!(Test-Path -LiteralPath 'world/level.dat')) { throw 'Contra world is missing; refusing to create an unrelated world.' }
& $Java "-Dproutlost.director.productionManifestSha256=$pin" '@user_jvm_args.txt' '@libraries/net/neoforged/neoforge/21.1.248/win_args.txt' 'nogui'
exit $LASTEXITCODE
