# Phase 5 — Aquatic and habitats

Starting SHA: `ba67172`
Ending SHA: this report's checkpoint commit
## Objective
Provide deterministic aquatic staging and a non-mutating coarse habitat API.
## Existing state audited
Neither subsystem existed.
## Work completed
Added depth-classified aquatic substrate/vegetation decisions, immutable overflow-checked coarse `HabitatMap` with stable block query, and empty opt-in semantic fauna tags. Initial population remains OFF/not implemented.
## Architecture added/changed
Habitat data is detached from Minecraft and read-only. Aquatic output separates substrate identity from vegetation eligibility.
## Main files changed
`AquaticPlanner.java`, `HabitatMap.java`, fauna tag resources, and `AquaticHabitatTest.java`.
## Persistence/schema changes
No habitat persistence repository yet; `cellSize` is mandatory in the API model.
## Tests executed
`gradle test` includes aquatic depth/vegetation and negative-coordinate coarse query tests.
## Exact test results
Reported by Phase 7; no uncompleted run is labelled PASS.
## Failures encountered
Live pass pipeline integration remains absent.
## Fixes applied
All new code is pure, bounded, deterministic and disabled from world mutation.
## Remaining limitations
Registry resolution, light/water-column validation, protection/recovery/rollback integration, stored habitat staleness/upstream identity, and preview-all are incomplete.
## Technical decisions
Empty tags prevent accidental claims that arbitrary mod entities belong to semantic groups.
## Git publication status
Not published: GitHub authentication unavailable.
## Phase status
PARTIAL
## Consequences for next phase
Tooling may render immutable `HabitatMap`/persisted data but must not asynchronously read Minecraft.
