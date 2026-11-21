# P007 — Contra transfer qualification

This is an independent G0 transport and standalone registry component. It is
not wired to native Director admission and does not qualify D0 physical content,
J04 generation activation, runtime hydration or the fullstack delivery.

## Preserved contracts

`LocationTransfer.prepare` fragments the exact canonical `LocationImport` bytes.
Each message is at most 32 KiB. The frozen manifest binds world, original source
namespace, revision, export digest, full SHA-256, byte count, entry count and every
page hash. The complete source is at most 1 MiB. Byte fragments may cross JSON
boundaries; they are never applied as individual import revisions.

`LocationTransferInbox` uses the existing registry's writer executor. A separate
exclusive staging lock protects its private directory. Forced immutable pages
may arrive out of order or repeat identically. Contradictory, foreign, oversized,
noncanonical or corrupted input is refused. A restart revalidates the staged
bytes. Temporary and orphaned files never constitute a committed manifest.

Only `commit` assembles and verifies the entire source and submits it once to
`StandaloneLocationImporter`. The existing receiver still validates dimensions,
tags, references, historical identities, revisions and its configured capacity.
Its original atomic `CURRENT` publication owns revision replacement and history.
The transport's receipt explicitly identifies its standalone-registry scope.
It is not an acknowledgment of native J04 or physical admission.

An interrupted commit can be reconciled with the same identity, including a
crash after actual registry commit but before the transport receipt. `cancel`
retires an uncommitted proposal without deleting its evidence or rolling back a
published revision. A cancelled generation cannot be revived. The source count
is bounded to 32 across the registry and staging inbox together.

All filesystem work and continuations run off the calling actor. Trusted server
integration remains responsible for authentication and authorization. No new
command or network endpoint is installed by this change.

## Qualification

Run `directorLocationTransferTest` through the existing pinned Gradle build.
The executable test main also runs directly against compiled Director classes.
All test data is explicitly synthetic G0 metadata, in private worldless folders.
It is not a replacement for an original RUN7 file or physical D0 evidence.

Coverage includes multiple-page and large-source transfer, all-or-nothing
visibility, immutable retained snapshots, complete replacement/removal history,
original native-cap refusal, wrong world/generation, duplicates, corruption,
source capacity, semantic refusal, explicit cancellation and abrupt child-JVM
termination at every forced boundary exercised by the test source. A fresh JVM
then restores, resumes and checks the exact complete old/new revision.

## Native work still required

The existing 96-entry / 32-KiB native envelope, 512 metadata capacity and 4-MiB
J04 transaction limit are unchanged. Feeding assembled large sources directly
into native admission remains forbidden. The actual producer's 4096 ceiling
counts native location records, not regions + locations + anchors combined.

The next native implementation needs staged J04 generation transactions, an
atomic visible activation with real aggregate heads/revisions, exact CAS at the
same durable cut, retained historical source references, and bounded on-demand
hydration. Blob-only overlays and pages published as ordinary revisions cannot
satisfy those contracts. The P006 replacement counterexample remains valid.

Canonical end-to-end qualification also needs the sealed V5 physical session,
its frozen request and witnesses, authored `adapters.json` and versioned physical
requirements. The recovered lineage contains an interrupted cold-seal attempt
and a backup-not-map-ready pin, not completed physical qualification. A current
V5 base catalogue alone supplies no authored capabilities or semantic authority.

No production JAR, retained QA world or consumed event identity is changed by
this component. The P006 release candidate remains the current incomplete binary
candidate until a separately qualified native integration replaces it.
