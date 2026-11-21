# J06 — quarantined cosmetic effect lifecycle (TEST_ONLY)
Date: 2026-10-30. Branch: `work/world-director-s2-gates-20261030`.
Base: `bc3d087812733b041146fe68716fbfaa00c77c78`. This is an isolated
integration *candidate*, not the currently active integration branch.

## Corrected
- A PerceptionStop for an effect which never reached its first PerceptionUpsert
  now leaves a terminal tombstone. Later higher-sequence messages for that
  effect ID receive EXPIRED; a fresh independently admitted effect ID remains
  available. Sequence memory stays subject to the existing `rememberedIds`
  cap and session reset.
- Renderer start/update exceptions retire that exact effect identity, issue
  ERROR if cleanup completes, and prevent a later higher-sequence replay.
- Failed update calls stop once: the prior catch path stopped an active effect
  in `remove()` and then called `effects.stop()` a second time.
- No world mutation, authorisation, real asset promotion, vanilla client,
  J05 physics, production provider or network permission changes.

## Validation on this exact source tree
- Java 21, Gradle 8.14.4, offline, one worker, nice 15, ionice idle.
- J06 network/client tests: 3625 assertions PASS (previous checkpoint 3618).
- Contract tests: 69927 assertions PASS.
- Catalog: 364 assertions PASS, 83 cases.
- Candidate catalog: 86 assertions PASS, 11 definitions.
- Scoring: 101 assertions PASS.
- Memory integration: 321 assertions PASS.
- Standalone `directorJar`: PASS, 891 class entries.
- JAR SHA256: `403a825344f51dbbbad59cfafe746cee6baf2b1492aa5e6aad48ae8349ffc317`.
- Logs: `J06_TOMBSTONE_20261030.log`,
  `J06_TOMBSTONE_FULL_RETRY_20261030.log`,
  `J06_QUARANTINE_FULL_20261030.log` archived with checkpoint.
- First full-suite attempt failed due to isolated worktree missing the
  test-profile fixture `build/director-input/spec-1.0.0/DEFAULT_FOR_TESTING.json`.
  That was explicitly recorded. 40 profile files (532 KiB) were copied from
  the existing test fixture with identical SHA256. The retry and final
  expanded suite PASSED; the first failure must not be rewritten as PASS.

## Non-qualified
- Native NeoForge GameTests on *this commit*: NOT_RUN (WorldPrep currently active).
- Two genuine Minecraft clients, F8 display, real camp visits and J06
  perception/audio: NOT_RUN; no approved asset registry.
- J02/J05 providers, actual catastrophes, J03 automatic production admission,
  J07 action workflows and G5: remain outstanding.
- The separate third 32-GameTest campaign on the original integration branch
  failed its J07 score-only test (31/32); do not claim another native PASS.
- Test-only, non-deployable; APPROVED calibration remains user-only.
- Original integration worktree had independent uncommitted J07 fixes:
  deliberately not staged, modified or integrated by this branch.
