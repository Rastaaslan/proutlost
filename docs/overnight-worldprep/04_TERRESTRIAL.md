# Phase 4 — Soils, flora, and trees

Starting SHA: `68cde54`
Ending SHA: this report's checkpoint commit
## Objective
Add deterministic, inspectable terrestrial planning primitives without unsafe opaque placement.
## Existing state audited
No soils/flora/trees implementation existed.
## Work completed
Added deterministic soil classification/depth, registry tag for replaceable soils, positional flora and tree candidate decisions with required modes, and immutable multi-position `MutationGroup` intent validation.
## Architecture added/changed
All decisions are pure. Tree/multi-block output must be fully enumerated as a group; no Minecraft feature placeur is invoked.
## Main files changed
`SoilPlanner.java`, `VegetationPlanner.java`, `MutationGroup.java`, soil tag, and `TerrestrialPlannerTest.java`.
## Persistence/schema changes
None; groups will be journal-page segments when integrated.
## Tests executed
`gradle test` includes soil, deterministic vegetation/manual-only, and duplicate group position tests.
## Exact test results
Reported in Phase 7 after execution completes.
## Failures encountered
No safe live application pipeline exists for these new pass outputs.
## Fixes applied
Refused opaque tree placement by providing only candidate/group primitives.
## Remaining limitations
No templates, leaf-state builder, support/canSurvive integration, cross-chunk acquisition, persisted plans, recovery, rollback, or preview-all wiring. Values are defaults in code pending full profile parsing.
## Technical decisions
Incomplete safe functionality remains inactive rather than risking the world.
## Git publication status
Not published: GitHub authentication unavailable.
## Phase status
PARTIAL
## Consequences for next phase
Aquatic planning and non-mutating habitats can follow the same pure deterministic model.
