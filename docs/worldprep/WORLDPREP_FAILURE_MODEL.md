# WorldPrep failure model

Every entry is normative; `IMPLEMENTED` means an automated control exists, while `SPECIFIED` remains fail-closed and must not be advertised as certified.

## WP-001 — missing chunk
- **ID:** WP-001
- **SEVERITY:** CRITICAL
- **FAILURE:** missing chunk.
- **PREVENTION:** Canonical sealed input, bounded preflight, and explicit eligibility checks.
- **DETECTION:** Validate identity, bounds, ownership, persistence integrity, and actual current state before mutation.
- **SAFE RESPONSE:** `CLEAN_REFUSAL`.
- **RECOVERY:** Correct the input or restore the verified prerequisite, then create a new exact plan.
- **AUTOMATED TEST:** `WorldPrepV2SafetyTest` / phase-specific integration test required.
- **STATUS:** SPECIFIED; certification matrix tracks implementation.

## WP-002 — incomplete ChunkStatus
- **ID:** WP-002
- **SEVERITY:** CRITICAL
- **FAILURE:** incomplete ChunkStatus.
- **PREVENTION:** Canonical sealed input, bounded preflight, and explicit eligibility checks.
- **DETECTION:** Validate identity, bounds, ownership, persistence integrity, and actual current state before mutation.
- **SAFE RESPONSE:** `CLEAN_REFUSAL`.
- **RECOVERY:** Correct the input or restore the verified prerequisite, then create a new exact plan.
- **AUTOMATED TEST:** `WorldPrepV2SafetyTest` / phase-specific integration test required.
- **STATUS:** SPECIFIED; certification matrix tracks implementation.

## WP-003 — world border
- **ID:** WP-003
- **SEVERITY:** HIGH
- **FAILURE:** world border.
- **PREVENTION:** Canonical sealed input, bounded preflight, and explicit eligibility checks.
- **DETECTION:** Validate identity, bounds, ownership, persistence integrity, and actual current state before mutation.
- **SAFE RESPONSE:** `CLEAN_REFUSAL`.
- **RECOVERY:** Correct the input or restore the verified prerequisite, then create a new exact plan.
- **AUTOMATED TEST:** `WorldPrepV2SafetyTest` / phase-specific integration test required.
- **STATUS:** SPECIFIED; certification matrix tracks implementation.

## WP-004 — build height
- **ID:** WP-004
- **SEVERITY:** HIGH
- **FAILURE:** build height.
- **PREVENTION:** Canonical sealed input, bounded preflight, and explicit eligibility checks.
- **DETECTION:** Validate identity, bounds, ownership, persistence integrity, and actual current state before mutation.
- **SAFE RESPONSE:** `CLEAN_REFUSAL`.
- **RECOVERY:** Correct the input or restore the verified prerequisite, then create a new exact plan.
- **AUTOMATED TEST:** `WorldPrepV2SafetyTest` / phase-specific integration test required.
- **STATUS:** SPECIFIED; certification matrix tracks implementation.

## WP-005 — negative coordinates
- **ID:** WP-005
- **SEVERITY:** MEDIUM
- **FAILURE:** negative coordinates.
- **PREVENTION:** Canonical sealed input, bounded preflight, and explicit eligibility checks.
- **DETECTION:** Validate identity, bounds, ownership, persistence integrity, and actual current state before mutation.
- **SAFE RESPONSE:** `CLEAN_REFUSAL`.
- **RECOVERY:** Correct the input or restore the verified prerequisite, then create a new exact plan.
- **AUTOMATED TEST:** `WorldPrepV2SafetyTest` / phase-specific integration test required.
- **STATUS:** SPECIFIED; certification matrix tracks implementation.

## WP-006 — stale terrain
- **ID:** WP-006
- **SEVERITY:** CRITICAL
- **FAILURE:** stale terrain.
- **PREVENTION:** Canonical sealed input, bounded preflight, and explicit eligibility checks.
- **DETECTION:** Validate identity, bounds, ownership, persistence integrity, and actual current state before mutation.
- **SAFE RESPONSE:** `CLEAN_REFUSAL`.
- **RECOVERY:** Correct the input or restore the verified prerequisite, then create a new exact plan.
- **AUTOMATED TEST:** `WorldPrepV2SafetyTest` / phase-specific integration test required.
- **STATUS:** SPECIFIED; certification matrix tracks implementation.

