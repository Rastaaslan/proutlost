# Phase 2 — Generic pass engine and planning pipeline

Starting SHA: `b8b8422`
Ending SHA: this report's checkpoint commit

## Objective
Introduce canonical pass/dependency identities and exact pipeline ownership.
## Existing state audited
The vertical slice dispatched separate latest plans and had no graph, overlay view, pipeline identity, or semantic/execution split.
## Work completed
Added all ten official pass IDs, their dependency graph and transitive invalidation, deterministic canonical order, immutable `PipelineRun`, overlay-first `PlanningWorldView`, canonical `SemanticConfig`, and fingerprint-excluded `ExecutionConfig`.
## Architecture added/changed
Apply-all now has a safe domain model: a sealed pipeline references exact plan UUIDs and has a root identity. Upstream planning can read immutable overlays before actual state.
## Main files changed
`pipeline/PassGraph.java`, `PipelineRun.java`, `PlanningWorldView.java`, `SemanticConfig.java`, `ExecutionConfig.java`, and `PipelineModelTest.java`.
## Persistence/schema changes
No live persistence migration; `PipelineRun` is ready for a future manifest repository.
## Tests executed
`gradle test` includes dependency, invalidation, overlay, and canonical semantic fingerprint tests.
## Exact test results
Final result is reported in Phase 7; no early PASS is claimed while the first pinned artifact build remains active.
## Failures encountered
Live planners are not abstracted behind the new engine.
## Fixes applied
Kept the model pure and immutable so it can be integrated without world access or ordering dependence.
## Remaining limitations
Profiles remain partly hardcoded; ore origins/MutationGroups and registry resolution are not implemented in the live runtime; preview-all is not wired.
## Technical decisions
Graph sets are immutable and pipeline fingerprint order is fixed, never HashMap iteration order.
## Git publication status
GitHub push unavailable (credentials/HTTP 403).
## Phase status
PARTIAL
## Consequences for next phase
Analysis and authoring can consume stable pass identities without coupling to Minecraft mutation APIs.
