# PROUTLOST — World Director V1 Canonical Specification

Status: THEORY FROZEN  
Target: Minecraft 1.21.1 / NeoForge 21.1.248 / Java 21  
Repository: Rastaaslan/proutlost  
Canonical scope: World Director V1 and its stable integration contracts  
Spoiler policy: MINIMAL / PLAYER-SAFE by default

## 1. Mission

World Director is the runtime orchestration layer that makes Proutlost feel like a living, reactive world without hard-coding a linear script.

It observes the actual game state, maintains a persistent semantic memory, evaluates opportunities, schedules events and consequences, applies only authorised world changes, preserves pacing, and remains explainable and recoverable.

The core must be structurally complete in V1. V1.1 may enrich tooling or behaviours, but must not require a fundamental persistence or architecture rewrite.

## 2. Absolute architectural rules

1. Server authoritative.
2. No giant god class.
3. One canonical owner per truth.
4. WorldPrep and Director are one-way coupled:
   WorldPrep -> PreparedWorldState -> Director.
5. Director never invokes WorldPrep analyze/apply at runtime.
6. Pure reusable planners may be shared by WorldPrep and runtime services when explicitly designed as shared components.
7. No full-world scanning or forced wild chunk generation.
8. No direct external-mod implementation classes in Director Core.
9. Prefer registries, namespaced IDs, tags, recipes, loot tables and NeoForge/Minecraft events.
10. Optional mod adapters are isolated, replaceable and must translate immediately into Proutlost semantic contracts.
11. Immersive Portals is explicitly outside Director integration scope.
12. Unknown or unclassified content is neutral/safe by default.
13. No generative runtime lore is required.
14. No arbitrary scripting language in content packs V1.
15. Minecraft must remain recoverable if Director enters safe mode or is disabled.

## 3. Runtime layers

Canonical flow:

~~~text
Minecraft / NeoForge / Mods
        |
Observation Layer
        |
FactJournal + StateStore + Metrics
        |
ArcEngine <--- authored Story/Season content
        |
World Director
        |
EventEngine / Consequence Engine
        |
Effect Engine / Mutation Engine / ResourceDirector
        |
Minecraft World

Quest Layer = presentation of canonical state only
~~~

Recommended core packages/components include:

- observation
- facts
- state
- region
- location
- arc
- director
- event
- consequence
- effect
- mutation
- resource
- technology
- content
- season
- questbridge
- persistence
- diagnostics
- integration
- shared

## 4. Time model

Director has three explicit clocks:

- ACTIVE_PLAY_TIME
- MINECRAFT_TIME
- WALL_TIME

Every timing rule must declare which clock it uses.

Short pacing primarily uses active play/server activity. Real time may be used for broad windows and long cooldowns. A powered-off server must not silently play through the story.

## 5. Hierarchical memory

Director memory is scoped across:

- world
- region
- location
- group/cohort
- player
- arc
- event
- environment

Facts, current states and aggregated metrics remain distinct.

A player death may influence environmental state, but must not directly branch narrative canon solely because the player died.

## 6. Event lifecycle

Canonical event lifecycle:

~~~text
CANDIDATE
-> RESERVED
-> STAGED
-> ACTIVE
-> RESOLVED | ABORTED
-> COOLDOWN
~~~

Reservation prevents incompatible concurrent activations.

Ambient/light events may coexist. A target player/group should have at most one major narrative event competing for attention unless a content contract explicitly allows composition.

Absent/offline players are never punished. Required personal interactions wait, defer or use an authored alternative.

Restart/crash recovery must be idempotent: no double rewards, double mutations, duplicate flags or ghost events.

## 7. Pacing and targeting

Director may choose to do nothing.

Pacing should follow waves of build-up, peak and breathing room rather than continuous escalation.

Use dynamic intensity budgets, not mandatory quotas.

Repeated targeting generates temporary fatigue for players, groups and regions. This lowers selection score except when required by critical arc logic.

