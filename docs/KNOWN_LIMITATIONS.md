# Known limitations

The current checkpoint contains controlled biome, geology, and ore vertical slices, but is not a production-ready environmental compiler. PNG output and later environmental passes are not implemented, and the new block passes still require manual MapDev acceptance. Do not use this build on River's map or interpret automated tests as River acceptance.
* Biome apply/rollback and persistence across dedicated-server restarts have been
  manually accepted on MapDev. Interrupting APPLY mid-job and then restarting to
  exercise resume has not yet been manually performed.
* Representative large-area operation under chunk-unloading timing has not yet
  been manually exercised.
* Geology and ore profiles are intentionally development distributions rather
  than exact vanilla parity. Their real BlockState apply, protection, failure,
  and rollback paths pass NeoForge GameTests, but manual MapDev acceptance is
  still required before production use.

## Block persistence hotfix limitations

The old per-block SavedData save design caused a real MapDev geology APPLY heap
exhaustion after a successful 474,240-position preview with 5,015 planned changes.
That APPLY is not accepted and motivated the checkpointed persistence refactor.
Plans remain represented in memory as a single map in this hotfix, but now fail
closed at an explicit 250,000-change ceiling rather than growing without limit;
chunk/page-streamed plan storage remains future work. Legacy block persistence
(format before version 2) is deliberately not resumed. An administrator must retain
the old world backup and create a fresh preview/job. Completed biome persistence is
stored separately and is unaffected. A controlled MapDev retry and a real process
kill during a batch are still required before River use.
