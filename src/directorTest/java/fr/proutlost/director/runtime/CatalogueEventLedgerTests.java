package fr.proutlost.director.runtime;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.engine.CandidateGenerator;
import fr.proutlost.director.engine.EligibilityEngine;
import fr.proutlost.director.api.permission.CommitPermit;
import fr.proutlost.director.api.permission.PermitResult;
import fr.proutlost.director.api.port.DurableStore;
import fr.proutlost.director.persistence.JournalStore;
import fr.proutlost.director.persistence.StoreOptions;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static fr.proutlost.director.runtime.CatalogueEventLedgerFixtures.*;

/** Pure original-catalog ledger tests + REAL private JournalStore/fsync/close/open.
 * SliceResult/completion DTOs are explicit unit fixtures: no Minecraft/client/human proof. */
public final class CatalogueEventLedgerTests {
    private CatalogueEventLedgerTests(){}
    private static int assertions;
    // One genuine Authority per private store/session. The adapter below only
    // observes the REAL JournalStore ACK; it never creates/replaces an ACK.
    private record Preparing(CommitPermit.Authority authority,Map<TransactionId,CompletionStage<CommitAck>> actualAcks) {}
    private static final Map<JournalStore,Preparing> AUTHORITIES=new IdentityHashMap<>();
    private static Preparing preparing(JournalStore store){
        var actualAcks=new HashMap<TransactionId,CompletionStage<CommitAck>>();
        var observed=new DurableStore(){
            public CompletionStage<CommitAck> append(DurableBatch batch){
                if(actualAcks.containsKey(batch.transactionId()))throw new IllegalStateException("duplicate private PREPARE append");
                var actual=store.append(batch);actualAcks.put(batch.transactionId(),actual);return actual;
            }
            public StoreSession session(){return store.session();}
            public StoreReadResult view(StoreQuery query){return store.view(query);}
            public CompletionStage<StoreReadResult> read(StoreQuery query){return store.read(query);}
            public CompletionStage<BlobReadResult> readBlobs(BlobReadRequest request){return store.readBlobs(request);}
        };
        return new Preparing(new CommitPermit.Authority(observed,4),actualAcks);
    }
    private static final StoreOptions OPTIONS=new StoreOptions(1048620,2097152,4194304,16777216,32,0,128);
    private static void check(boolean ok,String why){assertions++;if(!ok)throw new AssertionError(why);}
    private static void rejects(Runnable task,String why){
        try{task.run();}catch(IllegalStateException|IllegalArgumentException expected){assertions++;return;}
        throw new AssertionError(why);
    }
    private static <T> T await(CompletionStage<T> stage)throws Exception{return stage.toCompletableFuture().get(20,TimeUnit.SECONDS);}
    private static JournalStore fresh(Path path)throws Exception{
        if(Files.exists(path))throw new IllegalArgumentException("retain old evidence; choose a new pure-test directory: "+path);
        var store=await(JournalStore.open(path,WORLD,OPTIONS));check(!store.readOnlySafe(),"real J04 starts writable");return store;
    }
    private static JournalStore reopen(JournalStore store,Path path,StoreSession before)throws Exception{
        AUTHORITIES.remove(store);await(store.closeAsync());var next=await(JournalStore.open(path,WORLD,OPTIONS));
        check(!next.readOnlySafe()&&next.session().worldId().equals(before.worldId())&&next.session().storeGeneration().equals(before.storeGeneration())
            &&next.session().bootEpoch().compareTo(before.bootEpoch())>0&&!next.session().authoritySession().equals(before.authoritySession()),
            "same private WAL must reopen with real new boot/authority session");return next;
    }
    private static CommitAck commit(JournalStore store,List<DurableRecord> rows,List<BlobContent> blobs)throws Exception{
        var batch=ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),rows,blobs);
        var ack=await(store.append(batch));check(ack.ackStatus()==AckStatus.DURABLE&&ack.transactionId().equals(batch.transactionId())
            &&ack.transactionHash().equals(batch.transactionHash())&&ack.durableSequence().value()>0&&ack.forcedBlobs().equals(DurableBlobReferences.collect(rows)),
            "actual fsynced transaction/positive ACK/ref set required");return ack;
    }
    private static CommitAck prepare(JournalStore store,Prepared fixture,Tick now)throws Exception{
        var p=fixture.prepare();var batch=new DurableBatch(p.transactionId(),store.session().storeGeneration(),p.readSet().revisions(),
            List.of(new DurablePrepare(p)),fixture.blobs(),Optional.empty(),CommitPermit.Authority.prepareHash(p));
        var writer=AUTHORITIES.computeIfAbsent(store,CatalogueEventLedgerTests::preparing);
        var live=new LiveValidationSnapshot(p.readSet(),p.slice().footprint().loadedChunks(),HealthMode.NORMAL,now,p.reservationGeneration());
        writer.authority().submit(batch,live); // Authority assigns the real session-bound PermitAttempt.
        var actual=writer.actualAcks().remove(p.transactionId());
        check(actual!=null,"actual Authority PREPARE append must reach the real store");
        var ack=await(actual);
        var permission=writer.authority().poll(p.transactionId(),live);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(permission.status()==PermitResult.Status.PENDING&&System.nanoTime()<deadline){
            // Pure Java test process only; never wait on a Minecraft actor.
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
            permission=writer.authority().poll(p.transactionId(),live);
        }
        check(ack.ackStatus()==AckStatus.DURABLE&&ack.transactionId().equals(p.transactionId())
            &&ack.transactionHash().equals(batch.transactionHash())&&ack.storeGeneration().equals(store.session().storeGeneration())
            &&ack.permitAttempt().isPresent()&&ack.durableSequence().value()>0
            &&ack.forcedBlobs().equals(DurableBlobReferences.collect(p)),
            "actual forced PREPARE ACK: ack="+ack+" permission="+permission.status()+" reason="+permission.reason()+" store="+store.diagnostic());
        check(permission.status()==PermitResult.Status.GRANTED&&permission.permit().orElseThrow().prepareSequence().equals(ack.durableSequence()),
            "real Authority must grant only the exact forced PREPARE: status="+permission.status()+" reason="+permission.reason());
        return ack;
    }
    private static CommitAck result(JournalStore store,Prepared fixture,CatalogueEventLedger.Contribution contribution)throws Exception{
        return result(store,fixture,fixture.success(),contribution);
    }
    private static CommitAck result(JournalStore store,Prepared fixture,SliceResult actual,CatalogueEventLedger.Contribution contribution)throws Exception{
        var p=fixture.prepare();var rows=new ArrayList<DurableRecord>();
        rows.add(new DurableResult(new ResultRecord(p.transactionId(),p.operationToken(),actual,Set.of(),List.of(),List.of())));
        rows.addAll(contribution.records());return commit(store,rows,contribution.blobs());
    }
    private static SliceResult abortedSafe(){return new SliceResult(Outcome.ABORTED_SAFE,Set.of(),Map.of(),0,Set.of(),Set.of(),List.of());}
    /** Separate later operation with the SAME event/reservation token but NO unique rows.
     * Pure branch fixture; no physical application or extra original catalog step is claimed. */
    private static Prepared laterOperation(Prepared earlier,Tick now){
        var p=earlier.prepare();var old=p.slice().operations().getFirst();var operation=new OperationToken(UUID.randomUUID());
        var intent=new EffectIntent(operation,old.primitive(),old.version(),old.typedArgs(),old.expectedFootprint(),old.instanceId());
        var slice=new PreparedSlice(new SliceId(UUID.randomUUID()),operation,p.slice().footprint(),p.readSet(),List.of(intent),p.slice().maximumWork());
        var next=new PrepareRecord(new TransactionId(UUID.randomUUID()),operation,p.reservationGeneration(),p.readSet(),p.expiryTick(),slice,p.reservation(),
            p.hardValidation(),List.of(),p.noninvertibleEffects(),List.of(),p.durableBlobs());
        return new Prepared(next,earlier.blobs(),new SliceResult(Outcome.SUCCEEDED,Set.of(operation),earlier.success().observedHashes(),0,Set.of(),Set.of(),List.of()));
    }
    private record State(List<UniqueUseRecord> uniqueness,List<CooldownRecord> cooldowns,List<DirectorFact> facts,
            Map<AggregateKey,RestoredAggregate> aggregates,Sequence cut){
        DecisionInput input(Loaded loaded,RegionId region,List<PlayerId> players,Tick now,long revision){
            return CatalogueEventLedgerFixtures.input(loaded,region,players,now,revision,uniqueness,cooldowns,facts);
        }
    }
    private static State state(JournalStore store)throws Exception{
        var session=store.session();var q=new StoreQuery(WORLD,Set.of(),new Sequence(0),Optional.empty(),128);
        var response=await(store.read(q));ReadContracts.validate(response,q,session);
        check(response.status()==ReadStatus.AVAILABLE&&!response.page().orElseThrow().hasMore(),"bounded real private chronology read");
        var page=response.page().orElseThrow();var keys=new HashSet<AggregateKey>();var facts=new ArrayList<DirectorFact>();
        for(var row:page.records()){
            if(row instanceof DurableUnique u)keys.add(new UniqueAggregate(u.value()).key());
            if(row instanceof DurablePrepare p)for(var u:p.value().uniqueness())keys.add(new UniqueAggregate(u).key());
            if(row instanceof DurableAggregate a&&a.state() instanceof CooldownAggregate)keys.add(a.state().key());
            if(row instanceof DurableFact f)facts.add(f.value());
        }
        var uniques=new ArrayList<UniqueUseRecord>();var cooldowns=new ArrayList<CooldownRecord>();var projected=new HashMap<AggregateKey,RestoredAggregate>();
        if(!keys.isEmpty()){
            q=new StoreQuery(WORLD,keys,new Sequence(0),Optional.empty(),128);response=await(store.read(q));ReadContracts.validate(response,q,session);
            check(response.status()==ReadStatus.AVAILABLE&&!response.page().orElseThrow().hasMore()
                &&response.page().orElseThrow().lastDurableSequence().equals(page.lastDurableSequence()),"one-cut explicit ledger projection");
            check(response.page().orElseThrow().aggregates().size()==keys.size(),"all actual ledger aggregate keys restored");
            for(var row:response.page().orElseThrow().aggregates()){
                check(row.committedSequence().value()>0&&row.committedSequence().compareTo(page.lastDurableSequence())<=0,"actual aggregate COMMIT sequence bound");
                projected.put(row.state().key(),row);
                if(row.state() instanceof UniqueAggregate u)uniques.add(u.value());
                if(row.state() instanceof CooldownAggregate c)cooldowns.add(c.value());
            }
        }
        return new State(List.copyOf(uniques),List.copyOf(cooldowns),List.copyOf(facts),Map.copyOf(projected),page.lastDurableSequence());
    }
    private static UniqueUseRecord onlyUnique(State state,DefinitionId definition){
        var rows=state.uniqueness().stream().filter(u->u.definitionId().equals(definition)).toList();
        check(rows.size()==1,"one actual original definition scope reservation");return rows.getFirst();
    }
    private static void atCommit(State state,Collection<DurableRecord> rows,CommitAck ack){
        for(var row:rows){
            AggregateKey key=null;
            if(row instanceof DurableUnique u)key=new UniqueAggregate(u.value()).key();
            if(row instanceof DurableAggregate a)key=a.state().key();
            if(key!=null)check(state.aggregates().get(key)!=null&&state.aggregates().get(key).committedSequence().equals(ack.durableSequence()),
                "ledger state and actual provider RESULT share the same positive COMMIT cut");
        }
    }
    private static void typedCooldowns(State state,EventDefinition definition,ExecutionPlan plan,Tick firstEffect){
        var players=new HashSet<PlayerId>();plan.roles().roles().values().forEach(players::addAll);
        var expected=new HashMap<ScopeRef,DurationTicks>();
        var timing=definition.timing().cooldowns();
        if(timing.worldTicks().value()>0)expected.put(new ScopeRef(ScopeKind.WORLD,WORLD.value()),timing.worldTicks());
        if(timing.regionTicks().value()>0)expected.put(new ScopeRef(ScopeKind.REGION,REGION.value()),timing.regionTicks());
        if(timing.playerTicks().value()>0)for(var player:players)expected.put(new ScopeRef(ScopeKind.PLAYER,player.value()),timing.playerTicks());
        check(state.cooldowns().size()==expected.size(),"separate typed WORLD/REGION/PLAYER cooldowns");
        for(var c:state.cooldowns()){
            var binding=c.binding().orElseThrow();check(binding.selector().equals(new EventCooldownSelector(definition.id()))
                &&binding.affectedScopes().worldId().equals(WORLD)&&binding.affectedScopes().scopes().size()==1
                &&binding.releaseMode()==CooldownReleaseMode.AFTER_TICK&&binding.releasePredicate().isEmpty(),"actual typed event/single-scope cooldown binding");
            var scope=binding.affectedScopes().scopes().iterator().next();var duration=expected.remove(scope);
            check(duration!=null&&c.notBeforeTick().equals(firstEffect.plus(duration.value()))&&c.sourceInstanceId().equals(plan.effects().getFirst().instanceId()),
                "original configured cooldown duration starts at first actual result");
            if(scope.kind()==ScopeKind.REGION)check(binding.lineageComplete()&&binding.inheritedRegions().equals(Set.of(REGION)),"actual complete selected-region lineage");
            else check(binding.inheritedRegions().isEmpty(),"no unrelated region attached to WORLD/PLAYER cooldown");
        }
        check(expected.isEmpty(),"all configured typed scopes represented");
    }
    private static void oncePerPlayer(Path base,Loaded loaded)throws Exception{
        var path=base.resolve("parallel-steps-once-per-player");JournalStore store=fresh(path);
        try{
            var definition=loaded.event("parallel_steps");check(definition.uniqueness().scope()==UniquenessScope.ONCE_PER_PLAYER,"original parallel_steps uniqueness");
            var input=input(loaded,REGION,List.of(FIRST),new Tick(100));var plan=plan(definition,input,List.of(FIRST));
            var selection=new CatalogueEventLedger.Selection(definition,plan);var token=reserve(plan,new Tick(100));
            var reserved=CatalogueEventLedger.reserve(selection,input,token,new Tick(100));
            check(reserved.size()==1&&reserved.getFirst().status()==UniqueStatus.RESERVED&&reserved.getFirst().consumptionEvidence().isEmpty(),"reservation is not consumption");
            var prepared=prepared(plan,token,reserved,0,new Tick(100));prepare(store,prepared,new Tick(100));
            var current=state(store);var initial=onlyUnique(current,definition.id());
            check(initial.status()==UniqueStatus.RESERVED&&current.cooldowns().isEmpty(),"durable PREPARE alone neither spends nor starts cooldowns");
            var before=store.session();store=reopen(store,path,before);current=state(store);
            check(onlyUnique(current,definition.id()).equals(initial),"exact RESERVED survives real close/open/new session before server result");
            var currentInput=current.input(loaded,REGION,List.of(FIRST),new Tick(110),2);
            var noApply=new SliceResult(Outcome.ABORTED_SAFE,Set.of(),Map.of(),0,Set.of(),Set.of(),List.of());
            var absent=CatalogueEventLedger.effect(selection,currentInput,prepared.prepare(),noApply,new Tick(110));
            check(absent.records().isEmpty()&&absent.blobs().isEmpty(),"no applied/observed effect cannot spend RESERVED");
            var contribution=CatalogueEventLedger.effect(selection,currentInput,prepared.prepare(),prepared.success(),new Tick(110));
            var ack=result(store,prepared,contribution);current=state(store);atCommit(current,contribution.records(),ack);
            var possible=onlyUnique(current,definition.id());
            check(possible.status()==UniqueStatus.SPENT_POSSIBLE&&possible.consumptionEvidence().isPresent(),"send success is POSSIBLE, not a fabricated perception confirmation");
            var noReleasePossible=CatalogueEventLedger.aborted(selection,current.input(loaded,REGION,List.of(FIRST),new Tick(115),3),prepared.prepare(),abortedSafe(),new Tick(115));
            check(noReleasePossible.records().isEmpty()&&noReleasePossible.blobs().isEmpty(),"later abort cannot release actual SPENT_POSSIBLE");
            typedCooldowns(current,definition,plan,new Tick(110));var firstCooldowns=current.cooldowns();
            // Regression W: the real first RESULT's interval must not cancel its own original second step.
            // Observations remain explicit pure fixtures; ledger rows above come from the actual private J04/fsync readback.
            var audienceInput=current.input(loaded,REGION,List.of(FIRST),new Tick(120),1);
            var generated=new CandidateGenerator.Generated(plan.candidateId(),definition,plan.definition(),Optional.of(REGION),
                Optional.empty(),plan.roles(),plan.scopes(),true);
            var audience=new EligibilityEngine(ProviderMode.TEST_ONLY);var event=plan.effects().getFirst().instanceId();
            check(audience.validateAudience(generated,Set.of(FIRST),audienceInput).reasons().contains(ReasonCode.SAFETY_COOLDOWN),
                "a new start remains blocked by the actual first-result cooldown");
            check(audience.validateAudience(generated,Set.of(FIRST),audienceInput,event).valid(),
                "same actual event can continue after its real first-result cooldown without resetting ledger");
            check(audience.validateAudience(generated,Set.of(FIRST),audienceInput,new EventInstanceId(UUID.randomUUID())).reasons().contains(ReasonCode.SAFETY_COOLDOWN),
                "another event cannot borrow the continuing event exemption");
            var interval=firstCooldowns.getFirst();
            var safety=new CooldownRecord(interval.header(),interval.key(),interval.kind(),"REAL_SAFETY_HOLD",interval.notBeforeTick(),
                event,interval.safetyClass(),interval.releaseCondition(),interval.binding());
            var safetyInput=withLedger(audienceInput,current.uniqueness(),List.of(safety),current.facts());
            check(audience.validateAudience(generated,Set.of(FIRST),safetyInput,event).reasons().contains(ReasonCode.SAFETY_COOLDOWN),
                "same-source safety cooldown is not an ordinary selected-effect interval");
            var danger=new CooldownRecord(interval.header(),interval.key(),interval.kind(),interval.reason(),interval.notBeforeTick(),
                event,Risk.CONTROLLED_DANGER,interval.releaseCondition(),interval.binding());
            check(audience.validateAudience(generated,Set.of(FIRST),withLedger(audienceInput,current.uniqueness(),List.of(danger),current.facts()),event)
                .reasons().contains(ReasonCode.SAFETY_COOLDOWN),
                "same event/reason/selector never exempts a controlled-danger interval");
            for(var kind:List.of("UNKNOWN","RECOVERY_REQUIRED")){
                var uncertain=new CooldownRecord(interval.header(),interval.key(),kind,interval.reason(),interval.notBeforeTick(),
                    event,Risk.SAFE,interval.releaseCondition(),interval.binding());
                check(audience.validateAudience(generated,Set.of(FIRST),withLedger(audienceInput,current.uniqueness(),List.of(uncertain),current.facts()),event)
                    .reasons().contains(ReasonCode.SAFETY_COOLDOWN),
                    "same event/reason/SAFE never exempts an unknown or recovery cooldown kind: "+kind);
            }
            var foreign=new CooldownRecord(interval.header(),interval.key(),interval.kind(),interval.reason(),interval.notBeforeTick(),
                new EventInstanceId(UUID.randomUUID()),interval.safetyClass(),interval.releaseCondition(),interval.binding());
            var foreignInput=withLedger(audienceInput,current.uniqueness(),List.of(foreign),current.facts());
            check(audience.validateAudience(generated,Set.of(FIRST),foreignInput,event).reasons().contains(ReasonCode.SAFETY_COOLDOWN),
                "an ordinary interval from another source event still blocks the audience");
            check(new HashSet<>(state(store).cooldowns()).equals(new HashSet<>(firstCooldowns)),
                "continuation validation never deletes, resets or prolongs the real durable intervals");
            var sameInput=current.input(loaded,REGION,List.of(FIRST),new Tick(120),3);
            check(CatalogueEventLedger.reserve(selection,sameInput,token,new Tick(120)).isEmpty(),"same event later original step reuses reservation/spend");
            var second=prepared(plan,token,List.of(),1,new Tick(120));prepare(store,second,new Tick(120));
            var noProlong=CatalogueEventLedger.effect(selection,sameInput,second.prepare(),second.success(),new Tick(120));
            check(noProlong.records().isEmpty()&&noProlong.blobs().isEmpty(),"later original step cannot duplicate/prolong cooldown or spend");
            result(store,second,noProlong);current=state(store);check(new HashSet<>(current.cooldowns()).equals(new HashSet<>(firstCooldowns)),"original cooldown deadlines unchanged");
            var completion=completion(definition,plan);
            var completed=CatalogueEventLedger.completed(selection,current.input(loaded,REGION,List.of(FIRST),new Tick(130),4),completion,new Tick(130));
            var completionRows=new ArrayList<DurableRecord>();completionRows.add(new DurableFact(completion));completionRows.addAll(completed.records());
            var completedAck=commit(store,completionRows,completed.blobs());current=state(store);atCommit(current,completed.records(),completedAck);
            var spent=onlyUnique(current,definition.id());
            check(spent.status()==UniqueStatus.SPENT_CONFIRMED&&spent.header().id().equals(initial.header().id())
                &&spent.header().revision().value()==3,"actual original completion upgrades POSSIBLE in same uniqueness lineage");
            check(new HashSet<>(current.cooldowns()).equals(new HashSet<>(firstCooldowns)),"completion never restarts effect cooldowns");
            var noReleaseSpent=CatalogueEventLedger.aborted(selection,current.input(loaded,REGION,List.of(FIRST),new Tick(135),5),prepared.prepare(),abortedSafe(),new Tick(135));
            check(noReleaseSpent.records().isEmpty()&&noReleaseSpent.blobs().isEmpty(),"later abort cannot release actual SPENT_CONFIRMED");
            var owner=new OwnershipRecord(header(UUID.randomUUID(),new Tick(140)),RESOURCE,Set.of(new OwnerToken(UUID.randomUUID())),new Epoch(1),
                OwnershipDisposition.YIELDED_TO_PLAYER,new MutationId(UUID.randomUUID()),prepared.success().observedHashes().get(RESOURCE),new Epoch(1),Optional.empty());
            commit(store,List.of(new DurableOwnership(owner)),List.of());current=state(store);
            var afterEdit=CatalogueEventLedger.effect(selection,current.input(loaded,REGION,List.of(FIRST),new Tick(140),5),second.prepare(),noApply,new Tick(140));
            check(afterEdit.records().isEmpty()&&onlyUnique(current,definition.id()).equals(spent),"later player edit/cancel cannot reset spent use");
            var illegalReset=new UniqueUseRecord(new AggregateHeader(1,spent.header().id(),spent.header().revision().plus(1),spent.header().lastSequence(),new Tick(150)),
                spent.definitionId(),spent.scopeKind(),spent.canonicalScopeId(),UniqueStatus.RELEASED_BEFORE_EFFECT,Optional.empty(),spent.instanceId(),Optional.empty(),spent.originScopeIds());
            var resetBatch=ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),List.of(new DurableUnique(illegalReset)),List.of());
            check(await(store.append(resetBatch)).ackStatus()==AckStatus.REJECTED&&!store.readOnlySafe(),"real J04 rejects resetting spent use without poisoning writer");
            current=state(store);check(onlyUnique(current,definition.id()).equals(spent),"rejected reset leaves durable spent tombstone intact");
            long expired=current.cooldowns().stream().mapToLong(c->c.notBeforeTick().value()).max().orElse(0)+1;
            before=store.session();store=reopen(store,path,before);current=state(store);
            check(onlyUnique(current,definition.id()).equals(spent),"spent use remains exact after edit and second real reopen");
            var reconnected=current.input(loaded,REGION,List.of(FIRST),new Tick(expired),50);
            var again=new CatalogueEventLedger.Selection(definition,plan(definition,reconnected,List.of(FIRST)));
            rejects(()->CatalogueEventLedger.reserve(again,reconnected,reserve(again.plan(),new Tick(expired)),new Tick(expired)),
                "same player UUID in fresh snapshot remains blocked after all cooldowns expired");
            var other=current.input(loaded,REGION,List.of(SECOND),new Tick(expired),51);
            var alternate=new CatalogueEventLedger.Selection(definition,plan(definition,other,List.of(SECOND)));
            var available=CatalogueEventLedger.reserve(alternate,other,reserve(alternate.plan(),new Tick(expired)),new Tick(expired));
            check(available.size()==1&&available.getFirst().canonicalScopeId().equals(SECOND.value()),"another player UUID is not consumed by first player's spent use");
            System.out.println("PASS original parallel_steps: durable reservation / POSSIBLE / confirmation / scope cooldowns / reopen / player-edit tombstone");
        }finally{await(store.closeAsync());}
    }
    private static void outcomeOnly(Path base,Loaded loaded,String name,List<PlayerId> participants)throws Exception{
        var path=base.resolve(name+"-outcome-only");JournalStore store=fresh(path);
        try{
            var definition=loaded.event(name);check(definition.uniqueness().consumeOn()==ConsumeOn.OUTCOME_CONFIRMED,"original outcome-only definition");
            var input=input(loaded,REGION,participants,new Tick(200));var plan=plan(definition,input,participants);
            var selected=new CatalogueEventLedger.Selection(definition,plan);var token=reserve(plan,new Tick(200));
            var reserved=CatalogueEventLedger.reserve(selected,input,token,new Tick(200));
            var prepared=prepared(plan,token,reserved,0,new Tick(200));prepare(store,prepared,new Tick(200));
            var current=state(store);check(onlyUnique(current,definition.id()).status()==UniqueStatus.RESERVED,"real PREPARE reserve only");
            var effect=CatalogueEventLedger.effect(selected,input,prepared.prepare(),prepared.success(),new Tick(210));
            check(effect.records().stream().noneMatch(DurableUnique.class::isInstance),"Trace effect/shared-choice offer RESULT is not OUTCOME consumption");
            result(store,prepared,effect);current=state(store);
            check(onlyUnique(current,definition.id()).status()==UniqueStatus.RESERVED,"successful offer/effect stays RESERVED until original outcome fact");
            typedCooldowns(current,definition,plan,new Tick(210));
            var genuine=completion(definition,plan);
            var offerOnly=new DirectorFact(new FactId(UUID.randomUUID()),definition.completion().factType(),genuine.subjectRefs(),genuine.eventId(),
                EvidenceStatus.CONFIRMED,Map.of("source",new JsonValue.Text("OFFER_ONLY")),true,Optional.empty());
            final var gateInput=current.input(loaded,REGION,participants,new Tick(220),2);
            rejects(()->CatalogueEventLedger.completed(selected,gateInput,offerOnly,new Tick(220)),"offer/non-original fact must not consume outcome use");
            // The caller has a REAL prior PREPARE ACK, while its captured DecisionInput is the
            // pre-PREPARE cut. The explicit overload may recover only the sealed RESERVED rows.
            var completion=CatalogueEventLedger.completed(selected,input,prepared.prepare(),genuine,new Tick(220));
            check(completion.records().stream().filter(DurableUnique.class::isInstance).count()==1&&completion.records().stream()
                .noneMatch(r->r instanceof DurableAggregate a&&a.state() instanceof CooldownAggregate),"completion consumes once without duplicate cooldown revision1");
            var rows=new ArrayList<DurableRecord>();rows.add(new DurableFact(genuine));rows.addAll(completion.records());
            var ack=commit(store,rows,completion.blobs());current=state(store);atCommit(current,completion.records(),ack);
            var spent=onlyUnique(current,definition.id());check(spent.status()==UniqueStatus.SPENT_CONFIRMED&&spent.consumptionEvidence().isPresent(),"original outcome consumes CONFIRMED");
            var before=store.session();store=reopen(store,path,before);current=state(store);check(onlyUnique(current,definition.id()).equals(spent),"outcome spent evidence survives true new session");
            var later=current.input(loaded,REGION,participants,new Tick(1000000),3);
            var repeated=new CatalogueEventLedger.Selection(definition,plan(definition,later,participants));
            rejects(()->CatalogueEventLedger.reserve(repeated,later,reserve(repeated.plan(),new Tick(1000000)),new Tick(1000000)),"same canonical WORLD/REGION stays spent after cooldown expiration");
            var otherRegion=current.input(loaded,OTHER_REGION,participants,new Tick(1000000),4);
            var otherSelected=new CatalogueEventLedger.Selection(definition,plan(definition,otherRegion,participants));
            if(definition.uniqueness().scope()==UniquenessScope.ONCE_PER_REGION){
                var available=CatalogueEventLedger.reserve(otherSelected,otherRegion,reserve(otherSelected.plan(),new Tick(1000000)),new Tick(1000000));
                check(available.size()==1&&available.getFirst().canonicalScopeId().equals(OTHER_REGION.value()),"other region unaffected by region-only outcome spend");
            }else rejects(()->CatalogueEventLedger.reserve(otherSelected,otherRegion,reserve(otherSelected.plan(),new Tick(1000000)),new Tick(1000000)),"ONCE_PER_WORLD cannot be bypassed by changing region/player context");
            System.out.println("PASS original "+name+": no offer/effect spend, exact original outcome gate, durable "+definition.uniqueness().scope()+" across new session");
        }finally{await(store.closeAsync());}
    }
    private static void abortBeforeApplyAllowsRequeue(Path base,Loaded loaded)throws Exception{
        var path=base.resolve("required-signal-preapply-abort-requeue");JournalStore store=fresh(path);
        try{
            var definition=loaded.event("required_signal");var input=input(loaded,REGION,List.of(FIRST),new Tick(300));
            var plan=plan(definition,input,List.of(FIRST));var selection=new CatalogueEventLedger.Selection(definition,plan);
            var token=reserve(plan,new Tick(300));var reserved=CatalogueEventLedger.reserve(selection,input,token,new Tick(300));
            var prepared=prepared(plan,token,reserved,0,new Tick(300));prepare(store,prepared,new Tick(300));
            var current=state(store);var reservedHead=onlyUnique(current,definition.id());
            check(reservedHead.status()==UniqueStatus.RESERVED&&current.cooldowns().isEmpty(),"required signal actual PREPARE reserves before revalidation");
            // Exact original PREPARE had a real positive ACK, while the caller retains its
            // pre-PREPARE input cut. No operation/hash/work was applied in this unit result.
            var release=CatalogueEventLedger.aborted(selection,input,prepared.prepare(),abortedSafe(),new Tick(301));
            check(release.records().size()==1&&release.records().getFirst() instanceof DurableUnique&&release.blobs().size()==1,
                "pre-apply abort releases only this actual PREPARE reservation with sealed absence evidence");
            var ack=result(store,prepared,abortedSafe(),release);current=state(store);atCommit(current,release.records(),ack);
            var released=onlyUnique(current,definition.id());
            check(released.status()==UniqueStatus.RELEASED_BEFORE_EFFECT&&released.header().id().equals(reservedHead.header().id())
                &&released.header().revision().equals(reservedHead.header().revision().plus(1))&&current.cooldowns().isEmpty(),
                "ABORTED_SAFE RESULT and release commit atomically, no effect cooldown");
            var ref=released.consumptionEvidence().orElseThrow();var hydration=await(store.readBlobs(new BlobReadRequest(WORLD,store.session().storeGeneration(),Set.of(ref))));
            check(hydration.status()==AckStatus.DURABLE&&hydration.contents().size()==1&&hydration.contents().getFirst().reference().equals(ref),"real durable absence blob readback");
            var proof=new fr.proutlost.director.api.codec.ModelCodec<>(ResultRecord.class).decode(hydration.contents().getFirst().bytes());
            check(proof.prepareTransactionId().equals(prepared.prepare().transactionId())&&proof.operationToken().equals(prepared.prepare().operationToken())
                &&proof.result().equals(abortedSafe()),"absence blob seals exact original no-effect RESULT");
            var before=store.session();store=reopen(store,path,before);current=state(store);
            check(onlyUnique(current,definition.id()).equals(released),"release survives real same-WAL new session");
            var retryInput=current.input(loaded,REGION,List.of(FIRST),new Tick(310),2);
            var retryPlan=plan(definition,retryInput,List.of(FIRST));var retry=new CatalogueEventLedger.Selection(definition,retryPlan);
            check(!retry.event().equals(selection.event())&&retry.plan().scopes().worldId().equals(selection.plan().scopes().worldId()),
                "requeued required-signal execution uses new event UUID in same world");
            var retryToken=reserve(retryPlan,new Tick(310));var next=CatalogueEventLedger.reserve(retry,retryInput,retryToken,new Tick(310));
            check(next.size()==1&&next.getFirst().status()==UniqueStatus.RESERVED&&next.getFirst().header().id().equals(released.header().id())
                &&next.getFirst().header().revision().equals(released.header().revision().plus(1)),"released unique lineage permits new REQUIRE execution reserve");
            prepare(store,prepared(retryPlan,retryToken,next,0,new Tick(310)),new Tick(310));current=state(store);
            check(onlyUnique(current,definition.id()).instanceId().equals(retry.event()),"new same-world signal reservation really commits after prior abort");
            System.out.println("PASS original required_signal pre-apply ABORTED_SAFE: real RESULT/release/blob/reopen/new-event reserve");
        }finally{await(store.closeAsync());}
    }
    private static void laterBranchAbortCannotReleaseOffer(Path base,Loaded loaded)throws Exception{
        var path=base.resolve("shared-choice-later-branch-abort");JournalStore store=fresh(path);
        try{
            var definition=loaded.event("shared_choice");var input=input(loaded,REGION,List.of(FIRST,SECOND),new Tick(400));
            var plan=plan(definition,input,List.of(FIRST,SECOND));var selection=new CatalogueEventLedger.Selection(definition,plan);
            var token=reserve(plan,new Tick(400));var rows=CatalogueEventLedger.reserve(selection,input,token,new Tick(400));
            var offer=prepared(plan,token,rows,0,new Tick(400));prepare(store,offer,new Tick(400));
            var effect=CatalogueEventLedger.effect(selection,input,offer.prepare(),offer.success(),new Tick(410));result(store,offer,effect);
            var current=state(store);var originalReservation=onlyUnique(current,definition.id());
            check(originalReservation.status()==UniqueStatus.RESERVED&&originalReservation.reservationToken().equals(Optional.of(token)),
                "actual shared-choice offer keeps its original outcome reservation");
            var later=laterOperation(offer,new Tick(420));check(later.prepare().uniqueness().isEmpty()
                &&later.prepare().reservation().equals(offer.prepare().reservation())
                &&!later.prepare().operationToken().equals(offer.prepare().operationToken()),"later branch fixture retains same token/event but is a distinct PREPARE");
            prepare(store,later,new Tick(420));
            var contribution=CatalogueEventLedger.aborted(selection,current.input(loaded,REGION,List.of(FIRST,SECOND),new Tick(420),2),
                later.prepare(),abortedSafe(),new Tick(420));
            check(contribution.records().isEmpty()&&contribution.blobs().isEmpty(),"same reservation token cannot release an earlier applied offer's unique use");
            result(store,later,abortedSafe(),contribution);current=state(store);
            check(onlyUnique(current,definition.id()).equals(originalReservation),"real later ABORTED_SAFE RESULT leaves earlier offer reservation unchanged");
            var before=store.session();store=reopen(store,path,before);current=state(store);
            check(onlyUnique(current,definition.id()).equals(originalReservation),"prior offer protection survives true new J04 session");
            var anotherInput=current.input(loaded,REGION,List.of(FIRST,SECOND),new Tick(1000000),3);
            var another=new CatalogueEventLedger.Selection(definition,plan(definition,anotherInput,List.of(FIRST,SECOND)));
            rejects(()->CatalogueEventLedger.reserve(another,anotherInput,reserve(another.plan(),new Tick(1000000)),new Tick(1000000)),
                "later branch abort cannot admit a new region choice while original outcome remains reserved");
            System.out.println("PASS original shared_choice later-branch ABORTED_SAFE: earlier offer/token reservation retained across reopen");
        }finally{await(store.closeAsync());}
    }
    private static List<DurableRecord> chronology(JournalStore store)throws Exception{
        var q=new StoreQuery(WORLD,Set.of(),new Sequence(0),Optional.empty(),128);var r=await(store.read(q));ReadContracts.validate(r,q,store.session());
        check(r.status()==ReadStatus.AVAILABLE&&!r.page().orElseThrow().hasMore(),"bounded real chronology for END/PREPARE/RESULT distinction");
        return r.page().orElseThrow().records();
    }
    private static AnchorRecord anchor(JournalStore store,AnchorId id)throws Exception{
        var q=new StoreQuery(WORLD,Set.of(new AggregateKey(AggregateKind.ANCHOR,id.value())),new Sequence(0),Optional.empty(),1);
        var r=await(store.read(q));ReadContracts.validate(r,q,store.session());
        check(r.status()==ReadStatus.AVAILABLE&&r.page().orElseThrow().aggregates().size()==1,"actual anchor point projection");
        var row=r.page().orElseThrow().aggregates().getFirst();check(row.state() instanceof AnchorAggregate&&row.committedSequence().value()>0,"durable actual anchor row");
        return ((AnchorAggregate)row.state()).value();
    }
    private static Prepared unchangedOffer(ExecutionPlan plan,ReservationToken token,List<UniqueUseRecord> unique,AnchorRecord before){
        var effect=plan.effects().getFirst();var key=new ResourceKey(OwnershipDomain.ANCHOR,before.dimension(),before.anchorId().value().toString());
        var blob=BlobContent.capture(new ResourceId("proutlost_director:anchor_state_v1"),1,new fr.proutlost.director.api.codec.ModelCodec<>(AnchorRecord.class).encode(before));
        var refs=new EffectBlobs(Set.of(blob.reference()),blob.reference(),blob.reference(),Map.of(key,blob.reference()),Map.of());
        var read=new ReadSet(Map.of(),Map.of(key,new Epoch(1)),Map.of(key,blob.reference().hash()));
        var slice=new PreparedSlice(new SliceId(UUID.randomUUID()),effect.operationToken(),effect.expectedFootprint(),read,List.of(effect),CAP);
        var p=new PrepareRecord(new TransactionId(UUID.randomUUID()),effect.operationToken(),token.generation(),read,token.expiryTick(),slice,token,
            new ValidationResult(true,List.of(),read),unique,Set.of(),List.of(),refs);
        return new Prepared(p,List.of(blob),new SliceResult(Outcome.SUCCEEDED,Set.of(effect.operationToken()),Map.of(),0,Set.of(),Set.of(),List.of()));
    }
    private static void unchangedChoiceEnd(Path base,Loaded loaded,boolean changed)throws Exception{
        var path=base.resolve(changed?"choice-keep-unchanged-refuses-edited-anchor":"choice-keep-unchanged-durable-end");JournalStore store=fresh(path);
        try{
            var definition=loaded.event("shared_choice");var participants=List.of(FIRST,SECOND);var input=input(loaded,REGION,participants,new Tick(500));
            var plan=plan(definition,input,participants);var selection=new CatalogueEventLedger.Selection(definition,plan);
            var choice=(SharedChoice)definition.steps().getFirst().args();var id=new AnchorId(UUID.randomUUID());
            var before=new AnchorRecord(header(id.value(),new Tick(500)),id,Optional.empty(),Set.of(REGION),DIM,new BlockPosition(DIM,0,64,0),
                "PURE_LEDGER_FIXTURE",Set.of(choice.anchorTag()),AnchorOrigin.DISCOVERED,EvidenceStatus.CONFIRMED,true);
            commit(store,List.of(new DurableAggregate(new AnchorAggregate(before))),List.of());
            var resource=new ResourceKey(OwnershipDomain.ANCHOR,DIM,id.value().toString());var token=reserve(plan,new Tick(500),resource);
            var unique=CatalogueEventLedger.reserve(selection,input,token,new Tick(500));var offer=unchangedOffer(plan,token,unique,before);prepare(store,offer,new Tick(500));
            var effect=CatalogueEventLedger.effect(selection,input,offer.prepare(),offer.success(),new Tick(510));result(store,offer,effect);
            var current=state(store);var original=onlyUnique(current,definition.id());
            check(original.status()==UniqueStatus.RESERVED&&anchor(store,id).equals(before),"actual successful offer leaves anchor unchanged and outcome reserved");
            AnchorRecord after=before;
            if(changed){
                var tags=new HashSet<>(before.tags());tags.add(new TagId("proutlost:player_edited"));
                after=new AnchorRecord(new AggregateHeader(1,before.header().id(),before.header().revision().plus(1),before.header().lastSequence(),new Tick(515)),
                    before.anchorId(),before.locationId(),before.regionIds(),before.dimension(),before.position(),before.radiusProfile(),tags,before.origin(),before.evidence(),before.active());
                commit(store,List.of(new DurableAggregate(new AnchorAggregate(after))),List.of());
            }
            var closed=new DirectorFact(new FactId(UUID.randomUUID()),new FactType("proutlost_director:lab_choice_closed"),List.copyOf(plan.scopes().scopes()),
                Optional.of(selection.event()),EvidenceStatus.CONFIRMED,Map.of("reason",new JsonValue.Text("TIMEOUT_KEEP_UNCHANGED"),
                "selected",new JsonValue.Bool(false),"pureFixture",new JsonValue.Bool(true),"humanInteraction",new JsonValue.Bool(false)),true,Optional.empty());
            final var closingInput=current.input(loaded,REGION,participants,new Tick(520),2);final var actualAfter=anchor(store,id);
            check(actualAfter.equals(after),"closure compares the real latest stored anchor record");
            if(changed){
                rejects(()->CatalogueEventLedger.outcomeUnchanged(selection,closingInput,offer.prepare(),closed,before,actualAfter,new Tick(520)),
                    "changed full anchor record cannot claim KEEP_UNCHANGED and release prior offer");
                current=state(store);check(onlyUnique(current,definition.id()).equals(original),"rejected unchanged closure keeps original reservation");
                var prior=store.session();store=reopen(store,path,prior);current=state(store);
                check(anchor(store,id).equals(actualAfter)&&onlyUnique(current,definition.id()).equals(original),"edited anchor + retained reservation persist across new session");
                var retryInput=current.input(loaded,REGION,participants,new Tick(1000000),3);var retry=new CatalogueEventLedger.Selection(definition,plan(definition,retryInput,participants));
                rejects(()->CatalogueEventLedger.reserve(retry,retryInput,reserve(retry.plan(),new Tick(1000000)),new Tick(1000000)),
                    "changed-anchor closure cannot silently admit another same-region outcome");
            }else{
                var end=CatalogueEventLedger.outcomeUnchanged(selection,closingInput,offer.prepare(),closed,before,actualAfter,new Tick(520));
                check(end.records().size()==1&&end.records().getFirst() instanceof DurableUnique&&end.blobs().size()==1,"only original RESERVED use released by unchanged END");
                var rows=new ArrayList<DurableRecord>();rows.add(new DurableFact(closed));rows.addAll(end.records());
                var endAck=commit(store,rows,end.blobs());current=state(store);atCommit(current,end.records(),endAck);
                var released=onlyUnique(current,definition.id());
                check(released.status()==UniqueStatus.RELEASED_BEFORE_EFFECT&&released.header().id().equals(original.header().id())
                    &&anchor(store,id).equals(before),"real unchanged END releases use without rewriting anchor");
                var history=chronology(store);
                var results=history.stream().filter(DurableResult.class::isInstance).map(DurableResult.class::cast).map(DurableResult::value).toList();
                check(results.size()==1&&results.getFirst().result().equals(offer.success())&&results.getFirst().result().outcome()==Outcome.SUCCEEDED,
                    "KEEP_UNCHANGED is an END outcome, never a fabricated failed/ABORTED_SAFE offer RESULT");
                var ref=released.consumptionEvidence().orElseThrow();var hydrated=await(store.readBlobs(new BlobReadRequest(WORLD,store.session().storeGeneration(),Set.of(ref))));
                check(hydrated.status()==AckStatus.DURABLE&&hydrated.contents().size()==1
                    &&new fr.proutlost.director.api.codec.ModelCodec<>(DirectorFact.class).decode(hydrated.contents().getFirst().bytes()).equals(closed),
                    "real forced unchanged-outcome evidence seals exact CLOSED fact");
                var prior=store.session();store=reopen(store,path,prior);current=state(store);
                check(onlyUnique(current,definition.id()).equals(released)&&anchor(store,id).equals(before),"END release/anchor exact after true new J04 session");
                var sameInput=current.input(loaded,REGION,participants,new Tick(1000000),3);var same=new CatalogueEventLedger.Selection(definition,plan(definition,sameInput,participants));
                check(CatalogueEventLedger.reserve(same,sameInput,reserve(same.plan(),new Tick(1000000)),new Tick(1000000)).size()==1,"unchanged closed region can be selected anew");
                var otherInput=current.input(loaded,OTHER_REGION,participants,new Tick(1000000),4);var otherPlan=plan(definition,otherInput,participants);
                var other=new CatalogueEventLedger.Selection(definition,otherPlan);var otherToken=reserve(otherPlan,new Tick(1000000));
                var available=CatalogueEventLedger.reserve(other,otherInput,otherToken,new Tick(1000000));
                check(available.size()==1&&available.getFirst().canonicalScopeId().equals(OTHER_REGION.value()),"another region unaffected by old unchanged offer");
                prepare(store,prepared(otherPlan,otherToken,available,0,new Tick(1000000)),new Tick(1000000));current=state(store);
                check(current.uniqueness().stream().anyMatch(u->u.canonicalScopeId().equals(OTHER_REGION.value())&&u.status()==UniqueStatus.RESERVED),
                    "another region reservation actually commits after unchanged END/reopen");
            }
            System.out.println("PASS original shared_choice KEEP_UNCHANGED full-anchor equality changed="+changed+" real J04 END/reopen; no human claim");
        }finally{await(store.closeAsync());}
    }
    private static void composedFaunaEnd(Path base,Loaded loaded)throws Exception{
        var path=base.resolve("animal-migration-composed-end");JournalStore store=fresh(path);
        try{
            var definition=loaded.event("animal_migration");check(definition.uniqueness().scope()==UniquenessScope.REPEATABLE,"original fauna remains repeatable");
            var input=input(loaded,REGION,List.of(FIRST),new Tick(600));var plan=plan(definition,input,List.of(FIRST));var selected=new CatalogueEventLedger.Selection(definition,plan);
            var completion=completion(definition,plan);
            var contribution=CatalogueEventLedger.completedComposed(selected,input,completion,new Tick(600));
            check(contribution.records().stream().noneMatch(r->r instanceof DurablePrepare||r instanceof DurableResult||r instanceof DurableUnique)
                &&contribution.records().size()==3&&contribution.blobs().isEmpty(),"composed repeatable END creates configured cooldowns without global cohort PREPARE/RESULT");
            var rows=new ArrayList<DurableRecord>();rows.add(new DurableFact(completion));rows.addAll(contribution.records());
            var ack=commit(store,rows,contribution.blobs());var current=state(store);atCommit(current,contribution.records(),ack);
            typedCooldowns(current,definition,plan,new Tick(600));check(current.uniqueness().isEmpty(),"repeatable composed END has no invented unique spend");
            var history=chronology(store);check(history.stream().noneMatch(r->r instanceof DurablePrepare||r instanceof DurableResult)
                &&history.stream().filter(r->r instanceof DurableFact f&&f.value().equals(completion)).count()==1,"actual committed fauna END contains no synthetic global execution receipt");
            var deadlines=Set.copyOf(current.cooldowns());var previous=store.session();store=reopen(store,path,previous);current=state(store);
            check(Set.copyOf(current.cooldowns()).equals(deadlines)&&current.uniqueness().isEmpty(),"composed END cooldowns survive exact real close/open");
            var replay=CatalogueEventLedger.completedComposed(selected,current.input(loaded,REGION,List.of(FIRST),new Tick(700),2),completion,new Tick(700));
            check(replay.records().isEmpty()&&replay.blobs().isEmpty(),"same composed completion cannot prolong cooldowns or invent another RESULT");
            System.out.println("PASS original animal_migration composed END: three typed cooldowns, same COMMIT, real reopen, no global PREPARE/RESULT; physical cohort NOT_RUN");
        }finally{await(store.closeAsync());}
    }
    public static int run(Path base,Path catalogRoot)throws Exception{
        assertions=0;Files.createDirectories(base);var loaded=load(catalogRoot);
        check(loaded.catalog().events().size()==9,"unchanged original nine catalog definitions");
        oncePerPlayer(base,loaded);
        outcomeOnly(base,loaded,"required_signal",List.of(FIRST));
        outcomeOnly(base,loaded,"shared_choice",List.of(FIRST,SECOND));
        abortBeforeApplyAllowsRequeue(base,loaded);
        laterBranchAbortCannotReleaseOffer(base,loaded);
        unchangedChoiceEnd(base,loaded,false);
        unchangedChoiceEnd(base,loaded,true);
        composedFaunaEnd(base,loaded);
        System.out.println("PASS CatalogueEventLedger real private J04 tests assertions="+assertions+" minecraft=false client=false human=false");
        return assertions;
    }
    public static void main(String[] args)throws Exception{
        if(args.length<1)throw new IllegalArgumentException("explicit fresh pure-test output directory required");
        run(Path.of(args[0]),args.length>1?Path.of(args[1]):Path.of("src/directorTest/resources/catalog"));
    }
}