Context may include player/group presence, region/location, time, weather, current activity, progression, recent events, relevant inventory semantics, environment state and proximity.

Director reacts to observable gameplay facts, not inferred psychology.

Good opportunities may become latent with expiry rather than being forced immediately.

Catch-up after absence must be discreet and coherent.

Multiplayer experiences may be asymmetric while persistent world truth remains coherent.

## 8. Anti-speedrun policy

V1 protects three progression dimensions:

- narrative/arcs
- technology/capabilities
- resources

Anti-speedrun must not punish skill or efficient play.

Use settling states, contextual prerequisites, world consequences, exploration, soft gates and capability progression before artificial timers.

Hard gates are reserved for canon, safety or genuinely premature content.

Director never deletes legitimately obtained items merely because a player progressed faster than expected.

A player may progress faster through skill, but must not trivially skip multiple intended layers through broken recipes, unlimited critical resources or quest chaining.

## 9. ArcEngine contract

An arc is a versioned state machine, not a flat quest list.

It defines:

- phases/states
- entry and exit conditions
- authored branches
- invariants
- gates
- settling
- consequences
- recovery
- terminal states
- migration policy
- scope and criticality

Arcs may progress via exploration, facts, objects, environmental events, decisions or explicit quests.

Branches may converge deliberately to control content explosion.

Failure does not automatically mean game over; authored alternate consequences are preferred.

No softlock is acceptable for season-critical content.

Narrative critical objects must be trackable and recoverable.

## 10. Mutation model

Three mutation classes:

- ILLUSION: sound, particles, presentation, temporary perception-level phenomena
- LIGHT: vegetation, traces, decorations, minor state changes
- STRONG: access changes, controlled destruction/transformation, major authored state transitions

Real mutations require provenance and bounded spatial/work budgets.

Strong mutations must be transactional where feasible: complete or rollback/recover.

Persistence policy is explicit:

- EPHEMERAL
- TEMPORARY
- CONDITIONAL
- PERSISTENT
- PERMANENT

Illusions may mislead players, but never corrupt canonical internal truth.

Some resolved events may leave environmental scars.

WorldPrep/River protections always override Director intent.

## 11. Location and region model

WorldPrep LocationRegistry remains the canonical registry of specific authored places.

Director adds RegionRegistry for broader runtime memory/pacing areas.

Do not conflate Location and Region.

Locations keep namespaced stable IDs, bounds, anchors, zones, type, protection, prepared state, allowed states/transitions and Director capabilities.

Narrative content should prefer:

location ID + anchor ID

over hard-coded coordinates.

Useful location capabilities include:

- AMBIENCE
- SPAWN
- DECORATION_MUTATION
- BLOCK_MUTATION
- STRUCTURE_TRANSITION
- NARRATIVE_INTERACTION
- RESOURCE_CONTROL

## 12. PreparedWorldState handoff

WorldPrep publishes a versioned immutable baseline:

~~~text
PreparedWorldState
- schema version
- world ID
- fingerprint
- regions
- locations
- habitats
- resources
- environment metadata
- protections
- mutation capabilities
- provenance
~~~

Director records the expected fingerprint.

Prepared baseline differences are classified:

- SAFE
- MIGRATION_REQUIRED
- INCOMPATIBLE

Director must not silently continue through incompatible handoff changes.

WorldPrep itself does not wake up automatically in production.

## 13. Shared planners and ResourceRegenerator

WorldPrep may expose pure reusable planning components under shared code, for example:

- ResourcePlanner
- OrePlanner
- HostResolver
- protection/eligibility rules

Runtime resource regeneration is a separate service:

~~~text
WorldPrep -> initial bulk preparation
Director -> detects resource pressure and authorises policy
ResourceRegenerator -> targeted safe regeneration/reveal
~~~

ResourceRegenerator must not call WorldPrep.apply.

Automatic regeneration is OFF by default in production.

Policies:

- NONE
- NATURAL
- DIRECTOR_ASSISTED