## WP-007 — external block edit
- **ID:** WP-007
- **SEVERITY:** CRITICAL
- **FAILURE:** external block edit.
- **PREVENTION:** Canonical sealed input, bounded preflight, and explicit eligibility checks.
- **DETECTION:** Validate identity, bounds, ownership, persistence integrity, and actual current state before mutation.
- **SAFE RESPONSE:** `CLEAN_REFUSAL`.
- **RECOVERY:** Correct the input or restore the verified prerequisite, then create a new exact plan.
- **AUTOMATED TEST:** `WorldPrepV2SafetyTest` / phase-specific integration test required.
- **STATUS:** SPECIFIED; certification matrix tracks implementation.

## WP-008 — external biome edit
- **ID:** WP-008
- **SEVERITY:** CRITICAL
- **FAILURE:** external biome edit.
- **PREVENTION:** Canonical sealed input, bounded preflight, and explicit eligibility checks.
- **DETECTION:** Validate identity, bounds, ownership, persistence integrity, and actual current state before mutation.
- **SAFE RESPONSE:** `CLEAN_REFUSAL`.
- **RECOVERY:** Correct the input or restore the verified prerequisite, then create a new exact plan.
- **AUTOMATED TEST:** `WorldPrepV2SafetyTest` / phase-specific integration test required.
- **STATUS:** SPECIFIED; certification matrix tracks implementation.

## WP-009 — protected location
- **ID:** WP-009
- **SEVERITY:** CRITICAL
- **FAILURE:** protected location.
- **PREVENTION:** Canonical sealed input, bounded preflight, and explicit eligibility checks.
- **DETECTION:** Validate identity, bounds, ownership, persistence integrity, and actual current state before mutation.
- **SAFE RESPONSE:** `CLEAN_REFUSAL`.
- **RECOVERY:** Correct the input or restore the verified prerequisite, then create a new exact plan.
- **AUTOMATED TEST:** `WorldPrepV2SafetyTest` / phase-specific integration test required.
- **STATUS:** SPECIFIED; certification matrix tracks implementation.

## WP-010 — unknown BlockEntity
- **ID:** WP-010
- **SEVERITY:** CRITICAL
- **FAILURE:** unknown BlockEntity.
- **PREVENTION:** Canonical sealed input, bounded preflight, and explicit eligibility checks.
- **DETECTION:** Validate identity, bounds, ownership, persistence integrity, and actual current state before mutation.
- **SAFE RESPONSE:** `CLEAN_REFUSAL`.
- **RECOVERY:** Correct the input or restore the verified prerequisite, then create a new exact plan.
- **AUTOMATED TEST:** `WorldPrepV2SafetyTest` / phase-specific integration test required.
- **STATUS:** SPECIFIED; certification matrix tracks implementation.

## WP-011 — NBT mismatch
- **ID:** WP-011
- **SEVERITY:** CRITICAL
- **FAILURE:** NBT mismatch.
- **PREVENTION:** Canonical sealed input, bounded preflight, and explicit eligibility checks.
- **DETECTION:** Validate identity, bounds, ownership, persistence integrity, and actual current state before mutation.
- **SAFE RESPONSE:** `CLEAN_REFUSAL`.
- **RECOVERY:** Correct the input or restore the verified prerequisite, then create a new exact plan.
- **AUTOMATED TEST:** `WorldPrepV2SafetyTest` / phase-specific integration test required.
- **STATUS:** SPECIFIED; certification matrix tracks implementation.

## WP-012 — corrupt plan
- **ID:** WP-012
- **SEVERITY:** CRITICAL
- **FAILURE:** corrupt plan.
- **PREVENTION:** Canonical sealed input, bounded preflight, and explicit eligibility checks.
- **DETECTION:** Validate identity, bounds, ownership, persistence integrity, and actual current state before mutation.
- **SAFE RESPONSE:** `RECOVERY_REQUIRED`.
- **RECOVERY:** Reconcile durable records against actual state; never trust a cursor alone.
- **AUTOMATED TEST:** `WorldPrepV2SafetyTest` / phase-specific integration test required.
- **STATUS:** SPECIFIED; certification matrix tracks implementation.

