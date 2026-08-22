# PROUTLOST — WorldPrep Environmental Compiler Specification

## 1. Mission

Build a production-quality pre-production world environmental compiler named **WorldPrep** for Proutlost.

River manually creates the **topography** of the Proutlost archipelago.

WorldPrep analyzes that handmade terrain and turns it into a coherent Minecraft environment.

The intended pipeline is:

```text
RIVER TOPOGRAPHY
        ↓
TERRAIN ANALYSIS
        ↓
BIOMES
        ↓
GEOLOGY
        ↓
ORES / RESOURCES
        ↓
SOILS
        ↓
FLORA
        ↓
TREES
        ↓
AQUATIC ENVIRONMENT
        ↓
HABITATS / FAUNA PREPARATION
        ↓
VALIDATION
        ↓
PROUTLOST-READY MAP
```

WorldPrep must be:

- deterministic;
- safe;
- previewable;
- resumable;
- restart-safe;
- reversible;
- usable directly by River without Java knowledge.

WorldPrep is primarily a **map-development/admin tool**, not a live seasonal gameplay system.

---

# 2. Repository State

Connected GitHub repository:

```text
Rastaaslan/proutlost
```

Known initial remote state:

```text
4883a40c7a9c36cd79cbf55e35730755ca543bb2
Initialize repository
```

The repository intentionally starts essentially empty.

Previous Proutlost Core/Runtime implementations from other Codex sessions were lost and are not recoverable.

Previous WorldPrep task created no implementation.

Therefore:

- do not attempt to recover `6f73220`;
- do not attempt to recover `05485b7`;
- do not assume an existing Proutlost runtime;
- do not implement the World Director in this task;
- implement WorldPrep from zero with only the minimal reusable Proutlost/NeoForge foundation required for it.

---

# 3. Target Platform

Use:

```text
Minecraft 1.21.1
NeoForge 21.1.248
Java 21
```

Create a proper NeoForge Gradle project.

Required:

- Gradle wrapper;
- Java 21 toolchain;
- Minecraft 1.21.1;
- NeoForge 21.1.248;
- mod metadata;
- real NeoForge entry point;
- resources;
- unit tests;
- GameTest/integration support where practical.

Mod ID:

```text
proutlost
```

Base package:

```text
fr.proutlost
```

Do not silently change Minecraft or NeoForge versions.

Compile against the real pinned artifacts.

---

# 4. Durability / GitHub Publication

This requirement is critical.

A previous Proutlost implementation was lost because it remained only inside an ephemeral Codex workspace.

WorldPrep must not repeat this failure.

Before substantial implementation:

1. verify repository is `Rastaaslan/proutlost`;
2. verify the remote;
3. fetch remote state;
4. create a dedicated working branch, for example:

```text
codex/worldprep-environment
```

5. confirm that the work can be durably represented through the connected GitHub workflow.

Commit meaningful checkpoints throughout the task.

Suggested checkpoints:

```text
1. Bootstrap NeoForge and WorldPrep foundation
2. Terrain analysis and biome preview
3. Apply, snapshots, rollback and jobs
4. Geology, ores and soils
5. Flora, trees and aquatic environment
6. Habitats and LocationRegistry
7. Tests, River tooling and documentation
```

Do not perform hours of implementation as one uncommitted workspace.

At completion:

- source must exist in GitHub;
- branch/PR must contain actual source;
- perform a fresh checkout;
- verify files exist;
- run the build from the fresh checkout when environment permits.

A local-only commit is not considered durable completion.

---

# 5. Current MapDev Context

The actual external Proutlost MapDev server already exists.

Its initial world is an **Ocean Canvas**.

Known characteristics:

```text
Minecraft:           1.21.1
NeoForge:            21.1.248

Sea level:           Y=63
Initial biome:       minecraft:deep_ocean
Natural emergent land: none
Maximum natural solid terrain: approximately Y=52
Underwater terrain:  natural noise-based relief
Vanilla structures:  disabled
Game mode:           Creative
```

River manually creates islands and terrain from this ocean.

Important consequence:

A mountain manually raised by River may still have:

```text
minecraft:deep_ocean
```

as its biome.

Therefore:

> Existing biome data is NOT environmental truth.

WorldPrep must infer intended environment from actual terrain plus profile/configuration/overrides.

---

# 6. Responsibility Split

## River owns

- island shapes;
- mountains;
- valleys;
- cliffs;
- coastlines;
- rivers;
- lakes;
- caves and major underground volumes;
- paths and traversable terrain;
- artistic topology;
- important structures;
- narrative spaces;
- hand-authored decoration where desired.

## WorldPrep owns

- terrain analysis;
- biome assignment;
- geology;
- ore/resource population;
- soils;
- natural flora;
- trees;
- underwater environment;
- ecological habitat classification;
- environment validation.

WorldPrep assists River.

It does not replace River.

---

# 7. Explicit Non-Goals

Do NOT implement in this task:

- World Director;
- season progression;
- final narrative;
- Alpa/coma storyline;
- final Clairière puzzle;
- FTB Quests integration;
- JEI integration;
- Jade integration;
- Antique Atlas integration;
- WeatherCore integration;
- Corpse integration;
- full modpack compatibility layer;
- runtime ecological AI;
- dynamic biome mutation during the season;
- automatic cities;
- automatic ruins;
- procedural narrative structures.

Future systems may consume WorldPrep data.

