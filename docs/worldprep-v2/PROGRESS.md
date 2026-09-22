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

## 2026-09-20 live paged-plan production migration
- **START HEAD:** `8818844c99ac2a4b0c9407f31152ecca61192308`; the workspace had no remote and the local branch was renamed to the requested canonical PR #8 branch without resetting the checkpoint.
- **IMPLEMENTED:** BIOMES, GEOLOGY, and ORES preview publish immutable `PagedPlanStore` pages and retain only plan UUID/root/count/input metadata in SavedData. Live apply resolves the exact sealed manifest and reads a budget window one page at a time. ORES metadata binds the exact GEOLOGY UUID and manifest root. Legacy schemas fail closed.
- **BOUNDED PLAN PATH:** 256 entries/page; apply retains at most one decoded page plus one bounded mutation batch. Inline biome/block plan collections are transient authoring/GameTest staging and are not serialized or consulted after publication.
- **REMAINING GATE:** Journal ownership entries are still mirrored in SavedData collections for rollback. Although the durable journal pages remain authoritative and missing pages fail closed, this mirror prevents a truthful live bounded-memory certification. Restart/process-kill and a representative live large-plan memory measurement also remain incomplete. Ecology stays gated.

## 2026-09-20 PR #9 durable journal streaming gate
- **START HEAD:** `cb1375ca26f3754925fe98230f292203a8dd4262`; this is the environment's legitimate published descendant of the requested paged-plan work. The checkout had no remote; it was renamed to `codex/continuer-migration-des-plans-pagines` without resetting or merging.
- **MIGRATION MAP:** `Snapshot.cells`, `Snapshot.pages`, `Journal.changes`, and `Journal.pages` were authoritative unbounded mirrors and are removed. Snapshot/Journal UUID, exact plan UUID/root, pass, area/dimension, journal root, page count, and entry count are bounded metadata. `Plan.cells`/`Plan.changes` remain transient authoring/GameTest staging only and are cleared at publication.
- **IMPLEMENTED:** An append-only journal manifest binds journal UUID, exact plan UUID/root, pass, dimension, area, ordered page checksums, and total entry count. APPLY initializes and advances that commit record before mutation. APPLY recovery and ROLLBACK stream durable pages directly; rollback performs a complete bounded preflight before restoration, treats its cursor as advisory, and remains idempotent.
- **BOUNDED EVIDENCE:** live GEOLOGY and ORES: 600 entries, three pages, maximum one decoded page/256 entries. Store stress: 2,100 entries, 300 pages, maximum one decoded page/seven entries. BIOMES uses identical streaming traversal and no SavedData ownership-entry mirror.
- **RESTART EVIDENCE:** GameTests recreate compact journal metadata with zeroed advisory counters while retaining durable pages, then reconcile BEFORE/AFTER states. Duplicate rollback and cursor-ahead recovery complete safely; missing pages and third states fail closed.
- **NOT TESTED:** true operating-system process kill, filesystem-full, and permission-denied injection. Process-kill remains mandatory before River handoff but does not block Ecology under the requested gate.

## 2026-09-21 environmental planning checkpoint
- **BASE SHA:** `9d895ecf335f2cc7770f7f89b328c48e5e3173d3` (PR #9 substrate present).
- **IMPLEMENTED:** Exact environmental mutation model, profile/registry semantic catalog, configurable semantic soil depth, deterministic flora and exact small-tree materialization, structure sandbox-capture canonicalization with envelope enforcement, authoritative reservation zones, final habitat classification, deterministic fail-closed cross-pass validation, and complete graph publication through `PagedPlanStore` with exact upstream identities.
- **BOUNDED EVIDENCE:** The complete eleven-stage graph model publishes all ten pre-validation plans in fixed pages; tests reread each with one resident page and the configured page-entry bound.
- **EXTERNAL STATUS:** No YUNG artifacts or APIs are declared in this repository. Every listed YUNG family remains `BLOCKED_EXTERNAL / UNVERIFIED`; no APIs or IDs were invented.
- **LIMITATION:** Existing Minecraft command/runtime operations still expose only BIOMES, GEOLOGY, and ORES. The new environmental components are production-safe planning primitives, but SOILS/AQUATIC/STRUCTURES/FLORA/TREES are not yet connected to live commands and the generic live executor. Accordingly this checkpoint is PARTIAL and is not represented as environmental code complete.

## 2026-09-22 environmental exact-artifact continuation
- **START HEAD:** `03b9fcf1b2b8723be31f56f5b1794d74858b11c9` (the requested PR #10 head).
- **IMPLEMENTED:** Environmental mutation pages now have a lossless canonical codec containing pass, position, exact BEFORE and AFTER block state, exact BEFORE and AFTER BlockEntity type/NBT, and MutationGroup UUID. A single compare-and-write executor preflights a complete group before journaling and writes only after the journal callback returns.
- **PIPELINE:** The exact completion path reuses sealed TERRAIN/BIOMES/GEOLOGY/ORES manifests rather than republishing them, seals all five destructive environmental passes with the exact codec, and seals HABITATS and VALIDATION metadata. Fatal validation refuses final sealing. The compatibility publisher now also requires a real VALIDATION artifact, so its manifest order exactly equals `PassGraph.order()`.
- **OPERATIONS:** Stable named job operations now exist for SOILS, AQUATIC, STRUCTURES, FLORA, TREES, HABITATS, and VALIDATION. These names are persistence plumbing only; the server command/runtime planner integration remains incomplete.
- **LIMITATION:** This checkpoint does not falsely claim the requested live Minecraft integration. The current `BlockMutationRuntime` still only dispatches GEOLOGY/ORES and does not yet apply the exact environmental codec or BlockEntity snapshots in a `ServerLevel`. Therefore environmental live status and zero-touch readiness remain partial/no.
