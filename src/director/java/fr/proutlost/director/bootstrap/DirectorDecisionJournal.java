package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.DurableStore;
import fr.proutlost.director.runtime.ResultTransaction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * Actor-owned transition from a pure J03 decision to one atomic J04 audit
 * and state-change transaction. This is NOT gameplay execution authority.
 *
 * No AdmissionPort call, world access, physical mutation or background thread
 * happens here. Every selected plan still needs independent admission,
 * durable PREPARE, live validation, and a one-use commit permit.
 *
 * A deterministic transaction identity and immutable batch allow retry after
 * a lost ACK without committing a decision twice. A different payload using
 * the same decision ID is rejected, never silently normalized.
 */
public final class DirectorDecisionJournal {
    public enum State { WAITING, DURABLE, REJECTED, UNAVAILABLE }
    public record Receipt(DecisionId decisionId, State state, Optional<CommitAck> ack) {
        public Receipt {
            Objects.requireNonNull(decisionId);
            Objects.requireNonNull(state);
            Objects.requireNonNull(ack);
            if(state==State.DURABLE && (ack.isEmpty() ||
                ack.orElseThrow().ackStatus()!=AckStatus.DURABLE))
                throw new IllegalArgumentException("durable decision lacks WAL proof");
        }
        public boolean mayProceedToAdmission() {return state==State.DURABLE;}
    }
    private record Pending(DurableBatch batch, CompletableFuture<CommitAck> completion) {}

    private final Thread actor=Thread.currentThread();
    private final DurableStore store;
    private final StoreSession authoritySession;
    private final WorldId world;
    private final int maximumPending;
    private final Map<DecisionId,Pending> inflight=new LinkedHashMap<>();
    private final Map<DecisionId,Receipt> resolved=new LinkedHashMap<>();

    public DirectorDecisionJournal(DurableStore store,int maximumPending) {
        this.store=Objects.requireNonNull(store);
        authoritySession=Objects.requireNonNull(store.session(),"J04 session missing");
        world=authoritySession.worldId();
        if(maximumPending<1 || maximumPending>4096)
            throw new IllegalArgumentException("decision journal capacity");
        this.maximumPending=maximumPending;
    }
    private void actor() {
        if(Thread.currentThread()!=actor)
            throw new IllegalStateException("Director decision journal server actor required");
    }
    /** A durable flag alone is not an ACK for *this* J03 transaction.
     * Refuse a stale session, cross-transaction, cross-generation, forged
     * digest or unexpected physical permit before any admission call.
     */
    static boolean boundDurableAck(DurableBatch submitted, CommitAck ack) {
        return submitted!=null && ack!=null && ack.ackStatus()==AckStatus.DURABLE
            && ack.durableSequence().value()>0
            && ack.transactionId().equals(submitted.transactionId())
            && ack.storeGeneration().equals(submitted.expectedStoreGeneration())
            && ack.transactionHash().equals(submitted.transactionHash())
            && ack.permitAttempt().equals(submitted.permitAttempt())
            && ack.forcedBlobs().equals(DurableBlobReferences.collect(submitted.records()));
    }
    private boolean sameAuthority() {
        try { return authoritySession.equals(store.session()); }
        catch(RuntimeException lostSession) { return false; }
    }
    private static TransactionId txId(WorldId world,DecisionId proposal) {
        var identity=("proutlost-decision-v1\0"+world.value()+"\0"+
            proposal.value()).getBytes(StandardCharsets.UTF_8);
        return new TransactionId(UUID.nameUUIDFromBytes(identity));
    }
    private static ScopeRef scopeFor(AggregateKey key) {
        ScopeKind kind=switch(key.kind()) {
            case WORLD -> ScopeKind.WORLD;
            case REGION -> ScopeKind.REGION;
            case LOCATION -> ScopeKind.LOCATION;
            case EVENT -> ScopeKind.EVENT;
            case PLAYER_OPERATIONAL -> ScopeKind.PLAYER;
            default -> null;
        };
        return kind==null?null:new ScopeRef(kind,key.id());
    }