## WP-013 — corrupt journal
- **ID:** WP-013
- **SEVERITY:** CRITICAL
- **FAILURE:** corrupt journal.
- **PREVENTION:** Canonical sealed input, bounded preflight, and explicit eligibility checks.
- **DETECTION:** Validate identity, bounds, ownership, persistence integrity, and actual current state before mutation.
- **SAFE RESPONSE:** `RECOVERY_REQUIRED`.
- **RECOVERY:** Reconcile durable records against actual state; never trust a cursor alone.
- **AUTOMATED TEST:** `WorldPrepV2SafetyTest` / phase-specific integration test required.
- **STATUS:** SPECIFIED; certification matrix tracks implementation.

## WP-014 — truncated page
- **ID:** WP-014
- **SEVERITY:** CRITICAL
- **FAILURE:** truncated page.
- **PREVENTION:** Canonical sealed input, bounded preflight, and explicit eligibility checks.
- **DETECTION:** Validate identity, bounds, ownership, persistence integrity, and actual current state before mutation.
- **SAFE RESPONSE:** `RECOVERY_REQUIRED`.
- **RECOVERY:** Reconcile durable records against actual state; never trust a cursor alone.
- **AUTOMATED TEST:** `WorldPrepV2SafetyTest` / phase-specific integration test required.
- **STATUS:** SPECIFIED; certification matrix tracks implementation.

## WP-015 — missing page
- **ID:** WP-015
- **SEVERITY:** CRITICAL
- **FAILURE:** missing page.
- **PREVENTION:** Canonical sealed input, bounded preflight, and explicit eligibility checks.
- **DETECTION:** Validate identity, bounds, ownership, persistence integrity, and actual current state before mutation.
- **SAFE RESPONSE:** `RECOVERY_REQUIRED`.
- **RECOVERY:** Reconcile durable records against actual state; never trust a cursor alone.
- **AUTOMATED TEST:** `WorldPrepV2SafetyTest` / phase-specific integration test required.
- **STATUS:** SPECIFIED; certification matrix tracks implementation.

## WP-016 — duplicate page
- **ID:** WP-016
- **SEVERITY:** CRITICAL
- **FAILURE:** duplicate page.
- **PREVENTION:** Canonical sealed input, bounded preflight, and explicit eligibility checks.
- **DETECTION:** Validate identity, bounds, ownership, persistence integrity, and actual current state before mutation.
- **SAFE RESPONSE:** `RECOVERY_REQUIRED`.
- **RECOVERY:** Reconcile durable records against actual state; never trust a cursor alone.
- **AUTOMATED TEST:** `WorldPrepV2SafetyTest` / phase-specific integration test required.
- **STATUS:** SPECIFIED; certification matrix tracks implementation.

## WP-017 — wrong sequence
- **ID:** WP-017
- **SEVERITY:** CRITICAL
- **FAILURE:** wrong sequence.
- **PREVENTION:** Canonical sealed input, bounded preflight, and explicit eligibility checks.
- **DETECTION:** Validate identity, bounds, ownership, persistence integrity, and actual current state before mutation.
- **SAFE RESPONSE:** `RECOVERY_REQUIRED`.
- **RECOVERY:** Reconcile durable records against actual state; never trust a cursor alone.
- **AUTOMATED TEST:** `WorldPrepV2SafetyTest` / phase-specific integration test required.
- **STATUS:** SPECIFIED; certification matrix tracks implementation.

## WP-018 — unsupported format
- **ID:** WP-018
- **SEVERITY:** CRITICAL
- **FAILURE:** unsupported format.
- **PREVENTION:** Canonical sealed input, bounded preflight, and explicit eligibility checks.
- **DETECTION:** Validate identity, bounds, ownership, persistence integrity, and actual current state before mutation.
- **SAFE RESPONSE:** `RECOVERY_REQUIRED`.
- **RECOVERY:** Reconcile durable records against actual state; never trust a cursor alone.
- **AUTOMATED TEST:** `WorldPrepV2SafetyTest` / phase-specific integration test required.
- **STATUS:** SPECIFIED; certification matrix tracks implementation.

