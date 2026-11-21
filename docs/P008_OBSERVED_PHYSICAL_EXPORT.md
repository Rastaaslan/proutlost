# P008 — fresh observed physical export

This is a new geometry-only source, not a historical V5 receipt, a complete Contra catalogue, author semantics or a Director import. It preserves the one-way producer boundary and writes only immutable export/evidence sidecars inside an explicitly staged candidate.

## Existing path and missing connection

`DirectorStructureExportBridge` already validates completed native structure-module receipts, but emits G0. `NativeV5QualificationSession` can emit a D0 catalogue after its nine phases, but it does not expose a bounded observation/export route for the current independently prepared supports. The new route connects existing native receipt provenance and actual already-loaded cells to independent Anvil readback, then to `PhysicalCatalogExporter`.

The exporter never assigns capabilities, surfaces, actions, anchors, channels, protection policies, recovery rules or `EventNeed`. Native model tags can be carried only as an explicitly requested subset of a checked native module record. Generic observed cells carry no tags. Author inputs remain separate.

## Acceptance scenario, defined before native execution

In a new private QA copy, an existing saved 3×3×3 support is observed through the registered OP4 command. The actual loaded-state digest equals independent Anvil state for every cell, the complete sealed catalogue contains exactly that support and its current evidence, and an identical retry returns the same immutable snapshot. A missing chunk is refused without loading/generation. Semantic lists remain empty. No event is run and no source is activated in Director.

Native GameTest: `proutlost:observedphysicalexportusesrealloadedandanvilwitnesses` (method `WorldPrepPhysicalGameTests.observedPhysicalExportUsesRealLoadedAndAnvilWitnesses`, batch `p008-physical-survey`). Use only its new isolated staged QA world. The existing `qualification/arena` supplies the blocks; the harness saves its own fixture, while the collector itself never saves or mutates terrain.

Operator prerequisites for that QA world:

- Target MC 1.21.1 / NeoForge 21.1.248 / Java 21 and the exact newly compiled binary.
- `WorldPrepConfig.ENABLED=false`; no native qualification/physical/standalone module writer active.
- Existing `worldprep-candidate.json` with `schemaVersion=1`, exact `candidateDirectory`, `offlineBaseline=true`, and **`physicalSurveyScope="SYNTHETIC_QA"`**. The test does not create or overwrite this marker.
- Run at an already inspected, loaded FULL-chunk fixture outside the spawn reservation, as required by the existing physical arena.

The GameTest executes the actual registered command and checks real LoadedCell/Anvil witnesses, sealed geometry, empty semantics, immutable readback and missing-chunk refusal. Its native execution is **pending** until run by the integrator; compilation or the pure tests do not close that gate.

## Real source execution on a private traceable copy

1. Establish a coherent copy and exclusive QA owner. Preserve the live/active Debian COW and all original receipts. Keep the source-to-QA copy manifest and original identity separately from the new physical source/build identity.
2. Place the existing receipt sidecars under the copied world's normal module path, preserving their bytes. Do not create replacement placement receipts. If origin is claimed as native, the adapter requires a completed native structure-module batch, no rollback intent, exact native candidate record bytes, matching placement/plan/disk receipts and an explicitly contained support geometry. Non-QA sources refuse LAB biome/forced placement.
3. Set the private copy's explicit marker `physicalSurveyScope="PHYSICAL_GEOMETRY_ONLY"`. Choose a genuine support geometry from the validated physical inputs. The route cannot observe unloaded chunks; load only existing FULL chunks through the laboratory's already approved mechanism before invoking it, never by asking this collector to generate them.
4. Build the bounded request with `tools/make-observed-physical-survey.py`, for example:

```sh
python3 tools/make-observed-physical-survey.py \
  --world /absolute/owned-qa-world --seed 27594267 \
  --source-world proutlost:contra_s2 --source-build proutlost:p008_new_physical_source \
  --revision p008-physical-r1 --scope PHYSICAL_GEOMETRY_ONLY \
  --native-module worldprep-v2/structure-module/EXACT_BATCH/placement-0 \
  --bounds MIN_X MIN_Y MIN_Z MAX_X MAX_Y MAX_Z
```

The six values are inclusive and must come from the exact selected support. For a native module, the tool derives a **new support identity** from the original module location plus exact geometry; it does not rename a cropped support as the entire original structure. `--tags` is optional and must be a subset of the native record. Omit `--native-module` only for an explicitly observed existing-cell support; that mode has a fixed observed origin and no model tags. The tool creates an intention only, not a receipt.

5. Execute the printed command, `proutlost worldprep physical survey-export survey-UUID.json`, with the actual QA server OP4 source. Requests live under `worldprep-surveys/requests`. Unknown fields, duplicates, wrong marker SHA/seed, paths outside the fixed local request directory, absent provenance, unsupported geometry, unloaded cells, BlockEntities and loaded/disk differences refuse the export.
6. Verify `worldprep-surveys/exports/UUID/catalog.json` against `evidence/receipt.json` and its immutable request/witness records. A success is `PHYSICAL_GEOMETRY_ONLY_D0_SEMANTICS_UNAUTHORED_NOT_IMPORTED`. An identical retry verifies the retained snapshot and returns `RETAINED_SNAPSHOT_VERIFIED_NOT_CURRENT_OBSERVATION_OR_IMPORT`; it never pretends to perform a fresh observation.
7. Only after explicit author requirements and physical semantics are available, qualify the ordinary actual Director configuration/admission/selection/effect/recovery route. This command does not supply `adapters.json` or bypass J04.

## Bounds, retention and interruptions

- Request ≤32 KiB, 1–32 distinct targets, ≤8 geometry boxes per target, ≤32768 cells in the complete request. Each native capture is also bounded to 96 distinct already-loaded chunks, with one decoded Anvil chunk cached at a time.
- The existing D0 loaded-state validator's digest is reused exactly. Its current no-BlockEntity / ≤32768-cell restriction is preserved. A full structure containing BlockEntities is not silently accepted or truncated: an explicit smaller support has a distinct identity and scope, or the request is refused.
- Both loaded observations bracket independent Anvil reads. Missing/non-FULL/wrong-coordinate/oversized/corrupt persisted chunks fail through `ReadOnlyChunkNbt`; no RegionFile writer, chunk ticket, load, generation or terrain save is requested.
- All target witnesses are complete before catalogue publication. The final immutable receipt is written after forced evidence and catalogue. A crash before the final receipt is an incomplete attempt. Identical retries reuse frozen capture time and cannot overwrite conflicting witnesses/catalogues. A changed physical state needs a new explicit survey identity.
- These sidecars are producer evidence only; they are not a second runtime authority. Director's normal durable admission remains mandatory.

## Executed tests and limits

- `PhysicalCatalogExporterResolverScenario`: 15 stable assertions PASS on explicit synthetic semantics through actual exporter → actual J04 → actual D0 resolver, with legacy no-inference control. This is separate from the geometry-only producer.
- `ObservedPhysicalCatalogProducerScenario`: 23 checks PASS for closed decoding, refusal cases, complete witness binding, metadata preservation, absent invented semantics, sealed export and immutable publication. Its witnesses are explicitly synthetic.
- A separate fresh JVM verified the pure catalogue file and expected digest. This is artifact durability, not a Minecraft world restart.
- Native adapter/command/GameTest compilation and execution are pending the pinned FIXE build/runtime. Actual Contra supports, author semantics, complete V5/fullpack reception and human perception are not claimed.
