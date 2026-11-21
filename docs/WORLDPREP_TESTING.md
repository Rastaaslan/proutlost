# WorldPrep testing

Run `./gradlew build`. Unit tests cover normalized negative-coordinate areas, terrain fields, deterministic planning/fingerprints, registry fallbacks, protection precedence, ore host safety, snapshot integrity, and job pause/resume. Runtime biome writes, SavedData recovery, block apply/rollback, GameTests, dedicated-server startup, and River's disposable-island acceptance procedure remain mandatory before readiness may be declared.
## Manual MapDev biome acceptance (completed)

The real 1.21.1 dedicated-server MapDev flow passed player command execution,
selection and persisted area creation, analysis, preview, biome apply, restart
persistence after apply, exact biome rollback, and restart persistence after
rollback. The observed mutation was `minecraft:deep_ocean` to
`minecraft:ocean`, followed by restoration to `minecraft:deep_ocean`.

The timing-sensitive interrupted mid-APPLY restart/resume case and representative
large-area chunk-unloading timing remain **not manually tested**. These are known
limitations, not a downgrade of the completed biome acceptance gates.

## Block-pass runtime acceptance

NeoForge GameTests now exercise the real persisted `BlockMutationRuntime` apply
and rollback executor against real Minecraft chunks and `BlockState`s. The suite
covers geology mutation, protection, exact rollback; ore stone/deepslate hosts,
vertical ranges, protection, block entities, invalid hosts, exact rollback; and a
controlled post-mutation failure with preserved cursor/journal and usable rollback.
These automated runtime gates make the slices suitable for a controlled MapDev
trial; they do not replace manual acceptance or the outstanding restart timing test.

## MapDev geology persistence incident and hotfix

A controlled `ocean_test` MapDev preview **passed** after scanning 474,240 block
positions and persisted 5,015 intended geology replacements. The subsequent real
`APPLY_GEOLOGY` **failed** with a Java heap `OutOfMemoryError`; the old executor
called a full `DataStorage.save()` after adding every individual journal entry, so
NBT repeatedly copied and compressed the growing SavedData graph. Real MapDev
geology APPLY is therefore **not yet recorded as passed**.

The hotfix replaces that loop with 256-change two-phase batches. Each batch first
records all eligible intentions and saves once, then idempotently mutates only the
persisted planned changes, advances its cursor, and saves once more. GameTests cover
the journal-first interruption window, partial-batch rollback, ownership conflict,
and a 600-change real-chunk plan. Unit tests assert that 5,000 changes require a
batch-proportional number of checkpoints (tens, not thousands).