## WP-019 — low disk
- **ID:** WP-019
- **SEVERITY:** CRITICAL
- **FAILURE:** low disk.
- **PREVENTION:** Canonical sealed input, bounded preflight, and explicit eligibility checks.
- **DETECTION:** Validate identity, bounds, ownership, persistence integrity, and actual current state before mutation.
- **SAFE RESPONSE:** `RECOVERY_REQUIRED`.
- **RECOVERY:** Reconcile durable records against actual state; never trust a cursor alone.
- **AUTOMATED TEST:** `WorldPrepV2SafetyTest` / phase-specific integration test required.
- **STATUS:** SPECIFIED; certification matrix tracks implementation.

## WP-020 — disk full
- **ID:** WP-020
- **SEVERITY:** CRITICAL
- **FAILURE:** disk full.
- **PREVENTION:** Canonical sealed input, bounded preflight, and explicit eligibility checks.
- **DETECTION:** Validate identity, bounds, ownership, persistence integrity, and actual current state before mutation.
- **SAFE RESPONSE:** `RECOVERY_REQUIRED`.
- **RECOVERY:** Reconcile durable records against actual state; never trust a cursor alone.
- **AUTOMATED TEST:** `WorldPrepV2SafetyTest` / phase-specific integration test required.
- **STATUS:** SPECIFIED; certification matrix tracks implementation.

## WP-021 — permissions
- **ID:** WP-021
- **SEVERITY:** CRITICAL
- **FAILURE:** permissions.
- **PREVENTION:** Canonical sealed input, bounded preflight, and explicit eligibility checks.
- **DETECTION:** Validate identity, bounds, ownership, persistence integrity, and actual current state before mutation.
- **SAFE RESPONSE:** `RECOVERY_REQUIRED`.
- **RECOVERY:** Reconcile durable records against actual state; never trust a cursor alone.
- **AUTOMATED TEST:** `WorldPrepV2SafetyTest` / phase-specific integration test required.
- **STATUS:** SPECIFIED; certification matrix tracks implementation.

## WP-022 — atomic-move unsupported
- **ID:** WP-022
- **SEVERITY:** HIGH
- **FAILURE:** atomic-move unsupported.
- **PREVENTION:** Canonical sealed input, bounded preflight, and explicit eligibility checks.
- **DETECTION:** Validate identity, bounds, ownership, persistence integrity, and actual current state before mutation.
- **SAFE RESPONSE:** `RECOVERY_REQUIRED`.
- **RECOVERY:** Reconcile durable records against actual state; never trust a cursor alone.
- **AUTOMATED TEST:** `WorldPrepV2SafetyTest` / phase-specific integration test required.
- **STATUS:** SPECIFIED; certification matrix tracks implementation.

## WP-023 — crash during APPLY
- **ID:** WP-023
- **SEVERITY:** CRITICAL
- **FAILURE:** crash during APPLY.
- **PREVENTION:** Canonical sealed input, bounded preflight, and explicit eligibility checks.
- **DETECTION:** Validate identity, bounds, ownership, persistence integrity, and actual current state before mutation.
- **SAFE RESPONSE:** `RECOVERY_REQUIRED`.
- **RECOVERY:** Reconcile durable records against actual state; never trust a cursor alone.
- **AUTOMATED TEST:** `WorldPrepV2SafetyTest` / phase-specific integration test required.
- **STATUS:** SPECIFIED; certification matrix tracks implementation.

## WP-024 — crash during ROLLBACK
- **ID:** WP-024
- **SEVERITY:** CRITICAL
- **FAILURE:** crash during ROLLBACK.
- **PREVENTION:** Canonical sealed input, bounded preflight, and explicit eligibility checks.
- **DETECTION:** Validate identity, bounds, ownership, persistence integrity, and actual current state before mutation.
- **SAFE RESPONSE:** `RECOVERY_REQUIRED`.
- **RECOVERY:** Reconcile durable records against actual state; never trust a cursor alone.
- **AUTOMATED TEST:** `WorldPrepV2SafetyTest` / phase-specific integration test required.
- **STATUS:** SPECIFIED; certification matrix tracks implementation.