WorldPrep itself remains a pre-production compiler.

---

# 8. WorldPrep Passes

Implement explicit passes:

```text
PASS 0 — TERRAIN
PASS 1 — BIOMES
PASS 2 — GEOLOGY
PASS 3 — ORES
PASS 4 — SOILS
PASS 5 — FLORA
PASS 6 — TREES
PASS 7 — AQUATIC
PASS 8 — HABITATS
PASS 9 — VALIDATION
```

Each pass must be independently configurable.

Where meaningful, support:

```text
OFF
ANALYZE
PREVIEW
APPLY
ROLLBACK
```

Example:

```text
biomes   = ON
geology  = ON
ores     = ON
soils    = ON
flora    = ON
trees    = OFF
aquatic  = ON
habitats = ON
```

This allows River to keep manual control of selected environmental layers.

---

# 9. Suggested Architecture

Use a clean package structure similar to:

```text
fr.proutlost.worldprep

WorldPrepService
WorldPrepConfig
WorldPrepProfile

area/
    WorldPrepArea
    WorldPrepSelection
    AreaMask

job/
    WorldPrepJob
    WorldPrepJobState
    WorldPrepJobManager

analysis/
    TerrainAnalyzer
    TerrainSample
    HeightField
    SlopeField
    CoastDistanceField
    WaterField
    MoistureField
    TerrainClassification

biome/
    BiomePlanner
    BiomePlan
    BiomePalette
    BiomeCandidate
    BiomeRegistryResolver

geology/
    GeologyPlanner
    GeologicalProvince
    RockPalette

ore/
    OrePlanner
    OreProfile
    OreVeinPlan
    OreRegistryResolver

soil/
    SoilPlanner
    SoilProfile

vegetation/
    FloraPlanner
    FloraProfile
    TreePlanner
    TreeProfile

aquatic/
    AquaticPlanner
    AquaticProfile

ecology/
    HabitatPlanner
    HabitatDefinition
    HabitatMap

location/
    LocationRegistry
    LocationDefinition
    LocationAnchor
    LocationZone

override/
    ProtectedZone
    WorldPrepOverride

plan/
    WorldPrepPlan
    WorldPrepPlanFingerprint

snapshot/
    WorldPrepSnapshot
    ChunkChangeJournal

preview/
    PreviewRenderer
    PreviewReport

persistence/
    WorldPrepSavedData

command/
    WorldPrepCommands
```

Exact names may improve where justified.

Avoid giant god classes.

---

# 10. Admin / Security Model

WorldPrep is server authoritative.

Normal players must not control destructive operations.

Admin/dev commands control:

- plans;
- profiles;
- snapshots;
- apply;
- rollback;
- job state;
- registry lookups;
- overrides;
- locations.

Do not trust clients for any authoritative WorldPrep state.

Validate all command input.

Do not allow arbitrary filesystem paths through commands.

---

# 11. Command Root

Implement:

```text
/proutlost worldprep
```

and:

```text
/proutlost location
```

for authored places.

---

# 12. Area Selection

Implement:

```text
/proutlost worldprep pos1
/proutlost worldprep pos2
```

Use the executing player's current position.

Then:

```text
/proutlost worldprep area create <id>
/proutlost worldprep area info <id>
/proutlost worldprep area list
/proutlost worldprep area delete <id>
```

Initial area implementation may use rectangular X/Z bounds.

Persist areas.

---

# 13. Area Masks

Do not treat every block in the selection rectangle identically.

Support masks such as:

```text
FULL_AREA
LAND
COAST
MARINE
LAND_AND_COAST
```

Architect for:

```text
CONNECTED_ISLAND
```

where an anchor identifies one connected island.

If CONNECTED_ISLAND is implemented now, traversal must be bounded and resumable.

---

# 14. Optional WorldEdit Convenience

WorldPrep must work without WorldEdit.

If WorldEdit is installed and a clean compatible API is available, optionally implement:

```text
/proutlost worldprep area from-worldedit <id>
```

Keep this behind an isolated optional bridge.

WorldEdit must not become a hard dependency.

---

# 15. Terrain Analysis

Analyze River's real terrain.

At minimum derive:

- surface Y;
- normalized altitude;
- local slope;
- local height variation;
- land/water state;
- coast distance;
- inland-water distance;
- water depth;
- terrain roughness;
- deterministic low-frequency noise.

Where practical also derive:

- valley tendency;
- ridge tendency;
- moisture proxy;
- exposure;
- drainage tendency.

The system must correctly classify terrain even when the current biome is wrong.

Example:

```text
terrain:
Y=150 mountain

current biome:
minecraft:deep_ocean

expected WorldPrep classification:
highland/mountain
```

---

# 16. Analysis Performance

Do NOT:

- scan the entire world;
- load every chunk at once;
- store millions of full BlockState objects;
- process the entire island synchronously on one tick.

Use:

- chunk batches;
- biome-cell granularity where appropriate;
- compact numeric fields;
- bounded caches;
- resumable jobs.

---

# 17. Determinism

WorldPrep profiles use stable seeds.

Same:

- terrain;
- profile;
- configuration;
- registry state;
- seed;
- overrides;
- protections;

must result in the same plan.

PREVIEW and APPLY must use the exact same persisted plan.

APPLY must never silently regenerate random decisions.

---

# 18. Fingerprints

Persist fingerprints for:

- terrain/chunks;
- configuration;
- profile;
- seed;
- registry content where useful;
- overrides;
- protected zones.

