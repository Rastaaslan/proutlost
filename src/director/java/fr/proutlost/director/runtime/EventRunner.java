package fr.proutlost.director.runtime;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.permission.*;
import fr.proutlost.director.api.port.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/** Multiple bounded slice sagas. Poll only on the server actor; callbacks publish ACK data only.
 * Planning and blob hydration precede submit. Each slice has its own durable PREPARE and permit.
 * A result transaction builder must include all resulting state/facts/uniqueness/outbox changes.
 */
public final class EventRunner {
    public enum Stage { PREPARING, RESULT_PENDING, COMMITTED, REJECTED, RECOVERY_REQUIRED }
    public record View(TransactionId prepare, Stage stage, Optional<SliceResult> result, Optional<String> failure) {}
    private record Delivery(CommitAck ack, Throwable failure) {}
    private static final class Flight {
        final DurableBatch prepare; final PrepareRecord record; final Map<ResourceKey,Fingerprint> expected;
        final Function<SliceResult,DurableBatch> finish; final StoreSession session;
        CommitPermit.StabilizationPermit stopPermit;
        final Map<SliceId,SliceResult> stopResults=new LinkedHashMap<>();
        Stage stage=Stage.PREPARING; SliceResult result; String failure; DurableBatch resultBatch;
        final CompletableFuture<Delivery> delivery=new CompletableFuture<>();
        Flight(DurableBatch p,Map<ResourceKey,Fingerprint> expected,Function<SliceResult,DurableBatch> f,StoreSession s) {
            prepare=p;record=((DurablePrepare)p.records().getFirst()).value();this.expected=Map.copyOf(expected);finish=f;session=s;
        }
    }
    private final Thread actor=Thread.currentThread();
    private final CommitPermit.Authority authority; private final DurableStore store;
    private final WorldEffectPort effects; private final ResourceArbiter reservations; private final int capacity;
    private final Map<TransactionId,Flight> flights=new LinkedHashMap<>();
    public EventRunner(CommitPermit.Authority authority,DurableStore store,WorldEffectPort effects,ResourceArbiter reservations,int capacity) {
        this.authority=Objects.requireNonNull(authority);this.store=Objects.requireNonNull(store);
        this.effects=Objects.requireNonNull(effects);this.reservations=Objects.requireNonNull(reservations);
        if(capacity<1)throw new IllegalArgumentException("capacity");this.capacity=capacity;
    }
    private void actor(){if(Thread.currentThread()!=actor)throw new IllegalStateException("server actor required");}
    public void submit(DurableBatch prepare,Map<ResourceKey,Fingerprint> expected,LiveValidationSnapshot live,
                       Function<SliceResult,DurableBatch> resultTransaction) {
        actor();Objects.requireNonNull(resultTransaction);
        if(flights.size()>=capacity || flights.containsKey(prepare.transactionId()))throw new IllegalArgumentException("inflight bound/duplicate");
        if(prepare.records().size()!=1 || !(prepare.records().getFirst() instanceof DurablePrepare p))throw new IllegalArgumentException("PREPARE required");
        if(!reservations.valid(p.value().reservation(),live.now()))throw new IllegalArgumentException("reservation not held");
        Flight f=new Flight(prepare,expected,resultTransaction,store.session());
        authority.submit(prepare,live);flights.put(prepare.transactionId(),f);
        if(effects instanceof ReservationInspectionPort inspection){
            try{inspection.inspectReservations(p.value().reservation().instanceId(),reservations::snapshot);}
            catch(RuntimeException unavailable){System.err.println("DIRECTOR_RESERVATION_INSPECTION_UNAVAILABLE");}
        }
    }
    public View poll(TransactionId id,LiveValidationSnapshot live) {
        actor();Flight f=Objects.requireNonNull(flights.get(id),"unknown flight");
        if(f.stage==Stage.PREPARING) {
            PermitResult permit=authority.poll(id,live);
            if(permit.status()==PermitResult.Status.REJECTED){f.stage=Stage.REJECTED;f.failure=permit.reason().orElseThrow().name();}
            else if(permit.status()==PermitResult.Status.GRANTED) {
                if(!reservations.valid(f.record.reservation(),live.now())) {f.stage=Stage.REJECTED;f.failure="stale reservation";}
                else {
                    reservations.markStarted(f.record.reservation(),live.now());
                    // An exception can follow a partial physical write: never retry blindly or release claims.
                    try {
                        var granted=permit.permit().orElseThrow();
                        f.stopPermit=granted.stabilizationPermit().orElse(null);
                        f.result=effects.apply(f.record.slice(),granted);
                        Set<OperationToken> planned=new HashSet<>();
                        f.record.slice().operations().forEach(o->planned.add(o.operationToken()));
                        if(f.result.outcome()!=Outcome.SUCCEEDED || f.result.remainingWork()!=0 || !f.result.conflictCells().isEmpty()
                                || !f.result.appliedOperationIds().equals(planned)
                                || !f.result.observedHashes().entrySet().containsAll(f.expected.entrySet()))
                            throw new IllegalStateException("slice verification requires reconciliation");
                        f.resultBatch=Objects.requireNonNull(f.finish.apply(f.result));
                        if(f.resultBatch.transactionId().equals(id) || !f.resultBatch.expectedStoreGeneration().equals(f.session.storeGeneration())
                                || f.resultBatch.permitAttempt().isPresent())throw new IllegalArgumentException("result transaction binding");
                        long matches=f.resultBatch.records().stream().filter(r->r instanceof DurableResult d
                                && d.value().prepareTransactionId().equals(id) && d.value().operationToken().equals(f.record.operationToken())
                                && d.value().result().equals(f.result)).count();
                        if(matches!=1)throw new IllegalArgumentException("one exact result required");
                        f.stage=Stage.RESULT_PENDING;
                        store.append(f.resultBatch).whenComplete((ack,error)->f.delivery.complete(new Delivery(ack,error)));
                    } catch(RuntimeException failure) {f.stage=Stage.RECOVERY_REQUIRED;f.failure=failure.toString();}
                }
            }
        }
        if(f.stage==Stage.RESULT_PENDING) {
            Delivery d=f.delivery.getNow(null);
            if(d!=null) {
                CommitAck ack=d.ack();
                boolean valid=d.failure()==null && ack!=null && ack.ackStatus()==AckStatus.DURABLE
                        && f.session.equals(store.session()) && ack.storeGeneration().equals(f.session.storeGeneration())
                        && ack.transactionId().equals(f.resultBatch.transactionId()) && ack.transactionHash().equals(f.resultBatch.transactionHash())
                        && ack.durableSequence().value()>0 && ack.permitAttempt().isEmpty()
                        && ack.forcedBlobs().containsAll(f.resultBatch.blobs().stream().map(BlobContent::reference).toList());
                f.stage=valid?Stage.COMMITTED:Stage.RECOVERY_REQUIRED;
                if(!valid)f.failure="result ACK unavailable or mismatched";
            }
        }
        return new View(id,f.stage,Optional.ofNullable(f.result),Optional.ofNullable(f.failure));
    }
    /** Executes only a tranche of the original forced stop plan. No new writer ACK or
     * ordinary-impact authority is requested, even when result persistence has failed.
     * Failure retains the flight and its evidence for reconciliation.
     */
    public SliceResult stabilize(TransactionId id,SliceId stopId,StabilizationLiveSnapshot live) {
        actor();Flight f=Objects.requireNonNull(flights.get(id),"unknown flight");
        if(f.stopPermit==null)throw new IllegalStateException("no forced stabilization authority");
        var slice=f.record.stabilizationPlan().orElseThrow().slices().stream()
                .map(StabilizationSlice::slice).filter(s->s.sliceId().equals(stopId)).findFirst().orElseThrow();
        if(f.stopResults.containsKey(stopId))throw new IllegalStateException("stop already completed");
        f.stage=Stage.RECOVERY_REQUIRED;
        try {
            var result=effects.stabilize(slice,f.stopPermit,live);
            f.stopResults.put(stopId,result);
            f.failure="stop attempted; result reconciliation required";
            return result;
        } catch(RuntimeException failure) {f.failure="stop ambiguous or rejected: "+failure;throw failure;}
    }
    public Map<SliceId,SliceResult> stabilizationResults(TransactionId id) {
        actor();return Map.copyOf(Objects.requireNonNull(flights.get(id)).stopResults);
    }
    /** Release only after full-event cleanup/transfer, not automatically after an individual slice. */
    public void forget(TransactionId id) {
        actor();Flight f=Objects.requireNonNull(flights.get(id));
        if(f.stage!=Stage.COMMITTED && f.stage!=Stage.REJECTED)throw new IllegalStateException("recovery evidence still needed");
        flights.remove(id);
    }
}
