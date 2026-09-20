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
