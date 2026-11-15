# CB-202 — G0 candidate, SPEC/API 1.0.0

**NOT_FROZEN. No G0_PASS.json. J01–J08 must not start.** Source and contract tests are implemented. Work supplied successful final-snapshot Gradle evidence; Work owns the local commit and freeze decision. No product decision or credentials are missing. This replaces the earlier input-visibility blocker audit; that blocker is resolved.

## Baseline and scope

- Worktree: `/var/lib/codexbridge/worktrees/CB-202`; actual base/HEAD `9d895ecf335f2cc7770f7f89b328c48e5e3173d3`, branch `codexbridge/CB-202`. No new source commit: shared objects/refs are read-only under the execution instructions.
- Initial checkout was clean before the historical audit. Root AGENTS read; the ticket overrides its Director exclusion only.
- Pins preserved: Minecraft **1.21.1**, NeoForge **21.1.248**, Java **21**, ModDevGradle **2.0.144**, wrapper **8.14.4**, Parchment **2024.11.17**. Host `javac 21.0.12.1`.
- Baseline packages: `fr.proutlost`, `fr.proutlost.worldprep` (analysis, area, biome/v2, build, compiler, ecology, gametest, geology, job, location, mutation, ore, override, pack, persistence, pipeline, plan, preview, recovery, runtime, snapshot, storage, structure, transaction, validation, world). WorldPrep source unchanged.
- Added isolated `director`, `directorTest`, `directorPlatform` source sets. The Director classpaths use configurations.compileClasspath/runtimeClasspath (dependency configurations only, not SourceSet file collections), so future adapters compile without WorldPrep code. The isolated test script compiles pure packages with JDK only, then adapter/client/platform sources against the real cached platform with --platform. The separate TEST_ONLY platform probe is never included in the Director JAR. Existing mod build preserved; no server/client launch or optional integration required by bootstrap.

## Normative provenance — PASS

All 40 files were read before initial source edits: README, all WORLD_DIRECTOR documents, every schema/example, calibration profile, validation script/report, MANIFEST, source hashes, MISSION_SOURCE and CONCEPT_REFERENCE. Later targeted rereads informed corrections.

The authorized channel delivered a deterministic repack into ignored `build/director-input/`. `spec.tar.xz`: 90,696 bytes; SHA256 `1b00564384c295733beff865707f0da9267b4b0b8a8c1f98bd63fb1da9ef07f2`. Safe extraction checked relative regular-file paths, no traversal/links. All 39 MANIFEST entries and both SOURCE_SHA256 entries verified. Official ZIP hash supplied by operator: `ad09d2b65f234107e8db2e45778b7a78800a2d6806e32fa7570cb4adc7041252`; the ZIP itself is not locally available. Normative files remain outside tracked diff. Validation runs on a writable ignored copy.

`NORMATIVE_INPUTS.json` records all paths, sizes, SHA256 and computed Git blob IDs. These IDs are content identity checks, not a claim of published objects. Work can copy all 40 exact files directly from this worktree for later jobs; source visibility is resolved.

## Historical prototype audit — read-only

| Branch | Observed commit | Director paths |
| --- | --- | ---: |
| director/d2-integrated | 69eda7982e2e659a5ef15c6ea55c7975686218d2 | 172 |
| director/d3-integrated | c789e17999c93cfb858a562b79ab082f2cb284d8 | 192 |
| director/d4-integrated | d6c96837f4e37890a769f7c4b92a776dac157ec2 | 200 |
| director/d5-integrated | b4c1828c15d7eb3a3b2c9c6bee257e3daebda262 | 213 |
| director/d6-integrated | ba9e460f241b3b4dd2cb1de8d385047e24116942 | 213 |
| director/d7-integrated | 084b73a447af09796798becdddbbde04ff985f91 | 236 |

Inspected branch inventories and d7 API document, StrictCodec, Clock/RandomSource/Limits, plus d2 Limits and historical pinned-build script. **CONFLICT:** old `fr.proutlost.contract.director` IDs, singleton major-event limit and fixed season constants conflict with S2. **OBSOLETE:** old disabled bootstrap/API documents cannot qualify 1.0.0. **ADAPT:** strict parsing, golden/fault-test patterns. **REUSABLE:** pinned compilation strategy and authentic platform dependencies. No prototype copied over, checked out, executed, changed or deleted.

