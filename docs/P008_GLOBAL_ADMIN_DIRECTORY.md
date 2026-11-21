# P008 / T030-ADMIN – global MEMORY discovery

The reproduced failure used a real declared synthetic J04 generation with 600
regions. The game cache correctly held one scoped region without WORLD coverage;
the default MEMORY query consequently returned DEFERRED and could not discover
other IDs. It must not pretend that this scoped game cache is the whole world.

The new directory uses J04's own pinned REGION key index and reads actual
RegionRecord bodies in pages of at most 96 keys. It returns complete typed REGION
sections and reuses the existing hashed document transport for a large record.
No DTO or wire version changes beyond the earlier matched 1.1 candidate are added.
ColdAdminMemoryReader now handles the default RegionFilter as well as exact reads,
so the existing production and LAB read routes reach this directory. The earlier
dense-admin bootstrap patches must already preserve reader-provided stamps.

The directory stores at most four reads and two encoded pages per read, with a
4 MiB encoded-page guard and bounded single-worker queue. A read still doing IO
cannot be evicted, expired or disconnected out of the in-flight resource bound.
Its data is revoked immediately; a new job waits until capacity actually settles.
Empty windows always carry a continuation while loading or searching more keys.
They are never presented as evidence that no region exists. F8 automatically
loads these windows and transport fragments at its existing query cadence, with
one request in flight. Real rows and semantic pages remain user controlled.

Blank MEMORY search lists actual region references. The selected row has an
Inspecter button that requests its exact UUID. Existing exact player/region
search remains available; `tag namespace:tag` performs indexed global AND search.
The separate GlobalRegionTagCensus helper verifies authored sources and current
active metadata at the same cut. Unavailable/inconsistent census data refuses;
it does not manufacture an empty match. Detail text renders only the current
section, avoiding empty placeholders for bodies in other sections.

Continuation policy is explicitly historical. Every window retains its original
WORLD revision, captured tick and J04 cut. Ordinary REGION/ANCHOR/LOCATION head
updates within those same sources do not interrupt or rewrite that snapshot.
An inserted region appears on a new search, not halfway through the old one.
Each request still revalidates world/store/boot, the live authenticated OP4 actor,
filter, limit, opaque one-use cursor, idle TTL, source descriptors and generations.
A changed source or generation resets the read. An unavailable historical page
returns DEFERRED; no code substitutes a latest page. New searches use a new cut.
Administrative mutations keep their existing current CAS and fresh-state gates.

Local qualification is actual J04 plus encoded wire DTOs and pure client state.
It traverses 600 regions including a region with 600 anchor IDs, exercises 25
REGION updates and an insertion during pagination, checks a new 601-region start,
source/generation and legacy-descriptor refusal, permissions/reconnection, and
bounded pending jobs under an isolated executor pause. This is not a 25-player
load test, a Minecraft render proof, a JVM crash qualification, or Contra reception.

T030-ADMIN stays pending native reception: build matching current client/server
artifacts, open F8 > MEMORY with a blank filter, discover a distant region, select
Inspecter and view its real memory. Repeat global tag search, automatic loading,
ordinary REGION activity, source/generation reset and reconnect on those binaries.