    /** Exact idempotent commit shape; never authorizes a physical operation. */
    public static DurableBatch batch(DecisionProposal proposal, StoreSession session) {
        Objects.requireNonNull(proposal);
        Objects.requireNonNull(session);
        if(!proposal.proposalId().equals(proposal.audit().decisionId()) ||
            !proposal.audit().context().scopes().worldId().equals(session.worldId()))
            throw new IllegalArgumentException("decision identity or world changed");
        if(proposal.selectedPlan().isPresent() != proposal.audit().chosen().isPresent())
            throw new IllegalArgumentException("selected plan/audit disagreement");
        if(proposal.selectedPlan().isPresent() &&
            !proposal.selectedPlan().orElseThrow().candidateId().equals(
                proposal.audit().chosen().orElseThrow()))
            throw new IllegalArgumentException("selected candidate mismatch");
        Map<ScopeRef,Revision> cas=new HashMap<>();
        Set<AggregateKey> seen=new HashSet<>();
        List<DurableRecord> records=new ArrayList<>();
        records.add(new DurableAudit(proposal.audit()));
        for(var change:proposal.stateChanges().stream()
                .sorted(Comparator.comparing(c->c.key().kind()+":"+c.key().id())).toList()) {
            if(!seen.add(change.key()) ||
                !change.next().key().equals(change.key()))
                throw new IllegalArgumentException("duplicate/mismatched aggregate change");
            ScopeRef scope=scopeFor(change.key());
            if(scope!=null) {
                Revision old=change.expectedRevision().orElse(new Revision(0));
                Revision existing=cas.putIfAbsent(scope,old);
                if(existing!=null && !existing.equals(old))
                    throw new IllegalArgumentException("conflicting decision CAS scope");
            }
            records.add(new DurableAggregate(change.next()));
        }
        return ResultTransaction.batch(txId(session.worldId(),proposal.proposalId()),
            session.storeGeneration(),cas,records,List.of());
    }
    /** Enqueues one immutable decision. Returns only a non-authorizing receipt. */
    public Receipt submit(DecisionProposal proposal) {
        actor();
        Objects.requireNonNull(proposal);
        if(!sameAuthority())
            return new Receipt(proposal.proposalId(),State.REJECTED,Optional.empty());
        var batch=batch(proposal,authoritySession);
        return submitBatch(proposal,batch);
    }
    /** The archive is encoded on a bounded worker before this actor-only call. */
    public Receipt submitRecorded(DecisionProposal proposal,DirectorFact archive,List<BlobContent> contents) {
        actor();Objects.requireNonNull(proposal);Objects.requireNonNull(archive);contents=List.copyOf(contents);
        if(!sameAuthority())return new Receipt(proposal.proposalId(),State.REJECTED,Optional.empty());
        RecordedDecisionArchive.validateIndex(archive,proposal.audit(),authoritySession,RecordedDecisionArchive.mode(archive));
        if(!RecordedDecisionArchive.source(archive).equals(authoritySession))throw new IllegalArgumentException("obsolete archive source session");
        if(!contents.stream().map(BlobContent::reference).collect(java.util.stream.Collectors.toSet()).equals(DurableBlobReferences.recordedDecision(archive))
                ||contents.stream().map(BlobContent::reference).distinct().count()!=contents.size())throw new IllegalArgumentException("archive content/reference set");
        var original=batch(proposal,authoritySession);var rows=new ArrayList<DurableRecord>(original.records());rows.add(new DurableFact(archive));
        return submitBatch(proposal,ResultTransaction.batch(original.transactionId(),authoritySession.storeGeneration(),original.expectedAggregateRevisions(),rows,contents));
    }
    private Receipt submitBatch(DecisionProposal proposal,DurableBatch batch) {
        var ready=resolved.get(proposal.proposalId());
        if(ready!=null) {
            var previous=inflight.get(proposal.proposalId());
            if(previous!=null && !previous.batch().equals(batch))
                throw new IllegalArgumentException("decision ID reused with new content");
            return ready;
        }
        var old=inflight.get(proposal.proposalId());
        if(old!=null) {
            if(!old.batch().equals(batch))
                throw new IllegalArgumentException("decision ID reused with new content");
            return poll(proposal.proposalId());
        }
        if(pendingCount()>=maximumPending)
            return new Receipt(proposal.proposalId(),State.UNAVAILABLE,Optional.empty());
        // Only trusted J04 performs IO; completion callback does not touch actor state.
        CompletableFuture<CommitAck> async=new CompletableFuture<>();
        inflight.put(proposal.proposalId(),new Pending(batch,async));
        try {
            store.append(batch).whenComplete((ack,failure)->{
                if(failure!=null || ack==null)async.completeExceptionally(
                    failure!=null?failure:new IllegalStateException("empty durable ACK"));
                else async.complete(ack);
            });
        }catch(RuntimeException failure){
            async.completeExceptionally(failure);
        }
        return new Receipt(proposal.proposalId(),State.WAITING,Optional.empty());
    }
    /** Polled only by the Minecraft actor. No join/get blocking on IO threads. */
    public Receipt poll(DecisionId id) {
        actor();
        Objects.requireNonNull(id);
        // A cached DURABLE receipt from an old boot is not authority in a
        // replacement J04 session, even if its transaction digest is valid.
        if(!sameAuthority())return new Receipt(id,State.REJECTED,Optional.empty());
        var previous=resolved.get(id);
        if(previous!=null)return previous;
        var entry=inflight.get(id);
        if(entry==null)
            return new Receipt(id,State.UNAVAILABLE,Optional.empty());
        var completion=entry.completion();
        if(!completion.isDone())
            return new Receipt(id,State.WAITING,Optional.empty());
        CommitAck ack;
        try {ack=completion.getNow(null);}
        catch(CompletionException failure) {ack=null;}
        State state=ack==null?State.UNAVAILABLE:switch(ack.ackStatus()) {
            case DURABLE -> boundDurableAck(entry.batch(),ack) ? State.DURABLE : State.REJECTED;
            case REJECTED -> State.REJECTED;
            default -> State.UNAVAILABLE;
        };
        if(state==State.REJECTED && ack!=null && ack.ackStatus()==AckStatus.DURABLE)
            System.out.println("DIRECTOR_J03_J04_ACK_IDENTITY_FAILED_SAFE");
        var receipt=new Receipt(id,state,
            state==State.REJECTED && ack!=null && ack.ackStatus()==AckStatus.DURABLE
                ? Optional.empty() : Optional.ofNullable(ack));
        resolved.put(id,receipt);
        // Keep the immutable submission while a receipt is cached, so a
        // forged second payload under the same decision ID cannot be accepted.
        return receipt;
    }
    /** Forget only a confirmed durable receipt. J04 remains the authoritative\n     * idempotency ledger across this bounded actor-memory cache.\n     */
    public void forgetDurable(DecisionId id) {
        actor();
        var receipt=resolved.get(id);
        if(receipt==null || !receipt.mayProceedToAdmission())
            throw new IllegalStateException("cannot forget unconfirmed decision");
        resolved.remove(id);
        inflight.remove(id);
    }
    /** Release only a terminal actor receipt. Persistent J04 request digests,
     * not actor RAM, enforce idempotence across memory cleanup and reboots.
     */
    public boolean retireTerminal(DecisionId id) {
        actor();
        var existing=resolved.remove(id);
        if(existing==null)return false;
        inflight.remove(id);
        return true;
    }
    public int pendingCount() {
        actor();
        return (int)inflight.keySet().stream().filter(
            key->!resolved.containsKey(key)).count();
    }
}
