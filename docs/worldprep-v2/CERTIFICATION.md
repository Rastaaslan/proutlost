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
| external biome edit | PARTIAL | generic semantics only; production biome runtime not migrated |
| protected block | PASS | hardened foundation protection tests |
| missing/wrong-status chunk | PARTIAL | generation-free port tested; real region storage integration absent |
| negative coordinates | PASS | structure/chunk model tests use negatives |
| build height/world border | NOT TESTED | production preflight integration absent |
| cross-chunk/huge/overlapping structure | PARTIAL | overlap allocation tested; sandbox materialization absent |
| structure/flora/tree collision | PARTIAL | pass dependencies/reservations modeled; production ecology wiring absent |
| insufficient disk/publication/atomic move | PARTIAL | journal failure prevents write; durable store fallback tested |
| duplicate APPLY/ROLLBACK | PASS | idempotence tests |
| crash during APPLY/ROLLBACK/RECOVERY | PARTIAL | injected journal crash and actual-state reconcile; no process kill |
| different tick budget/iteration order | PARTIAL | pure biome/structure order independence; full pipeline not integrated |
| representative large plan/bounded memory | NOT TESTED | streaming executor boundary exists; no measurement |
