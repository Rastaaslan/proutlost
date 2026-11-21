# P008 — LAB shutdown close acknowledgement, isolated proposal

Native observation supplied by root: R4 server exited0 with BOOTSTRAP_SHUTDOWN_PASS, but neither AUTOSTORE_CLOSE_PASS nor AUTOSTORE_CLOSE_FAILED_SAFE. This remains **J04 close unconfirmed**, not data loss and not durable-close success.

Source cause: DirectorServerLifecycle LAB stopping queues checkpointAndClose and leaves only a whenComplete logger after stopped. JournalStore writer is daemon and its close waiter is virtual/daemon. The JVM may end before checkpoint/close or callback. A real local child-JVM regression proves exit0 with a requested pending daemon close and no terminal callback; no Minecraft/J04 is exercised by that counterexample.

## Minimal root integration

1. Verify manifest and exact R4 preimage SHA in P008_LAB_CLOSE_PREIMAGE.json. Apply DirectorServerLifecycle.LAB_CLOSE_ONLY.patch once. Only the LAB close block changes; the existing production close path is byte-identical.
2. Copy the new pure DirectorShutdownDrain.java and its test into corresponding source directories. Append lab-close-gradle.fragment to build.gradle, preserving existing tasks. Do not change frozen R4 sources/archive/binaries.
3. Build a new explicit R5 candidate and run directorShutdownDrainPureTest plus affected lifecycle/persistence regressions and class-body/manifest verification. This proposal has no native compile/acceptance.

The helper uses the real server.managedBlock supplied by the native lifecycle, bounded to10 seconds by a monotonic elapsed-time predicate. It never joins on normal ticks, forces completion, suppresses a failure, changes writer daemon policy, changes production behavior or prints PASS. When the future completes, finishClose preserves exceptional completion as FAILED_SAFE. Timeout explicitly prints DIRECTOR_NATIVE_CHECKPOINT_CLOSE_UNCONFIRMED. The existing stopped callback emits its actual terminal result only if it runs. Normal bootstrap shutdown is a distinct lifecycle fact.

## Native acceptance before closure

- Preserve the full closed R4 world/J04 and every absent-marker probe with exact hashes before any reopen. Do not modify retained QA-00–QA-05 worlds or consumed IDs.
- New identified JVM, same world/store identity and compatible verified candidate. Require genuine J04 recovered authority/new boot epoch, no failed-safe recovery, no source reimport/activation/recreation/reset.
- Read the previously durable source generations and all required heads through bounded real J04 reads; compare exact source namespace/revision/digest/active-generation to pre-stop proofs. Compare dense600region+600location+600anchor bodies, old80region/600location/600anchor source and separate4-head source independently. A recovered pointer alone is not all-head proof.
- Check the actual game consumers/F8 on a coherent new snapshot. Global revision/cut/tick may advance due legitimate boot/J01 writes; source generation/body identity must not change. Separate technical cold recovery from canonical reception.
- Stop the new owned QA JVM normally. Require the real AUTOSTORE_CLOSE_PASS, process lifetime end, closed locks and independently verified archive/readback. A10-second timeout keeps close unconfirmed and requires investigation; do not relabel it PASS. Preserve original R4 unconfirmed result even if R5cold succeeds.

## Local evidence

Java21 -Xlint:all -Werror compile PASS.24 assertions cover a real zero-exit daemon-only JVM without close callback, a bounded new-JVM completion, actual daemon/virtual-worker concurrency, pending timeout, original exceptional future preservation, early scheduler return and nanoTime wrap. These are local shutdown coordination tests, not J04 fsync/recovery, Minecraft shutdown, native acceptance or FULLSTACK_ACCEPTED.