Read-only cache discovery found the pinned NeoForge universal and compiled Minecraft/NeoForge artifact under `/var/lib/codexbridge/.gradle/caches`. Their paths/hashes are in ignored `build/director/platform-dependencies.json`. Gradle modules were copied into a private build cache; no shared cache writes.

## Implemented boundary

- Eleven principal ports plus primitive, optional location/AI transport/perception contracts; UUID/resource IDs, immutable aggregate/snapshot DTOs, enums and state-transition guards. Rich observation contexts separate sensors from normalized scoring features.
- AI projection uses pseudonyms and complete bounded request/proposal/hash/deadline/arc contracts; accepted numeric proposal metadata is retained in audit. No AI world port. Typed admin operations reject payload mismatch. Full perception/session/admin network DTOs and binary UUID/VarLong header codec.
- Canonical bounded JSON and closed model codec. Dedicated DirectorMessageCodec preserves normative envelope/request wire shapes; compiled content DTOs are separate from J02's authored-content loader. No arbitrary class loading/native Java serialization.
- TEST_ONLY calibration loader verifies all 231 typed keys and exact provenance. Clock snapshot/source and Java21 SplittableRandom preserve clock-domain separation and draw evidence.
- Private-constructor CommitPermit: validated PREPARE/read-set/reservation binding, injected durable writer, matched durable ACK, actor-only polling, post-ACK/current precondition checks, expiry and one-use slice binding. Completion callbacks only deliver ACK data. DurableStore is a trusted adapter boundary; this is not a sandbox against malicious in-process Java/reflection.
- Production bootstrap rejects TEST_ONLY providers, dependencies and capability/primitive provenance mismatches. Missing ports/capabilities remain explicit; no production fake or domain runner. Testkit has controlled clocks, delayed/failing store ACKs, observation fixtures, effect/fault boundaries.

## Final review corrections

- Closed `AggregateState` union transports all 16 durable families, complete headers and restore sequence/hash. `Reduction` returns complete expected-revision changes; `ObservationBatch` transports rich context, typed episodes/transitions and exposure inputs. No J01 reducer implementation was added.
- Camp/route sufficient statistics retain per-window contributions (up to 16 windows, 1024 episode IDs per window; 128 camp/256 route entries). Test-only union fixtures demonstrate exact W1+W2+W3 totals for overlapping A/B. No trajectories or psychological/social data.
- Immutable copied bounded blobs travel in DurableBatch, with SHA/codec references and asynchronous reads; all referenced bytes must be forced before ACK. DurableRequest preserves original envelope, payload, state and semantic idempotence hash.
- Permit Authority keeps only bounded inflight mailboxes, uses getNow and durable operation claims rather than an eternal seen cache. Fixtures cover more than 65536 completed operations, foreign issuer, replay, late ACK, reboot, missing forced blobs and stale bindings. J04 must implement the documented durable operation-claim atomicity; the TEST_ONLY ledger is an oracle, not production persistence.
- Domain import guards follow ARCHITECTURE §1, including neighbor implementation restrictions and no WorldPrep. Adapter/client/bootstrap may use platform classes. Testkit is excluded from the production packaging fixture; directorJar includes only director.output.
- Technical MOD_ID is `proutlost_director`. J06 perception assets, J07 GUI/lang and J05 GameTest resources are exclusively mapped in OWNERSHIP. Metadata and final lifecycle composition remain with Work.

## Final codec and decision/capture boundaries

