# P008 native paged import observer — declared synthetic QA

This scenario qualifies the actual native admission, the existing J04 writer and
the actual selected game readers. It does not qualify canonical Contra physical
inputs, human perception or fullstack delivery. No additional store/player is
created by the observer, and it never submits an import or moves a player.

## Files and registration

- Compile `DirectorNativePagedLocationImportGameTests` in the existing
  `directorGameTest` source set; the class is excluded from the production JAR.
- In `DirectorStandaloneMod`'s opt-in `RegisterGameTestsEvent` suite, register
  `fr.proutlost.director.adapter.neoforge.server.DirectorNativePagedLocationImportGameTests`.
  A surgical replacement is in
  `build/p008-planner-pure/native-paged-registration.json`; no global build change
  is required.
- Compile `NativePagedLocationQaInput` in `directorTest`; this pure utility only
  creates declared synthetic fixture files and refuses an unmarked QA world.

## Prepare an isolated QA world

Use a newly assigned traceable QA copy with the exact `TEST_ONLY` marker
`PROUTLOST_NEUTRAL_QA_V1`. Keep the world's SavedData identity, J04 and later
observer sidecars together. The same whole directory is reopened for cold
verification. Never use the retained canonical world or consumed event IDs.

After the actual world's UUID is known, run the compiled pure fixture utility:

```bash
java -cp "$DIRECTOR_TEST_CLASSPATH" \
  fr.proutlost.director.bootstrap.NativePagedLocationQaInput \
  "$QA_WORLD_ROOT" "$ACTUAL_WORLD_UUID" "qa:p008_l2_unique_run" p008_qa_transfer
```

The utility creates `director-location-imports/p008_qa_transfer/` with a canonical
manifest, pages created in reverse order and one identical duplicate. It also
creates `p008_qa_transfer-expected.json` **outside** that directory. The latter is
the read-only observer's expected source, not an import admission file.

The declared source has 80 disjoint regions, 600 locations and 600 anchors,
requiring 1,360 aggregate heads. The utility prints source/manifest SHA-256 and
the first/far positions. A locally exercised sample encoded to 356,266 bytes,
18 source pages and 19 page messages including the duplicate; every wire file
was at most 32 KiB. Those are fixture checks, not Minecraft results.

## Actual QA JVM flags

Append these flags to the actual isolated server JVM, using the source SHA printed
for its exact UUID/source fixture and the UUID of a real connected QA client:

```text
-Dproutlost.director.nativeGameTests=true
-Dproutlost.director.nativeLab=true
-Dproutlost.quest.neutralLab=true
-Dproutlost.quest.neutralLabRoot=ACTUAL_ABSOLUTE_QA_WORLD_ROOT
-Dproutlost.director.nativePagedLocationImportGameTest=true
-Dproutlost.director.nativePagedLocationImportRecoveryOnly=false
-Dproutlost.director.nativePagedLocationImportName=p008_qa_transfer
-Dproutlost.director.nativePagedLocationImportSource=qa:p008_l2_unique_run
-Dproutlost.director.nativePagedLocationImportSha256=EXACT_SOURCE_SHA256
-Dproutlost.director.nativePagedLocationImportOperator=REAL_QA_CLIENT_UUID
```

Use the ordinary paced QA server with a real connected client, so asynchronous
WAL and demand hydration get actual tick time. JVM flags must reach that JVM;
passing unrelated Gradle properties alone does not set these flags.

## First run

1. From the real OP4 QA client, run the actual existing LAB admission command:

   ```text
   /proutlost director lab locations import p008_qa_transfer
   /proutlost director lab locations status
   ```

   Alternatively the approved production bootstrap can request the same name.
   The existing LAB command wrapper specifically uses `labGameplay`; it must
   not be described as a production operator endpoint without corresponding
   native command wiring.

2. Correlate the command/bootstrap log with the generation's exact source and
   SHA. The read-only observer verifies terminal state and actual J04 state; a
   nearby OP4 player's presence alone is not proof that this player issued the
   command.
3. Place the actual QA client at the first printed position, then run:

   ```text
   /test run p008_paged_import_actual_j04_and_selected_readers
   ```

4. Wait for `P008_NATIVE_PAGED_ALL_HEADS_PASS` (1,360 heads, pages at most 96) and
   `P008_NATIVE_PAGED_FIRST_SELECTED_PASS`. The latter prints the far position.
5. Move the same actual client to that far position. The observer requires the
   far region/anchor/location to appear through actual native memory, primary
   region lookup and authored tag consumers. The far region must have been
   absent from the first selected memory. Both the selected metadata working
   set and J04 metadata cache remain at most 512 entries.
6. A final scoped success is
   `P008_NATIVE_PAGED_IMPORT_AND_SELECTED_READERS_PASS cold=false` plus the
   vanilla GameTest terminal success. Capture both with the exact binary hashes.

The observer queries complete source bytes and all expected heads from one
pinned J04 snapshot. Each actual head page is checked and discarded; it does
not retain 1,360 native state bodies as a test cache.

## Same-directory cold run

Stop the owned QA JVM, preserve the entire world/J04/input/sidecar tree, then
start a new JVM on that same directory with only
`nativePagedLocationImportRecoveryOnly=true` changed. Keep all exact fixture
SHA/source/operator values. Reissue the same actual command and wait for
`ALREADY_DURABLE`; do not manufacture a new source revision or source UUID.

Move the real client to the first position and run:

```text
/test run p008_paged_import_same_directory_cold_and_selected_readers
```

Repeat the first-to-far move after the first selected marker. Cold success
requires the original source, digest, generation, world/store generation,
directory file identity and selected region IDs, with a newer J04 boot epoch,
different process start and authority session, and a nonregressing durable cut.
OS PID reuse is permitted only with the changed process identity.

The first sidecar is preserved. A separate cold sidecar is created atomically;
an existing sidecar is never overwritten. Their names start with
`P008-paged-native-<source SHA prefix>-first/cold.properties`.

Durability and visibility failures, missing real-client demand, or a failed
native reader keep the GameTest open/failing. Fixture preparation, compilation,
the all-head intermediate log and the existence of a sidecar alone do not close
the full native scenario. Abrupt-JVM preparation/activation boundary tests are
separate J04 scenarios and must retain their separate results.

## Nonempty other-source baseline

Before the main paged import, run `NativeOtherLocationQaInput QA_WORLD_ROOT ACTUAL_WORLD_UUID` once. It creates one declared synthetic region/location/anchor at disjoint coordinates and transport `p008_other_transfer`. Admit it through the same real OP4 endpoint and observe DURABLE before importing the main source. The observer requires this exact baseline, checks its retained source manifest/bytes and four live heads at the same pinned cut after activation, and repeats those checks after a new JVM. It therefore refuses to claim other-source retention on an empty baseline. No writer or synthetic ACK is created by either fixture utility.
