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