If terrain changes after preview:

```text
PREVIEW
↓
River modifies terrain
↓
APPLY
```

default behavior must be:

```text
REFUSE — PREVIEW STALE
```

Require a new preview unless explicitly forced by admin.

---

# 19. Data-Driven Profiles

Environment content must be data-driven.

Ship at least:

```text
proutlost:river_test
```

Later support:

```text
proutlost:production
```

Do not hardcode final environmental content into Java.

---

# 20. River Test Profile

`proutlost:river_test` must use vanilla content only.

It must work before the final modpack exists.

Aim for a believable temperate archipelago.

The exact palette is development-only and may change later.

---

# 21. Future Production Profile

Architecture must support profile content referencing:

- modded biomes;
- modded rocks;
- modded ores;
- modded soils;
- modded flora;
- modded trees;
- modded aquatic content;
- modded fauna classifications.

Changing profile content should not require recompiling Java.

---

# 22. Biome Planner

Biome choice may use:

- altitude;
- slope;
- roughness;
- coast distance;
- water proximity;
- moisture;
- macro climate;
- exposure;
- deterministic noise;
- manual overrides.

Avoid simplistic rules such as:

```text
Y < 80 = beach
Y < 120 = forest
Y > 120 = mountain
```

Altitude may influence strongly, but boundaries must remain natural.

Avoid tiny biome confetti.

Prefer large coherent regions.

---

# 23. Semantic Biome Palettes

Support semantic palettes such as:

```text
coastal
stony_coast
open_lowland
lowland_forest
dense_forest
wetland
river
highland
rocky_highland
mountain

marine_shallow
marine
marine_deep
```

A palette may contain multiple candidate biomes.

---

# 24. Vanilla and Modded Biomes

Mandatory.

Biomes use namespaced registry IDs.

Examples:

```text
minecraft:forest
minecraft:meadow
minecraft:deep_ocean
```

Future example:

```text
some_mod:temperate_rainforest
```

Do not import implementation classes from biome mods merely to use a registered biome.

Resolve through actual Minecraft runtime registries.

---

# 25. Biome Registry Validation

Every configured biome candidate resolves as:

```text
VALID
MISSING
FALLBACK
```

Never silently ignore missing biomes.

---

# 26. Biome Fallbacks

Support explicit fallback.

Example:

```json
{
  "id": "some_mod:temperate_rainforest",
  "fallback": "minecraft:forest"
}
```

If requested biome is missing:

use fallback and report it.

If both are missing:

the plan must fail validation before APPLY.

---

# 27. Marine Biome Classification

The Ocean Canvas starts as `minecraft:deep_ocean` everywhere.

WorldPrep should later differentiate marine areas based on:

- depth;
- coast distance;
- underwater slope;
- macro climate.

Conceptually:

```text
shallow coastline
→ marine_shallow

normal offshore water
→ marine

deep water
→ marine_deep
```

Future modded marine biomes must work through registry IDs.

---

# 28. Real Biome Writing

APPLY must modify actual Minecraft biome storage.

Do not merely save metadata.

Respect Minecraft 1.21.1 biome storage granularity.

Do not pretend biomes are independent block-by-block values if the real system uses quart-resolution palettes/cells.

Compile against actual API signatures.

---

# 29. Biome Vertical Modes

Support at minimum:

```text
FULL_COLUMN
```

Architect for:

```text
SURFACE_BAND
```

FULL_COLUMN is acceptable for initial River tests if correct.

---

# 30. Client Biome Refresh

Applied biome changes must eventually become visible to River.

Use correct chunk/biome synchronization if safely available.

If reconnect or chunk reload is needed, document it clearly.

Do not claim immediate visual update unless validated.

---

# 31. Geology Pass

River creates topology, not detailed underground geology.

WorldPrep may harmonize natural rock.

Support at least:

- stone;
- deepslate;
- granite;
- diorite;
- andesite;
- tuff;
- future modded rocks.

---

# 32. Geological Provinces

Support broad geological regions/provinces.

Examples:

```text
MIXED_STONE
GRANITIC
TUFF_RICH
VOLCANIC
SEDIMENTARY_LIKE
```

The goal is coherent Minecraft exploration, not real-world geological simulation.

---

# 33. Geology Inputs

Geology may depend on:

- depth;
- macro region;
- deterministic noise;
- terrain type;
- profile;
- River override.

---

# 34. Geology Safety

Only modify declared natural blocks.

Use tags such as:

```text
#proutlost:geology_replaceable
```

Unknown/custom blocks default to:

```text
DO NOT TOUCH
```

Never replace:

- block entities;
- chests;
- handcrafted structures;
- narrative locations;
- protected zones;
- arbitrary custom blocks.

---

# 35. Ore Pass

The ore/resource pass is mandatory.

River-created islands would otherwise have manually placed stone but no coherent generated resources.

WorldPrep must populate natural terrain after topology creation.

---

# 36. Ore Profiles

Each resource profile should support:

- block ID or tag;
- valid host tag;
- vertical distribution;
- rarity;
- vein size;
- vein type;
- geological province preference;
- optional biome preference;
- exposure bias;
- deterministic seed;
- optional fallback.

---

# 37. Ore Shapes

Support architecture for:

```text
SMALL_CLUSTER
LARGE_CLUSTER
VEIN
LONG_VEIN
POCKET
```

Do not force every ore to use every shape.

