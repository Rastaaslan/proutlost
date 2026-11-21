P008 native R2 interruption campaign — declared synthetic QA only
================================================================

Acceptance scenario defined before the change
--------------------------------------------

Keep the exact original R1 source and first/cold sidecars. A second source
qa:p008_l2_other already has four active heads and must survive every step.
Generate R2 of qa:p008_l2_20261118 with the same 80 regions / 600 locations / 
600 anchors and identities. Only first-region maxX grows by 1; revision/digest
become new. Kill only the exact owned QA JVM after durable clock, sourcepage0,
metadatapage0 and activation boundaries. Each subsequent new JVM must show the
actual durable boundary and coherent old/new game readers. A log marker alone
does not qualify persistence or an exact boundary.

Known exact fixture identities for the existing a02 world
-------------------------------------------------------

    root=/workspace/P008-L2-20261118T062924Z-root-a02
    world=011e910f-674e-4464-81fd-bfb1dbf060b5
    source=qa:p008_l2_20261118
    R1 transfer=p008_qa_transfer
    R1 sourceSHA=1dfce91d9c7ce8566a6ef28e8ab2741f8c971037bc6c151e2c7eed59e3f66ea6
    R2 transfer=p008_qa_r2_transfer
    R2 sourceSHA=52f9b037b965534a85e216b1eb780dd8a43973b105386c8056e2a250031d0b0e
    R2 manifest/generation=378bf5e45f5377afbc87c00fb92385ba22f33ef41694f75c1e65b7e51f9f7635
    first region=8f513f61-171c-3890-a3e7-bde6dec93801
    R1 first-region maxX=1000064
    R2 first-region maxX=1000065
    expected R2 bytes=356264; source pages=18; native heads=1360
    first client position=1000004 64 1000004
    far client position=1080900 64 1000004

Compile and package only the QA changes
-------------------------------------

Reuse the already accepted installed-observer V1 recipe against the exact
official B05 Director JAR1428 and existing pinned dependency JARs. This bundle
contains byte-identical copies of its init, addon bootstrap/metadata and
assembler. Point -Pp008ObserverProposal to this new bundle. The assembler
replaces only DirectorNativePagedLocationImportGameTests*.class in the prior
QA derivative and verifies all official entry bodies unchanged. Stage NEW
outputs only after the owned JVM and launcher have ended, retaining V1 artifacts
and all process/exit/log receipts. No production recompilation is required.

Compile the pure fixture helper separately, against that SAME official B05 JAR:

    java com.sun.tools.javac.Main -source 21 -target 21 -Xlint:all -Werror \
      -cp /workspace/p008-observer-b05-base/proutlost-director-1.0.0.jar \
      -d /workspace/p008-edge-fixture-classes \
      /workspace/p008-native-edge-proposal/src/directorTest/java/fr/proutlost/director/bootstrap/NativePagedLocationQaInput.java

Root copies those helper classes and the exact frozen B05 JAR to the QA
container if compilation ran in the builder. Its only authority is writing NEW
transport input files in the TEST_ONLY world; it never opens J04.

    java -cp /workspace/p008-edge-fixture-classes:/workspace/p008-observer-b05-base/proutlost-director-1.0.0.jar \
      fr.proutlost.director.bootstrap.NativePagedLocationQaInput \
      /workspace/P008-L2-20261118T062924Z-root-a02/world \
      011e910f-674e-4464-81fd-bfb1dbf060b5 qa:p008_l2_20261118 p008_qa_r2_transfer revision2

    java -cp /workspace/p008-edge-fixture-classes:/workspace/p008-observer-b05-base/proutlost-director-1.0.0.jar \
      fr.proutlost.director.bootstrap.NativePagedLocationQaInput \
      /workspace/P008-L2-20261118T062924Z-root-a02/world \
      011e910f-674e-4464-81fd-bfb1dbf060b5 qa:p008_l2_20261118 p008_negative_transfer negative-cases

The fifth argument is optional: absence preserves original R1 behavior. Both
new modes require the preserved p008_qa_transfer-expected.json to be EXACTLY the
deterministic original R1 before writing anything. All output files use
CREATE_NEW. Fourth argument in negative-cases mode is syntactically validated;
the eight actual names are the explicit names below.

QA launcher V4
--------------

tools/run_linux_qa.py is a new V4 copy of local V2 plus the existing V3 mandatory
installedQaObserver=true flag. It retains the exact root/TEST_ONLY/whitelist/
port/config/process/resource guards, immutable per-launch argfiles, loopback
RCON and original launcher lock. Keep remote V3 and its receipts unchanged;
root places V4 and its controller in a NEW /workspace/p008-tools-v4 directory.

