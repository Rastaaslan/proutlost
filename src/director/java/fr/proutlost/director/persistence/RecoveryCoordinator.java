package fr.proutlost.director.persistence;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.*;
import fr.proutlost.director.api.permission.CommitPermit;
import java.util.*;
import java.util.concurrent.*;

/** Bounded actor mailbox for recovery probes. Never applies, cleans up, force-loads or issues permits.
 * A returned RECOVER requires a new admission and durable PREPARE in a new reservation generation.
 * Terminal decisions are persisted as technical facts before delivery; they never claim gameplay fulfillment.
 */
public final class RecoveryCoordinator {
    public record Decision(OperationToken operation, ReconcileStatus status, RecoveryMode mode,
                           List<RecoveryEvidence> evidence, boolean terminal, String reason) {
        public Decision { evidence=List.copyOf(evidence); }
    }
    private static final class Pending {
        final PreparedEffect intent; final Tick deadline; final RecoveryMode preferred; final CompletableFuture<BlobReadResult> mailbox;
        int attempts; Decision decision; CompletableFuture<CommitAck> audit;
        Pending(PreparedEffect intent,Tick deadline,RecoveryMode preferred,CompletableFuture<BlobReadResult> mailbox){this.intent=intent;this.deadline=deadline;this.preferred=preferred;this.mailbox=mailbox;}
    }
    private final Thread actor=Thread.currentThread();
    private final DurableStore store;
    private final WorldEffectPort effects;
    private final int capacity, maxAttempts;
    private final Map<OperationToken,Pending> pending=new HashMap<>();
    public RecoveryCoordinator(DurableStore store, WorldEffectPort effects, int capacity, int maxAttempts) {
        this.store=Objects.requireNonNull(store);this.effects=Objects.requireNonNull(effects);
        if(capacity<1||maxAttempts<1)throw new IllegalArgumentException("capacity/attempts");this.capacity=capacity;this.maxAttempts=maxAttempts;
    }
    private void actor() { if(Thread.currentThread()!=actor)throw new IllegalStateException("server actor required"); }
    public boolean submit(PreparedEffect intent, Tick deadline, RecoveryMode preferred) {
        actor();OperationToken token=intent.intent().operationToken();
        if(pending.size()>=capacity||pending.containsKey(token))return false;
        var session=store.session();var mailbox=new CompletableFuture<BlobReadResult>();
        pending.put(token,new Pending(intent,deadline,Objects.requireNonNull(preferred),mailbox));
        store.readBlobs(new BlobReadRequest(session.worldId(),session.storeGeneration(),CommitPermit.Authority.references(intent.durableBlobs())))
            .whenComplete((value,error)-> { if(error!=null)mailbox.complete(new BlobReadResult(AckStatus.UNAVAILABLE,List.of(),List.of()));else mailbox.complete(value); });
        return true;
    }
    /** Caller supplies ONLY current loaded proofs, on the actor. Missing proofs never authorize replay. */
    public Optional<Decision> poll(OperationToken token, List<RecoveryEvidence> proofs, Tick now) {
        actor();Pending p=pending.get(token);if(p==null)return Optional.empty();
        if(p.decision!=null) {
            CommitAck ack=p.audit.getNow(null);if(ack==null)return Optional.empty();
            if(ack.ackStatus()==AckStatus.DURABLE && now.compareTo(p.deadline)>=0 && p.decision.mode()==RecoveryMode.RECOVER)
                return persist(p,new Decision(token,ReconcileStatus.AMBIGUOUS,RecoveryMode.ABORT_SAFE,p.decision.evidence(),true,"deadline expired while awaiting durable recovery decision"),now);
            pending.remove(token);
            if(ack.ackStatus()!=AckStatus.DURABLE)return Optional.of(new Decision(token,ReconcileStatus.AMBIGUOUS,RecoveryMode.ABORT_SAFE,p.decision.evidence(),true,"WAL unavailable; recovery dossier not acknowledged; no replay"));
            return Optional.of(p.decision);
        }
        if(now.compareTo(p.deadline)>=0)return persist(p,new Decision(token,ReconcileStatus.AMBIGUOUS,RecoveryMode.ABORT_SAFE,proofs,true,"recovery deadline; preserve and isolate"),now);
        BlobReadResult blobs=p.mailbox.getNow(null);if(blobs==null)return Optional.empty();
        if(blobs.status()!=AckStatus.DURABLE)return persist(p,new Decision(token,ReconcileStatus.AMBIGUOUS,RecoveryMode.ABORT_SAFE,proofs,true,"missing/corrupt durable blobs"),now);
        p.attempts++;
        var result=effects.reconcile(new RecoveryProbe(p.intent,proofs,new BlobBundle(blobs.contents())));
        // A port's SAME_PRE/SAME_POST response still needs complete, confirmed local provenance.
        Set<ResourceKey> required=new HashSet<>(p.intent.preconditions().fingerprints().keySet());required.addAll(p.intent.expectedPost().keySet());
        Map<ResourceKey,RecoveryEvidence> evidence=new HashMap<>();
        boolean duplicate=false;for(var e:result.proofs())if(evidence.put(e.resource(),e)!=null)duplicate=true;
        ReconcileStatus status=result.status();
        if(!result.errors().isEmpty()||duplicate)status=ReconcileStatus.AMBIGUOUS;
        if(status==ReconcileStatus.SAME_POST || status==ReconcileStatus.SAME_PRE_PROVEN) {
            if(!evidence.keySet().containsAll(required))status=ReconcileStatus.AMBIGUOUS;
            else for(var key:required) {
                var e=evidence.get(key);var epoch=p.intent.preconditions().epochs().get(key);
                var hash=status==ReconcileStatus.SAME_POST?p.intent.expectedPost().get(key):p.intent.preconditions().fingerprints().get(key);
                if(e.status()!=EvidenceStatus.CONFIRMED||e.ownerToken().isEmpty()||epoch==null||!epoch.equals(e.ownerEpoch())
                    ||e.playerOverrideEpoch().value()!=0||hash==null||!e.observedHash().equals(Optional.of(hash))
                    ||status==ReconcileStatus.SAME_PRE_PROVEN&&!e.localAbsenceProven()) {status=ReconcileStatus.AMBIGUOUS;break;}
            }
        }
        RecoveryMode mode=switch(status) {
            case SAME_POST -> p.preferred==RecoveryMode.CLEANUP?RecoveryMode.CLEANUP:
                p.preferred==RecoveryMode.ABORT_SAFE?RecoveryMode.ABORT_SAFE:RecoveryMode.COMPLETE_FROM_STATE;
            case SAME_PRE_PROVEN -> p.preferred==RecoveryMode.RECOVER?RecoveryMode.RECOVER:
                p.preferred==RecoveryMode.CLEANUP?RecoveryMode.CLEANUP:RecoveryMode.ABORT_SAFE;
            case PLAYER_OVERRIDE, AMBIGUOUS, UNAVAILABLE -> RecoveryMode.ABORT_SAFE;
        };
        if(status==ReconcileStatus.UNAVAILABLE && p.attempts<maxAttempts)
            return Optional.of(new Decision(token,status,mode,result.proofs(),false,"defer unloaded resources; no force-load"));
        if(status==ReconcileStatus.UNAVAILABLE)status=ReconcileStatus.AMBIGUOUS;
        return persist(p,new Decision(token,status,mode,result.proofs(),true,
            p.attempts>=maxAttempts?"recovery attempts exhausted; isolate":"new admission required for any write"),now);
    }
    private Optional<Decision> persist(Pending p,Decision decision,Tick now) {
        p.decision=decision;p.audit=new CompletableFuture<>();
        var session=store.session();var tx=new TransactionId(UUID.randomUUID());
        Map<String,JsonValue> payload=new TreeMap<>();
        payload.put("operation_token",new JsonValue.Text(decision.operation().value().toString()));
        payload.put("reconcile_status",new JsonValue.Text(decision.status().name()));
        payload.put("recovery_mode",new JsonValue.Text(decision.mode().name()));
        payload.put("reason",new JsonValue.Text(decision.reason()));
        payload.put("attempts",new JsonValue.Text(Integer.toString(p.attempts)));
        payload.put("proofs",new JsonValue.Array(decision.evidence().stream().map(e->StoreJson.model(RecoveryEvidence.class,e)).toList()));
        var fact=new DirectorFact(new FactId(UUID.randomUUID()),new FactType("proutlost_director:recovery_decision"),
            List.of(new ScopeRef(ScopeKind.WORLD,session.worldId().value())),Optional.of(p.intent.intent().instanceId()),
            EvidenceStatus.CONFIRMED,payload,true,Optional.empty());
        var record=new DurableFact(fact);
        var hash=BlobContent.hash(new fr.proutlost.director.api.codec.ModelCodec<>(DurableRecord.class).encode(record));
        var batch=new DurableBatch(tx,session.storeGeneration(),Map.of(),List.of(record),List.of(),Optional.empty(),hash);
        store.append(batch).whenComplete((ack,error)->p.audit.complete(error==null?ack:
            new CommitAck(tx,session.storeGeneration(),new Sequence(0),hash,AckStatus.UNAVAILABLE,Optional.empty(),Set.of())));
        return Optional.empty();
    }

}
