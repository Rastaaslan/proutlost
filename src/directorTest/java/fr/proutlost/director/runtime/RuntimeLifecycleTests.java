package fr.proutlost.director.runtime;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.permission.CommitPermit;
import fr.proutlost.director.api.port.*;
import fr.proutlost.director.testkit.TestDurableStore;
import java.util.*;
import java.util.concurrent.*;

/** TEST_ONLY deterministic ports: validates runtime transitions, never claims disk/world qualification. */
public final class RuntimeLifecycleTests {
    static int assertions;
    static UUID id(long n){return new UUID(0,n);}
    static final DimensionId DIM=new DimensionId("minecraft:overworld");
    static final WorldId WORLD=new WorldId(id(1));
    static final ScopeRef SCOPE=new ScopeRef(ScopeKind.WORLD,id(1));
    static final ReadSet READ=new ReadSet(Map.of(),Map.of(),Map.of());
    static final BudgetVector ZERO=BudgetMath.zero();
    static final Footprint FOOT=new Footprint(DIM,Set.of(new ChunkRef(DIM,0,0)),Set.of(),List.of());
    static final BlobContent BLOB=BlobContent.capture(new ResourceId("proutlost_director:test"),1,new byte[]{1});
    static final EventInstanceId EVENT=new EventInstanceId(id(2));
    static final PlayerId PLAYER=new PlayerId(id(3));
    static void check(boolean b,String why){if(!b)throw new AssertionError(why);assertions++;}
    static void rejects(Runnable r,String why){try{r.run();}catch(IllegalArgumentException|IllegalStateException expected){assertions++;return;}throw new AssertionError(why);}
    static final class Store implements DurableStore {
        final TestDurableStore prepare=new TestDurableStore();
        int sessionReads;
        boolean sessionUnavailable;
        final Map<TransactionId,DurableBatch> batches=new HashMap<>();
        final Map<TransactionId,CompletableFuture<CommitAck>> pending=new HashMap<>();
        public StoreSession session(){sessionReads++;if(sessionUnavailable)throw new IllegalStateException("J04 session unavailable");return prepare.session();}
        public CompletionStage<CommitAck> append(DurableBatch batch){
            if(batch.permitAttempt().isPresent())return prepare.append(batch);
            batches.put(batch.transactionId(),batch);var future=new CompletableFuture<CommitAck>();pending.put(batch.transactionId(),future);return future;
        }
        void ack(TransactionId id){ackSequence(id,new Sequence(2));}
        void ackSequence(TransactionId id,Sequence durableSequence){
            var b=batches.get(id);
            pending.remove(id).complete(new CommitAck(id,session().storeGeneration(),durableSequence,
                b.transactionHash(),AckStatus.DURABLE,Optional.empty(),
                DurableBlobReferences.collect(b.records())));
        }
        void ackWithForeignBlob(TransactionId id) {
            var b=batches.get(id);
            var forced=new HashSet<BlobRef>(DurableBlobReferences.collect(b.records()));
            forced.add(BlobContent.capture(
                new ResourceId("proutlost_director:unrelated_blob"),1,
                new byte[]{99}).reference());
            pending.remove(id).complete(new CommitAck(id,session().storeGeneration(),
                new Sequence(2),b.transactionHash(),AckStatus.DURABLE,
                Optional.empty(),Set.copyOf(forced)));
        }
        void fail(TransactionId id){pending.remove(id).completeExceptionally(new IllegalStateException("TEST_ONLY writer loss"));}
        public StoreReadResult view(StoreQuery q){return prepare.view(q);}
        public CompletionStage<StoreReadResult> read(StoreQuery q){return prepare.read(q);}
        public CompletionStage<BlobReadResult> readBlobs(BlobReadRequest r){return prepare.readBlobs(r);}
    }
    static final class Effects implements WorldEffectPort {
        final CommitPermit.Authority authority; final LiveValidationSnapshot live; int calls; boolean mismatch;
        Effects(CommitPermit.Authority authority,LiveValidationSnapshot live){this.authority=authority;this.live=live;}
        public SliceResult apply(PreparedSlice slice,CommitPermit permit){permit.consume(authority,slice,live);calls++;return new SliceResult(Outcome.SUCCEEDED,mismatch?Set.of():Set.copyOf(slice.operations().stream().map(EffectIntent::operationToken).toList()),Map.of(),0,Set.of(),Set.of(),List.of());}
        public SliceResult stabilize(PreparedSlice s,CommitPermit.StabilizationPermit p,StabilizationLiveSnapshot l){throw new AssertionError("stabilization tested separately");}
        public EffectPreparation prepare(EffectIntent i){throw new AssertionError("planning outside this test");}
        public ReconcileResult reconcile(RecoveryProbe p){throw new AssertionError("recovery adapter not under test");}
        public CleanupResult cleanup(CleanupIntent i,CommitPermit p){throw new AssertionError("cleanup authority not under test");}
    }
    record Fixture(Store store,ResourceArbiter reservations,EventRunner runner,Effects effects,DurableBatch batch,LiveValidationSnapshot live,TransactionId resultId){}
    static Fixture fixture(){
        var store=new Store();var authority=new CommitPermit.Authority(store,4);
        var arbiter=new ResourceArbiter(WORLD,new Epoch(0),10,8,new ResourceArbiter.Compatibility(){
            public boolean share(ResourceClaim a,ResourceClaim b){return false;}public boolean merge(ResourceId id,ResourceClaim a,ResourceClaim b){return false;}});
        var candidate=new ResourceArbiter.Candidate(new ReservationId(id(4)),EVENT,new ResourceArbiter.Rank(false,false,false,Priority.AMBIENT,0,1,1),new Tick(100),List.of(),Map.of(SCOPE,ZERO));
        var reservation=arbiter.arbitrate(List.of(candidate),new Tick(1),Map.of(),Map.of(SCOPE,ZERO)).getFirst().accepted().orElseThrow();
        var live=new LiveValidationSnapshot(READ,FOOT.loadedChunks(),HealthMode.NORMAL,new Tick(1),reservation.generation());
        var effects=new Effects(authority,live);var runner=new EventRunner(authority,store,effects,arbiter,4);
        var op=new OperationToken(id(5));var intent=new EffectIntent(op,new PrimitiveId("proutlost:trace"),1,new Trace(new TagId("proutlost:test"),new TagId("proutlost:seen"),true),FOOT,EVENT);
        var slice=new PreparedSlice(new SliceId(id(6)),op,FOOT,READ,List.of(intent),ZERO);
        var prepare=new PrepareRecord(new TransactionId(id(7)),op,reservation.generation(),READ,new Tick(100),slice,reservation,new ValidationResult(true,List.of(),READ),List.of(),Set.of(),List.of(),new EffectBlobs(Set.of(),BLOB.reference(),BLOB.reference(),Map.of(),Map.of()));
        var batch=new DurableBatch(prepare.transactionId(),store.session().storeGeneration(),Map.of(),List.of(new DurablePrepare(prepare)),List.of(BLOB),Optional.empty(),CommitPermit.Authority.prepareHash(prepare));
        var resultId=new TransactionId(id(8));
        runner.submit(batch,Map.of(),live,r->ResultTransaction.batch(resultId,store.session().storeGeneration(),Map.of(),List.of(new DurableResult(new ResultRecord(batch.transactionId(),op,r,Set.of(),List.of(),List.of()))),List.of()));
        return new Fixture(store,arbiter,runner,effects,batch,live,resultId);
    }
    static void sagas(){
        var f=fixture();check(f.runner.poll(f.batch.transactionId(),f.live).stage()==EventRunner.Stage.PREPARING && f.effects.calls==0,"no world effect before durable prepare");
        f.store.prepare.force(f.batch.transactionId());check(f.effects.calls==0,"ACK callback has no world permission");
        check(f.runner.poll(f.batch.transactionId(),f.live).stage()==EventRunner.Stage.RESULT_PENDING && f.effects.calls==1,"one actor slice after ACK");
        check(f.runner.poll(f.batch.transactionId(),f.live).stage()==EventRunner.Stage.RESULT_PENDING && f.effects.calls==1,"no repeat while result pending");
        f.store.ack(f.resultId);check(f.runner.poll(f.batch.transactionId(),f.live).stage()==EventRunner.Stage.COMMITTED,"commit only after result ACK");
        check(f.reservations.snapshot().size()==1,"slice commit does not prematurely release full event");
        f.runner.forget(f.batch.transactionId());
        var failed=fixture();failed.store.prepare.force(failed.batch.transactionId());failed.runner.poll(failed.batch.transactionId(),failed.live);failed.store.fail(failed.resultId);
        check(failed.runner.poll(failed.batch.transactionId(),failed.live).stage()==EventRunner.Stage.RECOVERY_REQUIRED && failed.effects.calls==1,"lost result writer retains recovery");
        rejects(()->failed.runner.forget(failed.batch.transactionId()),"cannot forget uncertain physical write");
        var mismatch=fixture();mismatch.effects.mismatch=true;mismatch.store.prepare.force(mismatch.batch.transactionId());
        check(mismatch.runner.poll(mismatch.batch.transactionId(),mismatch.live).stage()==EventRunner.Stage.RECOVERY_REQUIRED,"incorrect applied operations fail verification");
        check(mismatch.store.batches.isEmpty(),"no success result for incorrect effect");
        var expired=fixture();expired.store.prepare.force(expired.batch.transactionId());
        var late=new LiveValidationSnapshot(READ,FOOT.loadedChunks(),HealthMode.NORMAL,new Tick(100),expired.live.reservationGeneration());
        check(expired.runner.poll(expired.batch.transactionId(),late).stage()==EventRunner.Stage.REJECTED && expired.effects.calls==0,"late ACK cannot resurrect expired lease");
    }
    static EventInstance event(){return new EventInstance(new AggregateHeader(1,EVENT.value(),new Revision(1),new Sequence(1),new Tick(1)),EVENT,new DefinitionRef(new DefinitionId("proutlost:test_event"),new Revision(1),BLOB.reference().hash()),new Epoch(1),EventState.RESOLVING,"result",new RoleAssignments(Map.of("witness",List.of(PLAYER))),Set.of(),BLOB.reference(),Map.of(),Set.of(),Set.of(),Set.of(),Optional.empty(),Set.of(),Optional.of(new Tick(1)),new DurationTicks(1),new DurationTicks(100),RecoveryMode.RECOVER,Optional.of(Outcome.SUCCEEDED));}
    static DirectorFact fact(long n){return new DirectorFact(new FactId(id(n)),new FactType("proutlost:confirmed"),List.of(SCOPE),Optional.of(EVENT),EvidenceStatus.CONFIRMED,Map.of(),true,Optional.empty());}
    static void results(){
        var fact=fact(20);var result=new SliceResult(Outcome.SUCCEEDED,Set.of(new OperationToken(id(5))),Map.of(),0,Set.of(),Set.of(),List.of(new FactEvidence(fact.factId(),EvidenceStatus.CONFIRMED,Map.of())));
        var envelope=new DirectorEnvelope(1,new MessageId(id(22)),WORLD,new ResourceId("proutlost_director:runtime"),"event-fact-20",SCOPE,new CorrelationId(EVENT.value()),Optional.empty(),new Sequence(1),Optional.empty(),new Tick(3),PayloadType.DirectorFact,1,new FactPayload(fact));
        var record=new ResultRecord(new TransactionId(id(7)),new OperationToken(id(5)),result,Set.of(),List.of(fact),List.of(envelope));
        var person=new ResultTransaction.Participant(PLAYER,"witness",PerceptionStatus.RECEIVED,Optional.empty(),Optional.empty(),Set.of(fact.factId()),Set.of());
        var batch=ResultTransaction.confirmed(WORLD,new Epoch(7),new TransactionId(id(21)),event(),record,List.of(person),List.of(),List.of(),Map.of(),new Tick(3));
        var memory=batch.records().stream().filter(r->r instanceof DurableAggregate a && a.state() instanceof PlayerSignificantAggregate).map(r->((PlayerSignificantAggregate)((DurableAggregate)r).state()).value()).findFirst().orElseThrow();
        check(memory.eventId().equals(EVENT) && memory.eventDefinitionId().equals(event().definition().id()),"real event identity");
        check(memory.observableReactionCodes().isEmpty() && memory.perceptionStatus()==PerceptionStatus.RECEIVED,"ACK does not invent observed reaction");
        check(batch.records().stream().anyMatch(r->r instanceof DurableResult) && batch.records().stream().anyMatch(r->r instanceof DurableFact),"memory result facts in same batch");
        var retry=ResultTransaction.confirmed(WORLD,new Epoch(7),new TransactionId(id(21)),event(),record,List.of(person),List.of(),List.of(),Map.of(),new Tick(3));
        check(batch.equals(retry),"stable complete retry and dedup identity");
        var noEvidence=new ResultRecord(record.prepareTransactionId(),record.operationToken(),new SliceResult(Outcome.SUCCEEDED,Set.of(),Map.of(),0,Set.of(),Set.of(),List.of()),Set.of(),List.of(fact),List.of());
        rejects(()->ResultTransaction.confirmed(WORLD,new Epoch(7),new TransactionId(id(21)),event(),noEvidence,List.of(person),List.of(),List.of(),Map.of(),new Tick(3)),"technical ACK alone cannot create significant memory");
        rejects(()->ResultTransaction.confirmed(WORLD,new Epoch(7),new TransactionId(id(21)),event(),record,List.of(person,person),List.of(),List.of(),Map.of(),new Tick(3)),"duplicate role rejected");
    }
    static void chains(){
        var transition=new ChainTransition(new ResourceId("proutlost:continue"),"ACTIVE","ACTIVE",new FactType("proutlost:confirmed"),Optional.of(new DefinitionId("proutlost:next")),new PlayersPresent(1),true,Optional.empty());
        var definition=new ChainDefinition(1,new DefinitionId("proutlost:chain"),new Revision(1),Calibration.DEFAULT_FOR_TESTING,ChainMode.DYNAMIC,ScopeKind.WORLD,"ACTIVE",Set.of("ACTIVE","END"),2,new DurationTicks(2),new UnitValue(.2),List.of(transition),Set.of("END"),RecoveryMode.RECOVER);
        var hash=BlobContent.hash(new ModelCodec<>(ChainDefinition.class).encode(definition));
        var initial=new ChainInstance(new AggregateHeader(1,id(30),new Revision(1),new Sequence(0),new Tick(0)),new ChainId(id(30)),new DefinitionRef(definition.id(),definition.contentRevision(),hash),ChainMode.DYNAMIC,ChainState.ACTIVE,Set.of("ACTIVE"),Set.of(),Set.of(),List.of(),List.of(),Optional.empty(),new UnitValue(1),new Tick(0));
        var store=new Store();var runner=new ChainRunner(store,definition,initial,SCOPE);
        for(int i=0;i<40;i++) {
            var fact=fact(100+i);var transaction=new TransactionId(id(200+i));var tick=new Tick(i*2);
            var proposal=runner.propose(fact,Map.of(transition.id(),1.0),123,tick,transaction).orElseThrow();
            check(proposal.nextEvent().isEmpty() && runner.pollCommitted().isEmpty(),"branch not emitted before ACK");
            store.ack(transaction);var committed=runner.pollCommitted().orElseThrow();
            check(committed.nextEvent().equals(transition.eventId()) && committed.state().consumedFactIds().contains(fact.factId()),"durable branch emits once");
            check(committed.state().plannedHorizon().size()<=2,"bounded horizon without total-length limit");
            check(runner.propose(fact,Map.of(transition.id(),1.0),123,tick.plus(2),new TransactionId(id(400+i))).isEmpty(),"consumed fact not replayed");
        }
        check(runner.snapshot().branchHistory().size()==40,"dynamic chain exceeds planning horizon in total length");
        var foreign=new DirectorFact(new FactId(id(900)),transition.onFact(),List.of(new ScopeRef(ScopeKind.WORLD,id(999))),Optional.of(EVENT),EvidenceStatus.CONFIRMED,Map.of(),true,Optional.empty());
        check(runner.propose(foreign,Map.of(transition.id(),1.0),1,new Tick(100),new TransactionId(id(901))).isEmpty(),"scope cannot silently move");
    }
    static void disasters(){
        var warning=new WarningEvidence(Set.of(PLAYER),new Tick(10),new DurationTicks(5),BLOB.reference().hash(),new Epoch(1),true,true);
        var before=new DisasterRunner.Conditions(new Tick(14),HealthMode.NORMAL,Set.of(PLAYER),new Epoch(1),true,false,false);
        var ready=new DisasterRunner.Conditions(new Tick(15),HealthMode.NORMAL,Set.of(PLAYER),new Epoch(1),true,false,false);
        check(DisasterRunner.next(DisasterStage.WARN,new UnitValue(1),new UnitValue(.5),warning,before)==DisasterStage.WARN,"full warning lead time");
        check(DisasterRunner.next(DisasterStage.WARN,new UnitValue(1),new UnitValue(.5),warning,ready)==DisasterStage.IMPACT,"pure stage requires warning evidence");
        check(!DisasterRunner.warningValidSince(warning,ready,new Tick(11)),"WATCH-era deployed warning cannot authorize new WARN impact");
        check(!DisasterRunner.warningValidSince(warning,ready,new Tick(10)),"same-tick pre-WARN warning cannot be reused");
        var fresh=new WarningEvidence(Set.of(PLAYER),new Tick(11),new DurationTicks(5),BLOB.reference().hash(),new Epoch(1),true,true);
        var mature=new DisasterRunner.Conditions(new Tick(16),HealthMode.NORMAL,Set.of(PLAYER),new Epoch(1),true,false,false);
        check(DisasterRunner.warningValidSince(fresh,mature,new Tick(10)),"post-WARN deployed and mature evidence accepted");
        check(!DisasterRunner.warningValidSince(fresh,ready,new Tick(10)),"post-WARN lead time cannot be shortened");
        var late=new DisasterRunner.Conditions(new Tick(20),HealthMode.NORMAL,Set.of(PLAYER,new PlayerId(id(99))),new Epoch(2),true,false,false);
        check(DisasterRunner.next(DisasterStage.IMPACT,new UnitValue(1),new UnitValue(.5),warning,late)==DisasterStage.WARN,"late audience rewarned");
        for(var health:List.of(HealthMode.DEGRADED,HealthMode.CRITICAL,HealthMode.READ_ONLY_SAFE)) {
            var live=new DisasterRunner.Conditions(new Tick(20),health,Set.of(PLAYER),new Epoch(1),true,false,false);
            check(DisasterRunner.next(DisasterStage.IMPACT,new UnitValue(1),new UnitValue(.5),warning,live)==DisasterStage.STABILIZE,"health stabilizes "+health);
            check(DisasterRunner.next(DisasterStage.STABILIZE,new UnitValue(1),new UnitValue(.5),warning,live)==DisasterStage.STABILIZE,"unverified stop never resolved");
        }
        check(DisasterRunner.recover(DisasterStage.IMPACT)==DisasterStage.STABILIZE,"reboot stabilizes before new impact");
    }
    static void entities(){
        var cohort=new CohortId(id(500));var uuid=EntityCohorts.stableEntityId(WORLD,EVENT,cohort,"0");
        check(uuid.equals(EntityCohorts.stableEntityId(WORLD,EVENT,cohort,"0")) && !uuid.equals(EntityCohorts.stableEntityId(WORLD,EVENT,cohort,"1")),"stable distinct slot UUIDs");
        var slot=new EntityOwnershipRecord(new AggregateHeader(1,id(501),new Revision(1),new Sequence(1),new Tick(1)),new OwnerToken(id(502)),uuid,EVENT,cohort,"0",EntityPhase.PRESENT,OwnershipDisposition.DIRECTOR_ACTIVE,new ChunkRef(DIM,0,0),new Tick(1),ArrivalPolicy.SETTLE_WORLD,CleanupReason.COMPLETED,Optional.empty(),new Epoch(1));
        var unloaded=EntityCohorts.unloaded(slot,new Tick(2));
        check(EntityCohorts.persistentSlots(List.of(unloaded))==1 && !EntityCohorts.cleanupEligible(unloaded,slot.ownerToken(),slot.generation()),"unloaded slot budget retained without deletion");
        var adopted=EntityCohorts.adopted(slot,PLAYER,new Tick(2));
        check(!EntityCohorts.cleanupEligible(adopted,adopted.ownerToken(),adopted.generation()),"adopted entity never cleanup eligible");
        check(EntityCohorts.loaded(EntityCohorts.unloaded(adopted,new Tick(3)),uuid,adopted.ownerToken(),adopted.generation(),slot.lastKnownChunk(),new Tick(4)).phase()==EntityPhase.ADOPTED,"adoption survives unload");
        var settled=EntityCohorts.settled(slot,new Tick(2));check(!EntityCohorts.cleanupEligible(settled,settled.ownerToken(),settled.generation()),"world settled survives event cleanup");
        rejects(()->EntityCohorts.loaded(unloaded,id(999),slot.ownerToken(),slot.generation(),slot.lastKnownChunk(),new Tick(3)),"duplicate or alien UUID rejected");
    }
    static void durableDisasters(){
        var store=new Store();var runner=new DurableDisasterRunner(store,2,d->new ScopeRef(ScopeKind.EVENT,d.header().id()));
        var warning=new WarningEvidence(Set.of(PLAYER),new Tick(1),new DurationTicks(5),BLOB.reference().hash(),new Epoch(1),true,true);
        var disaster=new DisasterInstance(new AggregateHeader(1,id(700),new Revision(1),new Sequence(1),new Tick(1)),new DisasterId(id(700)),EVENT,new DefinitionId("proutlost:test_disaster"),DisasterStage.WATCH,Map.of(),new UnitValue(.9),FOOT,warning,Set.of(PLAYER),List.of(),BLOB.reference(),Set.of(),"idle",Set.of());
        runner.restore(disaster);
        var conditions=new DisasterRunner.Conditions(new Tick(10),HealthMode.NORMAL,Set.of(PLAYER),new Epoch(1),true,false,false);
        var tx=new TransactionId(id(701));runner.propose(disaster.disasterId(),tx,new UnitValue(.5),conditions);
        var stagedDisaster=store.batches.get(tx);
        check(stagedDisaster.blobs().isEmpty() &&
            DurableBlobReferences.collect(stagedDisaster.records()).contains(BLOB.reference()),
            "existing warning reference is J04 WAL-attested even without newly staged blob bytes");
        check(store.sessionReads==1,"one exclusive J04 session captured for disaster append and ACK fence");
        check(runner.poll(disaster.disasterId()).status()==DurableDisasterRunner.Status.PENDING,"phase waits for durable ACK");
        check(runner.view(disaster.disasterId()).stage()==DisasterStage.WATCH,"pending warning not published");
        rejects(()->runner.propose(disaster.disasterId(),tx,new UnitValue(.5),conditions),"duplicate phase pending");
        store.ack(tx);check(runner.view(disaster.disasterId()).stage()==DisasterStage.WATCH,"callback only delivers data");
        check(runner.poll(disaster.disasterId()).state().stage()==DisasterStage.WARN,"actor publishes durable WARN");
        var staleWindow=new DisasterRunner.Conditions(new Tick(15),HealthMode.NORMAL,Set.of(PLAYER),new Epoch(1),true,false,false);
        var impact=new TransactionId(id(702));
        rejects(()->runner.propose(disaster.disasterId(),impact,new UnitValue(.5),staleWindow),"stale WATCH-era warning refused before WAL append");
        check(!store.batches.containsKey(impact)&&runner.view(disaster.disasterId()).stage()==DisasterStage.WARN,"stale warning never journals IMPACT");
        var emergency=new DisasterRunner.Conditions(new Tick(15),HealthMode.DEGRADED,Set.of(PLAYER),new Epoch(1),false,false,false);
        runner.propose(disaster.disasterId(),impact,new UnitValue(.5),emergency);store.fail(impact);
        check(runner.poll(disaster.disasterId()).status()==DurableDisasterRunner.Status.RECOVERY_REQUIRED,"writer loss fences emergency stabilization");
        check(runner.view(disaster.disasterId()).stage()==DisasterStage.WARN,"failed ACK never publishes stabilization");
        rejects(()->runner.propose(disaster.disasterId(),new TransactionId(id(703)),new UnitValue(.5),emergency),"ambiguous durable state cannot retry");
        rejects(()->runner.retire(disaster.disasterId()),"retain uncertain disaster for recovery");
        assertions+=DisasterWarningPublicationTests.run();

        var staleSequenceStore=new Store();
        var staleSequenceRunner=new DurableDisasterRunner(staleSequenceStore,2,
            d->new ScopeRef(ScopeKind.EVENT,d.header().id()));
        staleSequenceRunner.restore(disaster);
        var staleSequenceTx=new TransactionId(id(707));
        staleSequenceRunner.propose(disaster.disasterId(),staleSequenceTx,new UnitValue(.5),conditions);
        staleSequenceStore.ackSequence(staleSequenceTx,disaster.header().lastSequence());
        check(staleSequenceRunner.poll(disaster.disasterId()).status()==DurableDisasterRunner.Status.RECOVERY_REQUIRED,
            "stale J04 ACK sequence cannot advance disaster");
        check(staleSequenceRunner.view(disaster.disasterId()).stage()==DisasterStage.WATCH,
            "stale J04 ACK must not publish WARN");
        rejects(()->staleSequenceRunner.propose(disaster.disasterId(),new TransactionId(id(708)),
            new UnitValue(.5),conditions),"stale ACK requires replay, not new mutation");

        // A callback with extra, unjournaled blob attestations must be
        // quarantined even when the transaction hash and sequence match.
        // Disaster phase batches normally contain ZERO blobs: containsAll(empty)
        // is vacuously true and cannot establish exact WAL attestation.
        var foreignStore=new Store();
        var foreignRunner=new DurableDisasterRunner(foreignStore,2,
            d->new ScopeRef(ScopeKind.EVENT,d.header().id()));
        foreignRunner.restore(disaster);
        var foreignTx=new TransactionId(id(711));
        foreignRunner.propose(disaster.disasterId(),foreignTx,new UnitValue(.5),conditions);
        foreignStore.ackWithForeignBlob(foreignTx);
        check(foreignRunner.poll(disaster.disasterId()).status()==
            DurableDisasterRunner.Status.RECOVERY_REQUIRED,
            "foreign blob in durable disaster ACK must require replay");
        check(foreignRunner.view(disaster.disasterId()).stage()==DisasterStage.WATCH,
            "unauthorized blob attestation must not publish WARN");

        // A later phase must never accept the same durable sequence twice.
        // The serialized AggregateHeader is constructed before the writer
        // assigns its ACK sequence, so comparing against it alone is unsafe.
        var repeatedStore=new Store();
        var repeatedRunner=new DurableDisasterRunner(repeatedStore,2,
            d->new ScopeRef(ScopeKind.EVENT,d.header().id()));
        repeatedRunner.restore(disaster);
        var firstTx=new TransactionId(id(712));
        repeatedRunner.propose(disaster.disasterId(),firstTx,new UnitValue(.5),conditions);
        repeatedStore.ackSequence(firstTx,new Sequence(2));
        check(repeatedRunner.poll(disaster.disasterId()).state().stage()==DisasterStage.WARN,
            "initial durable warning sequence accepted");
        var repeatedTx=new TransactionId(id(713));
        repeatedRunner.propose(disaster.disasterId(),repeatedTx,new UnitValue(.5),emergency);
        repeatedStore.ackSequence(repeatedTx,new Sequence(2));
        check(repeatedRunner.poll(disaster.disasterId()).status()==
            DurableDisasterRunner.Status.RECOVERY_REQUIRED,
            "reused durable sequence must not advance disaster");
        check(repeatedRunner.view(disaster.disasterId()).stage()==DisasterStage.WARN,
            "same WAL sequence must preserve last confirmed disaster phase");

        var lostSessionStore=new Store();
        var lostSessionRunner=new DurableDisasterRunner(lostSessionStore,2,
            d->new ScopeRef(ScopeKind.EVENT,d.header().id()));
        lostSessionRunner.restore(disaster);
        var lostSessionTx=new TransactionId(id(709));
        lostSessionRunner.propose(disaster.disasterId(),lostSessionTx,new UnitValue(.5),conditions);
        lostSessionStore.ack(lostSessionTx);
        lostSessionStore.sessionUnavailable=true;
        check(lostSessionRunner.poll(disaster.disasterId()).status()==DurableDisasterRunner.Status.RECOVERY_REQUIRED,
            "unavailable J04 session must fail closed after ACK callback");
        check(lostSessionRunner.view(disaster.disasterId()).stage()==DisasterStage.WATCH,
            "J04 session loss must retain pre-transition state");
        rejects(()->lostSessionRunner.propose(disaster.disasterId(),new TransactionId(id(710)),
            new UnitValue(.5),conditions),"unavailable J04 session fences retries");
    }
    static void mutationAssembly(){
        var f=fixture();var coordinator=new MutationCoordinator(f.runner);
        var original=((DurablePrepare)f.batch.records().getFirst()).value();var intent=original.slice().operations().getFirst();
        var context=new ObservationContext(Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(),Map.of());
        var snapshot=new SnapshotBatch(new RequestId(id(800)),new ScopeSet(WORLD,Set.of(SCOPE)),new Tick(1),Map.of(),context,Set.of(),FOOT.loadedChunks(),HealthMode.NORMAL);
        var plan=new PreparedEffect(intent,snapshot,List.of(),READ,Map.of(),ZERO,List.of(),Set.of(),original.durableBlobs());
        var capture=new EffectPreparation(intent,new ValidationResult(true,List.of(),READ),Optional.of(plan),new BlobBundle(List.of(BLOB)));
        var batch=coordinator.assemble(new TransactionId(id(801)),new SliceId(id(802)),new OperationToken(id(803)),original.reservation(),f.store.session().storeGeneration(),new Tick(99),List.of(capture),Optional.empty(),Set.of(),List.of());
        var prepared=((DurablePrepare)batch.records().getFirst()).value();
        check(batch.transactionHash().equals(CommitPermit.Authority.prepareHash(prepared)),"assembled hash binds exact durable PREPARE");
        check(batch.blobs().stream().map(BlobContent::reference).toList().containsAll(CommitPermit.Authority.references(prepared.durableBlobs())),"all captured bytes forced together");
        rejects(()->coordinator.assemble(new TransactionId(id(804)),new SliceId(id(805)),new OperationToken(id(806)),original.reservation(),f.store.session().storeGeneration(),new Tick(99),List.of(capture,capture),Optional.empty(),Set.of(),List.of()),"duplicate captured op rejected");
        var missing=new EffectPreparation(intent,capture.validation(),capture.prepared(),new BlobBundle(List.of()));
        rejects(()->coordinator.assemble(new TransactionId(id(807)),new SliceId(id(808)),new OperationToken(id(809)),original.reservation(),f.store.session().storeGeneration(),new Tick(99),List.of(missing),Optional.empty(),Set.of(),List.of()),"missing captured bytes rejected");
    }
    public static void main(String[] args){sagas();mutationAssembly();results();chains();disasters();durableDisasters();entities();System.out.println("PASS J05 lifecycle assertions="+assertions);System.out.println("Pure TEST_ONLY port tests; no real persistence, entity navigation, Minecraft, or writer-loss stop qualification.");}
}