New launch options:

    --readback-run-id r2-clock-01
    --pending-transfer p008_qa_r2_transfer
    --pending-boundary clock

The run ID is cold-only, [a-z0-9_-]{1,48}. Pending transfer/boundary are a
cold-only pair and require a unique nonempty run ID. They inject the exact JVM
properties ReadbackRunId, PendingTransferName, ExpectedPendingBoundary. No
argfile is modified after launch. Default empty options preserve V3 behavior.
All first/cold starts still set installedQaObserver, nativeLab, nativeGameTests,
neutralLab, EXACT neutralLabRoot, original source/name/SHA and actual OP4 UUID.

Before the first R2 import (including its clock preparation), use --phase first
with R2 SHA/name. This does not require an R2 first sidecar before activation:

    python3 /workspace/p008-tools-v4/run_linux_qa.py --root /workspace/P008-L2-20261118T062924Z-root-a02 launch \
      --phase first --source qa:p008_l2_20261118 --transfer p008_qa_r2_transfer \
      --source-sha256 52f9b037b965534a85e216b1eb780dd8a43973b105386c8056e2a250031d0b0e

Do not start an observer expecting R2 while R1 remains active. Wait for READY,
both actual clients to reconnect and the actual QAAlpha OP4 UUID to be present.

Four interruption boundaries and new-JVM readbacks
------------------------------------------------

For each boundary, root arms the controller as a separate owned process and
waits for its ARMED_NO_KILL_YET receipt BEFORE issuing the real import command.
The controller follows only new lines of the exact owned JVM's current log.
It rechecks full PID/start_ticks/cwd/executable/cmdline identity. V4 controller
also binds a Linux pidfd before arming and sends SIGKILL through that handle;
there is no PID-only fallback. Kernel pidfd readiness proves that exact process
has ended. Its receipt proves interruption only, never recovery.

    python3 /workspace/p008-tools-v4/kill_owned_qa_at_boundary.py \
      --root /workspace/P008-L2-20261118T062924Z-root-a02 --boundary clock --timeout-seconds 240

Root then submits the real entry with the active real player:

    execute as QAAlpha at @s run proutlost director lab locations import p008_qa_r2_transfer

Wait for the exact JVM's abrupt end, the launcher exit receipt and actual lock
release. Do not delete an old lock or relaunch merely because a log is old.
Then start a NEW JVM configured to observe original R1 at the pending boundary:

    python3 /workspace/p008-tools-v4/run_linux_qa.py --root /workspace/P008-L2-20261118T062924Z-root-a02 launch \
      --phase cold --source qa:p008_l2_20261118 --transfer p008_qa_transfer \
      --source-sha256 1dfce91d9c7ce8566a6ef28e8ab2741f8c971037bc6c151e2c7eed59e3f66ea6 \
      --readback-run-id r2-clock-01 --pending-transfer p008_qa_r2_transfer --pending-boundary clock

After READY and real clients/OP4 reconnection:

    execute as QAAlpha at @s run proutlost director lab locations import p008_qa_transfer
    execute as QAAlpha at @s run proutlost director lab locations status
    tp QAAlpha 1000004 64 1000004
    proutlost_p008_qa observe cold
    proutlost_p008_qa observe status

Require R1 native ALREADY_DURABLE. This replay does not cancel or rewrite the
pending R2 generation. Require the pending-invisibility log, all 1360 R1 heads,
the other four heads, FIRST_SELECTED, then move QAAlpha to the far position.
Require both full-chain and installed-observer cold PASS plus its NEW sidecar.

The verifier reads J04.metadataPreparationTick and scans the actual complete
chronology through the existing writer in pages128 off the Minecraft actor.
It restarts the entire scan on STALE (bounded16restarts/2048pages), counts
distinct source/stage indices and distinct activations/cancellations, recouples
the current staging indexes and reads the actual hidden metadata page0.
It never treats NOT_RESIDENT/UNAVAILABLE as absence. No private spool is proof.

Exact boundary assertions:

| Boundary | R2 durable clock | R2 source indices | R2 metadata indices | R2 page0 | R2 activations/cancellations |
|---|---|---|---|---|---|
| clock | exact matching fact and metadataPreparationTick | empty | empty | absent | 0 / 0 |
| source | same durable tick | {0} | empty | absent | 0 / 0 |
| stage | same durable tick | {0..17} | {0} | bound to R2, <=96guards/states | 0 / 0 |

