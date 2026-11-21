# Read-only production start gate. NEVER starts a JVM or edits an instance.
# RUN ONLY on the authorized FIXE. This is a necessary, not sufficient, release gate.
[CmdletBinding()]
param(
  [string]$InstancePath = '',
  [string]$WorldClonePath = '',
  [string]$BArchivePath = '',
  [string]$BExpectedSha256 = '',
  [string]$ContraQualificationPath = '',
  [string]$ObsQualificationPath = ''
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$blocks = [System.Collections.Generic.List[string]]::new()
function Block([string]$reason) { $blocks.Add($reason) }
function ReadJson([string]$path, [string]$label) {
  if ([string]::IsNullOrWhiteSpace($path) -or !(Test-Path -LiteralPath $path -PathType Leaf)) {
    Block ("MISSING_" + $label); return $null
  }
  try { return Get-Content -LiteralPath $path -Raw -Encoding UTF8 | ConvertFrom-Json }
  catch { Block ("INVALID_" + $label); return $null }
}
function CheckFresh([object]$evidence, [string]$label) {
  try {
    $timestamp = [datetimeoffset]::Parse([string]$evidence.observedUtc).ToUniversalTime()
    if ($timestamp -gt ([datetimeoffset]::UtcNow.AddMinutes(1)) -or
        $timestamp -lt ([datetimeoffset]::UtcNow.AddMinutes(-30))) {
      Block ("STALE_" + $label)
    }
  } catch { Block ("INVALID_TIMESTAMP_" + $label) }
}
# .NET GetFullPath canonicalizes spelling, NOT NTFS junctions or symlinks.
# Inspect every existing directory component on both sides before trusting
# any containment comparison. Unverified reparse points cannot qualify a clone.
function CheckPathReparseAncestors([string]$path, [string]$label) {
  try {
    $current=[System.IO.DirectoryInfo]::new([System.IO.Path]::GetFullPath($path))
    while ($null -ne $current) {
      if (!(Test-Path -LiteralPath $current.FullName -PathType Container)) {
        Block ($label + '_PATH_IDENTITY_UNVERIFIABLE'); return
      }
      $item=Get-Item -LiteralPath $current.FullName -Force
      if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        Block ($label + '_REPARSE_POINT_UNVERIFIED'); return
      }
      $current=$current.Parent
    }
  } catch {
    Block ($label + '_PATH_IDENTITY_UNVERIFIABLE')
  }
}
if ($env:OS -ne 'Windows_NT') { Block 'NOT_WINDOWS' }
if ([string]::IsNullOrWhiteSpace($InstancePath) -or !(Test-Path -LiteralPath $InstancePath -PathType Container)) {
  Block 'INSTANCE_NOT_IDENTIFIED'
} else {
  CheckPathReparseAncestors $InstancePath 'INSTANCE'
  $identity=ReadJson (Join-Path $InstancePath 'proutlost-s2-instance.json') 'S2_IDENTITY'
  if ($null -ne $identity) {
    try {
      if ($identity.deploymentRole -cne 'real-proutlost-s2' -or
          $identity.minecraft -cne '1.21.1' -or
          $identity.neoForge -cne '21.1.248') { Block 'WRONG_SERVER_IDENTITY_OR_VERSION' }
    } catch { Block 'MALFORMED_S2_IDENTITY' }
  }
  $jvmPath=Join-Path $InstancePath 'user_jvm_args.txt'
  if (!(Test-Path -LiteralPath $jvmPath -PathType Leaf)) {
    Block 'S2_JVM_ARGS_MISSING'
  } else {
    $tokens=@(Get-Content -LiteralPath $jvmPath -Encoding UTF8 |
      Where-Object { $_ -notmatch '^\s*(#|$)' } | ForEach-Object { $_ -split '\s+' } |
      Where-Object { $_ -ne '' })
    $initial=@($tokens | Where-Object { $_ -match '^-Xms' })
    $maximum=@($tokens | Where-Object { $_ -match '^-Xmx' })
    if ($initial.Count -ne 1 -or $maximum.Count -ne 1 -or
        $initial[0] -cne '-Xms20G' -or $maximum[0] -cne '-Xmx20G') {
      Block 'HEAP_MUST_BE_EXACTLY_XMS20G_XMX20G'
    }
  }
}
# SHA alone is not enough: a copied arbitrary file with a matching claimed
# digest is not a bootable Minecraft level.dat. Validate gzip integrity and
# the NBT compound prefix WITHOUT loading/mutating chunks or world data.
function CheckCloneLevelDat([string]$levelPath) {
  $input=$null; $gzip=$null
  try {
    if ((Get-Item -LiteralPath $levelPath).Length -lt 16) {
      Block 'CLONE_LEVEL_DAT_INVALID_GZIP_NBT'; return
    }
    $input=[System.IO.File]::Open($levelPath,
      [System.IO.FileMode]::Open,[System.IO.FileAccess]::Read,
      [System.IO.FileShare]::Read)
    $gzip=[System.IO.Compression.GZipStream]::new(
      $input,[System.IO.Compression.CompressionMode]::Decompress,$true)
    [byte[]]$prefix=New-Object byte[] 3
    if ($gzip.Read($prefix,0,3) -ne 3 -or $prefix[0] -ne 10 -or
        (($prefix[1] -shl 8) -bor $prefix[2]) -gt 4096) {
      Block 'CLONE_LEVEL_DAT_INVALID_GZIP_NBT'; return
    }
    [byte[]]$buffer=New-Object byte[] 8192
    $total=3
    while (($n=$gzip.Read($buffer,0,$buffer.Length)) -gt 0) {
      $total+=$n
      if ($total -gt 8388608) {
        Block 'CLONE_LEVEL_DAT_DECOMPRESSED_OVER_8MIB'; return
      }
    }
  } catch { Block 'CLONE_LEVEL_DAT_INVALID_GZIP_NBT' }
  finally {
    if ($null -ne $gzip) { $gzip.Dispose() }
    if ($null -ne $input) { $input.Dispose() }
  }
}
if ([string]::IsNullOrWhiteSpace($WorldClonePath) -or
    !(Test-Path -LiteralPath $WorldClonePath -PathType Container) -or
    !(Test-Path -LiteralPath (Join-Path $WorldClonePath 'level.dat') -PathType Leaf)) {
  Block 'QUALIFIED_WORLD_CLONE_MISSING'
} else {
  CheckPathReparseAncestors $WorldClonePath 'CLONE'
  try {
    $cloneRoot=[System.IO.Path]::GetFullPath($WorldClonePath).TrimEnd([char]92)
    if (!( [string]::IsNullOrWhiteSpace($InstancePath) )) {
      $instanceRoot=[System.IO.Path]::GetFullPath($InstancePath).TrimEnd([char]92)
      $compare=[System.StringComparison]::OrdinalIgnoreCase
      $sep=[string][System.IO.Path]::DirectorySeparatorChar
      if ([string]::Equals($cloneRoot,$instanceRoot,$compare)) {
        Block 'CLONE_ALIASES_INSTANCE_ROOT'
      } elseif ($cloneRoot.StartsWith($instanceRoot+$sep,$compare) -or
                $instanceRoot.StartsWith($cloneRoot+$sep,$compare)) {
        # Two distinct directory strings can still refer to the same active
        # server/world tree. A clone must be an independently located copy.
        Block 'CLONE_PATH_OVERLAPS_INSTANCE'
      }
    }
    foreach ($path in @($WorldClonePath,(Join-Path $WorldClonePath 'level.dat'))) {
      if (((Get-Item -LiteralPath $path).Attributes -band
           [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        Block 'CLONE_REPARSE_POINT_UNVERIFIED'
      }
    }
  } catch { Block 'CLONE_PATH_IDENTITY_UNVERIFIABLE' }
  CheckCloneLevelDat (Join-Path $WorldClonePath 'level.dat')
}
# A valid level.dat alone is not evidence of a playable Contra clone.
# Inspect only the Anvil region headers; this is bounded, read-only work.
# Full per-chunk content and SHA qualification remain separate release gates.
function CheckCloneRegions([string]$root) {
  $region=Join-Path $root 'region'
  if (!(Test-Path -LiteralPath $region -PathType Container)) {
    Block 'CLONE_REGION_MISSING_OR_EMPTY'; return
  }
  try {
    if (((Get-Item -LiteralPath $region).Attributes -band
         [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
      Block 'CLONE_REGION_REPARSE_POINT'; return
    }
    $files=@(Get-ChildItem -LiteralPath $region -File -Filter 'r.*.*.mca')
    if ($files.Count -eq 0) { Block 'CLONE_REGION_MISSING_OR_EMPTY'; return }
    $foundChunk=$false
    foreach ($file in $files) {
      if ($file.Name -notmatch '^r\.-?\d+\.-?\d+\.mca$' -or
          ($file.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0 -or
          $file.Length -lt 12288 -or $file.Length % 4096 -ne 0) {
        Block 'CLONE_REGION_HEADER_CORRUPT'; return
      }
      $stream=[System.IO.File]::Open($file.FullName,[System.IO.FileMode]::Open,
        [System.IO.FileAccess]::Read,[System.IO.FileShare]::Read)
      try {
        [byte[]]$header=New-Object byte[] 4096
        if ($stream.Read($header,0,$header.Length) -ne $header.Length) {
          Block 'CLONE_REGION_HEADER_CORRUPT'; return
        }
        # A region may not assign the same 4 KiB sector to two chunks.
        # Overlapping intervals are corrupt even when individually in range.
        $allocated=[System.Collections.Generic.HashSet[long]]::new()
        for ($i=0;$i -lt 4096;$i+=4) {
          $sector=([int]$header[$i] -shl 16) -bor
                  ([int]$header[$i+1] -shl 8) -bor [int]$header[$i+2]
          $count=[int]$header[$i+3]
          if ($sector -eq 0 -and $count -eq 0) { continue }
          if ($sector -lt 2 -or $count -lt 1 -or
              (([long]$sector+[long]$count)*4096) -gt $file.Length) {
            Block 'CLONE_REGION_HEADER_CORRUPT'; return
          }
          for ($s=[long]$sector; $s -lt ([long]$sector+[long]$count); $s++) {
            if (!$allocated.Add($s)) {
              Block 'CLONE_REGION_OVERLAPPING_CHUNK_SECTORS'; return
            }
          }
          $foundChunk=$true
        }
      } finally { $stream.Dispose() }
    }
    if (!$foundChunk) { Block 'CLONE_REGION_MISSING_OR_EMPTY' }
  } catch { Block 'CLONE_REGION_INSPECTION_FAILED_SAFE' }
}
if (-not [string]::IsNullOrWhiteSpace($WorldClonePath) -and
    (Test-Path -LiteralPath $WorldClonePath -PathType Container)) {
  CheckCloneRegions $WorldClonePath
}
$contra=ReadJson $ContraQualificationPath 'CONTRA_QUALIFICATION'
if ($null -ne $contra) {
  try {
    if ($contra.status -cne 'QUALIFIED' -or
        $contra.shaVerified -ne $true -or
        $contra.worldSha256 -notmatch '^[a-fA-F0-9]{64}$' -or
        $contra.bArchiveSha256 -notmatch '^[a-fA-F0-9]{64}$' -or
        $BExpectedSha256 -notmatch '^[a-fA-F0-9]{64}$' -or
        $contra.bArchiveSha256 -ine $BExpectedSha256 -or
        $contra.cloneHealthy -ne $true -or
        $contra.cloneLevelDatSha256 -notmatch '^[a-fA-F0-9]{64}$') {
      Block 'CONTRA_NOT_QUALIFIED'
    } elseif (!(Test-Path -LiteralPath $WorldClonePath -PathType Container)) {
      Block 'CLONE_SHA_NOT_CHECKABLE'
    } else {
      $levelDat=Join-Path $WorldClonePath 'level.dat'
      if (!(Test-Path -LiteralPath $levelDat -PathType Leaf) -or
          (Get-FileHash -LiteralPath $levelDat -Algorithm SHA256).Hash -cne
          ([string]$contra.cloneLevelDatSha256).ToUpperInvariant()) {
        Block 'CLONE_LEVEL_DAT_SHA256_MISMATCH'
      }
    }
  } catch { Block 'MALFORMED_CONTRA_QUALIFICATION' }
  CheckFresh $contra 'CONTRA_QUALIFICATION'
}
$obs=ReadJson $ObsQualificationPath 'OBS_QUALIFICATION'
if ($null -ne $obs) {
  try {
    if ($obs.profile -cne '1080p60-NVENC' -or
        $obs.framesDropped -lt 0 -or $obs.framesTotal -le 0 -or
        $obs.framesDropped -gt 0 -or $obs.status -cne 'QUALIFIED') {
      Block 'OBS_UNQUALIFIED_OR_FRAMES_LOST'
    }
  } catch { Block 'MALFORMED_OBS_QUALIFICATION' }
  CheckFresh $obs 'OBS_QUALIFICATION'
}
try {
  $os=Get-CimInstance -ClassName Win32_OperatingSystem
  $freeGiB=[math]::Round($os.FreePhysicalMemory / 1048576.0,2)
  # 20 GiB HEAP plus 8 GiB physical headroom for OS, OBS, off-heap, and stability.
  if ($freeGiB -lt 28.0) { Block 'RAM_BELOW_20G_HEAP_PLUS_8G_OBS_OS_MARGIN' }
} catch { $freeGiB=$null; Block 'RAM_NOT_MEASURABLE' }
$diskGiB=$null
try {
  $target=if ($InstancePath -match '^[A-Za-z]:') { $InstancePath.Substring(0,1) } else { 'C' }
  $drive=Get-PSDrive -Name $target -PSProvider FileSystem
  $diskGiB=[math]::Round($drive.Free/1GB,2)
  if ($diskGiB -lt 60.0) { Block 'SSD_BELOW_60G_WORKING_MARGIN' }
} catch { Block 'SSD_NOT_MEASURABLE' }
# A B archive may be very large. Never hash it while the physical RAM/SSD
# admission guard is failing, even if Docker/Java/transfer audits look idle.
# Keep this independent of the concurrency veto for accurate diagnostics.
$resourceGuardBlocked=($null -eq $freeGiB -or $freeGiB -lt 28.0 -or
                       $null -eq $diskGiB -or $diskGiB -lt 60.0)
# Check active transfers FIRST. If detected, do not hash a potentially huge B archive.
$busy=$false
try {
  $dockerNames=@(docker ps --format '{{.Names}}' 2>$null)
  if ($LASTEXITCODE -ne 0) { Block 'DOCKER_UNAVAILABLE_FOR_CONTENTION_CHECK';$busy=$true }
  elseif (@($dockerNames | Where-Object { $_ -match '(?i)(contra|worldprep|worlddirector|migration|seal)' }).Count -gt 0) {
    Block 'CONCURRENT_CONTRA_WORLDPREP_OR_DIRECTOR_CONTAINERS';$busy=$true
  }
} catch { Block 'DOCKER_CONTENTION_UNKNOWN';$busy=$true }
try {
  $java=@(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'")
  if ($java.Count -gt 0) { Block 'JAVA_PROCESS_CONCURRENCY_REQUIRES_MANUAL_CLEARANCE';$busy=$true }
} catch { Block 'JAVA_PROCESS_AUDIT_FAILED';$busy=$true }
# Physical transfers and archive/sealing processes can saturate the SSD even when
# there is no Java VM. Fail closed rather than competing with the Contra seal.
try {
  $transfers=@(Get-CimInstance Win32_Process | Where-Object {
    $_.Name -match '^(?i:robocopy|rclone|rsync|xcopy|7z|7za|tar|zstd|pigz|aria2c)\.exe$'
  })
  if ($transfers.Count -gt 0) { Block 'CONCURRENT_FILE_TRANSFER_OR_ARCHIVE_PROCESS';$busy=$true }
} catch { Block 'TRANSFER_PROCESS_AUDIT_FAILED';$busy=$true }
if ($resourceGuardBlocked) { Block 'B_HASH_DEFERRED_RESOURCE_GUARD' }
elseif ($busy) { Block 'B_HASH_DEFERRED_WHILE_INTENSIVE_WORK_ACTIVE' }
elseif ([string]::IsNullOrWhiteSpace($BArchivePath) -or !(Test-Path -LiteralPath $BArchivePath -PathType Leaf)) {
  Block 'B_ARCHIVE_MISSING'
} elseif ($BExpectedSha256 -notmatch '^[a-fA-F0-9]{64}$') {
  Block 'B_EXPECTED_SHA256_MISSING_OR_INVALID'
} else {
  try {
    if ((Get-FileHash -LiteralPath $BArchivePath -Algorithm SHA256).Hash -cne $BExpectedSha256.ToUpperInvariant()) {
      Block 'B_SHA256_MISMATCH'
    }
  } catch { Block 'B_SHA256_UNVERIFIABLE' }
}
$result=[ordered]@{
  status=if($blocks.Count -eq 0){'PRELIMINARY_GUARDS_PASS_MANUAL_G5_STILL_REQUIRED'}else{'BLOCKED'}
  heap='-Xms20G -Xmx20G'
  startedServer=$false
  mutatedWorld=$false
  freePhysicalGiB=$freeGiB
  freeDiskGiB=$diskGiB
  blockers=@($blocks)
}
$result | ConvertTo-Json -Depth 5
if ($blocks.Count -gt 0) { exit 2 }
exit 0
