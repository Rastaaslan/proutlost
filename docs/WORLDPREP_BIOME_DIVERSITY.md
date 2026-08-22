# WorldPrep — Dynamic biome diversity budgets

This document is a normative WorldPrep specification addendum for biome planning and production profiles.

## Objective

WorldPrep must support large biome catalogs such as Biomes O' Plenty without turning the Proutlost archipelago into a patchwork of many tiny unrelated biomes.

Biome diversity is therefore controlled by **dynamic budgets per semantic biome family**, rather than by blindly making every compatible registered biome available everywhere.

## Semantic-family budgets

Each semantic family (for example `lowland_forest`, `open_lowland`, `wetland`, `highland`, `mountain`, `coastal`, `marine`) may define:

- `minDistinctBiomes` — minimum number of distinct biome IDs when enough eligible terrain exists;
- `targetDistinctBiomes` — preferred diversity target;
- `maxDistinctBiomes` — hard upper bound;
- `preferredAreaPerBiome` — amount of eligible terrain that normally justifies one additional distinct biome;
- `maxRegions` — optional maximum number of spatially separate regions produced for that family.

The selected number of distinct biomes must adapt to the **actual eligible terrain area**. A tiny forest should not be forced to contain four forest biomes merely because the profile target is four; a very large forest may use more diversity, but never beyond the configured maximum.

Conceptually:

```text
eligible area
    ↓
dynamic diversity budget
    ↓
clamp(minimum, target derived from area, maximum)
```

Exact formulae are implementation details, but they must remain deterministic for the same terrain, profile, configuration, registry state and seed.

## Distinct biome count vs region count

WorldPrep must distinguish:

1. **distinct biome IDs** used by a semantic family;
2. **number of spatial regions/patches** occupied by those biomes.

The same biome may appear in more than one coherent region without consuming another distinct-biome slot.

The planner must prefer a small number of large coherent regions over biome confetti.

## Archipelago and island-level budgets

Biome diversity may be constrained at multiple scales:

- global archipelago pool;
- main island;
- secondary island or connected-island group;
- semantic family inside those areas.

A production profile may therefore define a global pool of, for example, five forest biome IDs while allowing the main island to use up to four of them and a small secondary island to use only one or two.

This is intended to give islands recognizable environmental identities while preserving overall archipelago coherence.

## Macro-climate compatibility

Before selecting concrete biome IDs, WorldPrep should be able to classify broad environmental/macro-climate tendencies such as:

- `TEMPERATE`;
- `COOL`;
- `HUMID`;
- `DRY`;
- `WIND_EXPOSED`.

Concrete biome candidates should then be selected from the intersection of:

```text
terrain semantics
+ macro climate
+ production profile
+ dynamic diversity budget
+ available runtime registry IDs
```

This prevents incoherent adjacency such as a humid subtropical forest immediately followed by a cold forest and then a dry biome solely because all three exist in the mod registry.

## Modded biome catalogs

Large biome mods, including Biomes O' Plenty, must remain **registry/profile driven**. WorldPrep core must not depend on their implementation classes.

A production profile may reference namespaced biome IDs such as modded biome IDs and provide vanilla fallbacks. Only biomes that resolve in the active Minecraft biome registry are eligible.

The existence of a registered biome does **not** automatically make it eligible for use. It must belong to an allowed semantic/macro-climate candidate pool in the active WorldPrep profile.

## Spatial coherence

After selecting the allowed biome pool, WorldPrep must create coherent regions with natural-looking boundaries. It should avoid:

- tiny isolated biome cells;
- regular grids;
- rapid alternating biome stripes;
- excessive diversity over short distances;
- using every installed biome merely because it exists.

Boundary generation and smoothing must remain deterministic.

## Planning order

The intended biome-planning flow is:

```text
TERRAIN ANALYSIS
      ↓
SEMANTIC FAMILY
      ↓
MACRO-CLIMATE
      ↓
DYNAMIC DIVERSITY BUDGET
      ↓
SELECT SMALL ELIGIBLE BIOME POOL
      ↓
ASSIGN LARGE COHERENT REGIONS
      ↓
BOUNDARY COHERENCE / SMOOTHING
      ↓
PERSISTED PREVIEW PLAN
```

The final concrete biome IDs and all diversity-budget decisions must be part of the persisted deterministic plan/fingerprint so that APPLY cannot silently select a different biome set from PREVIEW.

## Production-profile intent

The production profile should be capable of expressing values conceptually similar to:

```text
lowland_forest:
  minDistinctBiomes: 2
  targetDistinctBiomes: 4
  maxDistinctBiomes: 5
  preferredAreaPerBiome: 400 chunks
  maxRegions: 8

mountain:
  minDistinctBiomes: 1
  targetDistinctBiomes: 2
  maxDistinctBiomes: 3
```

These numbers are examples only and are not final Proutlost balancing values.

Final values must be tuned against the real archipelago size, island layout and installed biome catalog.
