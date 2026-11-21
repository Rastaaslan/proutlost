# World Director S2 — J07 current-WORLD WAL proof, 28 October 2026

## Root cause and correction

The J07 NeoForge operator bridge previously read `DirectorServerLifecycle.recoveredWorld()`, which is the **verified startup-only** `DirectorNativeWorldStateBootstrap` snapshot. After the J03 native scheduler acknowledged a WORLD CAS, that bootstrap object remained at its boot revision, causing J07 WORLD / LABORATORY / memory and pagination metadata to use a stale revision and tick even though J04 contained a newer durable WORLD.

J07 now reads the **latest resident WORLD aggregate directly from J04**, checks `ReadContracts`, world identity, store generation and boot epoch, and requires `PAUSED` / `READ_ONLY_SAFE` in the TEST_ONLY bridge. Any unavailable, stale, corrupt or foreign projection fails closed without falling back to the boot cache. The admin WORLD revision, captured tick and wire sequence now derive from that exact durable readback. A requested stale revision is rejected.

MEMORY combines a J04 regional snapshot with WORLD. It now refuses a read if the published memory sequence is behind the currently verified WORLD sequence; it does not display a partial projection as complete.

## Native integration regression

`DirectorStandaloneNativeGameTests.nativeJ07WorldReadbackTracksDurableJ03Revision` waits for at least one **real scheduler commit through J04 fsync/CAS** and then verifies that the current WAL WORLD revision exceeds bootstrap, that WORLD/J07 query match the WAL revision, simtick and durable sequence, that a stale bootstrap page stamp is rejected, and that no admin action or gameplay admission is granted. A previous J07 world-read test now correctly demands revision >= bootstrap rather than the false equality assumption.

**Evidence — isolated NeoForge 1.21.1 / 21.1.248 TEST_ONLY:**
- `J07_LIVE_WORLD_29_NATIVE_20261028.log`: all **29/29** native GameTests PASS; Gradle success, 69,923 pure-domain contract assertions PASS, J02 catalog content/scoring PASS.
- `J07_LIVE_WORLD_29_COLD_WAL_RETEST_20261028.log`: the **same isolated GameTest directory**, cold J04 WAL recovery/replay, all **29/29** native GameTests PASS again; Gradle success.
- `J07_LIVE_WORLD_COMPILE_20261028.log`: isolated standalone `directorJar` compile PASS.
- JAR has no WorldPrep/TestKit/native GameTest classes. This is an **engineering-only** build, not a deployable gameplay mod.

## Unresolved release gates

No true two-client networking or physical F8 test on this updated build. J07 commands/simulation still not connected. J02 production primitive registry/provider graph, J05 full event/disaster effects, J06 targeted client perceptions, runtime admission and crash/performance/endurance G5 remain unqualified. `DEFAULT_FOR_TESTING` **never** grants production permission. Only the user can approve final gameplay calibration. Active worlds remain untouched.
