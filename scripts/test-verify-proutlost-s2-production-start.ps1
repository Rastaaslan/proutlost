# Disposable, local fixtures only. Never contacts/changes any real Minecraft instance.
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$base=Join-Path $PSScriptRoot '..\build\s2-preflight-fixtures'
$instance=Join-Path $base 'fake-instance'
$clone=Join-Path $base 'fake-clone'
New-Item -ItemType Directory -Path $instance,$clone -Force | Out-Null
$guard=Join-Path $PSScriptRoot 'verify-proutlost-s2-production-start.ps1'
$identityPath=Join-Path $instance 'proutlost-s2-instance.json'
$argsPath=Join-Path $instance 'user_jvm_args.txt'
$levelDat=Join-Path $clone 'level.dat'
$contraPath=Join-Path $base 'fake-contra.json'
$obsPath=Join-Path $base 'fake-obs.json'
# Tiny valid gzip/NBT fixture. It is deliberately NOT a qualified Minecraft
# world; tests only prove the format gate refuses obviously corrupt level.dat.
[byte[]]$nbt=@(10,0,0,10,0,4,68,97,116,97,0,0)
$raw=[System.IO.File]::Create($levelDat)
try {
  $zip=[System.IO.Compression.GZipStream]::new(
    $raw,[System.IO.Compression.CompressionMode]::Compress,$true)
  try { $zip.Write($nbt,0,$nbt.Length) } finally { $zip.Dispose() }
} finally { $raw.Dispose() }
$hash=(Get-FileHash -LiteralPath $levelDat -Algorithm SHA256).Hash
$bArchive=Join-Path $base 'fake-b-archive.bin'
'DISPOSABLE_B_FIXTURE_ONLY' | Set-Content -LiteralPath $bArchive -Encoding ASCII
$bHash=(Get-FileHash -LiteralPath $bArchive -Algorithm SHA256).Hash
$now=[datetimeoffset]::UtcNow.ToString('o')
@{deploymentRole='real-proutlost-s2';minecraft='1.21.1';neoForge='21.1.248'} |
  ConvertTo-Json | Set-Content -LiteralPath $identityPath -Encoding UTF8
@{status='QUALIFIED';shaVerified=$true;worldSha256=$hash;bArchiveSha256=$bHash;cloneHealthy=$true;cloneLevelDatSha256=$hash;observedUtc=$now} |
  ConvertTo-Json | Set-Content -LiteralPath $contraPath -Encoding UTF8
@{status='QUALIFIED';profile='1080p60-NVENC';framesTotal=1000;framesDropped=0;observedUtc=$now} |
  ConvertTo-Json | Set-Content -LiteralPath $obsPath -Encoding UTF8
$checks=0
function Check([bool]$condition,[string]$message) {
  if (-not $condition) { throw ('FAILED: '+$message) }
  $script:checks++
}
function RunGuard([string]$runClone=$clone,[string]$runInstance=$instance) {
  $raw= & powershell -NoProfile -ExecutionPolicy Bypass -File $guard -InstancePath $runInstance -WorldClonePath $runClone -BArchivePath $bArchive -BExpectedSha256 $bHash -ContraQualificationPath $contraPath -ObsQualificationPath $obsPath
  Check ($LASTEXITCODE -eq 2) 'BLOCKED must exit 2 without B evidence'
  return ($raw | Out-String | ConvertFrom-Json)
}
'# pretend config' + [Environment]::NewLine + '-Xms8G -Xmx12G' |
  Set-Content -LiteralPath $argsPath -Encoding ASCII
