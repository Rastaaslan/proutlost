P008 PUBLIC_GROUP native orchestration V2 — B07 INCOMPLETE
=====================================================

Root-only execution. This bundle starts no process when copied/imported. The
author performed no remote action. It drives the ALREADY COMPILED B07 installed
PublicGroupQaRuntime through native console commands, without GameTestHooks.
B07's full canonical architecture gate FAILED; this is explicitly an incomplete
binary cohort. An independent narrow QA result cannot accept that fullpack.

Scenario and authority
----------------------

A NEW empty private world has zero real clients and a closed empty whitelist.
The actual loaded spawn chunk gets one declared sacrificial STONE cell. Existing
J03/admission/PREPARE/permit/physical STONE→MOSS_BLOCK/RESULT/EVENT/Quest/SavedData
code must produce its native receipt. Root then saves/stops normally and starts
a NEW JVM on the same paired world/private WAL, and invokes reopen. Original
Quest core, ownership, event, fact, fixture and source/binary identities must
match; private J04 boot/session and JVM identity must be new. The native fixture
uses three assigned synthetic GROUP actors plus an ungrouped control actor.
No real multiplayer, production content, natural capture, cleanup/restoration
or human perception is claimed.

New paths; old a02 remains intact
-------------------------------

    container=proutlost-p008-l2-20261118
    tools=/workspace/p008-public-group-tools-v2
    root=/workspace/P008-PUBLIC-GROUP-20261118T062924Z-root-a01-INCOMPLETE
    previous=/workspace/P008-L2-20261118T062924Z-root-a02
    binary_inputs=/workspace/p008-public-group-b07-inputs-INCOMPLETE

Root copies only these bundle files into tools, plus the two exact official B07
JARs from checkpoint/qa-official-binaries-b07-INCOMPLETE into binary_inputs.
Do not use current mutable build/libs after a later B08 compilation. Record the
three full pins from the frozen B07 receipts: main JAR, Director JAR and original
B07 full-source ZIP (known source prefix74bb6458). The source hash is explicitly
root-verified provenance input, as required by the installed native harness.
No missing hash is guessed or obtained from later current sources.

Dependencies-server-50.json contains the 50 unchanged third-party hashes in
ROOT’s actual a02 server inventory. Its provenance retains the original client
inventory/manifest SHA and the single excluded PresenceFootsteps JAR/hash.
ROOT observed no other missing/additional/hash mismatch. The server has 52 JAR
files (50 dependencies plus two core); the clients retain 53. PresenceFootsteps
was not present on the server and is never installed to satisfy a client count.
ROOT’s persisted receipt is checkpoint/qa-public-group-tools-v1/
P008_ACTUAL_SERVER_DEPENDENCY_SUBSET.json. The excluded JAR is present only in
the historically qualified client cohort, SHA
ded9ea8677f407cbf280b0ee33866ae70859a7c476ef0f60fd35a9be2ee9b91a.
The exclusion follows the already native-tested B05 server/client distribution.
Client mixin lists were observed, but @Mod has no dist field and BOTH TOML
dependencies do not prove server support. No universal side restriction is
inferred from that metadata.
The preparer copies exactly those 50 files from previous/mods and exactly the two
explicitly pinned official B07 core JARs. QA derivatives and observer addons are
not copied. Config/defaultconfigs and already accepted EULA may be read; no
world, data, UUID, player, campaign, Quest state, source export or consumed ID is
copied. Libraries remain on /referenceqa's read-only installed runtime.

Preparation command inside the existing server container
-------------------------------------------------------

Supply MAIN_B07_SHA256, DIRECTOR_B07_SHA256 and SOURCE_ZIP_B07_SHA256 with the
exact full hashes recorded above. This operation creates files only:

    python3 /workspace/p008-public-group-tools-v2/public_group_qa.py \
      --root /workspace/P008-PUBLIC-GROUP-20261118T062924Z-root-a01-INCOMPLETE prepare \
      --previous-root /workspace/P008-L2-20261118T062924Z-root-a02 \
      --template /workspace/P008-L2-20261118T062924Z-root-a02 \
      --libraries /referenceqa/installed-server/libraries \
      --dependency-directory /workspace/P008-L2-20261118T062924Z-root-a02/mods \
      --main-jar /workspace/p008-public-group-b07-inputs-INCOMPLETE/proutlost-0.1.0.jar \
      --main-sha256 MAIN_B07_SHA256 \
      --director-jar /workspace/p008-public-group-b07-inputs-INCOMPLETE/proutlost-director-1.0.0.jar \
      --director-sha256 DIRECTOR_B07_SHA256 --source-sha256 SOURCE_ZIP_B07_SHA256

Before the first new-root write, the tool verifies a02's recorded owner and
full effective process identity, acquires its existing launcher lock without
deletion and checks the game/RCON ports are free. A distinct port lock and a
new-root launcher lock then prevent concurrent launches. It never changes a02
files or its source/current backups. An active or unresolved owner fails closed.

The NEW world marker is PUBLIC_GROUP_TEST_ONLY with exact text
PROUTLOST_PUBLIC_GROUP_QA_V1. No neutral TEST_ONLY marker is created. Both Quest
SERVER config paths are false/false before boot; copied config bytes are retained
outside loader paths. The seed180112026 and vanilla flat layers generate the
spawn surface at y60. No old level.dat or identity is copied. RCON secret and
server.properties stay private mode600 and are excluded from delivery/printing.