## WP-025 — crash during RECOVERY
- **ID:** WP-025
- **SEVERITY:** CRITICAL
- **FAILURE:** crash during RECOVERY.
- **PREVENTION:** Canonical sealed input, bounded preflight, and explicit eligibility checks.
- **DETECTION:** Validate identity, bounds, ownership, persistence integrity, and actual current state before mutation.
- **SAFE RESPONSE:** `RECOVERY_REQUIRED`.
- **RECOVERY:** Reconcile durable records against actual state; never trust a cursor alone.
- **AUTOMATED TEST:** `WorldPrepV2SafetyTest` / phase-specific integration test required.
- **STATUS:** SPECIFIED; certification matrix tracks implementation.

## WP-026 — missing mod
- **ID:** WP-026
- **SEVERITY:** CRITICAL
- **FAILURE:** missing mod.
- **PREVENTION:** Canonical sealed input, bounded preflight, and explicit eligibility checks.
- **DETECTION:** Validate identity, bounds, ownership, persistence integrity, and actual current state before mutation.
- **SAFE RESPONSE:** `CLEAN_REFUSAL`.
- **RECOVERY:** Correct the input or restore the verified prerequisite, then create a new exact plan.
- **AUTOMATED TEST:** `WorldPrepV2SafetyTest` / phase-specific integration test required.
- **STATUS:** SPECIFIED; certification matrix tracks implementation.

## WP-027 — changed mod
- **ID:** WP-027
- **SEVERITY:** CRITICAL
- **FAILURE:** changed mod.
- **PREVENTION:** Canonical sealed input, bounded preflight, and explicit eligibility checks.
- **DETECTION:** Validate identity, bounds, ownership, persistence integrity, and actual current state before mutation.
- **SAFE RESPONSE:** `CLEAN_REFUSAL`.
- **RECOVERY:** Correct the input or restore the verified prerequisite, then create a new exact plan.
- **AUTOMATED TEST:** `WorldPrepV2SafetyTest` / phase-specific integration test required.
- **STATUS:** SPECIFIED; certification matrix tracks implementation.

## WP-028 — changed JAR same version
- **ID:** WP-028
- **SEVERITY:** CRITICAL
- **FAILURE:** changed JAR same version.
- **PREVENTION:** Canonical sealed input, bounded preflight, and explicit eligibility checks.
- **DETECTION:** Validate identity, bounds, ownership, persistence integrity, and actual current state before mutation.
- **SAFE RESPONSE:** `CLEAN_REFUSAL`.
- **RECOVERY:** Correct the input or restore the verified prerequisite, then create a new exact plan.
- **AUTOMATED TEST:** `WorldPrepV2SafetyTest` / phase-specific integration test required.
- **STATUS:** SPECIFIED; certification matrix tracks implementation.

## WP-029 — changed config
- **ID:** WP-029
- **SEVERITY:** CRITICAL
- **FAILURE:** changed config.
- **PREVENTION:** Canonical sealed input, bounded preflight, and explicit eligibility checks.
- **DETECTION:** Validate identity, bounds, ownership, persistence integrity, and actual current state before mutation.
- **SAFE RESPONSE:** `CLEAN_REFUSAL`.
- **RECOVERY:** Correct the input or restore the verified prerequisite, then create a new exact plan.
- **AUTOMATED TEST:** `WorldPrepV2SafetyTest` / phase-specific integration test required.
- **STATUS:** SPECIFIED; certification matrix tracks implementation.

## WP-030 — changed datapack
- **ID:** WP-030
- **SEVERITY:** CRITICAL
- **FAILURE:** changed datapack.
- **PREVENTION:** Canonical sealed input, bounded preflight, and explicit eligibility checks.
- **DETECTION:** Validate identity, bounds, ownership, persistence integrity, and actual current state before mutation.
- **SAFE RESPONSE:** `CLEAN_REFUSAL`.
- **RECOVERY:** Correct the input or restore the verified prerequisite, then create a new exact plan.
- **AUTOMATED TEST:** `WorldPrepV2SafetyTest` / phase-specific integration test required.
- **STATUS:** SPECIFIED; certification matrix tracks implementation.

## WP-031 — changed registry
- **ID:** WP-031
- **SEVERITY:** CRITICAL
- **FAILURE:** changed registry.
- **PREVENTION:** Canonical sealed input, bounded preflight, and explicit eligibility checks.
- **DETECTION:** Validate identity, bounds, ownership, persistence integrity, and actual current state before mutation.
- **SAFE RESPONSE:** `CLEAN_REFUSAL`.
- **RECOVERY:** Correct the input or restore the verified prerequisite, then create a new exact plan.
- **AUTOMATED TEST:** `WorldPrepV2SafetyTest` / phase-specific integration test required.
- **STATUS:** SPECIFIED; certification matrix tracks implementation.