- Normalized JSON numbers use the same precision (384) and scale (±340) limits on construction and parsing. Plain decimal remains canonical up to 400 characters; larger values use bounded scientific notation. Exponent/precision roundtrips include the reported 70-digit coefficient × 10^340 regression.
- BlobCodec shares reference (1024 bytes) and frame (8 + 1024 + 16 MiB) bounds in both directions. Tests cover long IDs and the exact maximum frame.
- DecisionInput contains immutable DecisionState eligibility evidence and optional recorded AiProposalResult. Versioned EngineControlState persists pacing entry/deadline, tension EMA/update/recovery window and REQUIRE credit inside DirectorWorldState. DecisionProposal returns AggregateChange updates. No engine algorithm or calibration added.
- BlobBundle carries bounded immutable captured/hydrated bytes in PlanningSnapshot, EffectPreparation, RecoveryProbe and CleanupIntent. PreparedEffect/PREPARE remain reference-only. The capture→batch→simulated reboot→async read→recovery fixture preserves four distinct byte contents and hashes.
- Removed SourceSet runtime classpath subtraction: it retained main builtBy dependencies. Dependency configurations now supply platform libraries. An isolated Director task-graph guard rejects compileJava/processResources/generateModMetadata/classes, including during --dry-run. Local dry-run failed before configuration with the existing wildcard-IP error; final graph verification subsequently passed on Work’s clone; see the authenticated evidence below.

## Executed checks

1. `python3 scripts/verify-director-spec.py` — **PASS**: 40 files, 39 manifest entries, 2 source hashes; 5 schemas, 14 positive fixtures, 18 negative cases, 231 keys. Normative checker is the supplied evaluator, not a general JSON Schema conformance certification.
2. `python3 scripts/test-director.py --platform` — **PASS**: Java21 `-Xlint:all -Werror` compile; **68,926 assertions**; independent bootstrap; real pinned platform compile. Exact nested commands and exit codes: `build/director/executions.json`. Nice19/ionice idle, bounded JVM. Tests cover normative REQUIRE/AI/import and binary-header goldens, strict JSON rejection, rich decision context, AI privacy/correlation/deadline, admin authority/shape, session epochs, durability failure/staleness/expiry/single-use, production fake/dependency rejection, clocks/state guards and architecture.
3. Gradle invocation below — **FAIL_ENVIRONMENT**, before project configuration: `Could not create service of type FileLockContentionHandler ... Could not determine a usable wildcard IP for this machine.` Work subsequently reported PASS on previous export dcb035f5...: directorContractTest (2124 assertions), directorBootstrap/directorJar in 16s and compileDirectorPlatformJava in 29s, offline private cache. This establishes prior wiring only; Work has now validated the final b4196a26… snapshot as recorded below. No privilege/network bypass attempted.
4. Native/client/GameTests/world crash/performance qualification — **NOT_RUN**, outside G0 tests. No runtime capability is claimed qualified.

Attempted Gradle command (single build under mission lock):

```sh
GRADLE_USER_HOME="$PWD/build/director/gradle-home" nice -n 19 ionice -c 3 flock build/director-gradle.lock /var/lib/codexbridge/.gradle/wrapper/dists/gradle-8.14.4-bin/92wwslzcyst3phie3o264zltu/gradle-8.14.4/bin/gradle --offline --no-daemon --no-parallel --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1g compileDirectorJava directorContractTest directorJar
```

Work first verifies the final task graph with `nice -n 19 ionice -c 3 flock <shared-mission-lock> ./gradlew --offline --no-daemon --no-parallel --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1g --dry-run directorContractTest directorBootstrap directorJar`. No main compileJava/processResources/generateModMetadata/classes task may appear; the graph guard fails if one does.

Trusted host equivalent: `nice -n 19 ionice -c 3 flock <shared-mission-lock> ./gradlew --offline --no-daemon --no-parallel --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1g compileDirectorJava compileDirectorPlatformJava directorContractTest directorBootstrap directorJar`. Supply `-PdirectorSpec=<verified ignored input directory>` if needed. No full world generation required.

## Handoff

