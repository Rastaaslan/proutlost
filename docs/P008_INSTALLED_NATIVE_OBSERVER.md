P008 installed-server native observer, TEST_ONLY
================================================

This fixes only the evidence harness: the installed NeoForge production server
does not enable GameTestHooks. No production hook, gameplay admission, writer,
permission, player, position, fixture or canonical reception is changed.

The original GameTest generator and the installed commands use the exact same
Observer. Its constructor now takes MinecraftServer. Existing 1360-head,
four-head other-source, J04 source/manifest, bounded page/cache, actual selected
reader, real OP4 client and cold-JVM identity checks remain in that Observer.
The first installed observation accepts DURABLE or ALREADY_DURABLE because the
real source was activated before this harness replacement. Cold still requires
the real native admission's ALREADY_DURABLE result. Neither mode submits it.

Packaging and build against B05
------------------------------

Use the existing pinned build and dependency cache (its current source/output
may be B06), with a separately frozen official installed B05 Director JAR.
Supply this proposal at an independent path. The following command compiles
only the changed observer and addon bootstrap against that B05 JAR and pinned
NeoForge/Minecraft dependency JARs; all current production output directories
are excluded:

    ./gradlew --offline --no-configuration-cache \
      -I /workspace/p008-installed-observer-proposal/gradle/p008-installed-observer.init.gradle \
      -Pp008ObserverProposal=/workspace/p008-installed-observer-proposal \
      -Pp008ObserverDirectorJar=/workspace/p008-observer-b05-base/proutlost-director-1.0.0.jar \
      -Pp008ObserverDirectorSha256=RECORDED_OFFICIAL_B05_SHA256 \
      p008InstalledQaObserverJar

Do not rebuild directorJar or main jar for this harness. No B05 production
class-output directory is required. The new task detaches
classpath JAR paths from SourceSetOutput builtBy relationships, excludes every
classpath directory and refuses any additional Director API JAR. It has no
task dependency on production compilation. Check the task graph before execution if
the root build has additional environment-owned task rules.

Outputs:

    build/p008-installed-observer/classes/
    build/p008-installed-observer/proutlost-p008-qa-TEST_ONLY-1.0.0.jar

The addon contains only fr.proutlost.p008qa.InstalledObserverMod and its own
metadata. Its fixed reflection call installs the QA observer from the QA
Director derivative. It registers no network payload. The metadata declares
displayTest IGNORE_ALL_VERSION; do not add this addon to the real clients.

Build a NEW derivative from exact official B05 and the exact previous B05 QA
derivative (supply their recorded hashes; do not infer hashes from newer B06):

    python3 /workspace/p008-installed-observer-proposal/tools/build_installed_observer_derivative.py \
      --official-director /exact/frozen/B05/proutlost-director-1.0.0.jar \
      --official-sha256 ORIGINAL_B05_OFFICIAL_SHA256 \
      --prior-qa-director /exact/frozen/B05/proutlost-director-1.0.0-P008-TEST_ONLY.jar \
      --prior-qa-sha256 RECORDED_B05_QA_SHA256 \
      --compiled-classes /exact/B05/build/p008-installed-observer/classes \
      --addon-jar /exact/B05/build/p008-installed-observer/proutlost-p008-qa-TEST_ONLY-1.0.0.jar \
      --output /new/stage/proutlost-director-1.0.0-P008-observer-v2-TEST_ONLY.jar

The assembler writes a provenance JSON and refuses an existing output. It
compares EVERY original official JAR entry body, preserves all unrelated prior
QA entries, replaces only DirectorNativePagedLocationImportGameTests and its
nested classes, and refuses Director packages in the addon. The bootstrap
package must not appear in the QA Director derivative. Do not install a second
standalone overlay JAR containing the Director package: that would split it
across JPMS modules.

Root alone stops the exact owned server JVM and stages both NEW JARs. Preserve
the previous derivative and process/log/exit receipts outside mods. The mods
folder must contain exactly one Director JAR (the new QA derivative) and the
separate proutlost-p008-qa addon. Official B05 main and all dependencies remain
the same exact bytes. Retain an explicit installed inventory and body-parity
receipt. No world copy, world mutation, NBT mutation or rollback is required.

First observation on the existing a02 world
-----------------------------------------

Retain all existing first-phase JVM properties and add:

    -Dproutlost.director.p008InstalledQaObserver=true

