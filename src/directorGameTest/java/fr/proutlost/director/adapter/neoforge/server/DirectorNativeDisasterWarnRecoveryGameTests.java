package fr.proutlost.director.adapter.neoforge.server;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.persistence.JournalStore;
import fr.proutlost.director.persistence.StoreOptions;
import fr.proutlost.director.runtime.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** J05 actor-level native gate against reusing a WATCH-era warning to cause
 * IMPACT. Uses real J04 Linux/ext4 WAL and verifies recovery on the same
 * disposable world. No disaster effect is applied and no client ACK is faked.
 */
@GameTestHolder("proutlost_director")
@PrefixGameTestTemplate(false)
public final class DirectorNativeDisasterWarnRecoveryGameTests {
    private DirectorNativeDisasterWarnRecoveryGameTests() {}
    private static final StoreOptions OPTIONS =
        new StoreOptions(1_048_620,2_097_152,4_194_304,16_777_216,32,0,128);

    private static final class Probe {
        final MinecraftServer server;
        final WorldId world = new WorldId(UUID.randomUUID());
        final DisasterId id = new DisasterId(UUID.randomUUID());
        final PlayerId audience = new PlayerId(UUID.randomUUID());
        final Path root;
        final DisasterInstance initial;
        final BlobContent warningBlob;
        final CompletableFuture<JournalStore> opening;
        final TransactionId watchTx = new TransactionId(UUID.randomUUID());
        final TransactionId stabilizeTx = new TransactionId(UUID.randomUUID());
        JournalStore store;
        DurableDisasterRunner runner;
        CompletableFuture<CommitAck> boot;
        CompletableFuture<Void> closed;
        CompletableFuture<JournalStore> reopened;
        int stage;

        Probe(GameTestHelper helper) {
            server = Objects.requireNonNull(helper.getLevel().getServer());
            if (!server.isSameThread()) throw new IllegalStateException("server actor required");
            root = server.getWorldPath(LevelResource.ROOT)
                .resolve("director-j05-disaster-warn-test-only")
                .resolve(UUID.randomUUID().toString());
            var dim = new DimensionId(helper.getLevel().dimension().location().toString());
            var chunk = new ChunkRef(dim,0,0);
            warningBlob = BlobContent.capture(new ResourceId("proutlost_director:warning-test"),
                1,new byte[]{42});
            var blob = warningBlob.reference();
            var warning = new WarningEvidence(Set.of(audience),new Tick(1),
                new DurationTicks(5),blob.hash(),new Epoch(1),true,true);
            initial = new DisasterInstance(new AggregateHeader(1,id.value(),
                new Revision(1),new Sequence(0),new Tick(1)),id,
                new EventInstanceId(UUID.randomUUID()),
                new DefinitionId("proutlost:disaster_warn_test"),
                DisasterStage.WATCH,Map.of(),new UnitValue(.9),
                new Footprint(dim,Set.of(chunk),Set.of(),List.of()),
                warning,Set.of(audience),List.of(),blob,
                Set.of(),"test-only",Set.of());
            opening = JournalStore.open(root,world,OPTIONS).toCompletableFuture();
        }

        void actor() {
            if (!server.isSameThread()) throw new IllegalStateException("server actor required");
        }

        DisasterRunner.Conditions live(long tick,HealthMode health) {
            return new DisasterRunner.Conditions(new Tick(tick),health,
                Set.of(audience),new Epoch(1),health==HealthMode.NORMAL,false,false);
        }

        DisasterStage storedStage() {
            var row = store.view(new StoreQuery(world,Set.of(
                new AggregateKey(AggregateKind.DISASTER,id.value())),
                new Sequence(0),Optional.empty(),32));
            if (row.status()!=ReadStatus.AVAILABLE || row.page().isEmpty())
                throw new IllegalStateException("J04 disaster projection unavailable");
            return row.page().orElseThrow().aggregates().stream()
                .map(a->a.state())
                .filter(DisasterAggregate.class::isInstance)
                .map(DisasterAggregate.class::cast)
                .map(a->a.value().stage())
                .findFirst().orElseThrow();
        }

