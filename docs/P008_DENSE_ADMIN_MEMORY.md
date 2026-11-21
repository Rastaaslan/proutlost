# P008 – exact region memory above resident/wire page limits

The observed counterexample is a declared synthetic catalogue with one region,
one location and 600 anchors. G0 contains 602 combined entries and fits the retained
1 MiB source contract. The unchanged RegionRecord alone exceeds a 22,000-byte admin reply;
loading all region anchors also exceeds the 512 metadata working set.

The normal exact reader remains in use for ordinary scopes. Capacity failures
alone select a separate read-only document reader. It reads one pinned J04 cut
in 96-key pages, retains one page of at most 96 aggregate bodies, plus the region and region-memory bodies, and keeps
encoded results within 4 MiB/read and 4 reads. J04's cache remains 512. Cursors reference
those existing cached reads, expire after 60 seconds without a continuation, and
are deleted on eviction, disconnect or close.

The dense representation explicitly separates REGION, MEMORY, TAGS, ANCHORS and
LOCATIONS. RegionRecord and RegionMemory are whole immutable records. Anchor and
location sections carry offset/total and at most 96 bodies. Historical inactive
bodies are retained; the TAGS census includes active metadata and authored tags.
Each semantic section is an immutable AdminRow document. A document that cannot
fit in one existing reply is transferred in 10,240-byte fragments, each bearing
its identity, total length, offset and SHA256. No transport or source ceiling
is raised. No private file path or execution permission is exposed.

The client correlates connection, request, filter, scope and pinned stamp. It
assembles one document (at most 1 MiB), checks offsets and digest, decodes the closed
typed row, and only then displays semantic details. While incomplete it displays
progress and automatically fetches only the remaining transport fragments,
respecting the existing query cadence and one in-flight request. It stops at
each verified semantic section, where Suivante is ordinary pagination. It never
renders base64. All anchor
IDs in the current semantic page appear before bounded detailed text.

Both normal and document cursors retain their original WORLD revision, tick
and J04 cut across unrelated WORLD appends. Every continuation still checks
current session/permission and actual relevant head/census revisions. A changed
or newly visible selected memory head invalidates the retained read. A frozen
cut is never stamped as a newer cut. Exact tag filters are admitted only where
this complete regional census exists; unindexed global tag searches still refuse.

A REGION/PLAYER command with further pages returns DEFERRED/BUDGET_EXHAUSTED and
an exact reference. This explicitly reports an incomplete inspection. F8 allows
Ouvrir only for that matching read-only target and then uses the paginated query.
CLI output gives the exact F8 filter and continuation instructions without
printing fragment content. An uncertain modifying command cannot use this marker.

The closed AdminData schema has new variants. The candidate must declare API 1.1.0
and admin wire 1.1.0, with the NeoForge registrar 1.1.0 on both client and server.
An old closed client is not declared compatible. J06 perception payload fields
and its Capabilities protocol 1 are unchanged. The frozen historical CDC documents
and G0 source records are preserved.

Local qualification uses actual staged/activated J04 generations, a checkpoint
and a fresh J04 session, real wire encoding and pure client state/detail helpers.
Minecraft bootstrap, physical F8 rendering, matched-pack handshake and command
entry in real clients remain native qualification steps for the integrator.