First JVM
---------

Root starts the launcher as the owned detached/monitored operation:

    python3 /workspace/p008-public-group-tools-v2/public_group_qa.py \
      --root /workspace/P008-PUBLIC-GROUP-20261118T062924Z-root-a01-INCOMPLETE launch --phase produce

It uses heap2560MiB,2CPU and checks actual VM/cgroup headroom before launching.
It creates immutable per-JVM args/log/process/exit receipts, with full PID/start
ticks/cwd/executable/cmdline identity. No proxy, client or other process is started.
The normal libraries/net/neoforged/neoforge/21.1.248/unix_args.txt remains the
installed launcher, bound to game127.0.0.1:25579 and loopback RCON25584.

Exact flags include nativeLab=true, publicGroupQa=true, actual absolute
publicGroupQaRoot, full source SHA and the two installed official JAR paths.
neutralLab=false, realGroupClientsQa=false, nativeGameTests=false and installed
observer=false. No neutral/paged flags or GameTest production bypass is used.
The main mod's publicGroup bootstrap returns before canonical Quest listeners.

Root follows the new log to actual server Done. Then:

    python3 /workspace/p008-public-group-tools-v2/public_group_qa.py \
      --root /workspace/P008-PUBLIC-GROUP-20261118T062924Z-root-a01-INCOMPLETE produce

This first checks actual list=zero and original binaries/configs. It reads the
spawn cell0,60,0 via execute-if-loaded AND if-block-grass. Only after that proof
does it pin the ALREADY LOADED chunk, change that one grass cell to STONE and
read it back as STONE. No command loads/generates an unknown chunk. All setup
commands and responses are retained. After save-all flush it sends exactly:

    proutlost public_group_qa produce 0 60 0

It waits for the real harness done=true/failure=NONE, the native produce.json
and actual Quest SavedData. It additionally reads the actual loaded MOSS_BLOCK
through vanilla console, checks all source/JAR/path/zero-client identity fields,
and retains a controller receipt. A started command or a log line alone does
not pass. RCON commands/phase/failures persist before and after each action.
Failure leaves the process, partial world, private WAL and evidence intact.
Produce refuses a previous attempt; it never resets a failed world or cell.

Clean stop, NEW JVM and cold readback
------------------------------------

    python3 /workspace/p008-public-group-tools-v2/public_group_qa.py \
      --root /workspace/P008-PUBLIC-GROUP-20261118T062924Z-root-a01-INCOMPLETE stop

Stop requests save-all flush and the existing owned console FIFO stop, waits for
the exact process's normal exit receipt, and never kills/restarts it. Wait for
launcher exit/lock release. Keep all world, private WAL, native/controller
receipts and source/binary pins together. Then:

    python3 /workspace/p008-public-group-tools-v2/public_group_qa.py \
      --root /workspace/P008-PUBLIC-GROUP-20261118T062924Z-root-a01-INCOMPLETE launch --phase reopen

After actual server Done:

    python3 /workspace/p008-public-group-tools-v2/public_group_qa.py \
      --root /workspace/P008-PUBLIC-GROUP-20261118T062924Z-root-a01-INCOMPLETE reopen

Reopen NEVER executes setup, forceload, setblock or produce. It requires the
original physical MOSS_BLOCK in the loaded retained chunk and sends only the
existing public_group_qa reopen command. It requires one NEW native reopen
receipt, unchanged world/core/ownership/event/fact/fixture identities, larger
private WAL boot epoch, different authority/JVM and exact producing-process
reference. The native code also checks SavedData disk replay, original EVENT
replay and nonregressing actual persisted world clock.

Useful read-only status:

    python3 /workspace/p008-public-group-tools-v2/public_group_qa.py \
      --root /workspace/P008-PUBLIC-GROUP-20261118T062924Z-root-a01-INCOMPLETE status

Expected native logs are PUBLIC_GROUP_QA_PRODUCE_PASS then PUBLIC_GROUP_QA_REOPEN_PASS.
The actual receipts remain world/public-group-qa/produce.json and the new
reopen-PID-start.json. Quest data, MOSS_BLOCK and DIRECTOR_ACTIVE ownership stay
retained. This does not qualify restoration, cleanup, release or live deployment.

Local verification scope
------------------------

Python syntax/CLI and declared synthetic file-guard checks PASS: wrong whitelist,
enabled Quest, neutral marker, added QA addon, changed mod bytes, effective lock,
live full process identity, wrong-root no-write and exact public flags. The
source/bin JAR typecheck already belongs to root's B07 build. No Java/Minecraft
process, world, native produce/reopen or remote operation was executed by this
proposal author. Root retains the incomplete fullpack verdict while qualifying
this independent existing native chain.

V2 delta and preserved V1 refusal
--------------------------------

V1 incorrectly required the client dependency count on the server. ROOT observed
that only PresenceFootsteps-1.21.1-1.12.0-beta.1-1.21NeoForge.jar is absent from
a02, with no other missing/additional/hash mismatch. Its refusal occurred before
new-root mkdir; a01-INCOMPLETE does not exist. Preserve that refusal and V1
archive. V2 changes only this explicit dependency subset/count and provenance.
All ownership/process/port/whitelist/Quest/marker/source/JAR/cold guards remain
in place. No server/client/world action was performed by this proposal author.
