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