- **IMPLEMENTE_EN_SOURCE:** G0 contracts/bootstrap/config/codecs/testkit and isolated build wiring, including review fixes. No J01–J08 implementation.
- **COMPILE:** PASS direct Java21 + pinned API probe; prior operator Gradle PASS attributed above, final operator dry-run and build PASS (platform compile UP-TO-DATE with validated cache).
- **TESTE:** PASS contract/golden/config/schema/architecture/fake/permit checks; native/client NOT_RUN.
- **BLOQUE:** no source blocker or product decision. Final integrator qualification remains a Work handoff, not a sandbox G0_PASS.
- **public_interfaces_changed:** new `fr.proutlost.director.api` candidate API 1.0.0; exact files/digests in `API_MANIFEST_1.0.0.json`.
- **capabilities_added:** contract descriptors only; zero qualified production world capabilities.
- **migrations_added:** none; persistence migrations belong to J04.
- **integration_dependencies:** explicit per-job consumed/produced contracts and exact paths in `OWNERSHIP.json`; shared API/bootstrap/config/build become integrator-only after freeze.
- **next_smallest_blocker:** none for source/Gradle validation; Work creates its local commit and reviews the freeze. Do not generate G0_PASS merely from this audit.

`TEST_EVIDENCE.json` binds logs to the candidate source digest. `scripts/director-evidence.py` checks compiled source hashes and exports ignored `build/director/G0_SOURCE_EXPORT.tar.gz`; it neither writes Git nor issues PASS. No push, publish, merge, deployment, active-world/config/process change, or source commit performed.

## Final operator attestation — PASS, not frozen

The authentic Work bundle is preserved intact in [OPERATOR_GRADLE_EVIDENCE.json](OPERATOR_GRADLE_EVIDENCE.json). Its two UTF-8 stdout logs are preserved byte-for-byte in `operator-logs/`; both SHA256 values were recomputed locally and match the bundle. No host path was accessed to import this evidence.

- Tested source digest: `b4196a26d5f798edb9a7aba22a852eec61c6d781eaecdef35b49735936cfd8a3`; all 491 manifest files verified unchanged locally. Work attests matching checks before/after its build.
- Dry-run: exit 0, 9 seconds; build: exit 0, 1m58s; 68,926 assertions. compileDirectorJava, directorContractTest, directorBootstrap and directorJar executed; compileDirectorPlatformJava was UP-TO-DATE from the operator-validated cache.
- Both logs contain all five Director tasks and no main compileJava/processResources/generateModMetadata/classes task. Exact commands, constraints, timestamps and artifact hashes are in the bundle. This supersedes the local environment failure as the final wiring evidence; that historical failure remains recorded honestly.
- Dry-run SHA256: `cc9796ef13885555e812d4bd8a85b3a2d17eac48aea9460e68935a771e9f53fa`.
- Build SHA256: `e896d325f9513118e54fb6e379eab86d75640976e49a55e7f7c8a612054ce1b0`.
- This update changes handoff metadata only. API/source manifest and tested code are immutable. The refreshed export adds these attestations; the bundle’s archive hash intentionally identifies the original tested export. No tests were needlessly rerun. Native/client/G4 remain NOT_RUN. G0_PASS and source commit remain Work-owned; no J01–J08 launch.

## Review 17 — primitive argument bounds

TargetSound now rejects negative volume/radiusBlocks; TargetParticles rejects negative radiusBlocks, retaining finite-value checks. Zero remains valid. Constructor tests include negative subnormal values; codec tests reject negative JSON fields and roundtrip zero values. Only these two model files and ContractTests changed in the source manifest.

`python3 scripts/test-director.py --platform` — PASS: 68942 assertions, Java21 compile, pinned platform compile, bootstrap and packaging. The preserved operator bundle still attests the prior b4196a26 snapshot; it is not relabeled as a build of this correction. Build wiring is unchanged. Current source digest: `dcc13554443fd3cc00f22c86a8f9686be6fa3ac18fc3fadde598c35191d530e2`. Commit/G0_PASS remain Work-owned. Native/client/G4 NOT_RUN.

## Review 18 — primitive argument bounds

Apparition now rejects negative maxDistanceBlocks, retaining its finite-value check. Zero remains valid. Constructor tests include negative subnormal values; codec tests reject negative JSON fields and roundtrip zero values. Only Apparition.java and ContractTests changed in the source manifest.