        void advance(GameTestHelper helper) {
            actor();
            switch(stage) {
                case 0 -> {
                    if (!opening.isDone()) return;
                    helper.assertTrue(!opening.isCompletedExceptionally(),"J04 open failed");
                    store=Objects.requireNonNull(opening.getNow(null));
                    helper.assertTrue(!store.readOnlySafe(),"J04 read-only; no durability qualification");
                    boot=store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),
                        store.session().storeGeneration(),Map.of(),
                        List.of(new DurableAggregate(new DisasterAggregate(initial))),List.of(warningBlob)))
                        .toCompletableFuture();
                    stage=1;
                }
                case 1 -> {
                    if (!boot.isDone()) return;
                    helper.assertTrue(!boot.isCompletedExceptionally() &&
                        boot.getNow(null).ackStatus()==AckStatus.DURABLE,
                        "initial J04 disaster is not durably committed");
                    helper.assertTrue(storedStage()==DisasterStage.WATCH,
                        "J04 did not project WATCH");
                    runner=new DurableDisasterRunner(store,2,
                        disaster->new ScopeRef(ScopeKind.EVENT,disaster.header().id()));
                    runner.restore(initial);
                    runner.propose(id,watchTx,new UnitValue(.5),live(10,HealthMode.NORMAL));
                    stage=2;
                }
                case 2 -> {
                    var progress=runner.poll(id);
                    if (progress.status()==DurableDisasterRunner.Status.PENDING) return;
                    helper.assertTrue(progress.status()==DurableDisasterRunner.Status.COMMITTED &&
                        progress.state().stage()==DisasterStage.WARN,
                        "WATCH-to-WARN needs durable WAL ACK");
                    helper.assertTrue(storedStage()==DisasterStage.WARN,
                        "WARN not projected in real J04 WAL");
                    boolean rejected=false;
                    try {
                        runner.propose(id,new TransactionId(UUID.randomUUID()),new UnitValue(.5),
                            live(15,HealthMode.NORMAL));
                    } catch (IllegalStateException expected) { rejected=true; }
                    helper.assertTrue(rejected && runner.view(id).stage()==DisasterStage.WARN &&
                        storedStage()==DisasterStage.WARN,
                        "WATCH-era warning falsely authorized IMPACT");
                    System.out.println("DIRECTOR_NATIVE_J05_DISASTER_STALE_WARN_IMPACT_DENIED");
                    runner.propose(id,stabilizeTx,new UnitValue(.5),
                        live(16,HealthMode.DEGRADED));
                    stage=3;
                }
                case 3 -> {
                    var progress=runner.poll(id);
                    if(progress.status()==DurableDisasterRunner.Status.PENDING) return;
                    helper.assertTrue(progress.status()==DurableDisasterRunner.Status.COMMITTED &&
                        progress.state().stage()==DisasterStage.STABILIZE &&
                        storedStage()==DisasterStage.STABILIZE,
                        "health emergency must durably enter STABILIZE, never IMPACT");
                    closed=store.closeAsync().toCompletableFuture();
                    stage=4;
                }
                case 4 -> {
                    if(!closed.isDone())return;
                    helper.assertTrue(!closed.isCompletedExceptionally(),
                        "J04 close did not flush disaster");
                    reopened=JournalStore.open(root,world,OPTIONS).toCompletableFuture();
                    stage=5;
                }
                case 5 -> {
                    if(!reopened.isDone())return;
                    helper.assertTrue(!reopened.isCompletedExceptionally(),"J04 WAL reboot failed");
                    store=Objects.requireNonNull(reopened.getNow(null));
                    helper.assertTrue(storedStage()==DisasterStage.STABILIZE,
                        "WAL reboot lost safe STABILIZE or resurrected IMPACT");
                    closed=store.closeAsync().toCompletableFuture();
                    stage=6;
                }
                case 6 -> {
                    if(!closed.isDone())return;
                    helper.assertTrue(!closed.isCompletedExceptionally(),"J04 final flush failed");
                    System.out.println("DIRECTOR_NATIVE_J05_DISASTER_WARNING_WAL_REBOOT_PASS");
                    stage=7;
                }
                case 7 -> {}
                default -> throw new IllegalStateException("unknown J05 disaster stage");
            }
        }
    }

    @GameTest(templateNamespace="proutlost_director",template="smoke_empty",
              timeoutTicks=12000)
    public static void staleWarningNeverTriggersImpactAfterWalReboot(GameTestHelper helper) {
        var probe=new Probe(helper);
        helper.succeedWhen(()->{
            probe.advance(helper);
            helper.assertTrue(probe.stage==7,
                "J05 durable WARN, stabilization, same-WAL reboot still pending");
        });
    }
}