---

# 38. Vanilla Ore Defaults

Provide sensible development profiles for:

- coal;
- iron;
- copper;
- gold;
- redstone;
- lapis;
- diamond;
- emerald where appropriate.

Keep defaults reasonably Minecraft-like.

Do not claim exact vanilla parity unless intentionally implemented.

---

# 39. Modded Ores

Support future modded ore blocks through registry IDs/tags.

Example conceptual IDs:

```text
mod_x:tin_ore
mod_y:silver_ore
```

Never fabricate production mod IDs.

No direct external-mod implementation classes.

---

# 40. Ore Host Safety

Use semantic tags such as:

```text
#proutlost:ore_replaceable/stone
#proutlost:ore_replaceable/deepslate
```

Never place ores into:

- constructions;
- narrative structures;
- hidden rooms;
- containers;
- machines;
- block entities;
- protected zones.

---

# 41. Ore Preview

Provide useful admin statistics.

Example:

```text
Natural rock volume: 8,421,000 blocks

Estimated:
Coal      ...
Iron      ...
Copper    ...
Gold      ...
Redstone  ...
Lapis     ...
Diamond   ...

Large iron veins: ...
Large copper veins: ...

Protected volume: ...
```

Do not expose this to normal survival players.

---

# 42. Ore Heatmap

Optional but desirable.

Generate a coarse admin PNG heatmap.

Do not expose exact ore coordinates to normal players.

---

# 43. Soil Pass

Create coherent surface materials based on:

- biome;
- slope;
- altitude;
- moisture;
- coast distance;
- geology;
- water proximity.

Possible results:

- dirt;
- coarse dirt;
- podzol;
- sand;
- gravel;
- mud;
- exposed stone;
- future modded soils.

---

# 44. Soil Depth

Support variable soil depth.

Conceptually:

```text
steep cliff
→ almost no soil

meadow
→ deeper soil

forest
→ moderate organic soil

wetland
→ wet/muddy soil

beach
→ sand/gravel
```

---

# 45. Flora Pass

Generate natural ground vegetation based on:

- biome;
- soil;
- moisture;
- altitude;
- slope;
- open-space/light approximation;
- deterministic noise.

Support:

- grass;
- flowers;
- ferns;
- bushes;
- ground plants;
- future modded flora.

---

# 46. Flora Safety

Do not place flora:

- inside structures;
- inside protected areas;
- on invalid support;
- inside clearly enclosed handcrafted spaces unless explicitly allowed.

---

# 47. Tree Pass

Generate tree distributions after terrain creation.

Use:

- biome;
- soil;
- moisture;
- altitude;
- slope;
- forest density;
- clearing noise;
- manual overrides;
- deterministic seed.

Avoid:

- grids;
- uniform spacing;
- wall-to-wall forests.

Generate natural clearings.

---

# 48. Tree Density Overrides

Support:

```text
NO_TREES
SPARSE
NORMAL
DENSE
MANUAL_ONLY
```

---

# 49. Tree Content

Prefer controlled placement through:

- validated registered tree features where safely callable;
- controlled templates;
- dedicated placement logic.

Do not rerun complete vanilla chunk population.

---

# 50. No Full Worldgen Replay

This is critical.

Do not solve environment generation by rerunning full vanilla or modded decoration.

That could generate:

- structures;
- duplicate ores;
- random features;
- unwanted terrain;
- third-party worldgen.

WorldPrep executes controlled passes only.

---

# 51. Aquatic Pass

Prepare underwater environments using:

- water depth;
- underwater slope;
- coast distance;
- marine biome;
- substrate;
- macro climate.

Possible outputs:

- sandy seabed;
- gravel seabed;
- rocky seabed;
- seagrass;
- kelp;
- underwater plants;
- future reefs;
- future modded aquatic flora.

---

# 52. Aquatic Depth Rules

Content must respect appropriate depth.

Example:

```text
shallow coastal vegetation
!=
deep marine vegetation
```

Do not indiscriminately place all aquatic features everywhere.

---

# 53. Habitat Pass

WorldPrep does not own final animal AI.

It prepares a coarse `HabitatMap`.

Possible habitat categories:

```text
FOREST
DENSE_FOREST
OPEN_GRASSLAND
WETLAND
ROCKY_HIGHLAND
MOUNTAIN
COAST
RIVER
SHALLOW_MARINE
MARINE
DEEP_MARINE
CAMP_ADJACENT
```

---

# 54. Habitat Inputs

Habitat classification may use:

- biome;
- vegetation;
- forest density;
- water;
- coast distance;
- altitude;
- slope;
- openness;
- terrain type.

---

# 55. Habitat Storage

Do not store habitat per individual block unless necessary.

Prefer coarse:

- chunk cells;
- region cells;
- fixed-size ecology cells.

Future systems must query habitats cheaply.

---

# 56. Fauna Responsibility Split

WorldPrep:

- defines habitat suitability;
- prepares spawn suitability metadata;
- may optionally seed bounded initial populations.

Minecraft / future Re:Animal:

- baseline species AI;
- reproduction;
- animation;
- behavior.

Future Proutlost runtime:

- hunting pressure;
- fear/trust;
- camp tolerance;
- contextual aggression;
- migration pressure;
- corpse/death influence.

Do not implement Re:Animal AI in WorldPrep.

---

# 57. Semantic Fauna Tags

Prepare reusable tags:

