# Phase 0 — Baseline audit

Starting SHA: `106e12683ed70919fc50d079a91bd18182265015`
Ending SHA: this report's checkpoint commit (recorded by Git history; a commit cannot embed its own SHA)
Actual branch: `codex/worldprep-code-complete-overnight`

## Objective

Audit the actual repository and establish a truthful safety baseline from the current fetched `main` snapshot.

## Existing state audited

The repository is a small vertical slice (869 Java lines at start), not a code-complete environmental compiler. BIOMES, GEOLOGY, and ORES have preview/apply/rollback command paths; terrain is sampled directly from Minecraft. Unit tests and GameTests exist for the slice.

Confirmed debt:

- `worldPrep.enabled` was fingerprinted but did not gate enqueue or execution.
- Biome apply persists the complete `SavedData` for each quart cell before writing.
- Operation dispatch relies on enum names and, in block runtime, ordinal-derived pass selection.
- Biome plans and snapshots are whole in-memory maps. Block plans/journals are whole in-memory maps with a 250,000-change ceiling.
- Rollback selects the latest matching biome snapshot rather than an explicit apply record.
- ORES is hardcoded and only loosely tied to GEOLOGY state.
- Biome and ore decisions are hardcoded rather than profile-driven.
- `cellsPerTick`, an execution parameter, is included in the semantic plan identity.
- Protection during biome apply can silently skip a sealed cell.
- Terrain sampling uses `getChunk`, which can generate/load missing chunks; there is no full-chunk refusal policy.
- There are no paged stores, sealed manifests, recovery reconciliation, environment revisions, pipeline runs, authoring registry, terrestrial/aquatic/habitat passes, PNG previews, or validation pass.

## Work completed

Recorded repository, remote configuration, starting SHA, branch, clean tree, source inventory, pinned platform, core runtime/persistence/planner code, tests, profiles, and documentation. Added the requested report directory and this baseline.

## Architecture added/changed

No runtime architecture changed in Phase 0.

## Main files changed

- `docs/overnight-worldprep/00_BASELINE.md`

## Persistence/schema changes

None.

## Tests executed

- `./gradlew test` — failed before Gradle startup because `gradle/wrapper/gradle-wrapper.jar` is absent.
- `gradle test` — run against the pinned dependencies using installed Gradle 8.14.4; result recorded below.
- `./gradlew build` and `./gradlew runGameTestServer` — wrapper blocker; installed Gradle alternatives are attempted in later phases after dependency preparation.

## Exact test results

`./gradlew test`: **FAIL**, `Unable to access jarfile .../gradle/wrapper/gradle-wrapper.jar`.

`gradle test`: dependency/toolchain setup was started against Minecraft 1.21.1 and NeoForge 21.1.248. The final outcome is retained in `/tmp/worldprep-phase0-gradle-test.log` during this environment session and is not claimed PASS until completion.

## Failures encountered

- GitHub fetch returned HTTP 403. `.git/FETCH_HEAD` records `106e126...` as branch `main` from the requested repository and the initial `work` branch pointed to that SHA.
- The committed Gradle wrapper JAR is missing.

## Fixes applied

Created the isolated preferred task branch from the recorded `main` SHA. Used installed Java 21/Gradle for real pinned-API compilation rather than fabricating a wrapper result.

## Remaining limitations

All code-complete features listed above remain pending. Real process-kill recovery and external MapDev acceptance are unavailable at baseline.

## Technical decisions

Safety hardening precedes new planners. New storage and planning components will be pure, bounded, deterministic, and unit-testable; Minecraft mutation integration will remain fail-closed where exact guarantees cannot be established.

## Git publication status

Remote `origin` was configured as `https://github.com/Rastaaslan/proutlost.git`; fetch/push is blocked by HTTP 403 in this environment at this checkpoint.

## Phase status

PARTIAL

## Consequences for next phase

Phase 1 must gate the feature, introduce explicit operation/pass identities, durable paged storage and manifests, and fail-closed recovery primitives before broadening pass coverage.
