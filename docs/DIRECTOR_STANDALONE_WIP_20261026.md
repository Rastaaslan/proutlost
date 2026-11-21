# World Director S2 — Autonomous NeoForge Bootstrap (WIP)

Target: Minecraft 1.21.1, NeoForge 21.1.248, Java 21.

## Implemented and demonstrated
- Native `@Mod("proutlost_director")` entrypoint, no imports or runtime dependencies on WorldPrep main.
- Server-local DirectorServerLifecycle, J04 WAL test lane, J06 network handshake registration and read-only J07 commands.
- Standalone dedicated server startup confirmed by `STANDALONE_DIRECTOR_FIRST_REAL_SERVER.log` (the run was stopped normally).
- Original standalone baseline 17/17 GameTests passed twice, including test-only J02/J03 camp selection, J04 WAL and J05 real block effects, inverse and player ownership cession. Latest native qualification now has 21/21 GameTests after adding two J01 camp-anchor and camp-memory tests (see the latest J01 checkpoint).
- `directorJar` outputs `proutlost-director-1.0.0.jar`. The archive contains no WorldPrep code and no native GameTest classes; tests live in `directorGameTest` source set.

## J07 read-only server/client adapter — 27 October 2026

- Standalone native five-tab DirectorScreen opens on **F8** following a valid J06 SessionReset. The shortcut runs only on the physical client; user session correlation uses DirectorAdminSessionKey, not a trusted security token.
- J06 AdminQuery travels to the actual Minecraft server; the server checks the actual player permission level 4 and its active J06 session before returning any data.
- WORLD returns a truthful health/status projection from the verified J04 WorldAggregate, not fabricated budget numbers. MEMORY returns recovered RegionAdminData / PlayerAdminData, bounded/paginated, with a 22 KB wire cap.
- DECISIONS reads an exact J03 decision ID from one bounded chronological J04 WAL page, with an authentic continuation cursor and no invented audit. The selected candidate and validations survive WAL reboot. Large records are rejected rather than silently truncated.
- ACTIVE reads exact EVENT and CHAIN aggregate IDs from J04. The first native fixture includes an actually durable event and chain recovered from WAL. Joined mutations/claims/facts not yet projected are NOT represented as known empty; entries requiring missing joins remain unavailable.
- LABORATORY remains **unwired**. No mutating operation permissions are granted. AdminCommand remains rejected until the durable admin mailbox and J05 validation are connected.
- Rejected AdminQuery responses are correlated back to the five-tab GUI so stale or unavailable reads do not leave the user indefinitely on a loading state.
- The actual client UI opening/rendering and two-client session delivery remain **unverified on a physical Minecraft client**; compilation and server-side GameTests do not prove visual or interaction correctness.
- The standalone native J07 server tests check permission denial and exact WORLD readback. The region WAL recovery GameTest checks the MEMORY adapter using an actual J04 persisted region fixture.

## J01 anchor and camp-memory ingestion — 27 October 2026

- Real NeoForge campfire placement/destruction hooks already exist in `NeoForgeObservation`. They now queue distinct `AnchorRecord` revisions for the J04 actor, rather than holding only the last in-memory observation.
- New `DirectorNativeAnchorWalIngestion` writes each revision with a deterministic transaction ID. It acknowledges exactly the matching revision after J04 WAL fsync **and** publication in `DurableMemoryPort`. No fake region is created by placing a fire.
- The native nearby-camp lookup now uses only J04-published active anchors; a pending placement or deleted anchor is not a known persistent camp.
- The `CampSampler → ObservationPipeline → MemoryReducer → J04` route is connected for verified anchors and regions, while unknown IDs remain rejected. J04's separate region-memory aggregate revision is used rather than falsely sharing the RegionRecord CAS revision.
- Laboratory memory rehydration now reads at most 256 verified J04 entries per 40 server ticks instead of 64 per 200 ticks, reducing publishing latency without skipping or fabricating WAL history.
- GameTests test **two consecutive anchor revisions** (active then inactive, with distinct WAL ACKs), and one CampSampler observation with the native MemoryReducer. A single visit becomes durable `CampRoutineMemory` evidence but does not prematurely create a region.
- The camp visit is a **synthetic test witness**, not yet a real client observed repeatedly. Full independent-visit qualification, real multi-client camp recognition and production-approved scheduling remain unproven.

## Explicit release blockers
- The standalone bootstrap **does not** activate real production gameplay. Missing qualified J02/J05/J06 providers, approved gameplay calibration and final native orchestrator.
- Real region discovery/ingestion J01, J06 targeted rendering and J07 five-tab UI not complete.
- Nine events/two chains, five catastrophe families, crash matrix, native multiplayer endurance, production cleanup and G5 integration not fully qualified.
- The 17 tests still use lab-recovered J04 and synthetic player/camp inputs. Previous 91/91 native lab proof belongs to another test environment and is not a 91/91 standalone result.
- This is a **non-deployable engineering checkpoint**. Do not copy this JAR to active Proutlost, MapDev or Homestead instances.

## Controlled testing
- `./gradlew --offline runDirectorGameTestServer` — isolated game test world; Gradle config sets `proutlost.director.nativeLab=true` and `proutlost.director.nativeGameTests=true`.
- `./gradlew --offline directorJar` — compile independent mod. No TEST_ONLY GameTests are packaged in the output.
- Default regular server startup has **no lab WAL and no gameplay admission**. The native lab option is intended for disposable game directories only.
- The separate dedicated-server smoke run is stopped; if repeated, bind to localhost, enable authentication and close the server when done.

Canonical branch `work/world-director-s2-v1` remains unchanged.