```text
#proutlost:wild_animals
#proutlost:predators
#proutlost:prey
#proutlost:defensive_animals
#proutlost:camp_tolerant
#proutlost:marine_animals
```

Vanilla-safe initial content is acceptable.

No direct Re:Animal dependency.

---

# 58. Initial Population Seeding

Optional.

Default:

```text
OFF
```

If enabled, create only small bounded habitat-appropriate initial populations.

Do not flood the map with persistent mobs.

---

# 59. LocationRegistry

Implement a reusable first-class `LocationRegistry`.

Important authored locations must never be guessed automatically.

River explicitly declares them.

---

# 60. Location vs Future Region

A Location is a specific authored place.

Examples:

- crash site;
- clearing;
- ruin;
- observation station;
- hidden location.

A future Region is a larger World Director memory area.

Do not conflate them.

---

# 61. Location Types

Support:

```text
NARRATIVE_STATIC
NARRATIVE_TRANSFORMABLE
HIDDEN
PROTECTED
MANUAL_ENVIRONMENT
```

Normal free terrain does not necessarily require an entry.

---

# 62. Location Commands

Implement:

```text
/proutlost location pos1
/proutlost location pos2

/proutlost location create <id>
/proutlost location info <id>
/proutlost location list
/proutlost location delete <id>

/proutlost location anchor add <name>
/proutlost location anchor remove <name>

/proutlost location zone add <name> <type>
/proutlost location zone remove <name>

/proutlost location protect
/proutlost location unprotect

/proutlost location debug on
/proutlost location debug off

/proutlost location validate
/proutlost location export
```

---

# 63. Location IDs

Use namespaced IDs.

Development examples:

```text
proutlost:dev_test_site
proutlost:dev_clearing
```

Do not invent final production story locations unnecessarily.

---

# 64. Location Anchors

Allow River to stand at a meaningful point and record anchors such as:

```text
center
entrance
interaction
hidden_door
```

Future systems resolve:

```text
location ID + anchor name
```

rather than hardcoded coordinates.

---

# 65. Location Debug

Provide admin visualization.

Suggested debug colors:

```text
green  = bounds
blue   = anchors
orange = transformable
purple = hidden
red    = conflict/error
```

Do not send this to normal players.

---

# 66. WorldPrep Protection Rules

WorldPrep must automatically respect LocationRegistry.

Default:

```text
PROTECTED
→ never modify

NARRATIVE_STATIC
→ never modify

HIDDEN
→ never modify

NARRATIVE_TRANSFORMABLE
→ preserve unless explicitly allowed

MANUAL_ENVIRONMENT
→ preserve River's manually authored natural environment
```

---

# 67. Generic Protected Zones

Allow additional non-narrative protection.

Commands:

```text
/proutlost worldprep protect add <name>
/proutlost worldprep protect remove <name>
/proutlost worldprep protect list
```

---

# 68. Overrides

Using current selection, support at least:

```text
/proutlost worldprep override biome <biome_id>

/proutlost worldprep override palette <palette>

/proutlost worldprep override clear
```

Also provide architecture for overrides such as:

```text
geology
trees
flora
habitat
```

where practical.

---

# 69. Override Precedence

Use:

```text
PROTECTED
        >
EXACT MANUAL OVERRIDE
        >
SEMANTIC PALETTE OVERRIDE
        >
SMART PLANNER
```

---

# 70. Analyze

Implement:

```text
/proutlost worldprep analyze <area>
```

ANALYZE modifies nothing.

Report useful information such as:

- area size;
- chunks;
- altitude distribution;
- slope distribution;
- land/water ratio;
- coast statistics;
- likely biome distribution;
- natural geological host volume;
- ore host volume;
- vegetation suitability;
- habitat estimates;
- protected zones;
- registry validation.

---

# 71. Preview Commands

Implement:

```text
/proutlost worldprep preview terrain <area>
/proutlost worldprep preview biomes <area>
/proutlost worldprep preview geology <area>
/proutlost worldprep preview ores <area>
/proutlost worldprep preview soils <area>
/proutlost worldprep preview flora <area>
/proutlost worldprep preview trees <area>
/proutlost worldprep preview aquatic <area>
/proutlost worldprep preview habitats <area>
```

Prefer also:

```text
/proutlost worldprep preview all <area>
```

PREVIEW modifies nothing.

---

# 72. PNG Previews

Generate PNG previews in a headless server environment.

Possible maps:

```text
elevation.png
slope.png
biomes.png
geology.png
ore_heatmap_admin.png
soils.png
flora_density.png
tree_density.png
aquatic.png
habitats.png
```

Include legends.

No desktop GUI dependency.

---

# 73. Machine-Readable Preview Metadata

Save JSON metadata including:

- plan ID;
- profile;
- seed;
- area;
- dimension;
- fingerprint;
- pass;
- distribution statistics;
- registry validation;
- fallback usage;
- protected zones;
- overrides.

---

# 74. Inspect

Implement:

```text
/proutlost worldprep inspect
```

At the player's current location report relevant information such as:

```text
Area
Plan
Surface Y
Altitude
Slope
Coast distance
Water depth
Moisture

Current biome
Planned biome

Geology
Soil
Flora profile
Tree density
Aquatic profile
Habitat

Override
Protection
```

Do not expose exact ore coordinates by default.

---

# 75. Registry Search

Implement admin helper commands:

```text
/proutlost worldprep registry biome <search>
/proutlost worldprep registry block <search>
/proutlost worldprep registry entity <search>
```

