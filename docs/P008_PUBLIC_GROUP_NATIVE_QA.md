# P008 — public HYBRID to GROUP native QA

This is a new closed TEST_ONLY fixture, independent of QA-00 to QA-05 and the
canonical campaign. No existing IDs, worlds, saves or author decisions change.
It uses zero Minecraft clients and three declared synthetic actors in two groups.
Region, regional memory, capability descriptor, budgets and ACTIVE pacing are
declared synthetic J03 inputs. The actual loaded Minecraft cell, BlockTransformBinding,
AdmissionService, J04 PREPARE/permit/RESULT, EVENT lifecycle, shared Quest consumer,
QuestSavedData serialization and cold-world block readback are native operations.
This is not natural observation, a canonical story receipt, multiplayer, or a
human audio/visual receipt.
The MOSS_BLOCK cell and its DIRECTOR_ACTIVE ownership are retained as evidence.
This harness does not qualify cleanup, release, inverse or physical restoration.

Acceptance scenario defined before implementation:

1. A distinct private marked QA world contains one sacrificial loaded STONE cell.
2. The actual CandidateGenerator binds the fixture's legal min=max=0 role, producing
   WORLD and REGION scopes with no PLAYER scope. No evidence scopes are removed.
3. The actual J03 engine selects and replays the plan. Its audit is forced to J04
   before EVENT admission. AdmissionService reserves the actual captured block.
4. EventRunner forces PREPARE, obtains a real one-use permit, applies the actual
   STONE to MOSS_BLOCK transform, and forces RESULT and its original completion
   fact. No direct fact injection or hand-posted completion event is used.
5. DirectorNativeEventLifecycle independently reads J04 and publishes its confirmed
   completion to the shared QuestDirectorFacts consumer. Both assigned groups
   resolve QA_PUBLIC_GROUP_READ; an ungrouped fourth actor stays LOCKED.
6. The exact QuestSavedData is saved and read back from its actual NBT. The physical
   world and WAL are kept together. produce.json records exact core/ownership hashes,
   world/WAL paths and identity, fixture hash, source provenance, installed JAR hashes,
   process PID plus start instant, gameTime and private J04 boot/authority.
7. Stop the owned QA normally, then launch a NEW JVM with the same exact binaries,
   properties, world and WAL. reopen refuses a missing save, same process, mismatched
   world/source/JAR/fixture or rollback of the real persisted world clock. It reads
   the actual MOSS_BLOCK and J04 ownership, hydrates the original EVENT context and
   replays its actual completion into the already restored Quest core. The exact
   hash and facts/states remain unchanged. reopen never rewrites the cell or resets
   a GameTest structure.

Production constructors retain the nine-event/two-chain and PRODUCTION guards.
The additional lifecycle factory accepts only the exact closed one-event fixture
on the separately fenced QA world. The SavedData profile accepts only the exact
closed GROUP definition. The main mod's publicGroupQa bootstrap returns before
canonical campaign listeners are registered. The normal production receiver and
QA receiver call the same server-actor-enforced acceptConfirmed method.

## Current verification status

- PublicGroupQaCatalogTests: 18 checks PASS, JDK-only fixture/engine tests.
- A real red test reproduced the unconditional PLAYER_ELIGIBILITY gate rejecting
  a legal zero-role event. The fix requires that sensor only for bound players;
  all bound-player and incidental-audience validation remains active.
- Adverse pure checks: missing bound-player eligibility, incomplete min1 role,
  missing regional knowledge and unobserved incidental audience remain refused.
  PlayersPresent(0) retains UNKNOWN when player knowledge is absent.
- Private facts and closed Quest prerequisites retain their existing pure
  ScopedQuestTest regressions. No new native negative receipt is claimed.
- Native typecheck/build, installed binary run, produce, new-JVM reopen: PENDING
  until root executes them on the pinned native toolchain and an owned QA world.
- No result above is a fullpack acceptance or canonical content receipt.

## Build and first native execution

Integrate the reviewed files sequentially, build both exact JARs with the pinned
toolchain, and run publicGroupQaCatalogTest plus affected QuestDirectorFactsTest,
ScopedQuestTest and the normal Director contract regressions. The harness is in
the main mod QA package, not directorGameTest and not a GameTest in directorJar.
No cyclic dependency/classpath adjustment is introduced.

Use a NEW isolated private world, never the preserved QA world or a live map.
Port must be 25579, whitelist enabled, zero clients, enabled=false and
testCommands=false in both effective Quest SERVER configuration paths.
Do not set neutralLab. Create PUBLIC_GROUP_TEST_ONLY in that world's root containing
exactly PROUTLOST_PUBLIC_GROUP_QA_V1. The code never creates or rewrites this marker.
The world must have no canonical or neutral Quest SavedData. Place the single
sacrificial STONE cell as explicit QA setup in already existing, spawn-loaded terrain;
the harness never loads or generates a chunk. Keep that cell loaded after restart.

Required JVM properties, with actual absolute paths and the root-verified source hash:

```text
-Dproutlost.director.nativeLab=true
-Dproutlost.quest.publicGroupQa=true
-Dproutlost.quest.publicGroupQaRoot=/ABSOLUTE/PRIVATE_QA/world
-Dproutlost.quest.publicGroupQaSourcesSha256=ROOT_VERIFIED_64_HEX_SOURCE_SHA256
-Dproutlost.quest.publicGroupQaMainJar=/ABSOLUTE/PRIVATE_QA/mods/proutlost-0.1.0.jar
-Dproutlost.quest.publicGroupQaDirectorJar=/ABSOLUTE/PRIVATE_QA/mods/proutlost-director-1.0.0.jar
```

The named installed JAR must contain the exact loaded harness/lifecycle class
resource, and its entire SHA-256 is recorded. The source hash is an external
root-verified source/binary provenance input, not a self-authenticated source claim.

From the owned server console (OP4):

```text
proutlost public_group_qa produce <x> <y> <z>
proutlost public_group_qa status
```

Wait for actual PUBLIC_GROUP_QA_PRODUCE_PASS and inspect world/public-group-qa/produce.json,
the real SavedData data/proutlost_quest_public_group_qa_v1.dat and matching private WAL.
Stop the owned server normally. Start a new JVM with the same paired world and flags:

```text
proutlost public_group_qa reopen
proutlost public_group_qa status
```

Require PUBLIC_GROUP_QA_REOPEN_PASS and the new reopen-PID-start.json receipt. Preserve all
files and process/log identities. A failed/interrupted produce directory is retained;
produce refuses to overwrite it. The harness does not restore its deliberately
changed QA cell, reset an existing test, deploy, or touch canonical/live worlds.
