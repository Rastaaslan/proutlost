package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.DurableStore;
import fr.proutlost.director.memory.MemoryReducer;
import fr.proutlost.director.memory.MemorySettings;
import fr.proutlost.director.memory.ObservationAppendRecovery;
import fr.proutlost.director.persistence.JournalStore;
import fr.proutlost.director.runtime.ResultTransaction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.MinecraftServer;

/**
 * J01 -> deterministic J01 MemoryReducer -> J04 CAS WAL ingress.
 *
 * Durable acknowledgement is polled on the Minecraft actor; there is no
 * client-side or background access to Minecraft. The original observation
 * buffer remains sealed through the J04 fsync window. Loss or rejection
 * freezes the channel; never retry an ambiguous transaction as a new ID.
 *
 * This supplies durable observational memory, never gameplay admission authority.
 * Provider mode and configuration are explicit; production rejects test-only
 * dependencies and test calibration.
 */
public class DirectorObservationWalIngestion implements AutoCloseable {
    public enum Phase { WAITING_FOR_MEMORY, IDLE, WAL_PENDING, PROJECTION_PENDING,
                        FAILED_SAFE, CLOSED }
    public record View(Phase phase,long attemptedBatches,long durableBatches,
                       long durableObservations,long projectedSequence,
                       boolean permitsGameplay,boolean testOnly) {
        public View {
            Objects.requireNonNull(phase);
            if(attemptedBatches<0||durableBatches<0||
               durableObservations<0||projectedSequence<0||
               permitsGameplay)
                throw new IllegalArgumentException("unqualified J01 WAL");
        }
    }
    private static final long CADENCE=200;
    private final MinecraftServer server;
    private final ProviderMode mode;
    private final WorldId world;
    private final DurableStore store;
    private final StoreSession session;
    private final DirectorNativeObservationQualification observation;
    private final DirectorNativeMemoryProjectionQualification memory;
    private final MemoryReducer reducer;
    private Phase phase=Phase.WAITING_FOR_MEMORY;
    private long lastAttempt=-CADENCE,attempted,durable,observations;
    private long projectedSequence;
    private CompletableFuture<CommitAck> pending;
    private DurableBatch pendingBatch;
    private List<ObservationId> pendingIds=List.of();
    private CommitAck rejected;