If practical:

```text
/proutlost worldprep registry feature <search>
```

Use actual runtime registries.

This is important for discovering modded IDs later.

---

# 76. Job Engine

All large operations must use persisted incremental jobs.

Required states:

```text
QUEUED
ANALYZING
PREVIEWING
PREVIEW_READY
APPLYING
PAUSED
COMPLETED
CANCELLED
FAILED
ROLLING_BACK
ROLLED_BACK
```

---

# 77. Job Commands

Implement:

```text
/proutlost worldprep status
/proutlost worldprep pause
/proutlost worldprep resume
/proutlost worldprep cancel
```

---

# 78. Tick Budget

Large operations must not freeze the server.

Use configurable processing budgets.

Process bounded chunks/cells per server tick.

Do not:

- process the entire island synchronously;
- permanently force-load thousands of chunks;
- keep every processed chunk loaded.

Release temporary chunk resources.

---

# 79. Restart Recovery

Jobs must survive:

- world save;
- server stop;
- restart;
- pause.

After restart:

- restore job state;
- reconcile progress;
- resume or remain safely paused;
- do not blindly start again from zero.

---

# 80. Pass Dependencies

Track dependencies between passes.

Conceptual dependency graph:

```text
TERRAIN
   ↓
BIOMES
   ↓
GEOLOGY
   ↓
ORES

TERRAIN + BIOMES
   ↓
SOILS

SOILS + BIOMES
   ↓
FLORA / TREES

TERRAIN + MARINE BIOMES
   ↓
AQUATIC

BIOMES + TERRAIN + VEGETATION
   ↓
HABITATS
```

If an upstream input changes, invalidate downstream previews.

Examples:

```text
terrain changed
→ invalidate everything

geology changed
→ invalidate ores

biome changed
→ invalidate soils/flora/trees/habitats
```

---

# 81. Apply

Implement:

```text
/proutlost worldprep apply <pass> <area>
```

Prefer also:

```text
/proutlost worldprep apply all <area>
```

APPLY requires a persisted valid preview.

Do not compute a new random plan during APPLY.

---

# 82. Pre-Apply Safety

Before destructive work:

1. validate plan fingerprint;
2. validate world/dimension;
3. validate registries;
4. validate protected zones;
5. validate current assumptions;
6. create rollback snapshot;
7. acquire area/job lock;
8. only then modify the world.

---

# 83. Overlapping Job Safety

Do not allow two destructive jobs to modify overlapping chunks simultaneously.

Either:

- reject;
- queue;
- or serialize.

Never race world modifications.

---

# 84. Snapshot / Change Journal

Before APPLY, save only what is necessary for exact restoration.

Examples:

Biome pass:

- original biome cells.

Geology/Ore/Soil/Flora/Tree/Aquatic passes:

- original BlockStates changed.

Use compressed chunk-scoped change journals.

Do not duplicate entire world NBT unnecessarily.

---

# 85. Rollback

Implement:

```text
/proutlost worldprep rollback <pass> <area>
```

Prefer job-level rollback too.

Rollback must itself be:

- incremental;
- resumable;
- restart-safe.

---

# 86. Snapshot Safety Rule

If rollback cannot be guaranteed:

```text
APPLY MUST REFUSE TO START
```

Never automatically delete the only rollback snapshot.

---

# 87. Test / Production Modes

Configuration:

```text
worldPrep.enabled = false
worldPrep.mode = TEST | PRODUCTION
```

Default:

```text
enabled = false
```

TEST mode:

- admin-only;
- mandatory snapshots;
- clear warnings;
- no automatic processing;
- intended for map-development copies.

---

# 88. Public Season Behavior

WorldPrep should normally be disabled on the public season server.

It prepares the map.

It does not continuously mutate the environment during normal gameplay.

Future World Director may consume WorldPrep output.

---

# 89. Startup Report

Print a clear report such as:

```text
=== PROUTLOST WORLDPREP ===

WorldPrep enabled: YES
Mode: TEST

Profile:
proutlost:river_test PASS

Biome candidates:
valid: ...
fallback: ...
missing: ...

Geology:
...

Ore resources:
...

Flora:
...

Locations:
...

Protected zones:
...

Jobs requiring recovery:
...

STATUS:
READY / DEGRADED / FAILED
```

Do not silently ignore missing content.

---

# 90. Performance

Design for a large archipelago.

Avoid massive in-memory full-world representations.

Prefer:

- chunk streaming;
- biome-cell data;
- coarse habitat cells;
- compact plans;
- bounded caches;
- disk-backed plans/snapshots where useful.

Correctness and safety are more important than maximum throughput.

---

# 91. Block Safety Default

Unknown/custom block:

```text
DO NOT TOUCH
```

Block entity:

```text
DO NOT TOUCH
```

Protected zone:

```text
DO NOT TOUCH
```

Safety overrides aggressive automation.

---

# 92. Persistence

Implement actual Minecraft/NeoForge persistence for:

- areas;
- protections;
- overrides;
- locations;
- jobs;
- job progress;
- plan metadata;
- snapshot references;
- WorldPrep configuration state.

Use actual Minecraft 1.21.1 `SavedData` APIs or another appropriate persisted server mechanism.

Use versioned schema.

Unknown newer schema must not be silently overwritten.

---

# 93. Future World Director Compatibility

WorldPrep must not require World Director.

