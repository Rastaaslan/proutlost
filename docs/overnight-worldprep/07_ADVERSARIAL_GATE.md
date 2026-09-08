# Phase 7 — Adversarial code-complete gate

Starting SHA: `08dbdba`
Ending SHA: this report's checkpoint commit
## Objective
Try to break the result, repair gate defects only, and assess it truthfully.
## Existing state audited
Searches found ordinal dispatch, whole-plan sorts/maps, per-cell biome saves, latest biome journal ambiguity, generating `getChunk` calls, and execution configuration in semantic identity.
## Work completed
Removed ordinal dispatch, removed enable/mode/tick scheduling values from semantic plan identity, fixed terrain enum integration/compiler errors, corrected page publication validation, and made destructive GameTests opt in explicitly to the otherwise-disabled feature.
## Architecture added/changed
No new business functionality. The new page layer remains isolated from the unsafe legacy runtime.
## Main files changed
`BlockMutationRuntime.java`, `PlanIdentity.java`, `EnvironmentPlanner.java`, `SoilPlanner.java`, `DurablePageStore.java`, relevant tests, and this report.
## Persistence/schema changes
None beyond Phase 1 page format.
## Tests executed
- `gradle test --no-daemon`: 40 tests, PASS after two diagnosed/fixed page test failures.
- `gradle build --no-daemon`: PASS.
- `gradle runGameTestServer --no-daemon`: first run executed 14 tests; 11 failed because the new disabled gate correctly refused destructive test setup. Test fixtures were changed to opt in; final rerun status is recorded in `FINAL_REPORT.md`.
- Static searches for ordinal, getChunk, saves, latest, sorts/maps.
## Exact test results
Unit suite: `BUILD SUCCESSFUL`, 40 tests. Build: `BUILD SUCCESSFUL`. First GameTest: 14 run, 11 required failed, exit 11; this is not reported as PASS. Final rerun: see global report.
## Failures encountered
Page record equality compared byte-array identity; corruption fixture flipped metadata instead of payload; expanded enum broke an existing switch; GameTests relied on WorldPrep being accidentally enabled despite default false.
## Fixes applied
Compared page fields and payload bytes explicitly, corrupted the exact payload byte, updated switches/casts, and explicitly enabled WorldPrep only inside destructive GameTest contexts.
## Remaining limitations
Legacy live runtime still has unbounded whole plans/journals, 250k ceiling, repeated full persistence, latest-snapshot rollback, potential chunk generation, silent protected-cell skip, incomplete recovery/locking/preflight, and no integration for passes 4–10. Large test is 600 mutations, not above the historic 5,015 target. Determinism/restart/fault matrices and fresh remote checkout were not completed.
## Technical decisions
The gate is **PARTIAL** and code complete is **NO**. New pure components are retained because they compile/test and make forward migration safer; unsupported live operations remain unavailable.
## Git publication status
Push failed for missing GitHub credentials; fresh remote checkout impossible.
## Phase status
PARTIAL
## Consequences for next phase
Do not run on MapDev. Migrate the live runtime to pages/ApplyRecord and implement no-generation acquisition before any acceptance test.