Director-assisted replenishment should be diegetic where possible: newly accessible reserves, revealed veins, environmental changes, migration/recovery of renewable systems.

Unique/narrative resources are never automatically regenerated.

## 14. RuntimeModificationMap

V1 must account for player-built/modified terrain.

Track compact provenance sufficient for safe automated mutation:

- NATURAL_BASELINE
- PLAYER_MODIFIED
- DIRECTOR_MODIFIED
- RIVER_AUTHORED
- PROTECTED
- UNKNOWN

For resource regeneration and automated real mutations:

PLAYER_MODIFIED -> SKIP  
PROTECTED -> SKIP  
UNKNOWN -> SKIP by default

If actual runtime block state no longer matches a safe expected host, skip rather than force.

## 15. Observation contract

External systems do not write directly into Director logic. They emit normalised observations.

Observation records should support:

- type
- source
- server tick
- game time
- wall time
- actor
- targets
- scope
- payload
- correlation ID
- sequence/deduplication identity

Canonical distinction:

- Fact = immutable meaningful past event
- State = current mutable truth
- Metric = aggregated value used for analysis/pacing

Examples of semantic observations:

- PLAYER_CONNECTED
- PLAYER_DISCONNECTED
- PLAYER_DIED
- PLAYER_ENTERED_REGION
- PLAYER_ENTERED_LOCATION
- LOCATION_DISCOVERED
- ITEM_OBTAINED
- ITEM_CRAFTED
- RESOURCE_EXTRACTED
- ENTITY_KILLED
- CONTAINER_INTERACTION
- CAPABILITY_ACQUIRED
- TRAVEL_OCCURRED
- WEATHER_CHANGED

Do not retain useless movement/damage spam indefinitely; aggregate when detail has no semantic value.

Persistence pattern:

~~~text
append-only meaningful FactJournal
+ materialised StateStore
+ bounded metrics/history
+ snapshots/compaction
~~~

## 16. Action / Effect contract

Director requests semantic Proutlost actions, never arbitrary mod calls from core.

Core V1 primitives may include:

- PLAY_SOUND
- SPAWN_PARTICLES
- SPAWN_ENTITY
- DESPAWN_DIRECTOR_ENTITY
- GIVE_ITEM
- TAKE_TRACKED_ITEM
- APPLY_EFFECT
- PLACE_BLOCKS
- REPLACE_BLOCKS
- APPLY_TRANSFORMATION
- SET_LOCATION_STATE
- SET_ENVIRONMENT_STATE
- OPEN_ACCESS
- CLOSE_ACCESS
- CREATE_OPPORTUNITY
- SCHEDULE_EVENT
- ACTIVATE_CONSEQUENCE
- RECORD_FACT
- GRANT_KNOWLEDGE

Every authoritative action must support:

- action ID
- idempotency key
- source event/consequence
- target
- preconditions
- permissions
- budget
- provenance
- rollback/recovery policy

Prefer Minecraft/NeoForge generic APIs + registry IDs/tags.

Mod-specific write adapters are exceptional, isolated and optional.

## 17. Mod observation and semantic catalogue

Director Core never reasons in mod-specific implementation terms.

At startup build semantic catalogues from the real server:

ContentCatalog:
- items
- blocks
- entities
- biomes
- structures
- effects
- tags

TechnologyGraph:
- recipes
- ingredients
- outputs
- stations
- semantic tiers
- dependencies
- progression paths

WorldCapabilityCatalog:
examples include
- FAUNA
- FARMING
- FISHING
- WAYPOINT_TRAVEL
- HEALING_SOURCE
- DYNAMIC_WEATHER
- MODDED_FOOD

Director asks whether a capability exists, not whether a specific provider mod is installed.

Optional adapters may exist only when data is otherwise inaccessible.

Immersive Portals has no Director adapter.

Performance/client-only mods and visual-only mods are ignored by Director.

Voice content is not analysed.

WorldEdit remains authoring/MapDev tooling, never a required Director runtime engine.