However, future systems must be able to query stable interfaces such as:

- `LocationRegistry`;
- `HabitatMap`;
- prepared environment metadata.

Do not create architectural coupling in the opposite direction.

---

# 94. AGENTS.md

Create repository-level `AGENTS.md`.

Record enduring rules:

```text
Minecraft 1.21.1
NeoForge 21.1.248
Java 21

WorldPrep is deterministic.
WorldPrep is reversible.
Preview never modifies the world.
Unknown/protected content defaults to untouched.
External mod content is registry/tag driven.
No direct external-mod implementation classes in core.
No full worldgen replay.
No fabricated test results.
Compile against real pinned APIs.
World Director and story are future subsystems.
```

---

# 95. Documentation

Create at minimum:

```text
README.md

docs/WORLDPREP.md
docs/WORLDPREP_RIVER_GUIDE.md
docs/WORLDPREP_PROFILES.md
docs/WORLDPREP_GEOLOGY.md
docs/WORLDPREP_ORES.md
docs/WORLDPREP_ECOLOGY.md
docs/LOCATION_AUTHORING.md
docs/WORLDPREP_TESTING.md
docs/KNOWN_LIMITATIONS.md

AGENTS.md
```

---

# 96. River Guide

`docs/WORLDPREP_RIVER_GUIDE.md` must target a non-developer map creator.

Explain this workflow:

```text
1. Terraform.
2. Select area.
3. Analyze.
4. Preview.
5. Inspect while walking.
6. Protect handcrafted areas.
7. Override unwanted results.
8. Preview again.
9. Apply.
10. Test visually.
11. Roll back if needed.
```

Explicitly explain:

> River creates the topography. WorldPrep creates much of the natural environment.

---

# 97. Domain Tests

Add automated tests covering at minimum:

- area handling;
- negative coordinates;
- terrain classification;
- slope;
- coast distance;
- water depth;
- deterministic planning;
- biome weighting;
- biome fallback;
- registry validation;
- geological province selection;
- deterministic geology;
- ore vertical distribution;
- ore host validation;
- deterministic ore placement;
- soil classification;
- flora eligibility;
- tree density;
- aquatic classification;
- habitat classification;
- protection precedence;
- override precedence;
- fingerprints;
- plan invalidation;
- job transitions;
- pause/resume;
- serialization;
- snapshot integrity;
- rollback integrity.

---

# 98. NeoForge / GameTests

Where technically practical, test:

- actual biome writing;
- biome persistence;
- actual block replacement;
- ore replacement only in valid host blocks;
- protected structures remaining untouched;
- SavedData/job persistence;
- rollback restoration.

Do not fabricate results.

---

# 99. River Acceptance — Biomes

On a disposable test island:

1. run `pos1`;
2. run `pos2`;
3. create `river_test`;
4. run analyze;
5. generate biome preview;
6. confirm preview changed no world data;
7. inspect while walking;
8. force a small area to `minecraft:forest`;
9. protect another area;
10. regenerate preview;
11. apply biome pass;
12. confirm actual biome changed;
13. confirm protected area unchanged;
14. rollback;
15. confirm exact original biome state restored.

---

# 100. River Acceptance — Geology / Ores

On a handmade raw-stone test island:

1. preview geology;
2. apply geology;
3. confirm protected blocks untouched;
4. preview ores;
5. inspect ore statistics;
6. apply ores;
7. verify ores only appear in valid natural hosts;
8. verify no ore appears inside protected structure;
9. rollback ore pass;
10. verify exact restoration.

---

# 101. River Acceptance — Environment

On test land:

1. preview soils;
2. preview flora;
3. preview trees;
4. apply soils;
5. apply flora;
6. apply trees;
7. verify cliffs remain sparse;
8. verify open land remains open;
9. verify forest placement is not grid-like;
10. protect manually decorated zone;
11. rerun relevant pass;
12. verify protected decoration remains unchanged.

---

# 102. River Acceptance — Aquatic

Using Ocean Canvas-like terrain:

Verify:

```text
deep water
→ deep marine classification

shallow coast
→ shallow/coastal classification

underwater substrate
→ coherent with depth/slope

aquatic vegetation
→ respects depth

protected underwater site
→ unchanged
```

---

# 103. Ecology Acceptance

Verify:

```text
forest
→ forest habitat

open meadow
→ open habitat

wetland
→ wetland habitat

coast
→ coast habitat

deep ocean
→ deep marine habitat
```

No Re:Animal implementation classes may be required.

---

# 104. Pause / Resume Test

During APPLY:

1. pause;
2. verify progress stops;
3. verify status remains correct;
4. resume;
5. verify continuation starts from correct position.

---

# 105. Restart Test

During a real APPLY:

1. stop server;
2. restart;
3. verify job is restored;
4. resume;
5. complete;
6. verify no duplicate modifications.

---

# 106. Failure Test

Cause a safe intentional development failure.

Expected result:

```text
job → FAILED or PAUSED
server remains alive
progress preserved
snapshot preserved
failing chunk/position reported
rollback remains possible
```

---

# 107. Determinism Test

Same world copy + same profile + same seed:

```text
preview A
preview B
```

must produce identical:

- plan;
- fingerprint;
- deterministic output.

---

# 108. Structure Safety Test

Create a test structure containing:

- chest;
- signs;
- arbitrary custom blocks;
- protected area.

Run relevant WorldPrep passes nearby.

WorldPrep must not damage the structure.

