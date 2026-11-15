# Proutlost S2 — read-only 20 GiB production pre-start guard

**Status: preliminary technical veto only, NOT a G5 release approval.** Run exclusively on authorized FIXE. This script never starts Java, rewrites user_jvm_args.txt, creates a live server, or alters Minecraft worlds. It does not accept an 8/12/16 GiB downgrade. All live WorldPrep/Contra/OBS services remain untouched.

Guard: `scripts/verify-proutlost-s2-production-start.ps1`. Exit 2 means **BLOCKED**. Exit 0 means only that the named, machine-checkable prerequisites passed at that instant; the human gameplay calibration and complete G5 test remain indispensable. The evidence files must originate from actual independent qualification, never from the disposable fixtures used by the tests.

Example call **only after all input paths have been independently verified**:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-proutlost-s2-production-start.ps1 `
  -InstancePath 'C:\<real-s2-instance>' `
  -WorldClonePath 'C:\<healthy-contrafinal-clone>' `
  -BArchivePath 'C:\<verified-B-archive>' `
  -BExpectedSha256 '<64-character-B-SHA256>' `
  -ContraQualificationPath 'C:\<real-contra-qualified-evidence>.json' `
  -ObsQualificationPath 'C:\<actual-obs-1080p60-nvenc-metrics>.json'
```

The real S2 instance must carry a verified `proutlost-s2-instance.json` with `deploymentRole=real-proutlost-s2`, `minecraft=1.21.1`, and `neoForge=21.1.248`. The **actual S2 instance only** must contain exactly one `-Xms20G` and `-Xmx20G` entry in `user_jvm_args.txt`; conflicting or duplicated heap flags block startup. Never edit or apply these settings to Protocole Zéro Forge 1.20.1 or WorldPrep validation JVMs.

The Contra evidence JSON is required to contain `status=QUALIFIED`, `shaVerified=true`, `worldSha256` (64 hex), `bArchiveSha256` (64 hex, matching the separately supplied expected B SHA-256), `cloneHealthy=true`, `cloneLevelDatSha256` (64 hex chars), and `observedUtc` (ISO-8601). The `level.dat` hash is independently checked against that evidence. The OBS metrics JSON requires `status=QUALIFIED`, `profile=1080p60-NVENC`, `framesTotal>0`, `framesDropped=0`, `observedUtc`. Evidence older than 30 minutes is rejected. Neither metadata file is independently authenticated by this script: their provenance and physical-world qualification still require separate review.

The machine veto requires at least **28 GiB free physical RAM** (20 GiB heap plus 8 GiB conservative margin for Windows, OBS and off-heap) and **60 GiB free disk** on the instance drive. It blocks Java concurrency, recognized Contra/WorldPrep/Director/migration/sealing Docker jobs, and detectable OS transfer/archive executables (robocopy, rclone, rsync, xcopy, 7z, tar, zstd, pigz, aria2c). This is a conservative process-name guard, not exhaustive surveillance of every possible transfer or in-process file copy. When intensive work is detected, the potentially large B archive is deliberately **not hashed**. The presence of any unresolved item is a hard block. These thresholds are conservative engineering gates, not measured proof of sustained TPS/MSPT or OBS stability.

**Still required before deployment:** authoritative Contra B SHA and final-map qualification, sound complete world clone and native pack identity, independent container/process/transfer exclusion, genuine two-client J01/J06/F8/perception test, real J02/J05/J06 providers and J03 WAL-before-effect admissions, five physical catastrophes with WARN/cleanup/perception, J07/J08 administration, 24-hour G5 real-client crash/rollback/soak with TPS/MSPT, RAM committed and OBS lost-frame telemetry, then the user's explicit APPROVED decision.

Local disposable guard tests: `scripts/test-verify-proutlost-s2-production-start.ps1`. Their synthetic paths are **not** a qualifying instance, world, SHA seal or approval. They test rejection/identity/heap/clone-hash plus consistency of the map digest and expected B archive SHA; active transfer detection still requires observation on the real machine. Synthetic matching evidence is never proof that Contra or a genuine B archive is qualified. All test data stays under `build/s2-preflight-fixtures`.