$bad=RunGuard
Check ($bad.status -eq 'BLOCKED') 'bad heap denied'
Check ($bad.blockers -contains 'HEAP_MUST_BE_EXACTLY_XMS20G_XMX20G') 'bad heap explicit'
$noRegions=Join-Path $base 'fake-clone-no-regions'
New-Item -ItemType Directory -Path $noRegions -Force | Out-Null
Copy-Item -LiteralPath $levelDat -Destination (Join-Path $noRegions 'level.dat') -Force
$missingRegion=RunGuard $noRegions
Check ($missingRegion.blockers -contains 'CLONE_REGION_MISSING_OR_EMPTY') 'level.dat alone is not a healthy playable clone'
$region=Join-Path $clone 'region'
New-Item -ItemType Directory -Path $region -Force | Out-Null
$mcaPath=Join-Path $region 'r.0.0.mca'
[byte[]]$regionBytes=New-Object byte[] 16384
$regionBytes[2]=2
$regionBytes[3]=1
[System.IO.File]::WriteAllBytes($mcaPath,$regionBytes)
$validRegion=RunGuard
Check ($validRegion.blockers -notcontains 'CLONE_REGION_MISSING_OR_EMPTY') 'header with in-range chunk not missing'
Check ($validRegion.blockers -notcontains 'CLONE_REGION_HEADER_CORRUPT') 'valid Anvil header fixture accepted'
# Two distinct chunk indexes must never share a physical 4 KiB sector.
$regionBytes[6]=2
$regionBytes[7]=1
[System.IO.File]::WriteAllBytes($mcaPath,$regionBytes)
$overlap=RunGuard
Check ($overlap.blockers -contains 'CLONE_REGION_OVERLAPPING_CHUNK_SECTORS') 'two chunks sharing one sector rejected'
$regionBytes[6]=3
[System.IO.File]::WriteAllBytes($mcaPath,$regionBytes)
$adjacent=RunGuard
Check ($adjacent.blockers -notcontains 'CLONE_REGION_OVERLAPPING_CHUNK_SECTORS') 'two disjoint adjacent sectors accepted'
$regionBytes[3]=2
[System.IO.File]::WriteAllBytes($mcaPath,$regionBytes)
$partial=RunGuard
Check ($partial.blockers -contains 'CLONE_REGION_OVERLAPPING_CHUNK_SECTORS') 'partial overlap across a two-sector chunk rejected'
$regionBytes[6]=0
$regionBytes[7]=0
[System.IO.File]::WriteAllBytes($mcaPath,$regionBytes)
$twoSectors=RunGuard
Check ($twoSectors.blockers -notcontains 'CLONE_REGION_OVERLAPPING_CHUNK_SECTORS') 'valid multi-sector chunk alone accepted'
$regionBytes[3]=1
$regionBytes[2]=255
[System.IO.File]::WriteAllBytes($mcaPath,$regionBytes)
$invalidRegion=RunGuard
Check ($invalidRegion.blockers -contains 'CLONE_REGION_HEADER_CORRUPT') 'out-of-file Anvil chunk reference rejected'
$regionBytes[2]=2
[System.IO.File]::WriteAllBytes($mcaPath,$regionBytes)
'-Xms20G -Xmx20G' | Set-Content -LiteralPath $argsPath -Encoding ASCII
$goodHeap=RunGuard
Check ($goodHeap.blockers -notcontains 'HEAP_MUST_BE_EXACTLY_XMS20G_XMX20G') 'exact 20GiB accepted for config only'
Check ($goodHeap.startedServer -eq $false -and $goodHeap.heap -eq '-Xms20G -Xmx20G') 'read-only gate retains nonnegotiable heap'
# Deferring expensive B hashing must not depend on a Docker process being present:
# even an otherwise idle computer without 28 GiB headroom is unfit for it.
$fixtureFreeGiB=(Get-CimInstance -ClassName Win32_OperatingSystem).FreePhysicalMemory / 1048576.0
if ($fixtureFreeGiB -lt 28.0) {
  Check ($goodHeap.blockers -contains 'B_HASH_DEFERRED_RESOURCE_GUARD') 'low RAM forbids hashing B even if Docker is otherwise idle'
}

