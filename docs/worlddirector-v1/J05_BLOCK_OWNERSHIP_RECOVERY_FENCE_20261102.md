# J05 block ownership recovery fence — 2026-11-02

Status: code + pure-Java regression qualified; Linux NeoForge and real-client qualification require separate evidence. Release remains TEST_ONLY.

## Corrected production risk

A block WAL recovery previously accepted any CONFIRMED evidence whose token appeared in the live owner set. It did not require the proof's observed hash or its owner/player epochs to equal the current physical state. A later owner could therefore provide unrelated evidence and cause a false SAME_POST despite a different PRE owner. Pre-permit revalidation also ignored owner revision/provenance hash.

## Safe transitions

- PRE: complete owner identity, tokens, epochs, revision, provenance and block-state fingerprint must still match the original captured plan.
- POST: same owner identity and epochs, exact next local owner revision and expected post fingerprint; no requirement to retain PRE revision after a genuine mutation.
- Recovery: observed actual block fingerprint must agree with evidence, owner token and epochs. Missing, foreign or ambiguous evidence stays AMBIGUOUS, never an ACK.
- Duplicate durable owner snapshots for one block are rejected instead of selecting an arbitrary first one.
- The actor-only J04 PREPARE fsync and commit-permit boundary remain untouched. No gameplay activation, new provider grants or world edits outside the disposable lab.

## Qualification gates

The pure-Java BlockOwnershipFenceTests are automatically part of directorContractTest; they do not constitute native-gameplay proof. The existing selected camp regrowth GameTest now checks a real changed block and local ownership record against a valid recovery proof, a stale-hash proof, and a foreign-owner proof. It must pass on a new laboratory world and after a cold restart of the same world/WAL under Linux ext4 with strict fsync, 17 pinned MapDev mods and zero public ports. Two authenticated real clients, F8 visual, all providers/catastrophes and G5 remain independent gates.
