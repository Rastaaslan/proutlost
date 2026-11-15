# J05 — Reconciliation: evidence cardinality and weather blob version

**Scope:** World Director 1.0.0 / API 1.0.2, Minecraft 1.21.1 / NeoForge 21.1.248. **Status:** TEST_ONLY; native tests added but not executed at this checkpoint.

## Defect and fail-closed rule

Weather, block transformation, and decorative object reconciliation previously selected a first *favorable* recovery proof. A list containing (a) a confirmed observation with an authentic owner token and (b) a contradictory observation for the same resource could therefore be classified as `SAME_POST` depending on evidence order. This would promote an ambiguous physical history into an authoritative recovered outcome.

For each resource being reconciled, exactly **one** matching `RecoveryEvidence.resource()` must exist. Zero evidence retains the adapter's existing unavailable/ambiguous behavior; more than one evidence, whether byte-identical or conflicting, yields `AMBIGUOUS`, preserving all loaded proofs. Other resources' proofs remain allowed in a multi-cell recovery. A single proof still needs the existing owner/epoch/hash checks. This rule is stricter than picking the first passing candidate and never grants a permit or mutates the world.

Weather inverse hydration additionally refuses a blob whose codec ID matches but version is not **1**. It must never reinterpret future-version bytes as a version-1 weather state.

## Regression coverage

Native GameTest fixtures have new rejection cases for contradictory same-resource proofs in both orders (vanilla weather and actual block mutation), plus a version-2 weather inverse blob. Compilation proves source compatibility only, **not** that these scenarios passed in Minecraft. Decorative binding follows the same cardinality rule; its runtime path still requires a dedicated native test.

## Safety and release

No J04 PREPARE/fsync or WAL format was changed. No test on Contra, user servers, or OBS is authorized by this patch. The Linux/ext4 native fresh-world and cold-restart same-WAL runs must be repeated on this exact commit when Contra B and RAM guards permit; until then the latest confirmed native revision is the earlier `8a827f6` checkpoint, not this code.

**Rollback:** first archive and hash the exact commit and test logs; if any regression is found, use an isolated source revert of the affected commit on FIXE after verifying the River WIP index remains untouched. Do not alter saved worlds or weaken J04 durability to force green tests.