## 18. Proutlost semantic ontology

Use multi-axis semantic classification rather than one giant rigid enum.

Technology tiers:

- PRIMITIVE
- EARLY
- MID
- ADVANCED
- ENDGAME

Progression role:

- PROGRESSION_NEUTRAL
- PROGRESSION_SUPPORT
- PROGRESSION_GATE
- PROGRESSION_CRITICAL

Resource rarity:

- ABUNDANT
- COMMON
- UNCOMMON
- RARE
- VERY_RARE
- UNIQUE

Renewability:

- RENEWABLE
- SLOW_RENEWABLE
- FINITE
- UNIQUE

Importance:

- ORDINARY
- USEFUL
- STRATEGIC
- PROGRESSION
- CRITICAL

Acquisition semantics include:

- MINING
- HARVESTING
- HUNTING
- FISHING
- FARMING
- CRAFTING
- COOKING
- LOOT
- TRADE
- EVENT
- NARRATIVE

Regions use functional tags, danger and per-player/group familiarity.

Events have independent nature and intensity axes.

Consequences declare scope and persistence duration.

Gameplay semantics should generally be data-driven Proutlost IDs/tags rather than large closed Java enums.

UNKNOWN is always valid and conservative.

## 19. TechnologyGraph and AcquisitionGraph

Build from real loaded recipes/loot/content.

Recipes describe technical relations. Proutlost classifications describe intended semantic progression.

Progression is capability-oriented rather than item-oriented.

A useful progression state distinguishes:

- DISCOVERED
- POSSESSED
- CRAFTABLE
- SUSTAINABLY_ACCESSIBLE
- OPERATIONAL
- MASTERED

Finding one advanced item does not imply sustainable access to its technology tier.

AcquisitionGraph tracks meaningful sources including mining, crafting, mobs, farming, fishing, structure loot, events and trades.

Loot probability classes may be coarsely classified, e.g.:

- GUARANTEED
- COMMON_SOURCE
- LIMITED_SOURCE
- RARE_SOURCE
- EXCEPTIONAL_SOURCE
- UNIQUE_SOURCE

Detect suspicious resource multiplication/crafting cycles and unexpectedly short critical progression paths.

Director is not responsible for hiding broken modpack design. If a recipe fundamentally breaks progression, prefer fixing/configuring the pack.

No automatic destructive recipe correction.

## 20. Resource runtime states

Region/resource state may include:

- UNTAPPED
- HEALTHY
- USED
- PRESSURED
- DEPLETED
- RECOVERING

WorldPrep provides baseline potential/distribution.

Director maintains runtime discovery, accessibility, extraction pressure and recovery in aggregate where possible.

Do not count every ore block continuously.

## 21. Story / Arc / Quest / Season ownership

Director owns:
- world observation
- runtime memory
- pacing
- opportunity arbitration
- event scheduling
- environmental/resource runtime orchestration

ArcEngine owns:
- canonical structured narrative progression
- authored choices/transitions/invariants

Story is authored content using the engines, not a separate competing god-manager.

Quest layer owns presentation only. It must be reconstructible/resynchronisable from canonical state.

SeasonDefinition configures:
- season ID
- enabled content/arcs
- starting state
- policies
- Director profile
- content versions

SeasonRuntimeState is separate from immutable definition.

Avoid a universal chapter number as the only truth. Optional macro stages may exist for pacing:

- OPENING
- ESTABLISHMENT
- ESCALATION
- REVELATION
- ENDGAME
- EPILOGUE

Events do not permanently own canon after completion. Meaningful results become facts/states/arc state.

World truth and player knowledge are distinct.

Player knowledge may be asymmetric.

Spoiler-sensitive UI/content must check knowledge, not merely world truth.

## 22. Content pack contract

The engine defines what is possible. Content packs define what happens.

Recommended data layout:

~~~text
data/proutlost/
  director/
    events/
    consequences/
    conditions/
    effects/
    presets/
  story/
    seasons/
    arcs/
    knowledge/
    dialogue/
  world/
    regions/
    locations/
    transformations/
  progression/
    resources/
    technology/
    capabilities/
    classifications/
~~~

All important IDs are namespaced, stable and never recycled.

Families have explicit schema versions.

Load pipeline:

~~~text
parse
-> schema validation
-> semantic validation
-> reference resolution
-> compatibility validation
-> activation
~~~

Invalid content is isolated/disabled, not allowed to crash the server.

Conditions and effects use a finite set of tested primitives.

No arbitrary Lua/JS/Groovy style scripting in V1.

Prefer composition/presets over deep inheritance.

Support DRAFT and PRODUCTION status.

Active event instances retain their content version.

Removed used IDs become deprecated/tombstoned with migration policy.

CI and server validation should share the same validation core.

## 23. Modpack compatibility

At boot fingerprint relevant runtime surfaces:

- mod versions
- registries
- semantic tags
- recipes
- relevant loot
- Proutlost capabilities
- optional adapters

Content dependency policies:

- REQUIRED
- OPTIONAL
- FALLBACK
- DISABLE_IF_MISSING

REQUIRED should be rare.

No automatic replacement by semantic resemblance.

Mappings/migrations are explicit.

A missing optional adapter removes the capability; it does not crash Director.

Runtime changes are classified:

- NO_IMPACT
- CONTENT_CHANGED
- MIGRATION_REQUIRED
- UNSAFE

UNSAFE places Director in safe mode rather than blindly writing.

Never silently downgrade newer persisted schemas.

The production pack may be baseline-locked while still allowing controlled migrations.

## 24. Boot audit and self-check

Startup phases:

~~~text
BOOTSTRAP
-> DISCOVERY
-> VALIDATION
-> COMPATIBILITY
-> STATE_RECOVERY
-> SIMULATION_CHECK
-> READY
~~~

No new dangerous automated action before readiness.

Validate platform, catalogues, semantic tags, recipes, acquisition paths, WorldPrep handoff, regions, locations, content packs, arcs, events, canon/knowledge references, persisted state, active/reserved events, pending consequences and mutation transactions.

Boot status:

- READY
- READY_WITH_WARNINGS
- SAFE_MODE
- BLOCKED

Safe mode remains diagnostic-capable but does not launch new major mutations/events/regeneration.

Provide:

- /proutlost director status
- inspect
- explain
- timeline
- simulate
- pause
- resume
- arc
- event
- memory
- doctor
- doctor --explain
- diagnostic export
- validate-world

Human-readable short report must make health obvious within seconds.

CI and runtime must share the same validators wherever practical.

Maintain bounded boot history and runtime health monitoring.

## 25. Concurrency model

Canonical state mutations are serialised by one server-authoritative commit path.

Heavy computation may run asynchronously only over immutable snapshots.

Pattern:

~~~text
observe
-> snapshot
-> calculate async if useful
-> revalidate
-> reserve
-> authoritative commit
~~~

Never perform unsafe Minecraft world access off-thread.

If the source snapshot is stale at commit time, re-evaluate rather than force.

## 26. Persistence and scope

Every persisted datum declares scope:

- SESSION
- SEASON
- WORLD_PERSISTENT
- PLAYER_PERSISTENT

Story/arcs/Director S2 default to SEASON unless explicitly defined otherwise.

At season end, SeasonRuntimeState is archived read-only.

Future seasons use a distinct season instance ID rather than overwriting history.

Writes are versioned, atomic where appropriate and migration-aware.

Unknown newer schemas must not be overwritten.

## 27. Admin safety

Support:

- global pause
- pause by arc
- pause by region
- pause by player/group
- pause by category
- feature flags
- safe mode
- targeted rollback/recovery
- provenance audit
- simulation/dry-run
- explain mode
- timeline
- bounded diagnostic export

Admin force actions are recorded explicitly as admin provenance.

Doctor proposes before destructive repair.