---

# 109. Build Loop

Build iteratively.

Do not wait until the very end.

Suggested sequence:

```text
Bootstrap
→ ./gradlew build

Terrain/Biome MVP
→ build/tests

Jobs/Snapshots/Rollback
→ build/tests

Geology/Ores
→ build/tests

Soils/Flora/Trees/Aquatic
→ build/tests

Habitats/Locations
→ build/tests

Final
→ full build/tests
```

---

# 110. Real Build Gate

Required:

```bash
./gradlew build
```

Standalone `javac` is not an acceptable substitute.

---

# 111. API Rule

Do not invent Minecraft/NeoForge API signatures.

Inspect actual Minecraft 1.21.1 / NeoForge 21.1.248 APIs for:

- biome palette access/writes;
- chunks;
- chunk unsaved state;
- registries;
- SavedData;
- block replacement;
- chunk tickets/loading;
- server tick scheduling;
- biome synchronization;
- configured/placed features if used.

The pinned artifact wins over remembered API knowledge.

---

# 112. Dedicated Server Gate

When environment permits, run a real development/dedicated server.

Verify:

- NeoForge starts;
- Proutlost constructs;
- WorldPrep commands register;
- SavedData initializes;
- `river_test` profile loads;
- startup report runs;
- server reaches `Done`.

---

# 113. Modded Content Rule

The final modpack is not frozen yet.

Do not fabricate final modded IDs.

Build generic registry/tag/profile support.

Ship only vanilla-safe development content.

Production profile comes later.

---

# 114. External Mod Coupling Rule

WorldPrep core must not directly import implementation classes from biome, ore, flora, tree or fauna mods merely to use their registered content.

Prefer:

- registries;
- namespaced IDs;
- tags;
- data-driven profiles.

Only use isolated optional adapters when genuinely required by a specific API.

---

# 115. Definition of Done

WorldPrep is complete only when all applicable requirements below are genuinely implemented:

- real NeoForge project exists;
- Java 21 enforced;
- Minecraft 1.21.1;
- NeoForge 21.1.248;
- `./gradlew build` succeeds;
- commands actually register;
- area selection works;
- terrain analysis uses real terrain;
- planner is deterministic;
- biome planning works;
- actual biome writes work;
- modded biome registry architecture works;
- geology works;
- ore placement works;
- ore host safety works;
- soils work;
- flora works;
- trees work;
- aquatic pass works;
- HabitatMap works;
- LocationRegistry works;
- protection works;
- overrides work;
- preview modifies nothing;
- PNG previews work;
- APPLY uses persisted plans;
- jobs are incremental;
- pause/resume works;
- restart recovery works;
- snapshots exist before destructive writes;
- rollback restores original values;
- protected/unknown blocks remain safe;
- automated tests pass;
- River guide exists;
- source is durably published to GitHub;
- fresh checkout contains the implementation.

---

# 116. Explicit NOT READY Conditions

WorldPrep is **NOT READY** if any of these is true:

- repository still contains only `.gitkeep`;
- Gradle/NeoForge project is absent;
- commands are not registered;
- terrain planning exists but Minecraft biome writing does not;
- preview modifies the world;
- apply cannot resume;
- rollback is fake or missing;
- geology is documentation only;
- ores are not actually placed;
- flora/trees are interfaces without implementation;
- protected structures can be damaged;
- work exists only inside `/workspace`;
- GitHub does not contain the source;
- fresh checkout cannot reproduce the project.

---

# 117. Remote Publication Gate

Before declaring completion:

Verify the working branch exists remotely in:

```text
Rastaaslan/proutlost
```

Fresh checkout must contain at minimum:

```text
gradlew
Gradle build configuration
src/main/java
src/main/resources
src/test/java
README.md
AGENTS.md
docs/
```

Run the build from the fresh checkout if environment permits.

Do not repeat the previous Proutlost failure where implementation only existed inside an ephemeral Codex workspace.

---

# 118. Required Final Codex Report

Report exactly:

1. repository and starting SHA;
2. working branch;
3. published branch/PR;
4. final remote SHA;
5. fresh-checkout verification;

6. Minecraft version;
7. NeoForge version;
8. Java version;

9. architecture summary;
10. commands available;

11. terrain analysis result;

12. biome preview result;
13. actual biome-write result;
14. vanilla biome result;
15. modded-biome registry support;

16. geology result;

17. ore result;
18. ore host-safety result;
19. modded-ore support architecture;

20. soil result;
21. flora result;
22. tree result;
23. aquatic result;
24. HabitatMap result;

25. LocationRegistry result;
26. protected-zone result;
27. override result;

28. PNG preview paths;

29. incremental job result;
30. pause/resume result;
31. restart recovery result;

32. snapshot result;
33. rollback result;

34. exact build command and result;
35. unit-test result;
36. GameTest/integration-test result;
37. dedicated-server result;

38. River acceptance workflow result;

39. blocked gates;
40. known limitations;

41. files changed;
42. commit list;

43. explicit answers:

```text
WORLDPREP ENVIRONMENTAL COMPILER READY FOR RIVER: YES / NO

SOURCE DURABLY PUBLISHED TO GITHUB: YES / NO

SAFE FOR RIVER TO MOVE FROM TOPOGRAPHY TO ENVIRONMENTAL AMENAGEMENT: YES / NO
```

Do not answer YES unless the corresponding acceptance gates genuinely passed.