## WP-032 — missing biome
- **ID:** WP-032
- **SEVERITY:** CRITICAL
- **FAILURE:** missing biome.
- **PREVENTION:** Canonical sealed input, bounded preflight, and explicit eligibility checks.
- **DETECTION:** Validate identity, bounds, ownership, persistence integrity, and actual current state before mutation.
- **SAFE RESPONSE:** `CLEAN_REFUSAL`.
- **RECOVERY:** Correct the input or restore the verified prerequisite, then create a new exact plan.
- **AUTOMATED TEST:** `WorldPrepV2SafetyTest` / phase-specific integration test required.
- **STATUS:** SPECIFIED; certification matrix tracks implementation.

## WP-033 — missing block
- **ID:** WP-033
- **SEVERITY:** CRITICAL
- **FAILURE:** missing block.
- **PREVENTION:** Canonical sealed input, bounded preflight, and explicit eligibility checks.
- **DETECTION:** Validate identity, bounds, ownership, persistence integrity, and actual current state before mutation.
- **SAFE RESPONSE:** `CLEAN_REFUSAL`.
- **RECOVERY:** Correct the input or restore the verified prerequisite, then create a new exact plan.
- **AUTOMATED TEST:** `WorldPrepV2SafetyTest` / phase-specific integration test required.
- **STATUS:** SPECIFIED; certification matrix tracks implementation.

## WP-034 — missing structure
- **ID:** WP-034
- **SEVERITY:** CRITICAL
- **FAILURE:** missing structure.
- **PREVENTION:** Canonical sealed input, bounded preflight, and explicit eligibility checks.
- **DETECTION:** Validate identity, bounds, ownership, persistence integrity, and actual current state before mutation.
- **SAFE RESPONSE:** `CLEAN_REFUSAL`.
- **RECOVERY:** Correct the input or restore the verified prerequisite, then create a new exact plan.
- **AUTOMATED TEST:** `WorldPrepV2SafetyTest` / phase-specific integration test required.
- **STATUS:** SPECIFIED; certification matrix tracks implementation.

## WP-035 — opaque structure
- **ID:** WP-035
- **SEVERITY:** CRITICAL
- **FAILURE:** opaque structure.
- **PREVENTION:** Canonical sealed input, bounded preflight, and explicit eligibility checks.
- **DETECTION:** Validate identity, bounds, ownership, persistence integrity, and actual current state before mutation.
- **SAFE RESPONSE:** `CLEAN_REFUSAL`.
- **RECOVERY:** Correct the input or restore the verified prerequisite, then create a new exact plan.
- **AUTOMATED TEST:** `WorldPrepV2SafetyTest` / phase-specific integration test required.
- **STATUS:** SPECIFIED; certification matrix tracks implementation.

## WP-036 — structure overlap
- **ID:** WP-036
- **SEVERITY:** HIGH
- **FAILURE:** structure overlap.
- **PREVENTION:** Canonical sealed input, bounded preflight, and explicit eligibility checks.
- **DETECTION:** Validate identity, bounds, ownership, persistence integrity, and actual current state before mutation.
- **SAFE RESPONSE:** `CLEAN_REFUSAL`.
- **RECOVERY:** Correct the input or restore the verified prerequisite, then create a new exact plan.
- **AUTOMATED TEST:** `WorldPrepV2SafetyTest` / phase-specific integration test required.
- **STATUS:** SPECIFIED; certification matrix tracks implementation.

## WP-037 — structure/flora collision
- **ID:** WP-037
- **SEVERITY:** HIGH
- **FAILURE:** structure/flora collision.
- **PREVENTION:** Canonical sealed input, bounded preflight, and explicit eligibility checks.
- **DETECTION:** Validate identity, bounds, ownership, persistence integrity, and actual current state before mutation.
- **SAFE RESPONSE:** `CLEAN_REFUSAL`.
- **RECOVERY:** Correct the input or restore the verified prerequisite, then create a new exact plan.
- **AUTOMATED TEST:** `WorldPrepV2SafetyTest` / phase-specific integration test required.
- **STATUS:** SPECIFIED; certification matrix tracks implementation.