    public DirectorObservationWalIngestion(MinecraftServer server,WorldId world,
            DurableStore writer,DirectorNativeObservationQualification observation,
            DirectorNativeMemoryProjectionQualification memory,ProviderMode mode) {
        this.server=Objects.requireNonNull(server);
        this.mode=Objects.requireNonNull(mode);
        this.world=Objects.requireNonNull(world);
        this.store=Objects.requireNonNull(writer);
        this.observation=Objects.requireNonNull(observation);
        this.memory=Objects.requireNonNull(memory);
        actor();
        if(writer instanceof DirectorLabReadOnlyStore ||
           observation.mode()!=mode || memory.mode()!=mode ||
           observation.configuration().calibration()!=(mode==ProviderMode.PRODUCTION
               ?Calibration.APPROVED:Calibration.DEFAULT_FOR_TESTING) ||
           mode==ProviderMode.PRODUCTION &&
               writer instanceof DirectorBootstrap.TestOnlyBackend ||
           !world.equals(store.session().worldId()) ||
           !memory.status().j04Bound())
            throw new IllegalArgumentException("wrong J01 WAL dependencies");
        session=writer.session();
        reducer=new MemoryReducer(new MemorySettings(observation.configuration(),mode==ProviderMode.PRODUCTION));
    }
    private void actor(){
        if(!server.isSameThread())throw new IllegalStateException("Minecraft server actor");
    }
    private void fail(String reason){
        phase=Phase.FAILED_SAFE;
        System.out.println("DIRECTOR_NATIVE_J01_WAL_INGRESS_FAILED_SAFE "+reason);
    }
    public void poll(){poll(true);}
    public void poll(boolean allowNewWrite){
        actor();
        if(phase==Phase.CLOSED||phase==Phase.FAILED_SAFE)return;
        try {
            if(!session.equals(store.session())){
                fail("J04_SESSION_CHANGED");return;
            }
            if(phase==Phase.WAL_PENDING) {
                if(pending==null||!pending.isDone())return;
                if(pending.isCompletedExceptionally()){
                    fail("J04_ASYNC_WRITER_EXCEPTION");return;
                }
                var ack=pending.getNow(null);
                if(ack!=null&&ack.ackStatus()==AckStatus.REJECTED&&store instanceof JournalStore journal){
                    var recovery=ObservationAppendRecovery.verify(journal,session,pendingBatch,ack);
                    if(recovery==ObservationAppendRecovery.Status.WAIT_FOR_INDEX)return;
                    if(recovery==ObservationAppendRecovery.Status.REBASE_ALLOWED){
                        rejected=ack;pending=null;phase=Phase.WAITING_FOR_MEMORY;
                        System.out.println("DIRECTOR_NATIVE_J01_REJECTED_CAS_REBASE_WAIT tx="+pendingBatch.transactionId().value());return;
                    }
                }
                if(ack==null ||
                   ack.ackStatus()!=AckStatus.DURABLE ||
                   !ack.transactionId().equals(pendingBatch.transactionId()) ||
                   !ack.transactionHash().equals(pendingBatch.transactionHash()) ||
                   !ack.storeGeneration().equals(session.storeGeneration()) ||
                   ack.durableSequence().value()<1 ||
                   ack.permitAttempt().isPresent()) {
                    fail("J04_ACK_UNCERTAIN_OR_REJECTED");return;
                }
                // Only J04 ACK can consume a sealed J01 observation window.
                observation.acknowledge(pendingIds);
                durable=Math.addExact(durable,1);
                observations=Math.addExact(observations,pendingIds.size());
                projectedSequence=ack.durableSequence().value();
                phase=Phase.PROJECTION_PENDING;
                pending=null;pendingBatch=null;pendingIds=List.of();
                System.out.println("DIRECTOR_NATIVE_J01_WAL_DURABLE observations="+
                    observations+" batches="+durable+" sequence="+projectedSequence);
                return;
            }
            if(phase==Phase.PROJECTION_PENDING){
                var state=memory.status();
                if(state.failClosed()){
                    fail("MEMORY_PROJECTION_UNAVAILABLE");return;
                }
                if(!state.complete() ||
                   state.lastDurableSequence()<projectedSequence)
                    return;
                phase=Phase.IDLE;
            }
            if(!allowNewWrite)return;
            if(memory.status().failClosed()){
                fail("MEMORY_PROJECTION_FAILED");return;
            }
            if(!memory.status().complete() || observation.snapshot().isEmpty()){
                phase=Phase.WAITING_FOR_MEMORY;
                return;
            }
            if(!memory.currentHeads()){
                phase=Phase.WAITING_FOR_MEMORY;return;
            }
            if(observation.anchorOwner().traceBusy())return;
            long now=observation.now();
            if(rejected==null&&now-lastAttempt<CADENCE)return;
            lastAttempt=now;
            var before=memory.view().orElseThrow();
            var context=observation.snapshot().orElseThrow();
            if(!before.scopes().worldId().equals(world) ||
               !context.scopes().worldId().equals(world))
                throw new IllegalArgumentException("J01 memory world mismatch");
            ObservationBatch batch;
            if(rejected!=null){
                if(!(store instanceof JournalStore journal)){fail("REBASE_WITHOUT_ACTUAL_J04");return;}
                var recovery=ObservationAppendRecovery.verify(journal,session,pendingBatch,rejected);
                if(recovery==ObservationAppendRecovery.Status.WAIT_FOR_INDEX)return;
                if(recovery!=ObservationAppendRecovery.Status.REBASE_ALLOWED){fail("REBASE_RECEIPT_OR_CAS_PROOF_CHANGED");return;}
                batch=observation.rebaseAfterRejection(rejected,before);
                if(!new HashSet<>(batch.inputIds()).equals(new HashSet<>(pendingIds))){fail("REBASE_CHANGED_FROZEN_INPUTS");return;}
            }else batch=observation.seal(before);
            if(batch.inputIds().isEmpty()){
                observation.acknowledge(batch.inputIds());
                phase=Phase.IDLE;
                return;
            }
            // J01 region/camp data is admitted to MEMORY only if the exact
            // anchors and region identities have already been recovered
            // from a verified J04 durable projection. Unknown identities
            // still fail the batch instead of being invented from chunks.
            for(var episode:batch.campEpisodes()){
                var anchor=before.anchors().get(episode.anchorId());
                var region=before.regionRecords().get(episode.regionId());
                var candidate=episode.regionId().value().equals(
                    episode.anchorId().value());
                if(anchor==null || !anchor.active() ||
                   !(region!=null&&region.status()==RegionStatus.ACTIVE ||
                     region==null&&candidate&&anchor.regionIds().isEmpty()) ||
                   !anchor.regionIds().isEmpty() &&
                     !anchor.regionIds().contains(episode.regionId())){
                    fail("CAMP_ANCHOR_OR_REGION_UNVERIFIED");return;
                }
            }
            for(var route:batch.routeTransitions()){
                if(!before.regionRecords().containsKey(route.route().from()) ||
                   !before.regionRecords().containsKey(route.route().to())){
                    fail("ROUTE_UNKNOWN_REGION");return;
                }
            }
            for(var seen:batch.observations()){
                if(seen.coarseScope().kind()==ScopeKind.REGION &&
                    !before.regionRecords().containsKey(
                        new RegionId(seen.coarseScope().id()))){
                    fail("UNKNOWN_REGIONAL_OBSERVATION");return;
                }
                if(seen.coarseScope().kind()!=ScopeKind.PLAYER &&
                   seen.coarseScope().kind()!=ScopeKind.REGION){
                    fail("UNQUALIFIED_OBSERVATION_SCOPE");return;
                }
            }
            if(batch.inputIds().size()>64){
                fail("OBSERVATION_BATCH_TOO_LARGE");return;
            }
            var clock=new DirectorClock(new Tick(now),System.nanoTime(),Instant.now());
            var reduction=reducer.reduce(before,batch,clock);
            if(!new HashSet<>(reduction.consumedInputs()).equals(
                    new HashSet<>(batch.inputIds())) ||
               !reduction.proposedFacts().isEmpty()) {
                fail("UNQUALIFIED_REDUCTION_OUTPUT");return;
            }
            // Derived regional counters are informational. Persist their
            // exact RegionMemoryAggregate instead of writing a second
            // competing memory delta/CAS to J04.
            for(var delta:reduction.counterDeltas()){
                var memoryRecord=reduction.aggregateChanges().stream()
                    .map(AggregateChange::next)
                    .filter(RegionMemoryAggregate.class::isInstance)
                    .map(RegionMemoryAggregate.class::cast)
                    .filter(a->a.value().regionId().value().equals(delta.scope().id()))
                    .findFirst();
                if(delta.scope().kind()!=ScopeKind.REGION ||
                   memoryRecord.isEmpty() ||
                   !memoryRecord.orElseThrow().header().revision().equals(delta.afterRevision()) ||
                   !memoryRecord.orElseThrow().value().components().equals(delta.changes())) {
                    fail("UNVERIFIED_REGIONAL_COUNTER_DELTA");return;
                }
            }
            var records=new ArrayList<DurableRecord>();
            var revisions=new HashMap<ScopeRef,Revision>();
            for(var change:reduction.aggregateChanges()){
                ScopeRef scope=switch(change.next()){
                    case PlayerOperationalAggregate p ->
                        new ScopeRef(ScopeKind.PLAYER,p.value().playerId().value());
                    case RegionAggregate r ->
                        new ScopeRef(ScopeKind.REGION,r.value().regionId().value());
                    // RegionMemory and Anchor have independent AggregateKey
                    // revisions, enforced by StoreProjection.add() at J04.
                    // They do NOT use the RegionRecord's ScopeRef CAS revision.
                    case RegionMemoryAggregate ignored -> null;
                    case AnchorAggregate ignored -> null;
                    default -> throw new IllegalArgumentException(
                        "J01 unqualified aggregate kind");
                };
                if(scope!=null){
                    var expected=change.expectedRevision().orElse(new Revision(0));
                    var prior=revisions.putIfAbsent(scope,expected);
                    if(prior!=null&&!prior.equals(expected)){
                        fail("AMBIGUOUS_SCOPE_CAS");return;
                    }
                }
                records.add(new DurableAggregate(change.next()));
            }
            if(records.isEmpty()){
                fail("INPUT_WITHOUT_DURABLE_CHANGE");return;
            }
            // Deterministic transaction identity survives source replay.
            var ids=batch.inputIds().stream().sorted().map(
                id->id.value().toString()).toList();
            var tx=new TransactionId(UUID.nameUUIDFromBytes(
                (world.value()+"/j01-ingress-v1/"+String.join("/",ids))
                    .getBytes(StandardCharsets.UTF_8)));
            var durableBatch=ResultTransaction.batch(tx,session.storeGeneration(),
                revisions,records,List.of());
            rejected=null;pendingBatch=durableBatch;
            pendingIds=List.copyOf(batch.inputIds());
            attempted=Math.addExact(attempted,1);
            pending=store.append(durableBatch).toCompletableFuture();
            phase=Phase.WAL_PENDING;
            System.out.println("DIRECTOR_NATIVE_J01_WAL_PREPARED input_count="+
                pendingIds.size()+" no_gameplay=true recovered_simtick="+now+
                " changes="+records.size()+" projected_before="+
                memory.status().lastDurableSequence());
        }catch(RuntimeException invalid){
            fail(invalid.getClass().getSimpleName()+": "+invalid.getMessage());
        }
    }
    public ProviderMode mode(){return mode;}
    public View view(){
        actor();
        return new View(phase,attempted,durable,observations,projectedSequence,
            false,mode==ProviderMode.TEST_ONLY);
    }
    @Override public void close(){
        actor();
        // J04 closes asynchronously and drains its single writer. Never claim
        // pending unacknowledged J01 inputs were durably consumed.
        phase=Phase.CLOSED;
    }
}