'-Xms20G -Xmx20G -Xmx12G' | Set-Content -LiteralPath $argsPath -Encoding ASCII
$duplicate=RunGuard
Check ($duplicate.blockers -contains 'HEAP_MUST_BE_EXACTLY_XMS20G_XMX20G') 'duplicate max rejected'
'-Xms20G -Xmx20G' | Set-Content -LiteralPath $argsPath -Encoding ASCII
@{deploymentRole='legacy-protocole-zero';minecraft='1.20.1';neoForge='21.1.248'} |
  ConvertTo-Json | Set-Content -LiteralPath $identityPath -Encoding UTF8
$wrong=RunGuard
Check ($wrong.blockers -contains 'WRONG_SERVER_IDENTITY_OR_VERSION') 'legacy server rejected'
@{deploymentRole='real-proutlost-s2';minecraft='1.21.1';neoForge='21.1.248'} |
  ConvertTo-Json | Set-Content -LiteralPath $identityPath -Encoding UTF8
@{status='QUALIFIED';shaVerified=$true;worldSha256=$hash;bArchiveSha256=$bHash;cloneHealthy=$true;cloneLevelDatSha256=('0'*64);observedUtc=$now} |
  ConvertTo-Json | Set-Content -LiteralPath $contraPath -Encoding UTF8
$badClone=RunGuard
Check ($badClone.blockers -contains 'CLONE_LEVEL_DAT_SHA256_MISMATCH') 'clone hash mismatch rejected'
@{status='QUALIFIED';shaVerified=$true;worldSha256=$hash;bArchiveSha256=('0'*64);cloneHealthy=$true;cloneLevelDatSha256=$hash;observedUtc=$now} |
  ConvertTo-Json | Set-Content -LiteralPath $contraPath -Encoding UTF8
$otherB=RunGuard
Check ($otherB.blockers -contains 'CONTRA_NOT_QUALIFIED') 'qualification for different B archive refused'
@{status='QUALIFIED';shaVerified=$true;worldSha256='unverified';bArchiveSha256=$bHash;cloneHealthy=$true;cloneLevelDatSha256=$hash;observedUtc=$now} |
  ConvertTo-Json | Set-Content -LiteralPath $contraPath -Encoding UTF8
$badWorldHash=RunGuard
Check ($badWorldHash.blockers -contains 'CONTRA_NOT_QUALIFIED') 'unverified map hash refused'
@{status='QUALIFIED';shaVerified=$true;worldSha256=$hash;bArchiveSha256=$bHash;cloneHealthy=$true;cloneLevelDatSha256=$hash;observedUtc=$now} |
  ConvertTo-Json | Set-Content -LiteralPath $contraPath -Encoding UTF8
$correctB=RunGuard
Check ($correctB.blockers -notcontains 'CONTRA_NOT_QUALIFIED') 'synthetic matching B evidence accepted only as input consistency'
Check ($correctB.startedServer -eq $false) 'even matching synthetic evidence cannot start a server'
Check ($correctB.blockers -notcontains 'CLONE_LEVEL_DAT_INVALID_GZIP_NBT') 'well-formed gzip NBT header passes format-only inspection'
$originalLevel=[System.IO.File]::ReadAllBytes($levelDat)
'NOT_A_GZIP_NBT_LEVEL' | Set-Content -LiteralPath $levelDat -Encoding ASCII
$corruptHash=(Get-FileHash -LiteralPath $levelDat -Algorithm SHA256).Hash
@{status='QUALIFIED';shaVerified=$true;worldSha256=$hash;bArchiveSha256=$bHash;cloneHealthy=$true;cloneLevelDatSha256=$corruptHash;observedUtc=$now} |
  ConvertTo-Json | Set-Content -LiteralPath $contraPath -Encoding UTF8