`python3 scripts/test-director.py --platform` — PASS: 68950 assertions, Java21 compile, pinned platform compile, bootstrap and packaging. The preserved operator bundle still attests the prior b4196a26 snapshot; it is not relabeled as a build of this correction. Build wiring is unchanged. Current source digest: `82de69ef8b7aae1153456ee722ae17a42506c5bc7738388b95ea3a46e999e2ea`. Commit/G0_PASS remain Work-owned. Native/client/G4 NOT_RUN.

## Review 20 — normative structural bounds

SharedChoice enforces nonempty unique roles and at least two distinct choice IDs. EventStep enforces unique predecessors and the schema step-ID pattern. ChainDefinition enforces positive revision, nonempty transitions/terminals, REGION or WORLD scope, RECOVER or ABORT_SAFE recovery and DEFAULT_FOR_TESTING calibration. ImportedLocation rejects empty external IDs. Adjacent string and collection guards reviewed. Tests mutate one field of valid fixtures at a time through both constructors and ModelCodec; positive roundtrips are preserved. No signatures or gameplay algorithms changed.

`python3 scripts/test-director.py --platform` — PASS: 69043 assertions, Java21 compile, pinned platform compile, bootstrap and packaging. The preserved operator bundle still attests the prior b4196a26 snapshot; it is not relabeled as a build of this correction. Build wiring is unchanged. Current source digest: `bed080ee38357b779654f055221ac3ed80a275ae66517ff8261f035bc22a8e57`. Commit/G0_PASS remain Work-owned. Native/client/G4 NOT_RUN.

## Review 21 — evidence inventory binding

director-evidence.py now compares the complete current Java inventory in director, directorTest and directorPlatform with compiled_source_sha256 before verifying every hash or writing evidence. The --verify-only mode performs the same gate without changing handoff documents. python3 scripts/test-director-evidence.py PASS: additions in each source root, modifications and deletions reject the real generation entry point before evidence writes; restoring the exact tested inventory succeeds. No API or gameplay changes.

`python3 scripts/test-director.py --platform` — PASS: 69043 assertions, Java21 compile, pinned platform compile, bootstrap and packaging. The preserved operator bundle still attests the prior b4196a26 snapshot; it is not relabeled as a build of this correction. Build wiring is unchanged. Current source digest: `e769c9748318deffa7f81ad5352f4453e26e961ddb76abdbb969cbc1bcfbcbb2`. Commit/G0_PASS remain Work-owned. Native/client/G4 NOT_RUN.

## Final documentary handoff — Work ownership

IMPLEMENTE_EN_SOURCE: existing G0 foundation; all 492 manifest entries are now read-only. public_interfaces_changed: none in this handoff. capabilities_added: none. migrations_added: none.

COMPILE: PASS operator on exact source `e769c9748318deffa7f81ad5352f4453e26e961ddb76abdbb969cbc1bcfbcbb2`: compileDirectorJava executed; compileDirectorPlatformJava UP-TO-DATE with valid operator cache. TESTE: operator dry-run/build exit 0, 69,043 contract assertions; evidence regression exit 0. No main compileJava/processResources/generateModMetadata/classes tasks. Operator attests JAR excludes testkit, WorldPrep and platform probe. Full commands/stdout/hashes: OPERATOR_GRADLE_EVIDENCE.json and operator-logs/G0_*21*.log. No Java rerun during this documentary handoff. Native/client/GameTests/G4: NOT_RUN. Eleven implementation ports remain absent as expected for G0; no G1–G5 qualification inferred.

BLOQUE: no source or validation blocker known. integration_dependencies: Work integration ownership, local commit and G0_PASS certificate. next_smallest_blocker: Work certificate/commit; no J01–J08 launched here. Source commit: not created here; base remains `9d895ecf335f2cc7770f7f89b328c48e5e3173d3`. Own path: /var/lib/codexbridge/worktrees/CB-202.

Immutable source/JAR paths and hashes are recorded in FROZEN_HANDOFF.json, an external checksum receipt written after the archive (excluded from its own archive to avoid a recursive hash). Archive contains all manifest files and all other handoff documents/operator logs. These artifacts must not be overwritten; later source defects require API_CHANGE_REQUEST to Work.
