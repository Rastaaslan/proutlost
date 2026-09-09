# WorldPrep overnight final report

Repository: `Rastaaslan/proutlost`

Initial `main` SHA: `106e12683ed70919fc50d079a91bd18182265015`

Actual remote task branch: `codex/executer-le-master-run-overnight-worldprep`

Pull request: **#5** — `Overnight: Harden WorldPrep storage, pipeline models, and safety gates (Phase 0–7)`

Remote publication status: **PUBLISHED**. The initial shell/Codex push attempts failed because credentials were unavailable, but the work was subsequently published through Codex Web and then hardened directly on the GitHub PR branch.

## Pinned platform

- Minecraft 1.21.1
- NeoForge 21.1.248
- Java 21
- Gradle Wrapper 8.14.4
- Verified wrapper JAR SHA-256: `7d3a4ac4de1c32b59bc6a4eb8ecb8e612ccd0cf1ae1e99f66902da64df296172`

## Functional matrix

| Pass | Implemented | Tested | Blocked/incomplete |
|---|---|---|---|
| TERRAIN | Existing sampler + expanded pure analysis | Unit slice | Safe no-generation acquisition, full V2 fields/tiles |
| BIOMES | Existing live vertical slice | GameTest | Paged integration/idempotent recovery/profile data |
| GEOLOGY | Existing live vertical slice | Unit + GameTest | Paged integration/provinces from profile |
| ORES | Existing per-block slice | Unit + GameTest | Origin shapes, MutationGroups, exact upstream identity |
| SOILS | Pure planner only | Unit | Live/persisted preview/apply/rollback |
| FLORA | Pure density decision only | Unit | Placement/support/groups/live pipeline |
| TREES | Pure candidate modes only | Unit | Inspectable templates/leaves/groups/live pipeline |
| AQUATIC | Pure classifier only | Unit | Registry validation/live reversible pipeline |
| HABITATS | Immutable query model only | Unit | Planner/persistence/upstream identity |
| VALIDATION | Finding/severity model only | Unit | Detectors/commands/persistence/live pass |

## Architecture matrix

| Capability | State |
|---|---|
| Durable page codec | Implemented, bounded, canonical, SHA-256 protected and independently tested |
| Page integrity | Digest covers immutable page metadata and payload; forged/corrupt identity fails closed |
| Sealed PlanManifest | Full canonical identity/root verification implemented and tested |
| PipelineRun | Exact plan UUID + fingerprint references with canonical self-verifying root implemented/tested |
| PassGraph | Explicit dependencies/invalidation; VALIDATION is invalidated by every environmental pass |
| SemanticConfig | Canonical length-prefixed hashing; execution settings excluded from semantic identity |
| Recovery/idempotence | Domain states and three-state semantics implemented; live restart reconciliation incomplete |
| EnvironmentRevision / ApplyRecord | Model implemented, not yet persisted/integrated into live execution |
| PlanningWorldView | Immutable overlay model implemented/tested, not live-wired |
| ProtectionResolver | Central fail-closed model implemented/tested, not yet used by every legacy path |
| WorldPrep disabled gate | Enqueue, global tick and direct public `tickLevel` execution are gated; GameTest covered |
| Chunk safety | **BLOCKED**: live sampler/runtime still has paths that can load/generate missing chunks |
| Disk preflight | Not implemented |
| Paged live plans/journals | Foundation exists but BIOMES/GEOLOGY/ORES still use legacy runtime persistence |
| LocationRegistry / OverrideRegistry | Incomplete |
| PNG previews | Scale-safety primitive only; renderer/metadata incomplete |
| Validation runtime | Severity/FATAL primitive only; complete live validation pass incomplete |

## PR #5 pre-merge hardening

The initial foundation review found several issues before merge. They were corrected on the PR branch:

- page checksums now cover page identity metadata as well as payload;
- `PlanManifest` roots are canonically calculated and self-verified;
- `PipelineRun` roots cover area/dimension/profile/base-world identity and exact plan UUID+fingerprint references;
- `VALIDATION` now depends on all nine environmental passes;
- `SemanticConfig` uses an unambiguous length-prefixed canonical encoding;
- direct `tickLevel()` execution is refused while WorldPrep is disabled;
- the complete Gradle 8.14.4 wrapper was restored directly on GitHub and verified by SHA-256;
- dedicated regression tests were added for the hardening changes.

Validated foundation commit before report-only cleanup: `7c6ecabc05038c61a00fba99bd2da346446b7e23`.

### Independent GitHub Actions validation

Run: `34318887696`

The clean GitHub-hosted runner checked out the remote PR branch and passed all required foundation gates:

- wrapper binary SHA-256 verification: **PASS**;
- Gradle distribution pinned to 8.14.4: **PASS**;
- `git diff --check`: **PASS**;
- no `.ordinal(` use under Java sources: **PASS**;
- `./gradlew test --no-daemon`: **BUILD SUCCESSFUL**;
- `./gradlew build --no-daemon`: **BUILD SUCCESSFUL**;
- `./gradlew runGameTestServer --no-daemon`: **15/15 required GameTests passed**;
- `./gradlew runServer --no-daemon`: dedicated Minecraft 1.21.1 / NeoForge 21.1.248 server reached **`Done (0.576s)!`**.

The temporary validation workflow used only to establish this independent proof was removed by the report-cleanup commit and is not intended to become a permanent project workflow.

## Historical failures fixed during the work

- Initial compile issues in the expanded terrain code were fixed.
- Initial durable-page regression failures were fixed.
- GameTests initially exposed the newly enforced disabled-mode gate; fixtures were corrected to opt in explicitly.
- Codex Web could not transport `gradle-wrapper.jar`; the verified official binary was restored directly on the GitHub PR branch instead.

## Still not proven / remaining blockers

- Real mid-APPLY process kill/restart/reconcile/resume.
- Real mid-ROLLBACK process kill/recovery.
- Complete crash-boundary fault-injection matrix.
- Representative large-area bounded-memory live execution.
- Full determinism matrix across execution budgets/workers/restarts/order.
- MapDev River acceptance.
- Most importantly, the hardened paged storage/recovery models are **not yet wired into the live BIOMES/GEOLOGY/ORES runtime**.
- The live sampler/runtime does not yet enforce the required no-generation chunk acquisition policy.
- Disk preflight, complete maintenance locking and complete rollback ownership integration remain unfinished.
- SOILS/FLORA/TREES/AQUATIC/HABITATS remain safe planning/model primitives rather than complete reversible live passes.

Therefore this PR is safe to merge **as a hardened foundation**, not as a finished environmental compiler.

PR #5 PRE-MERGE HARDENING:
PASS

SAFE TO MERGE THIS FOUNDATION INTO MAIN:
YES

WORLDPREP CODE COMPLETE:
NO

SOURCE DURABLY PUBLISHED TO GITHUB:
YES

AUTOMATED SAFETY GATES PASSED:
NO

REAL MID-APPLY PROCESS-KILL RECOVERY TESTED:
NO

REAL MID-ROLLBACK PROCESS-KILL RECOVERY TESTED:
NO

READY FOR MAPDEV RIVER ACCEPTANCE:
NO

WORLDPREP ENVIRONMENTAL COMPILER READY FOR RIVER:
NO