Required properties (unchanged contracts):

    -Dproutlost.director.nativeGameTests=true
    -Dproutlost.director.nativeLab=true
    -Dproutlost.quest.neutralLab=true
    -Dproutlost.quest.neutralLabRoot=/workspace/P008-L2-20261118T062924Z-root-a02/world
    -Dproutlost.director.nativePagedLocationImportGameTest=true
    -Dproutlost.director.nativePagedLocationImportRecoveryOnly=false
    -Dproutlost.director.nativePagedLocationImportName=p008_qa_transfer
    -Dproutlost.director.nativePagedLocationImportSource=qa:p008_l2_20261118
    -Dproutlost.director.nativePagedLocationImportSha256=1dfce91d9c7ce8566a6ef28e8ab2741f8c971037bc6c151e2c7eed59e3f66ea6
    -Dproutlost.director.nativePagedLocationImportOperator=ACTUAL_EXISTING_QAALPHA_UUID

The configured actual world remains 011e910f-674e-4464-81fd-bfb1dbf060b5. No
fixture regeneration is needed. The helper paths/ports remain V2: actual
127.0.0.1:25579, client proxy25583, loopback RCON25584. Both real QA clients
reconnect to this same world with unchanged profiles and B05 client binaries.

After READY and the real configured OP4 UUID is connected, replay through the
actual entry if this new JVM's native import status is not already terminal:

    execute as QAAlpha at @s run proutlost director lab locations import p008_qa_transfer

This must produce ALREADY_DURABLE for the same source/generation. The observer
never calls it. Keep the true native-entry receipt and command output.

Then place the actual client at the existing first-region position and start:

    tp QAAlpha 1000004 64 1000004
    proutlost_p008_qa observe first
    proutlost_p008_qa observe status

Commands require source permission4 plus the configured real online OP4
player, exact neutral root/marker, whitelist, port25579 and explicit flags.
An execute-as wrapper is optional for this read-only observer command; the
separate real import command retains its actual-player requirement.

Wait for:

    P008_NATIVE_OTHER_SOURCE_RETAINED_PASS cold=false heads=4
    P008_NATIVE_PAGED_ALL_HEADS_PASS cold=false heads=1360 pages=15
    P008_NATIVE_PAGED_FIRST_SELECTED_PASS cold=false

Only after FIRST_SELECTED, move the actual connected client:

    tp QAAlpha 1080900 64 1000004

Wait for both:

    P008_NATIVE_PAGED_IMPORT_AND_SELECTED_READERS_PASS cold=false
    P008_INSTALLED_OBSERVER_PASS cold=false

The original immutable -first.properties sidecar must now exist. A mere
PENDING/registered/command result is not a pass. Status logs include exact
source SHA, export digest, generation, world, boot/session, pinned cut and
selected reader cuts. The run permits 12000 actual ticks with a 20-minute wall
ceiling. Exceptions, loss of the real OP4 client, guard failures and timeouts
produce terminal P008_INSTALLED_OBSERVER_FAILED; retained server logs are the
failure evidence. The observer does not retry or replace a failed run in that
JVM. No sidecar is overwritten.

Cold observation
----------------

After successful first observation, root gracefully stops the owned JVM and
retains its process/log/exit receipts. Start a NEW JVM with the same binaries,
same directory/source/manifest/store and RecoveryOnly=true. Reconnect the same
real clients, replay the same actual import entry and require ALREADY_DURABLE.
Place QAAlpha at the first position and run:

    proutlost_p008_qa observe cold

After FIRST_SELECTED cold=true, move to the far position. Require both final
cold=true PASS lines and the NEW -cold.properties sidecar. The verifier checks
the original directory file identity, world/store/source/generation/all source
manifests/nonempty other source/operator/heads, a larger boot epoch, changed
authority session, different JVM start time and nonregressing pinned cut.
It does not treat PID difference alone as proof of a new process.

Preserve first/cold sidecars, source/manifest hashes, old/new installed inventory,
parity provenance, actual entry receipts, both JVM identities and logs. This
qualifies only the declared synthetic native QA chain. It makes no claim of
Contra canonical data, fullpack coverage, human perception or FULLSTACK_ACCEPTED.

Local checks completed before delivery
-------------------------------------

Python assembler syntax PASS. Synthetic assembler checks: exact official entry
parity and unrelated prior QA preservation; obsolete observer nested class
removal; existing output refusal; changed production body refusal; split
Director package addon refusal. These are packaging/helper checks, not native
runtime or durability proofs. The Java/pinned-addon compile and installed native
scenario still have to execute on the owned QA server.