$corrupt=RunGuard
Check ($corrupt.blockers -contains 'CLONE_LEVEL_DAT_INVALID_GZIP_NBT') 'claimed matching SHA cannot bless corrupt level.dat'
[System.IO.File]::WriteAllBytes($levelDat,$originalLevel)
@{status='QUALIFIED';shaVerified=$true;worldSha256=$hash;bArchiveSha256=$bHash;cloneHealthy=$true;cloneLevelDatSha256=$hash;observedUtc=$now} |
  ConvertTo-Json | Set-Content -LiteralPath $contraPath -Encoding UTF8
[System.IO.File]::WriteAllBytes((Join-Path $instance 'level.dat'),$originalLevel)
$alias=RunGuard $instance
Check ($alias.blockers -contains 'CLONE_ALIASES_INSTANCE_ROOT') 'world clone cannot alias instance root'
Check ($alias.startedServer -eq $false) 'alias fixture cannot launch JVM'
# A clone nested inside the server instance is not an independent protected
# copy. It could be the actual world directory or be overwritten by startup.
$nestedClone=Join-Path $instance 'world-nested-fixture'
New-Item -ItemType Directory -Path (Join-Path $nestedClone 'region') -Force | Out-Null
Copy-Item -LiteralPath $levelDat -Destination (Join-Path $nestedClone 'level.dat') -Force
Copy-Item -LiteralPath $mcaPath -Destination (Join-Path (Join-Path $nestedClone 'region') 'r.0.0.mca') -Force
$nested=RunGuard $nestedClone
Check ($nested.blockers -contains 'CLONE_PATH_OVERLAPS_INSTANCE') 'clone nested inside server instance must be rejected'
# The inverse containment is equally dangerous: it can alias user files or
# place a server under the source clone despite two distinct path strings.
New-Item -ItemType Directory -Path (Join-Path $base 'region') -Force | Out-Null
Copy-Item -LiteralPath $levelDat -Destination (Join-Path $base 'level.dat') -Force
Copy-Item -LiteralPath $mcaPath -Destination (Join-Path (Join-Path $base 'region') 'r.0.0.mca') -Force
$ancestor=RunGuard $base
Check ($ancestor.blockers -contains 'CLONE_PATH_OVERLAPS_INSTANCE') 'server instance nested inside clone must be rejected'
# Canonical path spelling does not resolve NTFS junctions in parent directories.
# Both clone and instance paths must refuse a hidden intermediate alias.
$junction=Join-Path $base 'fresh-intermediate-junction-fixture'
if (Test-Path -LiteralPath $junction) { throw 'Fixture junction already exists; refuse replacement' }
try {
  & cmd.exe /d /c "mklink /J `"$junction`" `"$instance`"" | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'Unable to create isolated junction fixture' }
  $aliasedClone=RunGuard (Join-Path $junction 'world-nested-fixture')
  Check ($aliasedClone.blockers -contains 'CLONE_REPARSE_POINT_UNVERIFIED') 'junction in clone ancestor must be refused'
  Check ($aliasedClone.startedServer -eq $false) 'aliased clone may never start a server'
  $aliasedInstance=RunGuard $clone $junction
  Check ($aliasedInstance.blockers -contains 'INSTANCE_REPARSE_POINT_UNVERIFIED') 'junction in instance ancestor must be refused'
  Check ($aliasedInstance.startedServer -eq $false) 'aliased instance may never start a server'
} finally {
  if (Test-Path -LiteralPath $junction) {
    $item=Get-Item -LiteralPath $junction -Force
    if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -eq 0) {
      throw 'Fixture cleanup refused: junction path is no longer a reparse point'
    }
    & cmd.exe /d /c "rmdir `"$junction`"" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Fixture junction cleanup failed' }
  }
}
Write-Output ("PASS_PRELIMINARY_GUARD_TEST_ASSERTIONS=" + $checks)
Write-Output 'ALL TEST INSTANCES ARE DISPOSABLE FIXTURES, NOT A QUALIFIED WORLD OR SERVER'