Clock preparation already creates an empty staging slot in J04. The verifier
checks its empty page sets, not absence of that slot. Polling can overshoot a
log boundary: any extra durable page causes failure, never a softened PASS.

After the clock readback PASS, keep that JVM, arm --boundary source and replay
R2's REAL import entry. After its crash, use the same R1 cold command with:

    --readback-run-id r2-source-01 --pending-transfer p008_qa_r2_transfer --pending-boundary source

After source readback PASS, keep that JVM, arm --boundary stage and resume the
same R2 entry. After its crash, use:

    --readback-run-id r2-stage-01 --pending-transfer p008_qa_r2_transfer --pending-boundary stage

Use the same real R1 replay/first-position/observe-cold/far-position sequence
for each. Every cold proof references the ORIGINAL R1 -first.properties.
Only the new cold target filename receives the run-ID suffix. Empty run ID
keeps the exact V1 target. Existing original cold/first sidecars are never
overwritten. Each new receipt includes boundary/generation/source/stage sets,
durable clock, complete scan cut/pages/restarts and the common identity checks.

After the stage readback PASS, keep that JVM, arm --boundary activation and
resume R2's real entry. After its crash, start a NEW --phase first JVM with R2
SHA/name as above, OMIT readback/pending options. Real replay must now return
ALREADY_DURABLE for R2. Run:

    tp QAAlpha 1000004 64 1000004
    proutlost_p008_qa observe first

Wait for FIRST_SELECTED, then tp QAAlpha 1080900 64 1000004. Require the same
1360 heads and four other heads, R2 geometry, selected readers, both final PASS
lines and the new SHA-prefixed R2 -first.properties sidecar. Gracefully stop
that exact JVM, then start a NEW --phase cold JVM with R2 SHA/name and no new
options. Perform the real R2 replay, first/far movement and observe cold.
Require the original cold identity/recovery checks and new R2-cold sidecar.

Negative input operations
-------------------------

All eight prepared directories are declared QA inputs. They do not submit
themselves. Before first R2 preparation, root submits transport cases and the
R1 conflict through the SAME real endpoint and actual OP4 identity, waiting
for terminal status after EACH operation. Initial asynchronous command ACK is
not the refusal result.

| Input name | Expected native outcome | When |
|---|---|---|
| p008_bad_missing_transfer | REFUSED, missing page0; prior active retained | before R2 |
| p008_bad_duplicate_transfer | REFUSED, contradictory page0 fails fragment hash | before R2 |
| p008_bad_corrupt_transfer | REFUSED, page1 fragment hash differs | before R2 |
| p008_bad_oversize_transfer | REFUSED,32769-byte message exceeds32768 | before R2 |
| p008_bad_conflict_r1_transfer | REFUSED, revision1 with different valid digest | while R1 active |
| p008_qa_r1_replay_transfer | ALREADY_DURABLE, exact original R1 | while R1 active |
| p008_bad_conflict_r2_transfer | REFUSED, revision2 with different valid digest | after R2 activation |
| p008_bad_stale_r1_transfer | REFUSED, obsolete revision1 | after R2 activation |

Example real command and terminal readback:

    execute as QAAlpha at @s run proutlost director lab locations import p008_bad_missing_transfer
    execute as QAAlpha at @s run proutlost director lab locations status

A distinct altered duplicate cannot retain the expected SHA256 fragment hash.
It is therefore rejected by the real hash check before the duplicate-equality
branch; report that actual outcome. Do not fabricate a hash-valid contradiction.

Before the pending R1 cold observer, restore terminal status with the real
R1 replay. Before the first R2 observer, restore it with the real R2 replay.
The common observer then verifies full source/manifest/pointer/1360 heads and
the nonempty other source. Retain each invalid input's hashes, real entry and
terminal refusal, process identity and subsequent full native readback.

Qualification scope and local checks
------------------------------------

The pure helper compiles Java21 with -Xlint:all -Werror. Local generation of R1
matches the actual known SHA exactly; R2 preserves all identities and only the
declared bound/revision/digest changes. Local checks cover all transport file
mutations, conflicts/replay/stale bytes and no-overwrite behavior. Launcher V4
valid/invalid option sets and Python syntax pass; pidfd signal0 is available
locally without terminating a process. These checks qualify helper contracts
only. Root still executes pinned Java observer compilation and all native
interruption/readback cases on the owned server. Original P007 transport/J08
counts, R1 native receipts and this campaign remain separate evidence scopes.
No source is canon, physical production data or human reception; no live deploy.
