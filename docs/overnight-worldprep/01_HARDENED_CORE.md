# Phase 1 — Hardened core / storage / recovery

Starting SHA: `d8f20dc`
Ending SHA: this report's checkpoint commit

## Objective
Establish fail-closed primitives before expanding pass coverage.

## Existing state audited
Both persistence implementations retained complete plans in `SavedData`; biome apply saved per cell, rollback used latest-snapshot lookup, and feature enablement was not enforced.

## Work completed
Added stable `PassId` and `OperationType`, `PageCursor`, a binary immutable plan/journal page codec, SHA-256 verification, bounded payloads, validated temp-file publication with fsync and atomic-move fallback, sealed plan manifest validation, explicit `ApplyRecord`/environment revisions, and shared three-state apply/rollback semantics. Enqueue and tick are now disabled when configuration is disabled.

## Architecture added/changed
`DurablePageStore` is disk-backed and reads one bounded page at a time. Pages carry format/kind/owner/pass/dimension/chunk/sequence/count/length/payload/checksum. `PlanManifest` validates canonical sequence and sealing. Recovery models explicitly include partial, recovery-required, reconciliation, and conflict states.

## Main files changed
`pipeline/PassId.java`, `pipeline/OperationType.java`, `storage/DurablePageStore.java`, `storage/PageCursor.java`, `storage/PlanManifest.java`, `recovery/ApplyRecord.java`, `recovery/MutationSemantics.java`, `runtime/WorldPrepRuntime.java`, and unit tests.

## Persistence/schema changes
New page format version 1 is introduced without migrating the legacy SavedData runtime. Unknown/truncated/corrupt pages fail closed. No legacy data is deleted.

## Tests executed
`gradle test` (real pinned dependency preparation and compilation); focused page/recovery tests are included in the suite.

## Exact test results
Final suite result is recorded by the later gate report. Baseline Gradle artifact preparation was still running while this checkpoint was authored; no PASS is claimed here.

## Failures encountered
The wrapper JAR is absent. Existing runtime integration is tightly coupled to legacy SavedData.

## Fixes applied
Used installed Gradle 8.14.4 and limited integration to safe enable gating while introducing isolated, tested migration targets.

## Remaining limitations
The live Minecraft BIOMES/block runtimes have not yet migrated to pages or ApplyRecord, biome save-per-cell remains, chunk acquisition/disk preflight/maintenance interaction hooks are incomplete, and crash recovery is modelled but not wired. Therefore this phase is not production-safe.

## Technical decisions
A bounded 16 MiB payload cap prevents hostile allocation. Publication never overwrites immutable targets. Directory fsync is best-effort because some filesystems do not support it; inability to validate a temporary page always aborts publication.

## Git publication status
Push attempted after checkpoint; GitHub access is HTTP 403.

## Phase status
PARTIAL

## Consequences for next phase
Pipeline identities can use stable pass IDs and sealed page references, but APPLY remains restricted to the legacy slice and must not be represented as code complete.
