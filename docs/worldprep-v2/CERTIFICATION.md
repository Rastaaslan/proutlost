# Adversarial certification matrix

`PASS` denotes an automated test in this repository. `PARTIAL` denotes a tested
pure boundary but not a real Minecraft/OS integration. `NOT TESTED` is never a
claim of safety certification.

| Attack | Status | Evidence / limitation |
|---|---|---|
| corrupt/truncated page, unsupported format | PASS | DurablePageStore tests |
| missing/duplicate/wrong-order page | PASS | manifest sequence validation tests |
| changed source/profile/mod/JAR/config/datapack/registry | PARTIAL | canonical identity models tested; real pack absent |
| missing biome | PASS | candidate pool refuses absent registry id |
| missing/unsupported structure | PASS | catalog refuses missing/opaque required capability |
| missing block / unknown BlockEntity / NBT conflict | NOT TESTED | exact production NBT codec not implemented |
| external block edit | PASS | generic three-state transaction conflict |
| external biome edit | PASS | live biome three-state ownership plus durable-page GameTest |
| protected block | PASS | hardened foundation protection tests |
| missing/wrong-status chunk | PARTIAL | generation-free port tested; real region storage integration absent |
| negative coordinates | PASS | structure/chunk model tests use negatives |
| build height/world border | NOT TESTED | production preflight integration absent |
| cross-chunk/huge/overlapping structure | PARTIAL | overlap allocation tested; sandbox materialization absent |
| structure/flora/tree collision | PARTIAL | pass dependencies/reservations modeled; production ecology wiring absent |
| insufficient disk/publication/atomic move | PARTIAL | journal failure prevents write; durable store fallback tested |
| duplicate APPLY/ROLLBACK | PASS | idempotence tests |
| crash during APPLY/ROLLBACK/RECOVERY | PARTIAL | live block and biome durable-journal failure/reconcile coverage; no OS process kill |
| different tick budget/iteration order | PARTIAL | pure biome/structure order independence; full pipeline not integrated |
| representative large plan/bounded memory | NOT TESTED | streaming executor boundary exists; no measurement |

## PR #7 paged-plan substrate evidence

| Control | Status | Evidence / limitation |
|---|---|---|
| immutable exact sealed manifest | PASS | `PagedPlanStoreTest`; disk manifest is the post-page commit record |
| PLAN page owner/pass/dimension/sequence/count/checksum | PASS | incremental reader validates every reference against every page |
| missing/corrupt/trailing/wrong-owner page | PASS | adversarial store tests refuse reads |
| bounded generic reader | PASS | 3 × 50,003 entries; max one page/127 entries resident |
| lifecycle diagnostics | PASS | temporary, unreferenced, and referenced-missing files reported; no deletion |
| live BIOMES/GEOLOGY/ORES paged plan | NOT TESTED | production runtime is not wired to this store |
| live large-plan bounded memory | NOT TESTED | SavedData production collections remain |

## PR #8 live plan migration evidence

| Control | Status | Evidence / limitation |
|---|---|---|
| BIOMES/GEOLOGY/ORES exact paged production plan | PASS | live preview publishes sealed pages; apply requires exact UUID/root/pass/input |
| ORES exact upstream identity | PASS | persisted GEOLOGY UUID and manifest root are both required |
| SavedData production plan collections | PASS | schema 3 persists compact references only; older inline formats fail closed |
| live streaming plan apply | PASS | `readRange` decodes at most one 256-entry page and a bounded block batch |
| paged durable journals | PASS | all three passes publish journal pages before mutation |
| fully bounded live working set | FAIL | rollback ownership mirrors remain unbounded in SavedData |
| restart/process-kill certification | PARTIAL | reconciliation is idempotent; true process kill not tested |

## PR #9 durable journal and memory certification

| Control | Status | Evidence / limitation |
|---|---|---|
| SavedData ownership mirrors removed | PASS | schema/format 4 serialize compact journal identity/counters only |
| canonical complete journal identity | PASS | manifest binds journal/plan UUID, plan root, pass, dimension, area, ordered checksums and entry count |
| journal-before-write | PASS | manifest page commit and SavedData metadata save precede mutation |
| streaming APPLY recovery | PASS | durable pages reconcile BEFORE/AFTER/THIRD without replanning |
| streaming ROLLBACK | PASS | full validation/preflight then one-page traversal; cursor is advisory |
| missing/corrupt/conflicting page | PASS | unit and live missing-page GameTests fail closed |
| live bounded memory | PASS | GEOLOGY/ORES 600 entries and <=256 resident; 300-page store stress and <=7 resident |
| restart reconciliation | PASS | compact metadata recreation, cursor behind/ahead, duplicate rollback, BEFORE/AFTER/THIRD |
| true process kill | NOT TESTED | no kill -9 claim; mandatory before final River handoff |
| disk exhaustion/permissions | PARTIAL | integrity and publication faults covered; OS resource faults not injected |

## Environmental planning checkpoint

| Control | Status | Evidence / limitation |
|---|---|---|
| exact complete dependency graph | PASS | `PassGraph` and `EnvironmentalPipeline`; every upstream UUID/root is included in semantic identity |
| generic paged publication / bounded read | PASS | complete graph test; one decoded page and configured entry bound |
| structure opaque-generator isolation | PASS | only captured disposable-world results enter `StructureMaterializer`; unsupported status and envelope escape refuse |
| structure reservations | PASS | footprint, clearance, terrain-adaptation and owned-mutation zone model |
| deterministic flora/tree/habitat planning | PASS | positional decisions, registry allow-list, reservations and exact tree groups tested |
| deterministic complete validator | PARTIAL | missing plans, identity/profile/registry, collisions, protected/reserved zones and envelope violation implemented; biome fragmentation/density/aquatic support need production models |
| live Minecraft environmental passes | NOT TESTED | command/runtime integration remains BIOMES/GEOLOGY/ORES only |
| YUNG families | BLOCKED_EXTERNAL | no artifacts/APIs available in the declared dependency graph |
| true process kill | NOT TESTED | not executed |

## Environmental exact-artifact continuation

| Control | Status | Evidence / limitation |
|---|---|---|
| lossless environmental plan codec | PASS | exact pass/position, BEFORE+AFTER state, BEFORE+AFTER BlockEntity type/NBT, and group UUID round-trip through paged storage |
| mutation-group preflight | PASS (model) | complete supplied group is conflict-checked before durable journal callback and first write; duplicate positions/group mismatch refuse |
| exact complete manifest composition | PASS | existing first four manifests reused; environmental, habitat, and validation artifacts seal in `PassGraph.order()` |
| fatal validation gate | PASS | a fatal report cannot produce the VALIDATION manifest or sealed run |
| live `ServerLevel` environmental apply/rollback | FAIL | generic exact executor is not yet connected to Minecraft state/NBT and paged runtime journals |
| complete environmental gate | FAIL | preview planners, planning-world paged overlay, live commands, and end-to-end GameTests remain outstanding |