## WP-038 — structure/tree collision
- **ID:** WP-038
- **SEVERITY:** HIGH
- **FAILURE:** structure/tree collision.
- **PREVENTION:** Canonical sealed input, bounded preflight, and explicit eligibility checks.
- **DETECTION:** Validate identity, bounds, ownership, persistence integrity, and actual current state before mutation.
- **SAFE RESPONSE:** `CLEAN_REFUSAL`.
- **RECOVERY:** Correct the input or restore the verified prerequisite, then create a new exact plan.
- **AUTOMATED TEST:** `WorldPrepV2SafetyTest` / phase-specific integration test required.
- **STATUS:** SPECIFIED; certification matrix tracks implementation.

## WP-039 — different tick budget
- **ID:** WP-039
- **SEVERITY:** HIGH
- **FAILURE:** different tick budget.
- **PREVENTION:** Canonical sealed input, bounded preflight, and explicit eligibility checks.
- **DETECTION:** Validate identity, bounds, ownership, persistence integrity, and actual current state before mutation.
- **SAFE RESPONSE:** `CLEAN_REFUSAL`.
- **RECOVERY:** Correct the input or restore the verified prerequisite, then create a new exact plan.
- **AUTOMATED TEST:** `WorldPrepV2SafetyTest` / phase-specific integration test required.
- **STATUS:** SPECIFIED; certification matrix tracks implementation.

## WP-040 — different iteration order
- **ID:** WP-040
- **SEVERITY:** HIGH
- **FAILURE:** different iteration order.
- **PREVENTION:** Canonical sealed input, bounded preflight, and explicit eligibility checks.
- **DETECTION:** Validate identity, bounds, ownership, persistence integrity, and actual current state before mutation.
- **SAFE RESPONSE:** `CLEAN_REFUSAL`.
- **RECOVERY:** Correct the input or restore the verified prerequisite, then create a new exact plan.
- **AUTOMATED TEST:** `WorldPrepV2SafetyTest` / phase-specific integration test required.
- **STATUS:** SPECIFIED; certification matrix tracks implementation.

## WP-041 — very large area
- **ID:** WP-041
- **SEVERITY:** HIGH
- **FAILURE:** very large area.
- **PREVENTION:** Canonical sealed input, bounded preflight, and explicit eligibility checks.
- **DETECTION:** Validate identity, bounds, ownership, persistence integrity, and actual current state before mutation.
- **SAFE RESPONSE:** `CLEAN_REFUSAL`.
- **RECOVERY:** Correct the input or restore the verified prerequisite, then create a new exact plan.
- **AUTOMATED TEST:** `WorldPrepV2SafetyTest` / phase-specific integration test required.
- **STATUS:** SPECIFIED; certification matrix tracks implementation.

## 2026-09-20 live BIOMES durability evidence
BIOMES now publishes a checksummed journal page before palette mutation and persists its exact sequence/checksum ownership reference. Rollback validates every referenced page before mutation; missing pages and legacy nonempty snapshots without durable references fail closed. This is real in-server fault injection, not evidence of an OS process-kill restart.

## 2026-09-20 paged-plan substrate evidence
`PagedPlanStore` implements incremental detection for WP-012, WP-014 through WP-018 and reports orphan temporary/unreferenced pages without destructive cleanup. Publication validates all immutable pages before atomically publishing the SEALED manifest commit record. These controls are substrate-only until all three live planners and executors stop using SavedData plan collections; a missing production plan page is therefore not yet a certified live failure path.

## 2026-09-20 production paged-plan integration
BIOMES, GEOLOGY, and ORES production plans now use exact UUID-addressed, sealed manifests. Apply rereads the durable manifest, requires pass/dimension/input/root identity, and streams a cursor window without recalculating decisions. Missing, corrupt, reordered, or mismatched plan pages refuse mutation. ORES additionally persists and requires both the exact upstream GEOLOGY plan UUID and root; there is no “latest geology” resolution. Schema-2 inline plans are rejected rather than guessed.

The durability gate is not yet complete: rollback journal entries are still mirrored in SavedData even though journal pages are published first and validated. This is a bounded-memory blocker, not a weakened ownership rule; ecology remains prohibited until rollback streams journal pages and the mirrors are removed.
