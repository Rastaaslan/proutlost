package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.persistence.JournalStore;
import fr.proutlost.director.runtime.ResultTransaction;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Explicit, session-scoped OP4 activation of the real TEST_ONLY catalogue.
 * WORLD control changes are acknowledged before J01 can report NORMAL and before
 * a catalogue decision can be evaluated. Boot always returns to paused through
 * DirectorNativeWorldStateBootstrap; this class never approves production.
 */
public final class DirectorNativeCatalogueControl implements AutoCloseable {
    private final MinecraftServer server;
    private final WorldId world;
    private final JournalStore store;
    private final BooleanSupplier auditWriterIdle;
    private final StoreSession session;
    private final DirectorRuntimeContent content;
    private Boolean requested;
    private BooleanSupplier adminPending=()->false;
    private UUID operator;
    private Fingerprint requestedHash;
    private DurableBatch batch;
    private DirectorWorldState expected;
    private CompletableFuture<CommitAck> ack;
    private boolean enabled,closed,failed,configured;
    private String state="PAUSED";
    public DirectorNativeCatalogueControl(MinecraftServer server,WorldId world,
            JournalStore store,BooleanSupplier auditWriterIdle) {
        this(server,world,store,null,auditWriterIdle);
    }
    public DirectorNativeCatalogueControl(MinecraftServer server,WorldId world,
            JournalStore store,DirectorRuntimeContent content,BooleanSupplier auditWriterIdle) {
        this.content=content;
        this.server=Objects.requireNonNull(server);this.world=Objects.requireNonNull(world);
        this.store=Objects.requireNonNull(store);this.auditWriterIdle=Objects.requireNonNull(auditWriterIdle);
        actor();session=store.session();
        if(!session.worldId().equals(world)||(content==null?!Boolean.getBoolean("proutlost.director.nativeLab"):!content.production()))
            throw new IllegalArgumentException("explicit native runtime provenance required");
        if(content!=null){configured=true;requestedHash=content.catalog().hash();}
    }
    private void actor(){if(closed||!server.isSameThread())throw new IllegalStateException("catalogue control actor");}
    public boolean enabled(){actor();if(content!=null){var w=latest();return !failed&&!adminPending.getAsBoolean()&&session.equals(store.session())&&!store.readOnlySafe()&&w.runControl()==RunControl.RUNNING&&(w.healthMode()==HealthMode.NORMAL||w.healthMode()==HealthMode.DEGRADED);}
        return enabled&&!failed&&!adminPending.getAsBoolean()&&session.equals(store.session())&&!store.readOnlySafe();}
    /** The old no-plan scheduler may drain a pending ACK but must not start a new WORLD write. */
    public boolean ownsWorldLane(){actor();return requested!=null||batch!=null||enabled||failed||configured||adminPending.getAsBoolean();}
    public boolean transitionPending(){actor();return requested!=null||batch!=null||failed||adminPending.getAsBoolean();}
    public HealthMode observationHealth(){actor();return content==null?(enabled()?HealthMode.NORMAL:HealthMode.READ_ONLY_SAFE):latest().healthMode();}
    public String request(ServerPlayer player,boolean run,CatalogSnapshot catalog,boolean providersReady){
        actor();if(content!=null)content.requireLaboratory();Objects.requireNonNull(catalog);
        if(!player.isAlive()||!player.hasPermissions(4))return "Joueur OP4 vivant requis.";
        if(!session.equals(store.session())||store.readOnlySafe())return "Session J04 indisponible.";
        if(requested!=null||batch!=null||adminPending.getAsBoolean())return "Changement de catalogue deja en attente de J04.";
        if(run&&(!providersReady||catalog.events().size()!=9||catalog.chains().size()!=2))
            return "Les neuf compositions et leurs providers doivent etre disponibles.";
        requested=run;operator=player.getUUID();requestedHash=catalog.hash();
        if(!run)enabled=false;
        failed=false;state="WAIT_WORLD_WRITER_IDLE";
        return run?"Activation du catalogue TEST_ONLY demandee; admission apres ACK J04.":
            "Pause demandee; aucune nouvelle admission, nettoyages existants conserves.";
    }
    private DirectorWorldState latest(){
        var query=new StoreQuery(world,Set.of(new AggregateKey(AggregateKind.WORLD,world.value())),
            new Sequence(0),Optional.empty(),1);
        var read=store.view(query);ReadContracts.validate(read,query,session);
        if(read.status()!=ReadStatus.AVAILABLE||read.page().orElseThrow().aggregates().size()!=1||
            !(read.page().orElseThrow().aggregates().getFirst().state() instanceof WorldAggregate row))
            throw new IllegalStateException("WORLD unavailable");
        var value=row.value();
        if(!value.worldId().equals(world)||!value.bootEpoch().equals(session.bootEpoch())||
            !value.storeGeneration().equals(session.storeGeneration()))throw new IllegalStateException("stale WORLD");
        return value;
    }
    public void poll(){
        actor();if(content!=null)return;if(failed||requested==null)return;
        try{
            if(!session.equals(store.session())||store.readOnlySafe())throw new IllegalStateException("J04 session lost");
            if(batch!=null){
                if(!ack.isDone())return;
                var receipt=ack.getNow(null);
                if(receipt==null||receipt.ackStatus()!=AckStatus.DURABLE||
                    !receipt.transactionId().equals(batch.transactionId())||
                    !receipt.transactionHash().equals(batch.transactionHash())||
                    !receipt.storeGeneration().equals(session.storeGeneration())||
                    receipt.durableSequence().value()==0||receipt.permitAttempt().isPresent()||
                    !receipt.forcedBlobs().equals(DurableBlobReferences.collect(batch.records())))
                    throw new IllegalStateException("control ACK is not exact");
                if(!latest().equals(expected))throw new IllegalStateException("control WORLD readback mismatch");
                enabled=requested;configured=true;state=enabled?"RUNNING_TEST_ONLY":"PAUSED";
                System.out.println("DIRECTOR_NATIVE_CATALOGUE_CONTROL_DURABLE enabled="+enabled+
                    " world_revision="+expected.header().revision().value()+" sequence="+receipt.durableSequence().value()+
                    " operator="+operator+" production_approved=false");
                batch=null;ack=null;expected=null;requested=null;return;
            }
            if(!auditWriterIdle.getAsBoolean())return;
            var old=latest();
            var targetHash=requestedHash;
            var tick=new Tick(Math.max(server.overworld().getGameTime(),Math.addExact(old.simTick().value(),1)));
            var header=new AggregateHeader(1,old.header().id(),old.header().revision().plus(1),old.header().lastSequence(),tick);
            expected=new DirectorWorldState(header,world,session.storeGeneration(),tick,
                requested?RunControl.RUNNING:RunControl.PAUSED,requested?HealthMode.NORMAL:HealthMode.READ_ONLY_SAFE,
                old.pacingState(),old.tension(),old.intensityProfileId(),targetHash,old.configHash(),
                session.bootEpoch(),old.lastCheckpointSeq(),old.engineControl());
            var fact=new DirectorFact(new FactId(UUID.randomUUID()),new FactType("proutlost_director:lab_catalogue_control"),
                List.of(new ScopeRef(ScopeKind.WORLD,world.value())),Optional.empty(),EvidenceStatus.CONFIRMED,
                Map.of("enabled",new JsonValue.Bool(requested),"operator",new JsonValue.Text(operator.toString()),
                    "catalog_hash",new JsonValue.Text(requestedHash.value()),"test_only",new JsonValue.Bool(true)),true,Optional.empty());
            batch=ResultTransaction.batch(new TransactionId(UUID.randomUUID()),session.storeGeneration(),
                Map.of(new ScopeRef(ScopeKind.WORLD,world.value()),old.header().revision()),
                List.of(new DurableAggregate(new WorldAggregate(expected)),new DurableFact(fact)),List.of());
            ack=store.append(batch).toCompletableFuture();state="WAIT_EXACT_J04_ACK";
        }catch(RuntimeException failure){
            enabled=false;failed=true;state="CONTROL_FAILED_SAFE "+failure.getClass().getSimpleName();
            System.out.println("DIRECTOR_NATIVE_CATALOGUE_CONTROL_FAILED_SAFE "+failure);
        }
    }
    void adminPending(BooleanSupplier pending){actor();adminPending=Objects.requireNonNull(pending);}
    boolean adminConfigured(){actor();return configured&&!failed&&requested==null&&batch==null&&
        session.equals(store.session())&&!store.readOnlySafe()&&requestedHash!=null;}
    boolean adminWorldWriterIdle(){actor();return requested==null&&batch==null&&!failed&&auditWriterIdle.getAsBoolean();}
    AdminSnapshotStamp adminStamp(){
        actor();var query=new StoreQuery(world,Set.of(new AggregateKey(AggregateKind.WORLD,world.value())),
            new Sequence(0),Optional.empty(),1);
        var read=store.view(query);ReadContracts.validate(read,query,session);
        if(read.status()!=ReadStatus.AVAILABLE||read.page().orElseThrow().hasMore()||
            read.page().orElseThrow().aggregates().size()!=1||
            !(read.page().orElseThrow().aggregates().getFirst().state() instanceof WorldAggregate row))
            throw new IllegalStateException("WORLD unavailable for J07");
        var value=row.value();
        if(!value.worldId().equals(world)||!value.bootEpoch().equals(session.bootEpoch())||
            !value.storeGeneration().equals(session.storeGeneration())||!session.equals(store.session()))
            throw new IllegalStateException("foreign WORLD");
        return new AdminSnapshotStamp(world,session.bootEpoch(),value.header().revision(),value.simTick(),
            read.page().orElseThrow().lastDurableSequence());
    }
    fr.proutlost.director.runtime.RuntimeAdminMailbox.Change proposeAdmin(AdminCommand command,AdminSnapshotStamp before){
        actor();if(!adminConfigured()||!adminWorldWriterIdle())throw new IllegalStateException("WORLD writer busy");
        var old=latest();
        if(!old.header().revision().equals(before.snapshotRevision()))throw new IllegalStateException("WORLD changed");
        boolean run=command.operation()==AdminOperation.RESUME;
        var actual=fr.proutlost.director.adapter.neoforge.network.DirectorNativeNetworkBootstrap
            .adminActor(server,command.actorSession()).filter(a->AdminContracts.authorized(command,a)).orElseThrow();
        var tick=new Tick(Math.max(server.overworld().getGameTime(),Math.addExact(old.simTick().value(),1)));
        var header=new AggregateHeader(1,old.header().id(),old.header().revision().plus(1),old.header().lastSequence(),tick);
        var target=new DirectorWorldState(header,world,session.storeGeneration(),tick,
            run?RunControl.RUNNING:RunControl.PAUSED,run?HealthMode.NORMAL:HealthMode.READ_ONLY_SAFE,
            old.pacingState(),old.tension(),old.intensityProfileId(),requestedHash,old.configHash(),
            session.bootEpoch(),old.lastCheckpointSeq(),old.engineControl());
        var fact=new DirectorFact(new FactId(UUID.randomUUID()),new FactType("proutlost_director:lab_catalogue_control"),
            List.of(new ScopeRef(ScopeKind.WORLD,world.value())),Optional.empty(),EvidenceStatus.CONFIRMED,
            Map.of("enabled",new JsonValue.Bool(run),"operator",new JsonValue.Text(actual.playerId().orElseThrow().value().toString()),
                "catalog_hash",new JsonValue.Text(requestedHash.value()),"test_only",new JsonValue.Bool(true),
                "admin_request",new JsonValue.Text(command.requestId().value().toString())),true,Optional.empty());
        var after=new AdminSnapshotStamp(world,session.bootEpoch(),header.revision(),tick,before.lastDurableSequence());
        var data=new AdminRow(new ScopeRef(ScopeKind.WORLD,world.value()),header.revision(),tick,
            new PublicStatusData(target.healthMode(),!run,Set.of()));
        var result=new AdminResult(AdminStatus.SUCCEEDED,command.requestId(),header.revision(),Optional.of(data),Optional.empty());
        return new fr.proutlost.director.runtime.RuntimeAdminMailbox.Change(after,result,
            Map.of(new ScopeRef(ScopeKind.WORLD,world.value()),old.header().revision()),
            List.of(new DurableAggregate(new WorldAggregate(target)),new DurableFact(fact)),List.of());
    }
    void publishAdmin(fr.proutlost.director.runtime.RuntimeAdminMailbox.Change committed){
        actor();var target=committed.records().stream().filter(r->r instanceof DurableAggregate a&&a.state() instanceof WorldAggregate)
            .map(r->((WorldAggregate)((DurableAggregate)r).state()).value()).findFirst().orElseThrow();
        if(committed.after().lastDurableSequence().value()<1||!latest().equals(target))
            throw new IllegalStateException("J07 control WORLD readback mismatch");
        enabled=target.runControl()==RunControl.RUNNING;configured=true;
        state=enabled?"RUNNING_TEST_ONLY":"PAUSED";
        System.out.println("DIRECTOR_NATIVE_J07_CONTROL_DURABLE enabled="+enabled+" sequence="+
            committed.after().lastDurableSequence().value()+" production_approved=false");
    }
    /** Stable exact-ACKed ROOT configuration, including explicit pause. */
    public Optional<Fingerprint> configuredCatalogHash(){
        actor();return configured&&!failed&&!transitionPending()&&session.equals(store.session())&&!store.readOnlySafe()
            ?Optional.ofNullable(requestedHash):Optional.empty();
    }
    public String status(){actor();return state+" enabled="+enabled()+" mode="+(content==null?ProviderMode.TEST_ONLY:content.mode());}
    @Override public void close(){actor();enabled=false;closed=true;}
}