## 28. Performance and bounded work

No entire-world per-tick analysis.

Use event-driven observations, bounded queues, compact aggregates, targeted context loading and explicit budgets.

Watch:

- CPU/tick budget
- mutation work/tick
- chunk work
- event frequency
- queue growth
- history size
- cache size

Director should self-degrade/safe-mode affected subsystems under repeated critical failure rather than continue damaging state.

## 29. V1 completeness and V1.1 rule

Anything required to avoid later structural rewrite belongs in V1.

V1.1 may add:
- richer admin visualisation
- more comfortable River/content authoring UI
- more sophisticated ecology/economy simulations
- richer optional social/NPC behaviours
- non-structural tooling

V1.1 must not require a new fundamental persistence model or break stable Director contracts.

If a V1.1 feature would require such a foundation, that foundation is promoted into V1.

## 30. Player-safe anti-spoiler policy

Default project mode for the project owner/player is MINIMAL SPOILERS.

Expose intentions, categories, technical health and decision constraints without exposing concrete hidden content unless required.

Prefer player-safe metadata such as:

- arc criticality
- tone/intensity
- number of branches
- validation status
- recovery coverage
- event counts
- technical dependencies

Avoid spoiler-bearing IDs in standard admin output where possible.

Provide separate safe/full explain/diagnostic paths.

Automated validation reports should prefer counts and technical results rather than enumerating secret event content.

If debugging requires revealing a major story secret, keep the technical diagnosis hidden/abstracted whenever possible.

## 31. Definition of Done

Director V1 is not complete until all applicable points hold:

1. Fresh state boots without ghosts.
2. Restart/crash recovery is idempotent.
3. Incompatible reservations cannot double-book narrative resources.
4. River/WorldPrep/player protection rules are enforced.
5. Important changes have provenance.
6. Important decisions are explainable.
7. Event eligibility/execution/recovery is tested.
8. Arc branches/settling/migrations/recovery are tested.
9. Death/disconnect/reconnect paths are safe.
10. Narrative/technology/resource speedrun shortcuts are bounded without punishing legitimate skill.
11. Absent players are not narratively punished.
12. No unbounded event or mutation cost.
13. Strong mutations have safety/recovery.
14. Invalid content cannot crash the server.
15. Doctor identifies major known inconsistency classes.
16. Diagnostic export is sufficient for remote investigation.
17. Accelerated synthetic simulation can run long Director time spans.
18. Long simulation shows no event spiral, permanent starvation or unbounded memory.
19. Real Minecraft human test validates natural pacing, believable world reaction, credible surprise, no harassment feel, no trivial progression speedrun and no artificial grind solely for delay.
20. Production baseline is fingerprinted/tagged before mass seasonal content expansion.

## 32. Implementation order

Engine before mass content.

Recommended implementation sequence:

D0 — contracts, package boundaries, shared IDs/types, persistence skeleton, validators  
D1 — PreparedWorldState/Region/Location handoff and runtime state foundation  
D2 — Observation Layer, FactJournal, StateStore, RuntimeModificationMap  
D3 — EventEngine lifecycle, scheduler, reservation, clocks, targeting/pacing foundation  
D4 — ArcEngine, consequences, season scope, knowledge model, QuestBridge contract  
D5 — semantic ContentCatalog, TechnologyGraph, AcquisitionGraph, resource pressure and ResourceRegenerator foundation  
D6 — Effect/Mutation Engine, transactions, provenance, protections, admin controls  
D7 — content-pack loader/schema/CI validators, boot audit/doctor/diagnostic export, accelerated simulator and integration tests

A small vertical slice must validate the engine before mass story/event authoring.

## 33. Freeze rule

This document is the canonical V1 architectural specification.

Implementation may refine names or internal decomposition when required by the real Minecraft 1.21.1 / NeoForge 21.1.248 APIs, but must not silently change the validated behavioural contracts.

Any required structural deviation must be documented with rationale and compatibility impact before becoming canonical.
