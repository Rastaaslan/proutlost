package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.DurableStore;
import fr.proutlost.director.runtime.ResultTransaction;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Pure J03/J04 seam fixtures. No disk, Minecraft world or forged fsync PASS. */
public final class DirectorDecisionJournalAckFenceTests {
    private static int assertions;
    private static void check(boolean accepted, String message) {
        if(!accepted) throw new AssertionError(message);
        assertions++;
    }
    private static final class Fixture {
        final StoreSession initial = new StoreSession(
            new WorldId(UUID.randomUUID()),new Epoch(7),new Epoch(2),
            new SessionId(UUID.randomUUID()));
        final AtomicReference<StoreSession> current = new AtomicReference<>(initial);
        final DurableStore store = (DurableStore)Proxy.newProxyInstance(
            DurableStore.class.getClassLoader(),new Class<?>[]{DurableStore.class},
            (proxy, method, args) -> {
                if(method.getName().equals("session"))return current.get();
                throw new UnsupportedOperationException("fixture does not perform J04 IO");
            });
        final DurableBatch submitted=ResultTransaction.batch(
            new TransactionId(UUID.randomUUID()),initial.storeGeneration(),
            Map.of(),List.of(),List.of());
        final DecisionId decision=new DecisionId(UUID.randomUUID());
        final DirectorDecisionJournal journal=new DirectorDecisionJournal(store,2);
        CommitAck ack(AckStatus state) {
            return new CommitAck(submitted.transactionId(),initial.storeGeneration(),
                new Sequence(17),submitted.transactionHash(),state,Optional.empty(),Set.of());
        }
        void inject(CommitAck ack) throws Exception {
            Class<?> pending=Class.forName(DirectorDecisionJournal.class.getName()+"$Pending");
            Constructor<?> ctor=pending.getDeclaredConstructor(DurableBatch.class,CompletableFuture.class);
            ctor.setAccessible(true);
            var field=DirectorDecisionJournal.class.getDeclaredField("inflight");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<DecisionId,Object> inflight=(Map<DecisionId,Object>)field.get(journal);
            inflight.put(decision,ctor.newInstance(submitted,CompletableFuture.completedFuture(ack)));
        }
    }
    public static void main(String[] args) throws Exception {
        var f=new Fixture();
        var valid=f.ack(AckStatus.DURABLE);
        check(DirectorDecisionJournal.boundDurableAck(f.submitted,valid),"exact J04 ACK allowed");
        check(!DirectorDecisionJournal.boundDurableAck(f.submitted,null),"null ACK denied");
        check(!DirectorDecisionJournal.boundDurableAck(f.submitted,
            new CommitAck(new TransactionId(UUID.randomUUID()),valid.storeGeneration(),
                valid.durableSequence(),valid.transactionHash(),AckStatus.DURABLE,
                Optional.empty(),Set.of())),"cross-transaction ACK denied");
        check(!DirectorDecisionJournal.boundDurableAck(f.submitted,
            new CommitAck(valid.transactionId(),new Epoch(8),
                valid.durableSequence(),valid.transactionHash(),AckStatus.DURABLE,
                Optional.empty(),Set.of())),"cross-generation ACK denied");
        check(!DirectorDecisionJournal.boundDurableAck(f.submitted,
            new CommitAck(valid.transactionId(),valid.storeGeneration(),
                valid.durableSequence(),new Fingerprint("1".repeat(64)),AckStatus.DURABLE,
                Optional.empty(),Set.of())),"forged digest denied");
        check(!DirectorDecisionJournal.boundDurableAck(f.submitted,
            new CommitAck(valid.transactionId(),valid.storeGeneration(),
                new Sequence(0),valid.transactionHash(),AckStatus.DURABLE,
                Optional.empty(),Set.of())),"zero durable sequence denied");
        var unexpectedPermit=new PermitAttempt(f.initial,new Sequence(1),
            new OperationToken(UUID.randomUUID()),new ReservationId(UUID.randomUUID()),new Epoch(1));
        check(!DirectorDecisionJournal.boundDurableAck(f.submitted,
            new CommitAck(valid.transactionId(),valid.storeGeneration(),
                valid.durableSequence(),valid.transactionHash(),AckStatus.DURABLE,
                Optional.of(unexpectedPermit),Set.of())),"unexpected physical J05 permit denied");
        check(!DirectorDecisionJournal.boundDurableAck(f.submitted,
            f.ack(AckStatus.UNAVAILABLE)),"unavailable ACK denied");
        f.inject(valid);
        check(f.journal.poll(f.decision).mayProceedToAdmission(),
            "exact durable ACK retained as audit-only authority");
        check(DirectorDecisionAdmissionBridge.auditStillDurable(f.journal,f.decision),
            "deferred J03 admission may retry under same exact J04 authority");
        f.current.set(new StoreSession(f.initial.worldId(),f.initial.storeGeneration(),
            new Epoch(3),new SessionId(UUID.randomUUID())));
        check(!DirectorDecisionAdmissionBridge.auditStillDurable(f.journal,f.decision),
            "deferred J03 admission must not retry after WAL boot rotation");
        check(f.journal.poll(f.decision).state()==DirectorDecisionJournal.State.REJECTED,
            "cached durable ACK revoked after boot/authority rotation");
        f.current.set(null);
        check(f.journal.poll(f.decision).state()==DirectorDecisionJournal.State.REJECTED,
            "missing J04 authority denied");
        var changed=new Fixture();
        changed.current.set(new StoreSession(changed.initial.worldId(),
            changed.initial.storeGeneration(),new Epoch(3),new SessionId(UUID.randomUUID())));
        check(changed.journal.poll(changed.decision).state()==
            DirectorDecisionJournal.State.REJECTED,
            "session change rejected before any pending work");
        var mismatched=new Fixture();
        mismatched.inject(new CommitAck(new TransactionId(UUID.randomUUID()),
            mismatched.initial.storeGeneration(),new Sequence(17),
            mismatched.submitted.transactionHash(),AckStatus.DURABLE,Optional.empty(),Set.of()));
        check(mismatched.journal.poll(mismatched.decision).state()==
            DirectorDecisionJournal.State.REJECTED,
            "actual poll refuses foreign durable ACK");
        check(!mismatched.journal.poll(mismatched.decision).mayProceedToAdmission(),
            "repeated poll never promotes foreign durable ACK");
        var hashed=new Fixture();
        hashed.inject(new CommitAck(hashed.submitted.transactionId(),
            hashed.initial.storeGeneration(),new Sequence(17),
            new Fingerprint("2".repeat(64)),AckStatus.DURABLE,Optional.empty(),Set.of()));
        check(hashed.journal.poll(hashed.decision).state()==
            DirectorDecisionJournal.State.REJECTED,
            "actual poll refuses bad transaction hash");
        var absent=new Fixture();
        check(absent.journal.poll(absent.decision).state()==
            DirectorDecisionJournal.State.UNAVAILABLE,
            "no pending batch never becomes authorized");
        // A previously accepted logical reservation must not stay usable if
        // J04 WAL authority disappears between actor callbacks. The arbiter
        // may still own the token, so it cannot become an ordinary refusal
        // that retireNonAccepted can forget.
        var logicalId=new DecisionId(UUID.randomUUID());
        var token=new ReservationToken(new ReservationId(UUID.randomUUID()),
            f.initial.worldId(),new EventInstanceId(UUID.randomUUID()),
            new Epoch(4),new Tick(100),List.of());
        var accepted=new DirectorDecisionAdmissionBridge.Progress(logicalId,
            DirectorDecisionAdmissionBridge.Stage.LOGICAL_RESERVATION_ONLY,
            Optional.of(new AdmissionResult(AdmissionStatus.ACCEPTED,
                Optional.of(token),List.of(),Optional.empty())));
        var quarantined=DirectorDecisionAdmissionBridge.quarantineAfterAuthorityLoss(accepted);
        check(quarantined.stage()==DirectorDecisionAdmissionBridge.Stage.RESERVATION_QUARANTINED,
            "cached accepted J03 reservation must be quarantined on J04 authority loss");
        check(!quarantined.needsJ05Prepare() && quarantined.admission().isEmpty(),
            "quarantined logical acceptance must expose no stale J05 token");
        var deferred=new DirectorDecisionAdmissionBridge.Progress(logicalId,
            DirectorDecisionAdmissionBridge.Stage.ADMISSION_DEFERRED,Optional.empty());
        check(DirectorDecisionAdmissionBridge.quarantineAfterAuthorityLoss(deferred).stage()==
            DirectorDecisionAdmissionBridge.Stage.RESERVATION_QUARANTINED,
            "previously deferred reservation may have been accepted inside unresolved J03 window");
        System.out.println("PASS_J03_J04_BOUND_ACK_FAIL_CLOSED_ASSERTIONS="+assertions);
        System.out.println("PURE_FAKE_STORE_FIXTURE_ONLY_NO_NATIVE_WAL_OR_GAMEPLAY_PASS");
    }
}
