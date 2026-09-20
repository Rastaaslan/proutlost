# WorldPrep V2 progress / recovery

## Continuation base
- **REMOTE BRANCH:** `codex/finalize-worldprep-v2-build-and-certification`
- **REMOTE HEAD RECEIVED:** `7ba59b33ba1e16cb8e62d7e8137da4f1eb5336de`
- **CONTENT RECONCILIATION:** The remote tree was byte-for-byte identical to the supplied local PR commit before continuation.
- **TEST EVIDENCE RECONCILIATION:** The earlier local report said 15 GameTests passed, while the published PR body said GameTests were not run. There is no durable CI artifact establishing which statement described the published commit, so neither statement is used as certification evidence. This continuation reran `runGameTestServer`; the first integration run exposed two failures, and the post-fix run passed all 17 tests. The final post-change run passed all 18 required GameTests.

## Runtime integration checkpoint
- **PHASE:** BIOMES/GEOLOGY/ORES runtime safety migration
- **STATUS:** PARTIAL
- **COMMIT:** `b84c677`
- **IMPLEMENTED:** Generation-free `getChunkNow` gateway with real dimension, area, border, and build-height checks; production sampler/biome/block paths migrated away from generation-prone `getChunk`; sealed block mutations no longer silently skip protected, BlockEntity, invalid-host, or third-state conflicts; rollback requires an exact snapshot UUID; biome apply/recovery and rollback use symmetric before/after/conflict reconciliation.
- **TESTS RUN:** `test` and `build` passed. Initial GameTest integration run failed 2/17 and was not reported as success. Root causes were obsolete tests expecting silent protected skips and an invalid sealed ore mutation; both tests were corrected to enforce fail-closed semantics. A correction run passed 17/17; the final expanded suite passed 18/18.
- **FAILURES FOUND:** Silent skip expectations; invalid sealed ore mutation expectation.
- **FAILURES FIXED:** Protected/invalid sealed plans now fail atomically at batch preflight; valid ore test contains only eligible sealed mutations.
- **NOT TESTED:** True JVM/process kill; unloaded-on-disk complete chunk access (production intentionally requires explicit loaded-chunk preflight).
- **KNOWN LIMITATIONS:** BIOMES journal remains SavedData-backed rather than disk-paged; GEOLOGY/ORES plans remain SavedData collections even though live journals are paged.
- **NEXT PHASE:** Complete disk-paged plan migration and biome journal paging.

## Durable journal and immutable workspace checkpoint
- **PHASE:** Runtime pages, recovery validation, immutable candidate creation
- **STATUS:** PARTIAL
- **COMMIT:** `b84c677`, `7c1011f`
- **IMPLEMENTED:** Live GEOLOGY/ORES writes publish and reread checksummed `DurablePageStore` journal pages before SavedData ownership and world mutation. Rollback validates all referenced pages before mutation. Missing-page GameTest fails closed. Workspace copies through an explicitly marked partial directory, hashes source/candidate, rechecks immutable source, and publishes a PLANNING candidate only after verification.
- **TESTS RUN:** Unit/build/GameTest gates as recorded in final report.
- **FAILURES FOUND:** None after correction run.
- **FAILURES FIXED:** N/A.
- **NOT TESTED:** Filesystem-full injection, true crash during recovery, multi-gigabyte representative world copy.
- **KNOWN LIMITATIONS:** Paged plan migration and biome paged journal remain incomplete.
- **NEXT PHASE:** Environmental and MapDev artifact audit.

## Planning/compiler corrections
- **PHASE:** Exact compiler and automatic structure allocation
- **STATUS:** PARTIAL
- **COMMIT:** `7c1011f`
- **IMPLEMENTED:** Pipeline identity now binds every exact SEALED `PlanManifest`, plan UUID/root, source, profile, semantic pack, seed, and upstream chain. Structure envelopes use verified width/height/depth with rotation, eligibility, rarity, global/region density, clearance, and spacing; arbitrary `+255` geometry was removed. Runtime pack inspector hashes installed artifacts/configs/datapacks and eight live registries; unknown mods refuse classification.
- **TESTS RUN:** Unit/build/GameTest gates as recorded in final report.
- **FAILURES FOUND:** No representative environmental/YUNG artifacts installed.
- **FAILURES FIXED:** Fake adapter work was not added.
- **NOT TESTED:** YUNG APIs/materialization; selected production biome provider.
- **KNOWN LIMITATIONS:** Structure materialization, ecology runtime, and final compile/build commands remain incomplete.
- **NEXT PHASE:** Continue only with verified artifacts and MapDev candidate.

## 2026-09-20 biome durable-journal continuation
- **START HEAD:** `aca337fcb369d5ca2ad6325a10e5089f7e77ee76` (the expected PR #6 head was already checked out). No Git remote was configured in this workspace, so fetch, push, and server-side PR-body reconciliation were unavailable.
- **IMPLEMENTED:** Live BIOMES apply now publishes and rereads a checksummed `DurablePageStore` journal page before each column mutation. Snapshot metadata persists exact page sequence/checksum references. Resume adopts only the exact durable ownership page, rollback validates every referenced page before its first mutation, and legacy nonempty biome snapshots without pages refuse rollback.
- **REAL GAMETEST:** The controlled partial BIOMES apply proves a durable page exists, deletes it, and verifies exact rollback fails closed before restoring test state.
- **GATES:** 61 unit tests passed; build passed; all 18 required GameTests passed; `git diff --check` passed; production-source generation-prone access scan found no matches (the sole textual match is intentional GameTest setup).
- **REMAINING:** BIOMES plan pages and removal of legacy full plan/snapshot cells from SavedData are not complete. Therefore BIOMES remains `PARTIAL`, bounded-memory certification remains `NOT_TESTED`, and code-complete/safety-certified remain `NO`.

## 2026-09-20 paged-plan substrate checkpoint (PR #7)
- **START HEAD:** `faf643089a33e77057c87191ba571fe03c2f58e7`; the checkout had no configured remote and was renamed locally to the requested canonical branch.
- **IMPLEMENTED:** `PagedPlanStore` publishes immutable checksummed PLAN pages, validates every page before publishing the exact sealed manifest commit record, rereads exact manifests, streams one page at a time, and reports orphan temporary/completed files and referenced missing pages without deleting recovery material.
- **BOUNDED TEST:** 50,003 entries for each of BIOMES, GEOLOGY, and ORES, 127 entries/page (394 pages/pass), with measured maximum residency of one page/127 entries. This certifies the generic reader, not live runtime integration.
- **CORRUPTION TESTS:** missing page, trailing bytes, checksum/owner corruption, exact-manifest mismatch, and lifecycle diagnostics refuse or report as designed.
- **REMAINING BLOCKER:** live preview/apply still uses the existing SavedData plan collections. The generic store is not yet wired into BIOMES/GEOLOGY/ORES, so no pass is claimed to have a paged production plan and ecology remains gated.
