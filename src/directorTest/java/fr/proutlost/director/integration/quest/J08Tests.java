package fr.proutlost.director.integration.quest;

import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.*;
import fr.proutlost.director.integration.ai.*;
import fr.proutlost.director.integration.locations.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** TEST_ONLY executable domain suite. No test implementation is packaged in gameplay. */
public final class J08Tests {
    static int assertions;
    static final Fingerprint HASH=new Fingerprint("a".repeat(64));
    static final WorldId WORLD=new WorldId(new UUID(0,1));
    static final ResourceId PRODUCER=new ResourceId("test:quest");
    static final ScopeRef SCOPE=new ScopeRef(ScopeKind.WORLD,WORLD.value());
    static final CandidateId CANDIDATE=new CandidateId(new UUID(0,2));
    static void check(boolean condition,String label){assertions++;if(!condition)throw new AssertionError(label);}
    static <T> T await(CompletionStage<T> stage)throws Exception{return stage.toCompletableFuture().get(5,TimeUnit.SECONDS);}
    static void rejected(Runnable action,String label){try{action.run();throw new AssertionError(label);}catch(IllegalArgumentException expected){assertions++;}}
    public static void main(String[] args)throws Exception{
        ai();assertions+=AiLifecycleTests.run();assertions+=HttpAiTransportTests.run();locations(Path.of(args[0]),Path.of(args[1]));quest();questPolicies101();questScheduledBindings();questKeyAndTransitionContracts();questTerminalCancellationReceipts();questConflict();questBindingRestoreGuard();questRestoreFailure();locationIoFence(Path.of(args[0]).resolve("io-fence"),Path.of(args[1]));
        System.out.println("PASS J08 assertions="+assertions+"; AI/locations/quest; external mods absent");
    }
    static AiInput input(long deadline,Fingerprint context){
        var candidate=new AiCandidate(CANDIDATE,new DefinitionId("test:event"),Category.AMBIENT,Set.of(Family.AMBIENCE),Map.of(),1,Map.of(),Set.of(),Set.of());
        return new AiInput(1,new RequestId(UUID.randomUUID()),new Pseudonym("p_0123456789abcdef"),new DecisionId(UUID.randomUUID()),HASH,context,HASH,deadline,List.of(candidate),List.of(),4,
                new AiOutputLimits(8192,4,2,4,.2,BudgetMath.zero()));
    }
    static byte[] proposal(AiInput input){return new ModelCodec<>(AiProposal.class).encode(new AiProposal(1,input.requestId(),input.candidateSetHash(),List.of(new AiAdjustment(CANDIDATE,.1)),List.of(),Set.of(AiRationale.NOVELTY)));}
    static void ai()throws Exception{
        var input=input(System.nanoTime()+TimeUnit.SECONDS.toNanos(5),HASH);
        var disabled=new DisabledAiAdvisor().propose(input).toCompletableFuture();
        check(disabled.isDone() && disabled.get().status()==AiStatus.FALLBACK,"disabled immediate");
        byte[] valid=proposal(input);String json=new String(valid,StandardCharsets.UTF_8);
        check(AiProposalContract.decode(valid,input,System.nanoTime(),true,HASH).adjustments().size()==1,"valid proposal");
        List<String> invalid=List.of(json+" trailing",json.replaceFirst("\\{","{\"command\":\"op @a\","),json.replaceFirst("\\{","{\"protocol_version\":1,"),json.replace("0.1","NaN"),json.replace("0.1","1e309"),json.replace("0.1","0.3"),json.replace(CANDIDATE.value().toString(),UUID.randomUUID().toString()),json.replace(HASH.value(),"b".repeat(64)),json.substring(0,json.length()-1));
        for(String bad:invalid)rejected(() -> AiProposalContract.decode(bad.getBytes(StandardCharsets.UTF_8),input,System.nanoTime(),true,HASH),"malicious proposal");
        rejected(() -> AiProposalContract.decode(valid,input,System.nanoTime(),false,HASH),"invalid token");
        rejected(() -> AiProposalContract.decode(valid,input,System.nanoTime(),true,new Fingerprint("c".repeat(64))),"stale context");
        rejected(() -> AiProposalContract.decode(new byte[8193],input,System.nanoTime(),true,HASH),"byte bound");
        var chain=new ChainId(UUID.randomUUID());
        var first=new ResourceId("test:first");var second=new ResourceId("test:second");var cycle=new ResourceId("test:cycle");
        var transitions=List.of(new AiAllowedTransition(chain,first,"a","b",BudgetMath.zero(),Set.of()),new AiAllowedTransition(chain,second,"b","c",BudgetMath.zero(),Set.of()),new AiAllowedTransition(chain,cycle,"b","a",BudgetMath.zero(),Set.of()));
        var arcInput=new AiInput(1,input.requestId(),input.worldPseudonym(),input.decisionId(),HASH,HASH,HASH,input.expiresMonotonicDeadline(),input.candidates(),transitions,2,input.outputLimits());
        var arcCodec=new ModelCodec<>(AiProposal.class);
        byte[] arc=arcCodec.encode(new AiProposal(1,input.requestId(),HASH,List.of(),List.of(new AiArcProposal(chain,List.of(first,second))),Set.of()));
        check(AiProposalContract.decode(arc,arcInput,System.nanoTime(),true,HASH).arcProposals().size()==1,"valid arc");
        for(var ids:List.of(List.of(first,cycle),List.of(second,first),List.of(first,second,cycle),List.of(new ResourceId("test:unknown")))) {
            byte[] bad=arcCodec.encode(new AiProposal(1,input.requestId(),HASH,List.of(),List.of(new AiArcProposal(chain,ids)),Set.of()));
            rejected(() -> AiProposalContract.decode(bad,arcInput,System.nanoTime(),true,HASH),"arc cycle/causality/horizon/unknown");
        }
        var costly=new AiAllowedTransition(chain,first,"a","b",new BudgetVector(1,0,0,0,0,0,0,0,0,0),Set.of());
        var budgetInput=new AiInput(1,input.requestId(),input.worldPseudonym(),input.decisionId(),HASH,HASH,HASH,input.expiresMonotonicDeadline(),input.candidates(),List.of(costly),2,input.outputLimits());
        byte[] costlyArc=arcCodec.encode(new AiProposal(1,input.requestId(),HASH,List.of(),List.of(new AiArcProposal(chain,List.of(first))),Set.of()));
        rejected(() -> AiProposalContract.decode(costlyArc,budgetInput,System.nanoTime(),true,HASH),"arc budget");
        var scheduler=new ScheduledThreadPoolExecutor(1);scheduler.setRemoveOnCancelPolicy(true);
        try{
            AtomicInteger calls=new AtomicInteger();AtomicLong tick=new AtomicLong(1000);
            AiTransport good=request -> {calls.incrementAndGet();return CompletableFuture.completedFuture(new AiTransportResponse(proposal(request.input()),System.nanoTime()));};
            try(var advisor=new OptionalAiAdvisor(good,scheduler,System::nanoTime,tick::get,()->HASH,new OptionalAiAdvisor.Policy(TimeUnit.SECONDS.toNanos(1),10,2,TimeUnit.MILLISECONDS.toNanos(80),100,128),new ResourceId("test:ai"),"v1")){
                var result=await(advisor.propose(input));check(result.status()==AiStatus.VALID_PROPOSAL,"advisor accepted");
                check(result.accepted().orElseThrow().proposalHash().equals(BlobContent.hash(valid)),"canonical audit hash");
                tick.addAndGet(10);
                check(await(advisor.propose(input)).status()==AiStatus.FALLBACK && calls.get()==1,"dedup hashes after minimum gap");
                var changed=input(System.nanoTime()+TimeUnit.SECONDS.toNanos(5),HASH);
                changed=new AiInput(1,changed.requestId(),changed.worldPseudonym(),changed.decisionId(),HASH,HASH,new Fingerprint("b".repeat(64)),changed.expiresMonotonicDeadline(),changed.candidates(),changed.allowedChainTransitions(),changed.planningHorizon(),changed.outputLimits());
                check(await(advisor.propose(changed)).status()==AiStatus.VALID_PROPOSAL && calls.get()==2,"changed hashes eligible window");
                tick.addAndGet(90);
                check(await(advisor.propose(input)).status()==AiStatus.VALID_PROPOSAL && calls.get()==3,"same hashes after dedup window expires");
            }
            CompletableFuture<AiTransportResponse> late=new CompletableFuture<>();
            try(var advisor=new OptionalAiAdvisor(r -> late,scheduler,System::nanoTime,tick::get,()->HASH,new OptionalAiAdvisor.Policy(TimeUnit.MILLISECONDS.toNanos(40),0,1,TimeUnit.SECONDS.toNanos(1),100,128),new ResourceId("test:ai"),"v1")){
                var stage=advisor.propose(input);check(await(advisor.propose(input)).status()==AiStatus.FALLBACK,"bounded concurrency");
                var result=await(stage);check(result.fallbackReason().orElseThrow()==ReasonCode.AI_TIMEOUT,"timeout");
                late.complete(new AiTransportResponse(valid,System.nanoTime()));
                check(await(stage).equals(result),"late token invalid");
                check(await(advisor.propose(input(System.nanoTime()+1000000000,new Fingerprint("b".repeat(64))))).fallbackReason().orElseThrow()==ReasonCode.RATE_LIMITED,"circuit open");
            }
            AtomicBoolean fail=new AtomicBoolean(true); AtomicLong clock=new AtomicLong(100);
            AiTransport transport=r -> fail.get()?CompletableFuture.failedFuture(new IllegalStateException("credential must not leak")):CompletableFuture.completedFuture(new AiTransportResponse(proposal(r.input()),clock.get()));
            try(var advisor=new OptionalAiAdvisor(transport,scheduler,clock::get,tick::get,()->HASH,new OptionalAiAdvisor.Policy(1000000000,0,1,100,100,128),new ResourceId("test:ai"),"v1")){
                check(await(advisor.propose(input(1000000000,HASH))).fallbackReason().orElseThrow()==ReasonCode.AI_INVALID,"transport failure sanitized");
                clock.set(201);tick.addAndGet(100);fail.set(false);
                check(await(advisor.propose(input(1000000000,HASH))).status()==AiStatus.VALID_PROPOSAL,"half open recovery same context");
                var changed=input(1000000000,new Fingerprint("b".repeat(64)));
                check(await(advisor.propose(changed)).status()==AiStatus.FALLBACK,"stale response rejected");
            }
            try(var advisor=new OptionalAiAdvisor(r -> CompletableFuture.completedFuture(new AiTransportResponse("{\"command\":\"op @a\"}".getBytes(StandardCharsets.UTF_8),System.nanoTime())),scheduler,System::nanoTime,tick::get,()->HASH,new OptionalAiAdvisor.Policy(TimeUnit.SECONDS.toNanos(1),0,2,100,100,128),new ResourceId("test:ai"),"v1")) {
                check(await(advisor.propose(input)).fallbackReason().orElseThrow()==ReasonCode.AI_INVALID,"malicious transport fallback");
            }
            AtomicLong duringDecode=new AtomicLong(100);
            try(var advisor=new OptionalAiAdvisor(r -> CompletableFuture.completedFuture(new AiTransportResponse(proposal(r.input()),100)),scheduler,duringDecode::get,tick::get,()->{duringDecode.set(2000000000);return HASH;},new OptionalAiAdvisor.Policy(1000000000,0,2,100,100,128),new ResourceId("test:ai"),"v1")) {
                check(await(advisor.propose(input(1000000000,HASH))).fallbackReason().orElseThrow()==ReasonCode.AI_TIMEOUT,"deadline rechecked after decode");
            }
            rejected(() -> new HttpAiTransport(java.net.URI.create("http://localhost/v1/director/propose"),Optional::empty,System::nanoTime,scheduler,java.time.Duration.ofSeconds(1)),"explicit secure endpoint");
        }finally{scheduler.shutdownNow();}
        System.out.println("PASS T-AI disabled/strict JSON/hash/bounds/stale/timeout/late/circuit/concurrency");
    }
    static LocationImport rehash(LocationImport i,Revision revision,List<ImportedRegion> r,List<ImportedLocation> l,List<ImportedAnchor> a){
        var n=new LocationImport(1,i.worldId(),i.sourceNamespace(),revision,HASH,r,l,a);
        return new LocationImport(1,n.worldId(),n.sourceNamespace(),revision,StandaloneLocationImporter.digest(n),r,l,a);
    }
    static void locations(Path root,Path fixture)throws Exception{
        var executor=Executors.newSingleThreadExecutor();
        try{
            var input=new ModelCodec<>(LocationImport.class).decode(Files.readAllBytes(fixture));
            check(input.exportDigest().equals(StandaloneLocationImporter.digest(input)),"authentic fixture digest");
            var border=Map.of(new DimensionId("minecraft:overworld"),new ImportBounds(-1000,-64,-1000,1000,320,1000));
            var importer=await(StandaloneLocationImporter.open(root,input.worldId(),border,t->t.value().startsWith("proutlost:"),executor,16777216,20000));
            check(await(importer.submit(input)).status()==AckStatus.DURABLE,"import install");var pinned=importer.snapshot();
            check(await(importer.submit(input)).status()==AckStatus.DURABLE,"identical import");
            var badDigest=new LocationImport(1,input.worldId(),input.sourceNamespace(),new Revision(2),HASH,input.regions(),input.locations(),input.anchors());
            check(await(importer.submit(badDigest)).status()==AckStatus.REJECTED,"bad digest");
            var duplicate=rehash(input,new Revision(2),List.of(input.regions().getFirst(),input.regions().getFirst()),input.locations(),input.anchors());
            check(await(importer.submit(duplicate)).status()==AckStatus.REJECTED,"duplicate id");
            var broken=rehash(input,new Revision(2),List.of(),input.locations(),input.anchors());
            check(await(importer.submit(broken)).status()==AckStatus.REJECTED,"missing reference");
            var old=input.anchors().getFirst();
            var outside=new ImportedAnchor(old.anchorId(),old.locationId(),old.dimension(),new ImportPosition(10000,65,0),old.tags());
            check(await(importer.submit(rehash(input,new Revision(2),input.regions(),input.locations(),List.of(outside)))).status()==AckStatus.REJECTED,"anchor border");
            var unknown=new ImportedRegion(input.regions().getFirst().regionId(),new DimensionId("unknown:dimension"),input.regions().getFirst().bounds(),Set.of());
            check(await(importer.submit(rehash(input,new Revision(2),List.of(unknown),List.of(),List.of()))).status()==AckStatus.REJECTED,"unknown dimension");
            check(importer.snapshot()==pinned,"invalid imports atomic");
            var empty=rehash(input,new Revision(2),List.of(),List.of(),List.of());
            check(await(importer.submit(empty)).status()==AckStatus.DURABLE,"revision install");
            check(importer.snapshot().historicalInactive().size()==3,"removed entries historical inactive");
            check(pinned.current().get(input.sourceNamespace()).anchors().size()==1,"running instance pinned snapshot");
            check(await(importer.submit(input)).status()==AckStatus.REJECTED,"revision rollback rejected");
            try{await(StandaloneLocationImporter.open(root,input.worldId(),border,t->true,executor,16777216,20000));throw new AssertionError("two writers");}catch(ExecutionException expected){assertions++;}
            await(importer.closeAsync());
            var restarted=await(StandaloneLocationImporter.open(root,input.worldId(),border,t->true,executor,16777216,20000));
            check(restarted.snapshot().equals(importer.snapshot()),"restore committed import");
            byte[] manifest=Files.readAllBytes(root.resolve("CURRENT.json"));
            Files.writeString(root.resolve("orphan.tmp"),"incomplete transaction");
            await(restarted.closeAsync());
            restarted=await(StandaloneLocationImporter.open(root,input.worldId(),border,t->true,executor,16777216,20000));
            check(restarted.snapshot().equals(importer.snapshot()),"uncommitted temp ignored");
            check(Arrays.equals(manifest,Files.readAllBytes(root.resolve("CURRENT.json"))),"restore does not rewrite");
            var changedIdentity=new ImportedLocation(new LocationId(UUID.randomUUID()),input.locations().getFirst().externalId(),Set.of(),input.locations().getFirst().dimension(),input.locations().getFirst().bounds(),Set.of());
            check(await(restarted.submit(rehash(input,new Revision(3),List.of(),List.of(changedIdentity),List.of()))).status()==AckStatus.REJECTED,"historical external identity retained");
            var otherSource=new LocationImport(1,input.worldId(),new ResourceId("other:export"),new Revision(1),HASH,input.regions(),input.locations(),input.anchors());
            check(await(restarted.submit(rehash(otherSource,new Revision(1),otherSource.regions(),otherSource.locations(),otherSource.anchors()))).status()==AckStatus.REJECTED,"historical source collision");
            String malformed=new String(Files.readAllBytes(fixture),StandardCharsets.UTF_8).replaceFirst("\\{","{\"schema_version\":1,");
            try{await(restarted.submitJson(malformed.getBytes(StandardCharsets.UTF_8)));throw new AssertionError("duplicate import keys");}catch(ExecutionException expected){assertions++;}
            check(restarted.snapshot().equals(importer.snapshot()),"bad JSON unchanged");
            await(restarted.closeAsync());
            var archive=(JsonValue.ObjectValue)((JsonValue.ObjectValue)CanonicalJsonV1.decode(manifest)).values().get("imports");
            String archiveName=((JsonValue.Text)archive.values().values().iterator().next()).value();
            Files.writeString(root.resolve(archiveName),"corrupt");
            try{await(StandaloneLocationImporter.open(root,input.worldId(),border,t->true,executor,16777216,20000));throw new AssertionError("corrupt restore");}catch(ExecutionException expected){assertions++;}

        }finally{executor.shutdownNow();}
        System.out.println("PASS location import digest/references/dimension/border/revision/atomic/history/reopen");
    }
    static DirectorRequest request(RequestId id,String key,FulfillmentMode mode){
        return new DirectorRequest(new MessageId(UUID.randomUUID()),WORLD,PRODUCER,key,SCOPE,new CorrelationId(UUID.randomUUID()),Optional.empty(),new Sequence(1),new Tick(10),
                new DirectorRequirement(id,new AnyOfEvents(Set.of(new DefinitionId("test:event"))),new TargetConstraints(Set.of(),Set.of()),new FactMatch(new FactType("test:done"),SCOPE,EvidenceStatus.CONFIRMED,Map.of()),mode));
    }
    static DirectorEnvelope fact(EventInstanceId event,EvidenceStatus evidence){
        var fact=new DirectorFact(new FactId(UUID.randomUUID()),new FactType("test:done"),List.of(SCOPE),Optional.of(event),evidence,Map.of(),true,Optional.empty());
        return new DirectorEnvelope(1,new MessageId(UUID.randomUUID()),WORLD,new ResourceId("test:runtime"),UUID.randomUUID().toString(),SCOPE,new CorrelationId(UUID.randomUUID()),Optional.empty(),new Sequence(1),Optional.of(new Sequence(5)),new Tick(20),PayloadType.DirectorFact,1,new FactPayload(fact));
    }
    static void quest()throws Exception{
        var executor=Executors.newSingleThreadExecutor();var store=new MemoryStore();var bus=new PullBus(store.session);AtomicLong tick=new AtomicLong(100);
        try{
            var adapter=new DurableQuestAdapter(store,bus,PRODUCER,executor,tick::get,32,.5);await(adapter.restore());
            RequestId id=new RequestId(UUID.randomUUID());var request=request(id,"one",FulfillmentMode.NEW_EXECUTION);
            store.hold=true;var pending=adapter.submit(request);
            var held=store.pendingBatches.poll(2,TimeUnit.SECONDS);check(held!=null,"append submitted");
            check(!pending.toCompletableFuture().isDone(),"no ACK before durability");
            try{adapter.inspect(id);throw new AssertionError("uncommitted inspect");}catch(NoSuchElementException expected){assertions++;}
            check(held.records().stream().anyMatch(r->r instanceof DurableInbox) && held.records().stream().anyMatch(r->r instanceof DurableOutbox),"request inbox and outbox atomic");
            var duplicatePending=adapter.submit(request);
            store.force(held);store.hold=false;var accepted=await(pending);
            check(await(duplicatePending).equals(accepted),"duplicate while commit pending");check(accepted.state()==RequestStatus.ACCEPTED,"durable accepted");
            int count=store.records.size();check(await(adapter.submit(request)).equals(accepted) && store.records.size()==count,"duplicate no write");
            check(await(adapter.submit(request(new RequestId(UUID.randomUUID()),"one",FulfillmentMode.NEW_EXECUTION))).reasons().contains(ReasonCode.IDEMPOTENCY_CONFLICT),"key payload conflict");
            var event=new EventInstanceId(UUID.randomUUID());
            check(await(adapter.acceptFact(id,fact(event,EvidenceStatus.CONFIRMED))).state()==RequestStatus.ACCEPTED,"new execution rejects historical fact");
            var waiting=await(adapter.update(id,accepted.revision(),RequestStatus.WAITING_SAFETY,List.of(ReasonCode.SAFETY_COOLDOWN)));
            check(waiting.state()==RequestStatus.WAITING_SAFETY,"safety wait durable");
            var bound=await(adapter.bindExecution(id,waiting.revision(),event));
            check(await(adapter.acceptFact(id,fact(event,EvidenceStatus.POSSIBLE))).state()==RequestStatus.SCHEDULED,"possible not fulfillment");
            var confirmed=fact(event,EvidenceStatus.CONFIRMED);var fulfilled=await(adapter.acceptFact(id,confirmed));
            check(fulfilled.state()==RequestStatus.FULFILLED && fulfilled.factRefs().size()==1,"confirmed fulfillment");
            count=store.records.size();check(await(adapter.acceptFact(id,confirmed)).equals(fulfilled) && store.records.size()==count,"duplicate fact no progression");
            var cancelReq=request(new RequestId(UUID.randomUUID()),"two",FulfillmentMode.ALLOW_EXISTING_FACT);var c=await(adapter.submit(cancelReq));
            var cancel=new CancelRequest(c.requestId(),c.revision(),"cancel-two");var cancelled=await(adapter.cancel(cancel));
            check(cancelled.state()==RequestStatus.CANCELLED,"cancel durable");check(await(adapter.cancel(cancel)).equals(cancelled),"cancel duplicate");
            check(await(adapter.acceptFact(c.requestId(),confirmed)).state()==RequestStatus.CANCELLED,"cancel cannot fulfill later");
            var existing=request(new RequestId(UUID.randomUUID()),"three",FulfillmentMode.ALLOW_EXISTING_FACT);await(adapter.submit(existing));
            check(await(adapter.acceptFact(RequestContracts.id(existing.payload()),confirmed)).state()==RequestStatus.FULFILLED,"allow existing confirmed");
            var restarted=new DurableQuestAdapter(store,bus,PRODUCER,executor,tick::get,32,.5);await(restarted.restore());
            check(restarted.inspect(id).state().status()==RequestStatus.FULFILLED,"restore state");check(await(restarted.submit(request)).equals(fulfilled),"restore idempotence");
            var preference=new DirectorRequest(new MessageId(UUID.randomUUID()),WORLD,PRODUCER,"prefer",SCOPE,new CorrelationId(UUID.randomUUID()),Optional.empty(),new Sequence(1),new Tick(100),new DirectorPreference(new RequestId(UUID.randomUUID()),new AnyOfEvents(Set.of(new DefinitionId("test:event"))),.2,new Tick(120)));
            var pref=await(adapter.submit(preference));tick.set(121);check(await(adapter.expirePreference(pref.requestId())).state()==RequestStatus.EXPIRED,"preference expiry");
            var consumer=new ConsumerSpec(new ConsumerId(UUID.randomUUID()),Set.of(),new Sequence(0),1);bus.messages.add(confirmed);
            check(adapter.poll(consumer).batch().equals(adapter.poll(consumer).batch()) && bus.acks==0,"slow consumer redelivery no implicit ack");
            await(adapter.acknowledge(new ConsumerAck(consumer.consumerId(),Set.of(confirmed.messageId()),new Sequence(5),new Sequence(0))));check(bus.acks==1,"explicit consumer ACK");
            store.hold=true;
            var staleRequest=request(new RequestId(UUID.randomUUID()),"stale-ack",FulfillmentMode.NEW_EXECUTION);
            var stale=adapter.submit(staleRequest);var staleBatch=store.pendingBatches.poll(2,TimeUnit.SECONDS);
            check(staleBatch!=null,"stale ACK pending");
            store.pending.remove(staleBatch.transactionId()).complete(new CommitAck(new TransactionId(UUID.randomUUID()),store.session.storeGeneration(),new Sequence(99),staleBatch.transactionHash(),AckStatus.DURABLE,Optional.empty(),Set.of()));
            check(await(stale).reasons().contains(ReasonCode.WAL_UNAVAILABLE),"wrong transaction ACK rejected");store.hold=false;
            try{await(adapter.submit(staleRequest));throw new AssertionError("uncertain ACK must fence writes");}catch(ExecutionException expected){assertions++;}
            await(adapter.restore());
            store.fail=true;var failed=await(adapter.submit(request(new RequestId(UUID.randomUUID()),"failure",FulfillmentMode.NEW_EXECUTION)));
            check(failed.reasons().contains(ReasonCode.WAL_UNAVAILABLE),"failed store not success");
        }finally{executor.shutdownNow();}
        System.out.println("PASS T-QST durable ACK/idempotence/fulfillment/safety/cancel/restore/slow consumer");
    }
    static DirectorRequest cancellation(DirectorRequest target,Revision revision,String key) {
        return new DirectorRequest(new MessageId(UUID.randomUUID()),WORLD,PRODUCER,key,target.scope(),target.correlationId(),Optional.empty(),new Sequence(2),new Tick(100),new CancelPayload(RequestContracts.id(target.payload()),revision));
    }
    static DirectorRequest ordinary(boolean prefer,String key) {
        var r=request(new RequestId(UUID.randomUUID()),key,FulfillmentMode.ALLOW_EXISTING_FACT);
        if(!prefer)return r;
        return new DirectorRequest(r.messageId(),r.worldId(),r.producerId(),r.producerKey(),r.scope(),r.correlationId(),r.causationId(),r.producerSequence(),r.createdTick(),new DirectorPreference(RequestContracts.id(r.payload()),((DirectorRequirement)r.payload()).selector(),.2,new Tick(1000)));
    }
    static void questPolicies101() throws Exception {
        var executor=Executors.newSingleThreadExecutor();
        var legacy=RequestSchedulingPolicy.legacyWire();
        var explicit=new RequestSchedulingPolicy(new NarrativePriority(17),PreferenceDecay.LINEAR_TO_TTL,RequestPolicySource.AUTHORIZED_ADAPTER);
        var normal=new RequestSchedulingPolicy(NarrativePriority.NORMAL,PreferenceDecay.LINEAR_TO_TTL,RequestPolicySource.AUTHORIZED_ADAPTER);
        try {
            for(boolean prefer:List.of(false,true))for(var policy:List.of(legacy,explicit,normal)) {
                var store=new MemoryStore();var bus=new PullBus(store.session);var tick=new AtomicLong(100);
                var adapter=new DurableQuestAdapter(store,bus,PRODUCER,executor,tick::get,32,.5);await(adapter.restore());
                var request=ordinary(prefer,"policy-request");
                if(prefer)for(long created:List.of(1000L,1001L)) {
                    var invalid=ordinary(true,"invalid-ttl-"+created);
                    invalid=new DirectorRequest(invalid.messageId(),invalid.worldId(),invalid.producerId(),invalid.producerKey(),invalid.scope(),invalid.correlationId(),invalid.causationId(),invalid.producerSequence(),new Tick(created),invalid.payload());
                    int writes=store.appendCalls.get();
                    check(await(adapter.submit(invalid,policy)).reasons().contains(ReasonCode.STALE_CONTEXT),"nonpositive original TTL rejected");
                    check(store.appendCalls.get()==writes,"invalid TTL never persisted");
                }
                store.hold=true;
                var pending=policy.equals(legacy)?adapter.submit(request):adapter.submit(request,policy);
                var batch=store.pendingBatches.poll(2,TimeUnit.SECONDS);
                check(batch!=null && !pending.toCompletableFuture().isDone(),"policy waits for durable ACK");
                var durable=(DurableRequest)batch.records().stream().filter(r->r instanceof DurableRequest).findFirst().orElseThrow();
                check(durable.request().state().schedulingPolicy().equals(policy),"policy explicit in durable state");
                check(durable.schedulingPolicyHash().equals(DurableRequest.policyHash(policy)),"separate durable policy hash");
                check(durable.idempotenceHash().equals(RequestContracts.idempotenceHash(request)),"wire semantic hash unchanged");
                var codec=new ModelCodec<>(DurableRecord.class);
                check(codec.decode(codec.encode(durable)).equals(durable),"policy durable codec roundtrip");
                String wire=new String(DirectorMessageCodec.encode(durable.original()),StandardCharsets.UTF_8);
                check(!wire.contains("scheduling_policy") && !wire.contains("narrative_priority"),"closed wire remains policy-free");
                check(MessageContracts.request(DirectorMessageCodec.decode(wire.getBytes(StandardCharsets.UTF_8))).equals(request),"wire roundtrip unchanged");
                String extended=wire.replaceFirst("\\{","{\"scheduling_policy\":{},");
                rejected(()->DirectorMessageCodec.decode(extended.getBytes(StandardCharsets.UTF_8)),"wire rejects policy injection");
                store.force(batch);store.hold=false;var current=await(pending);var id=current.requestId();
                if(prefer) {
                    long created=request.createdTick().value(),expires=((DirectorPreference)request.payload()).expiresTick().value();
                    check(policy.preferenceMultiplier(new Tick(created),new Tick(expires),new Tick(created)).value()==1,"decay starts at original created tick");
                    check(policy.preferenceMultiplier(new Tick(created),new Tick(expires),new Tick(expires)).value()==0,"decay reaches zero at TTL");
                    long midpoint=created+(expires-created)/2;
                    check(Math.abs(policy.preferenceMultiplier(new Tick(created),new Tick(expires),new Tick(midpoint)).value()-(double)(expires-midpoint)/(expires-created))<1e-12,"linear decay uses original TTL");
                }
                for(int pass=0;pass<2;pass++) {
                    if(pass==1){adapter=new DurableQuestAdapter(store,bus,PRODUCER,executor,tick::get,32,.5);await(adapter.restore());}
                    var frozen=adapter.inspect(id);var records=List.copyOf(store.records);int writes=store.appendCalls.get();
                    check(frozen.state().schedulingPolicy().equals(policy),"policy retained before/after restore");
                    check(await(adapter.submit(request,policy)).equals(current),"identical policy replay");
                    for(var other:List.of(legacy,explicit,normal))if(!other.equals(policy))
                        check(await(adapter.submit(request,other)).reasons().contains(ReasonCode.IDEMPOTENCY_CONFLICT),"different rank/source conflicts");
                    check(store.appendCalls.get()==writes && store.records.equals(records) && adapter.inspect(id).equals(frozen),"policy replay/conflicts append nothing");
                    current=await(adapter.update(id,current.revision(),pass==0?RequestStatus.WAITING_SAFETY:RequestStatus.SCHEDULED,List.of()));
                    check(adapter.inspect(id).state().schedulingPolicy().equals(policy),"state update preserves policy");
                }
                await(adapter.bindExecution(id,current.revision(),new EventInstanceId(UUID.randomUUID())));
                check(adapter.inspect(id).state().schedulingPolicy().equals(policy),"binding preserves policy");
                if(prefer){tick.set(1001);current=await(adapter.expirePreference(id));}
                else current=await(adapter.acceptFact(id,fact(new EventInstanceId(UUID.randomUUID()),EvidenceStatus.CONFIRMED)));
                check(current.state()==(prefer?RequestStatus.EXPIRED:RequestStatus.FULFILLED),"policy request reaches terminal state");
                check(adapter.inspect(id).state().schedulingPolicy().equals(policy),"terminal transition preserves policy");
                var cancel=cancellation(request,current.revision(),"policy-cancel");
                var receipt=await(adapter.submit(cancel,policy));
                for(int pass=0;pass<2;pass++) {
                    if(pass==1){adapter=new DurableQuestAdapter(store,bus,PRODUCER,executor,tick::get,32,.5);await(adapter.restore());}
                    var records=List.copyOf(store.records);int writes=store.appendCalls.get();
                    check(await(adapter.submit(cancel,policy)).equals(receipt),"cancel policy replay");
                    for(var other:List.of(legacy,explicit,normal))if(!other.equals(policy))
                        check(await(adapter.submit(cancel,other)).reasons().contains(ReasonCode.IDEMPOTENCY_CONFLICT),"cancel policy collision");
                    check(store.records.equals(records) && store.appendCalls.get()==writes,"cancel policy replay/conflicts append nothing");
                }
                var active=ordinary(prefer,"active-policy-cancel");
                tick.set(100);var activeAck=await(adapter.submit(active,policy));
                var activeCancel=cancellation(active,activeAck.revision(),"active-cancel-key");
                var cancelled=await(adapter.submit(activeCancel,policy));
                check(cancelled.state()==RequestStatus.CANCELLED && adapter.inspect(activeAck.requestId()).state().schedulingPolicy().equals(policy),"active cancellation retains policy");
                adapter=new DurableQuestAdapter(store,bus,PRODUCER,executor,tick::get,32,.5);await(adapter.restore());
                var snapshot=List.copyOf(store.records);int appendCount=store.appendCalls.get();
                check(await(adapter.submit(activeCancel,policy)).equals(cancelled),"active cancellation policy restored");
                var other=policy.equals(explicit)?legacy:explicit;
                check(await(adapter.submit(activeCancel,other)).reasons().contains(ReasonCode.IDEMPOTENCY_CONFLICT),"active cancellation changed policy conflicts");
                check(store.records.equals(snapshot) && store.appendCalls.get()==appendCount,"restored active cancel replay writes nothing");
                for(var record:store.records)if(record instanceof DurableRequest r)
                    check(r.request().state().schedulingPolicy().equals(policy) && r.schedulingPolicyHash().equals(DurableRequest.policyHash(policy)),"all durable revisions retain original policy/hash");
            }
        } finally {executor.shutdownNow();}
        System.out.println("PASS T-QST API1.0.1 explicit/default policies, durable hashes, closed wire, replay/conflicts/restore, original TTL decay and state transitions");
    }
    static void questScheduledBindings() throws Exception {
        var executor=Executors.newSingleThreadExecutor();
        try {
            for(boolean scheduleByUpdate:List.of(true,false)) {
                var store=new MemoryStore();var bus=new PullBus(store.session);
                var adapter=new DurableQuestAdapter(store,bus,PRODUCER,executor,()->100,32,.5);await(adapter.restore());
                var id=new RequestId(UUID.randomUUID());
                var accepted=await(adapter.submit(request(id,"binding-target",FulfillmentMode.NEW_EXECUTION)));
                var seed=new EventInstanceId(UUID.randomUUID());
                var scheduled=scheduleByUpdate
                        ?await(adapter.update(id,accepted.revision(),RequestStatus.SCHEDULED,List.of()))
                        :await(adapter.bindExecution(id,accepted.revision(),seed));
                check(scheduled.state()==RequestStatus.SCHEDULED,"binding fixture scheduled");
                var frozen=adapter.inspect(id);var events=new ArrayList<EventInstanceId>();
                if(!scheduleByUpdate)events.add(seed);
                for(int pass=0;pass<2;pass++) {
                    if(pass==1){adapter=new DurableQuestAdapter(store,bus,PRODUCER,executor,()->100,32,.5);await(adapter.restore());}
                    for(int binding=0;binding<2;binding++) {
                        var event=new EventInstanceId(UUID.randomUUID());
                        var before=List.copyOf(store.records);int writes=store.appendCalls.get();
                        store.hold=true;var pending=adapter.bindExecution(id,scheduled.revision(),event);
                        var batch=store.pendingBatches.poll(2,TimeUnit.SECONDS);
                        check(batch!=null && !pending.toCompletableFuture().isDone(),"scheduled binding waits for durable ACK");
                        check(adapter.inspect(id).equals(frozen) && store.records.equals(before),"pending binding leaves projection and records unchanged");
                        check(batch.records().size()==1 && batch.records().getFirst() instanceof DurableFact,
                                "scheduled binding persists only one fact, no request/aggregate/outbox");
                        var fact=((DurableFact)batch.records().getFirst()).value();
                        check(fact.factType().equals(new FactType("proutlost_director:request_execution")) && fact.eventId().equals(Optional.of(event)),"binding fact identifies execution");
                        store.force(batch);store.hold=false;
                        check(await(pending).equals(scheduled) && adapter.inspect(id).equals(frozen),"binding preserves state revision and ACK");
                        check(store.appendCalls.get()==writes+1 && store.records.size()==before.size()+1,"one binding-only append");
                        check(store.records.subList(0,before.size()).equals(before),"prior outbox and records unchanged");
                        events.add(event);
                    }
                    var before=List.copyOf(store.records);int writes=store.appendCalls.get();
                    for(var event:events)check(await(adapter.bindExecution(id,scheduled.revision(),event)).equals(scheduled),"duplicate binding replay before/after restore");
                    check(await(adapter.bindExecution(id,accepted.revision(),new EventInstanceId(UUID.randomUUID()))).reasons().contains(ReasonCode.STALE_CONTEXT),"stale binding rejected");
                    check(adapter.inspect(id).equals(frozen) && store.records.equals(before) && store.appendCalls.get()==writes,"duplicate/stale bindings append nothing");
                }
                adapter=new DurableQuestAdapter(store,bus,PRODUCER,executor,()->100,32,.5);await(adapter.restore());
                check(await(adapter.acceptFact(id,fact(events.getLast(),EvidenceStatus.CONFIRMED))).state()==RequestStatus.FULFILLED,
                        "restored binding-only fact authorizes NEW_EXECUTION fulfillment");
            }
        } finally {executor.shutdownNow();}
    }
    static void questKeyAndTransitionContracts() throws Exception {
        var executor=Executors.newSingleThreadExecutor();
        try {
            for(boolean prefer:List.of(false,true))for(boolean direct:List.of(false,true)) {
                var store=new MemoryStore();var bus=new PullBus(store.session);
                var adapter=new DurableQuestAdapter(store,bus,PRODUCER,executor,()->100,32,.5);await(adapter.restore());
                var r=ordinary(prefer,"ordinary-key");var accepted=await(adapter.submit(r));
                var target=ordinary(false,"target");var targetAck=await(adapter.submit(target));
                var cancelFirst=ordinary(false,"cancel-first-target");var cancelTargetAck=await(adapter.submit(cancelFirst));
                var cancelRequest=cancellation(cancelFirst,cancelTargetAck.revision(),"cancel-first-key");
                var cancelled=await(direct?adapter.cancel(new CancelRequest(cancelTargetAck.requestId(),cancelTargetAck.revision(),"cancel-first-key")):adapter.submit(cancelRequest));
                check(cancelled.state()==RequestStatus.CANCELLED,"first cancellation committed");
                for(int pass=0;pass<2;pass++) {
                    if(pass==1){adapter=new DurableQuestAdapter(store,bus,PRODUCER,executor,()->100,32,.5);await(adapter.restore());}
                    int writes=store.appendCalls.get();var records=List.copyOf(store.records);
                    var oldTarget=adapter.inspect(targetAck.requestId());var oldOrdinary=adapter.inspect(accepted.requestId());
                    var conflict=await(direct?adapter.cancel(new CancelRequest(targetAck.requestId(),targetAck.revision(),"ordinary-key")):adapter.submit(cancellation(target,targetAck.revision(),"ordinary-key")));
                    check(conflict.reasons().equals(List.of(ReasonCode.IDEMPOTENCY_CONFLICT)),"ordinary then cancel key collision");
                    check(adapter.inspect(targetAck.requestId()).equals(oldTarget) && adapter.inspect(accepted.requestId()).equals(oldOrdinary),"conflicting cancel preserves target and original request");
                    check(await(adapter.submit(ordinary(prefer,"cancel-first-key"))).reasons().equals(List.of(ReasonCode.IDEMPOTENCY_CONFLICT)),"cancel then ordinary key collision");
                    check(await(adapter.submit(cancellation(target,targetAck.revision(),"cancel-first-key"))).reasons().equals(List.of(ReasonCode.IDEMPOTENCY_CONFLICT)),"same cancellation key different target conflicts");
                    check(await(adapter.submit(cancellation(cancelFirst,cancelTargetAck.revision().plus(1),"cancel-first-key"))).reasons().equals(List.of(ReasonCode.IDEMPOTENCY_CONFLICT)),"same cancellation key different revision conflicts");
                    check(await(adapter.submit(cancelRequest)).equals(cancelled),"cancellation envelope replay retains original durable ACK");
                    check(await(adapter.cancel(new CancelRequest(cancelTargetAck.requestId(),cancelTargetAck.revision(),"cancel-first-key"))).equals(cancelled),"direct cancellation replay shares key and ACK");
                    check(await(adapter.submit(r)).equals(accepted),"ordinary key replay retains ACK");
                    check(store.appendCalls.get()==writes && store.records.equals(records),"key conflicts and replay append nothing");
                }
                // Prior J08 stores have the synthetic cancellation inbox but no new receipt fact.
                store.records.removeIf(record -> record instanceof DurableFact f && f.value().factType().equals(new FactType("proutlost_director:request_cancel_receipt")));
                adapter=new DurableQuestAdapter(store,bus,PRODUCER,executor,()->100,32,.5);await(adapter.restore());
                int legacyWrites=store.appendCalls.get();
                check(await(adapter.submit(ordinary(prefer,"cancel-first-key"))).reasons().equals(List.of(ReasonCode.IDEMPOTENCY_CONFLICT)),"legacy durable cancellation inbox reserves key");
                check(await(adapter.submit(cancelRequest)).equals(cancelled),"legacy cancellation ACK replay");
                check(store.appendCalls.get()==legacyWrites,"legacy cancellation replay appends nothing");
                // Exercise all allowed nonterminal update states, then replay each with its current revision.
                for(var state:List.of(RequestStatus.WAITING_CONTEXT,RequestStatus.WAITING_SAFETY,RequestStatus.SCHEDULED,RequestStatus.EXECUTING)) {
                    var before=adapter.inspect(accepted.requestId());
                    var changed=await(adapter.update(accepted.requestId(),before.revision(),state,List.of(ReasonCode.SAFETY_COOLDOWN)));
                    check(changed.state()==state,"valid transition accepted");
                    for(int pass=0;pass<2;pass++) {
                        if(pass==1){adapter=new DurableQuestAdapter(store,bus,PRODUCER,executor,()->100,32,.5);await(adapter.restore());}
                        var frozen=adapter.inspect(accepted.requestId());int writes=store.appendCalls.get();var records=List.copyOf(store.records);
                        var repeated=await(adapter.update(accepted.requestId(),frozen.revision(),state,List.of(ReasonCode.BUDGET_EXHAUSTED)));
                        check(repeated.reasons().equals(List.of(ReasonCode.STALE_CONTEXT)),"same-state update rejected by frozen guard");
                        check(repeated.state()==state && repeated.revision().equals(frozen.revision()) && adapter.inspect(accepted.requestId()).equals(frozen),"same-state update preserves revision/state/reasons");
                        check(store.appendCalls.get()==writes && store.records.equals(records),"same-state update appends no state fact/outbox");
                    }
                }
            }
        } finally {executor.shutdownNow();}
        System.out.println("PASS T-QST frozen update guard and producer-key collisions both orders, PREFER/REQUIRE, direct/enveloped cancellation, durable replay after restore");
    }
    static void terminalReceiptOnly(DurableQuestAdapter adapter,MemoryStore store,RequestView frozen,List<DurableRecord> before,int appends,boolean fresh,String label) {
        var added=store.records.subList(before.size(),store.records.size());
        check(store.appendCalls.get()==appends+(fresh?1:0),label+" exactly one receipt commit for fresh key");
        check(adapter.inspect(frozen.state().requestId()).equals(frozen),label+" unchanged terminal view");
        check(added.stream().allMatch(r -> r instanceof DurableInbox || (r instanceof DurableFact f && f.value().factType().equals(new FactType("proutlost_director:request_cancel_receipt")))),label+" no request state/aggregate/transition outbox written");
        check(!fresh || (added.stream().filter(r -> r instanceof DurableFact).count()==1 && added.stream().anyMatch(r -> r instanceof DurableInbox)),label+" durable receipt and inbox together");
    }
    static void questTerminalCancellationReceipts() throws Exception {
        var executor=Executors.newSingleThreadExecutor();
        try {
            for(var status:List.of(RequestStatus.CANCELLED,RequestStatus.CONFLICT,RequestStatus.FULFILLED,RequestStatus.EXPIRED))for(boolean direct:List.of(false,true)) {
                var store=new MemoryStore();var bus=new PullBus(store.session);var tick=new AtomicLong(100);
                var adapter=new DurableQuestAdapter(store,bus,PRODUCER,executor,tick::get,32,.5);await(adapter.restore());
                var target=ordinary(status==RequestStatus.EXPIRED,"terminal-target");var accepted=await(adapter.submit(target));
                RequestAck terminal=switch(status) {
                    case CANCELLED -> await(adapter.cancel(new CancelRequest(accepted.requestId(),accepted.revision(),"initial-cancel")));
                    case CONFLICT -> await(adapter.update(accepted.requestId(),accepted.revision(),status,List.of(ReasonCode.REQUIREMENT_UNSATISFIABLE)));
                    case FULFILLED -> await(adapter.acceptFact(accepted.requestId(),fact(new EventInstanceId(UUID.randomUUID()),EvidenceStatus.CONFIRMED)));
                    case EXPIRED -> {tick.set(1001);yield await(adapter.expirePreference(accepted.requestId()));}
                    default -> throw new AssertionError(status);
                };
                check(terminal.state()==status,"terminal fixture reached");
                var active=ordinary(false,"active-target");var activeAck=await(adapter.submit(active));
                var frozen=adapter.inspect(terminal.requestId());var frozenActive=adapter.inspect(activeAck.requestId());
                var message=cancellation(target,terminal.revision(),"fresh-terminal-key");
                var cancel=new CancelRequest(terminal.requestId(),terminal.revision(),"fresh-terminal-key");
                var before=List.copyOf(store.records);int appends=store.appendCalls.get();store.hold=true;
                var pending=direct?adapter.cancel(cancel):adapter.submit(message);
                var batch=store.pendingBatches.poll(2,TimeUnit.SECONDS);
                check(batch!=null && !pending.toCompletableFuture().isDone(),"terminal cancellation waits for durable receipt ACK");
                check(adapter.inspect(terminal.requestId()).equals(frozen) && store.records.equals(before),"pending receipt changes no state");
                store.force(batch);store.hold=false;
                check(await(pending).equals(terminal),"terminal receipt returns original ACK");
                terminalReceiptOnly(adapter,store,frozen,before,appends,true,"fresh terminal "+status);
                for(int pass=0;pass<2;pass++) {
                    if(pass==1){adapter=new DurableQuestAdapter(store,bus,PRODUCER,executor,tick::get,32,.5);await(adapter.restore());}
                    var records=List.copyOf(store.records);int writes=store.appendCalls.get();
                    check(await(adapter.cancel(cancel)).equals(terminal),"terminal direct replay ACK");
                    check(await(adapter.submit(message)).equals(terminal),"terminal envelope replay ACK");
                    check(await(adapter.cancel(new CancelRequest(activeAck.requestId(),activeAck.revision(),cancel.producerKey()))).reasons().contains(ReasonCode.IDEMPOTENCY_CONFLICT),"terminal key cannot cancel active target directly");
                    check(await(adapter.submit(cancellation(active,activeAck.revision(),cancel.producerKey()))).reasons().contains(ReasonCode.IDEMPOTENCY_CONFLICT),"terminal key cannot cancel active target by envelope");
                    check(await(adapter.submit(cancellation(target,terminal.revision().plus(1),cancel.producerKey()))).reasons().contains(ReasonCode.IDEMPOTENCY_CONFLICT),"terminal key cannot change expected revision");
                    var changedScope=new DirectorRequest(new MessageId(UUID.randomUUID()),message.worldId(),message.producerId(),message.producerKey(),new ScopeRef(ScopeKind.PLAYER,UUID.randomUUID()),message.correlationId(),message.causationId(),message.producerSequence(),message.createdTick(),message.payload());
                    check(await(adapter.submit(changedScope)).reasons().contains(ReasonCode.IDEMPOTENCY_CONFLICT),"terminal key cannot change scope");
                    check(await(adapter.submit(ordinary(false,cancel.producerKey()))).reasons().contains(ReasonCode.IDEMPOTENCY_CONFLICT),"terminal key cannot become REQUIRE");
                    check(await(adapter.submit(ordinary(true,cancel.producerKey()))).reasons().contains(ReasonCode.IDEMPOTENCY_CONFLICT),"terminal key cannot become PREFER");
                    check(adapter.inspect(activeAck.requestId()).equals(frozenActive) && adapter.inspect(terminal.requestId()).equals(frozen),"terminal receipt collisions preserve both targets");
                    check(store.appendCalls.get()==writes && store.records.equals(records),"terminal replay/collisions append nothing");
                }
            }
        } finally {executor.shutdownNow();}
        System.out.println("PASS T-QST fresh cancellation of all terminal states: receipt/inbox before ACK, no state transition, direct/envelope replay and key conflicts before/after restore");
    }
    static void questConflict() throws Exception {
        var executor=Executors.newSingleThreadExecutor();
        try {
            for (var mode:FulfillmentMode.values()) {
                var store=new MemoryStore();var bus=new PullBus(store.session);
                var adapter=new DurableQuestAdapter(store,bus,PRODUCER,executor,()->100,32,.5);
                await(adapter.restore());
                var request=request(new RequestId(UUID.randomUUID()),"terminal-conflict-"+mode,mode);
                var accepted=await(adapter.submit(request));
                var event=new EventInstanceId(UUID.randomUUID());
                // Even a fact for an already bound execution must not fulfill a later conflict.
                var bound=await(adapter.bindExecution(accepted.requestId(),accepted.revision(),event));
                var conflict=await(adapter.update(bound.requestId(),bound.revision(),RequestStatus.CONFLICT,List.of(ReasonCode.REQUIREMENT_UNSATISFIABLE)));
                check(conflict.state()==RequestStatus.CONFLICT && conflict.reasons().equals(List.of(ReasonCode.REQUIREMENT_UNSATISFIABLE)),"conflict recorded with reason");
                var frozen=adapter.inspect(conflict.requestId());
                var records=List.copyOf(store.records);int appends=store.appendCalls.get();
                var confirmed=fact(event,EvidenceStatus.CONFIRMED);
                for (int pass=0;pass<2;pass++) {
                    if(pass==1) {
                        adapter=new DurableQuestAdapter(store,bus,PRODUCER,executor,()->100,32,.5);
                        await(adapter.restore());
                        check(adapter.inspect(conflict.requestId()).equals(frozen),"restore preserves complete conflicted state");
                    }
                    for(var target:List.of(RequestStatus.WAITING_CONTEXT,RequestStatus.WAITING_SAFETY,RequestStatus.SCHEDULED,RequestStatus.EXECUTING,RequestStatus.CONFLICT)) {
                        check(!StateTransitions.allows(RequestStatus.CONFLICT,target,RequestKind.REQUIRE,false),"frozen contract forbids conflict update");
                        var ack=await(adapter.update(conflict.requestId(),conflict.revision(),target,List.of()));
                        unchangedConflict(adapter,store,frozen,records,appends,ack,"update "+target);
                        check(ack.reasons().equals(List.of(ReasonCode.STALE_CONTEXT)),"conflict update rejected");
                    }
                    var ack=await(adapter.bindExecution(conflict.requestId(),conflict.revision(),new EventInstanceId(UUID.randomUUID())));
                    unchangedConflict(adapter,store,frozen,records,appends,ack,"bind execution");
                    check(ack.reasons().equals(List.of(ReasonCode.STALE_CONTEXT)),"conflict binding rejected");
                    ack=await(adapter.cancel(new CancelRequest(conflict.requestId(),conflict.revision(),"conflict-cancel")));
                    terminalReceiptOnly(adapter,store,frozen,records,appends,pass==0,"cancel");
                    records=List.copyOf(store.records);appends=store.appendCalls.get();
                    unchangedConflict(adapter,store,frozen,records,appends,ack,"cancel");
                    check(ack.equals(conflict),"cancel retains original conflict ACK");
                    var cancelMessage=new DirectorRequest(new MessageId(UUID.randomUUID()),WORLD,PRODUCER,"conflict-envelope-cancel",SCOPE,new CorrelationId(UUID.randomUUID()),Optional.empty(),new Sequence(2),new Tick(100),new CancelPayload(conflict.requestId(),conflict.revision()));
                    ack=await(adapter.submit(cancelMessage));
                    terminalReceiptOnly(adapter,store,frozen,records,appends,pass==0,"cancel envelope");
                    records=List.copyOf(store.records);appends=store.appendCalls.get();
                    unchangedConflict(adapter,store,frozen,records,appends,ack,"cancel envelope");
                    ack=await(adapter.acceptFact(conflict.requestId(),confirmed));
                    unchangedConflict(adapter,store,frozen,records,appends,ack,"confirmed fulfillment");
                    check(ack.equals(conflict),"confirmed fact retains original conflict ACK");
                    ack=await(adapter.submit(request));
                    unchangedConflict(adapter,store,frozen,records,appends,ack,"duplicate request");
                }
            }
        } finally { executor.shutdownNow(); }
        System.out.println("PASS T-QST CONFLICT terminal: update/bind/cancel/confirmed fact/duplicate before and after restore, both fulfillment modes, receipt-only first cancellation and no replay append");
    }
    static void unchangedConflict(DurableQuestAdapter adapter, MemoryStore store, RequestView frozen,
            List<DurableRecord> records, int appends, RequestAck ack, String operation) {
        check(ack.state()==RequestStatus.CONFLICT && ack.revision().equals(frozen.revision()) && ack.factRefs().equals(frozen.state().resultFactIds()),operation+" ACK preserves conflict/revision/facts");
        check(adapter.inspect(frozen.state().requestId()).equals(frozen),operation+" preserves entire request including reason");
        check(store.records.equals(records) && store.appendCalls.get()==appends,operation+" appends no state/fact/inbox/outbox");
    }
    static void questBindingRestoreGuard() throws Exception {
        var executor=Executors.newSingleThreadExecutor();
        var store=new MemoryStore();var bus=new PullBus(store.session);
        try {
            var adapter=new DurableQuestAdapter(store,bus,PRODUCER,executor,()->100,32,.5);
            await(adapter.restore());
            var original=request(new RequestId(UUID.randomUUID()),"binding-guard",FulfillmentMode.NEW_EXECUTION);
            var accepted=await(adapter.submit(original));
            var event=new EventInstanceId(UUID.randomUUID());
            var scheduled=await(adapter.bindExecution(accepted.requestId(),accepted.revision(),event));
            var originalView=adapter.inspect(accepted.requestId());
            var authentic=store.records.stream()
                    .filter(r -> r instanceof DurableFact f && f.value().factType().equals(new FactType("proutlost_director:request_execution")))
                    .map(r -> ((DurableFact)r).value()).findFirst().orElseThrow();
            var foreignScope=new ScopeRef(ScopeKind.REGION,UUID.randomUUID());
            var forged=new ArrayList<DirectorFact>();
            forged.add(new DirectorFact(new FactId(UUID.randomUUID()),authentic.factType(),authentic.subjectRefs(),
                    authentic.eventId(),EvidenceStatus.POSSIBLE,authentic.payload(),true,Optional.empty()));
            forged.add(new DirectorFact(new FactId(UUID.randomUUID()),authentic.factType(),authentic.subjectRefs(),
                    authentic.eventId(),EvidenceStatus.CONFIRMED,authentic.payload(),false,Optional.empty()));
            forged.add(new DirectorFact(new FactId(UUID.randomUUID()),authentic.factType(),List.of(foreignScope),
                    authentic.eventId(),EvidenceStatus.CONFIRMED,authentic.payload(),true,Optional.empty()));
            forged.add(new DirectorFact(new FactId(UUID.randomUUID()),authentic.factType(),authentic.subjectRefs(),
                    Optional.empty(),EvidenceStatus.CONFIRMED,authentic.payload(),true,Optional.empty()));
            forged.add(new DirectorFact(new FactId(UUID.randomUUID()),authentic.factType(),authentic.subjectRefs(),
                    authentic.eventId(),EvidenceStatus.CONFIRMED,
                    Map.of("request_id",new JsonValue.Text(UUID.randomUUID().toString()),
                           "producer_id",new JsonValue.Text(PRODUCER.value())),true,Optional.empty()));
            var before=List.copyOf(store.records);int appends=store.appendCalls.get();
            for(var unsafe:forged) {
                var record=new DurableFact(unsafe);
                store.records.add(record);
                try { await(adapter.restore());throw new AssertionError("untrusted binding restored"); }
                catch(ExecutionException failure) {
                    check(failure.getCause() instanceof IllegalArgumentException,"binding restore refuses forged evidence");
                }
                check(adapter.inspect(accepted.requestId()).equals(originalView),"failed binding restore keeps prior projection");
                try { await(adapter.submit(original));throw new AssertionError("binding corruption reopened admission"); }
                catch(ExecutionException failure) {
                    check(failure.getCause() instanceof IllegalStateException &&
                            "restore required".equals(failure.getCause().getMessage()),"failed binding restore fences submissions");
                }
                check(store.appendCalls.get()==appends,"binding corruption performs zero writes");
                check(store.records.remove(record),"forged record removed from TEST_ONLY fixture");
                await(adapter.restore());
                check(store.records.equals(before),"recovery restores original fixture without edits");
            }
            // Pagination may return an authorized binding before its matching request.
            var bindingRecord=store.records.stream()
                    .filter(r -> r instanceof DurableFact f && f.value().equals(authentic))
                    .findFirst().orElseThrow();
            check(store.records.remove(bindingRecord),"reorder only TEST_ONLY binding fixture");
            store.records.add(0,bindingRecord);store.paged=true;
            await(adapter.restore());
            check(adapter.inspect(accepted.requestId()).equals(originalView),"out-of-order paginated binding restores after complete read");
            check(await(adapter.acceptFact(accepted.requestId(),fact(event,EvidenceStatus.CONFIRMED))).state()==RequestStatus.FULFILLED,
                    "authentic bound event remains fulfillable after safe recovery");
            check(scheduled.state()==RequestStatus.SCHEDULED,"real binding requires durable scheduling");
        } finally { executor.shutdownNow(); }
        System.out.println("PASS J08 binding WAL restore rejects forged scope/evidence/durability/missing event/unknown request");
    }
    static void questRestoreFailure() throws Exception {
        var executor=Executors.newSingleThreadExecutor();var store=new MemoryStore();var bus=new PullBus(store.session);
        try {
            var adapter=new DurableQuestAdapter(store,bus,PRODUCER,executor,()->100,32,.5);await(adapter.restore());
            var original=request(new RequestId(UUID.randomUUID()),"restore-original",FulfillmentMode.ALLOW_EXISTING_FACT);
            var originalAck=await(adapter.submit(original));
            var boundRequest=request(new RequestId(UUID.randomUUID()),"restore-bound",FulfillmentMode.NEW_EXECUTION);
            var accepted=await(adapter.submit(boundRequest));var event=new EventInstanceId(UUID.randomUUID());
            var bound=await(adapter.bindExecution(accepted.requestId(),accepted.revision(),event));
            var cancelledRequest=request(new RequestId(UUID.randomUUID()),"restore-cancelled",FulfillmentMode.NEW_EXECUTION);
            var c=await(adapter.submit(cancelledRequest));var cancel=new CancelRequest(c.requestId(),c.revision(),"restore-cancel-key");
            var cancelled=await(adapter.cancel(cancel));
            store.paged=true;await(adapter.restore()); // Complete two-page restore before the failing second restore.
            check(await(adapter.submit(original)).equals(originalAck),"initial paginated restore retains original ACK");
            var oldProjection=adapter.inspect(originalAck.requestId());
            var records=List.copyOf(store.records);int appends=store.appendCalls.get();
            store.failPageTwo=true;
            try {await(adapter.restore());throw new AssertionError("page two failure expected");}
            catch(ExecutionException expected){check(expected.getCause() instanceof IllegalStateException,"injected page-two restore failure observed");}
            check(store.pageTwoCalls>=2,"second restore reached page two");
            check(adapter.inspect(originalAck.requestId()).equals(oldProjection),"failed restore retains last complete projection");
            var newRequest=request(new RequestId(UUID.randomUUID()),"restore-new",FulfillmentMode.NEW_EXECUTION);
            for(var operation:List.of(adapter.submit(original),adapter.submit(newRequest),
                    adapter.update(bound.requestId(),bound.revision(),RequestStatus.EXECUTING,List.of()),
                    adapter.bindExecution(bound.requestId(),bound.revision(),new EventInstanceId(UUID.randomUUID())),
                    adapter.cancel(new CancelRequest(bound.requestId(),bound.revision(),"blocked-cancel")),
                    adapter.acceptFact(bound.requestId(),fact(event,EvidenceStatus.CONFIRMED)))) {
                try {await(operation);throw new AssertionError("admission after failed restore");}
                catch(ExecutionException expected){check(expected.getCause() instanceof IllegalStateException && "restore required".equals(expected.getCause().getMessage()),"failed restore fences operation rather than partial-index rejection");}
            }
            check(store.records.equals(records) && store.appendCalls.get()==appends,"failed restore admits no append");
            store.failPageTwo=false;store.pageTwoEntered=new CountDownLatch(1);store.releasePageTwo=new CountDownLatch(1);
            var restoring=adapter.restore();
            check(store.pageTwoEntered.await(2,TimeUnit.SECONDS),"recovery restore paused at page two");
            var replay=adapter.submit(original);var newAdmission=adapter.submit(newRequest);
            check(!restoring.toCompletableFuture().isDone() && !replay.toCompletableFuture().isDone() && !newAdmission.toCompletableFuture().isDone(),"queued admissions wait for complete restore");
            check(store.appendCalls.get()==appends && adapter.inspect(originalAck.requestId()).equals(oldProjection),"partial successful restore not published");
            store.releasePageTwo.countDown();await(restoring);
            check(await(replay).equals(originalAck),"same producer key and hash retains original ACK after recovery");
            check(await(newAdmission).state()==RequestStatus.ACCEPTED,"complete restore reopens admission");
            check(await(adapter.cancel(cancel)).equals(cancelled),"restored inbox retains cancellation receipt");
            check(await(adapter.acceptFact(bound.requestId(),fact(event,EvidenceStatus.CONFIRMED))).state()==RequestStatus.FULFILLED,"restored execution index retains binding");
        } finally {if(store.releasePageTwo!=null)store.releasePageTwo.countDown();executor.shutdownNow();}
        System.out.println("PASS T-QST second restore page-two failure fences admissions; private indexes publish only after complete recovery");
    }
    static void locationIoFence(Path root,Path fixture) throws Exception {
        var executor=Executors.newSingleThreadExecutor();var dispatched=new AtomicInteger();
        Executor writer=task->{dispatched.incrementAndGet();executor.execute(task);};
        var input=new ModelCodec<>(LocationImport.class).decode(Files.readAllBytes(fixture));
        var border=Map.of(new DimensionId("minecraft:overworld"),new ImportBounds(-1000,-64,-1000,1000,320,1000));
        var importer=await(StandaloneLocationImporter.open(root,input.worldId(),border,t->true,writer,16777216,20000));
        var release=new CountDownLatch(1);
        try {
            var entered=new CountDownLatch(1);
            executor.execute(()->{entered.countDown();try{if(!release.await(2,TimeUnit.SECONDS))throw new AssertionError("test writer release timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}});
            check(entered.await(2,TimeUnit.SECONDS),"test writer held before submit");
            // Force a real rename IOException after the archive was written, without permissions tricks.
            Files.createDirectory(root.resolve("CURRENT.json"));Files.writeString(root.resolve("CURRENT.json/blocker"),"test only");
            var installing=importer.submit(input);
            var busy=await(importer.submit(input));var busyError=busy.errors().getFirst();
            check(busy.status()==AckStatus.REJECTED && busyError.code()==ReasonCode.RATE_LIMITED && busyError.safeState()==SafeState.UNCHANGED,"busy retains distinct backpressure reason");
            check(busyError.retriable() && busyError.retryCondition().orElseThrow().reason()==ReasonCode.RATE_LIMITED,"busy retry metadata coherent");
            release.countDown();var failed=await(installing);
            assertWalFence(failed,"initial rename IOException");
            check(importer.snapshot().current().isEmpty(),"failed install not published");
            // Repair the test obstacle: a still-fenced importer must not retry IO even when it could succeed.
            Files.delete(root.resolve("CURRENT.json/blocker"));Files.delete(root.resolve("CURRENT.json"));
            Map<String,Fingerprint> before=new TreeMap<>();
            try(var paths=Files.list(root)){for(var file:paths.toList())before.put(file.getFileName().toString(),BlobContent.hash(Files.readAllBytes(file)));}
            int tasks=dispatched.get();
            assertWalFence(await(importer.submit(input)),"fenced resubmit");
            assertWalFence(await(importer.submit(input)),"repeated fenced resubmit");
            try{await(importer.submitJson(new ModelCodec<>(LocationImport.class).encode(input)));throw new AssertionError("JSON fence expected");}
            catch(ExecutionException expected){check(expected.getCause() instanceof IllegalStateException && expected.getCause().getMessage().contains("WAL_UNAVAILABLE"),"JSON fence distinct from busy");}
            Map<String,Fingerprint> after=new TreeMap<>();
            try(var paths=Files.list(root)){for(var file:paths.toList())after.put(file.getFileName().toString(),BlobContent.hash(Files.readAllBytes(file)));}
            check(dispatched.get()==tasks && before.equals(after),"fenced retries schedule no writer and modify no files");
            check(importer.snapshot().current().isEmpty(),"fenced retries preserve published snapshot");
        } finally {release.countDown();await(importer.closeAsync());executor.shutdownNow();}
        System.out.println("PASS location IOException and fenced resubmits: WAL_UNAVAILABLE/READ_ONLY_SAFE, coherent retry, no writer dispatch/write");
    }
    static void assertWalFence(LocationImportResult result,String label) {
        var error=result.errors().getFirst();
        check(result.status()==AckStatus.UNAVAILABLE && error.code()==ReasonCode.WAL_UNAVAILABLE && error.safeState()==SafeState.READ_ONLY_SAFE,label+" reports IO fence");
        check(error.retriable() && error.retryCondition().orElseThrow().reason()==ReasonCode.WAL_UNAVAILABLE && error.retryCondition().orElseThrow().notBefore().isEmpty(),label+" retry requires storage recovery, no fabricated deadline");
    }
    /** TEST_ONLY logical store. Does not claim disk/fsync or crash qualification. */
    static final class MemoryStore implements DurableStore {
        final List<DurableRecord> records=new CopyOnWriteArrayList<>();
        final AtomicInteger appendCalls=new AtomicInteger();
        final BlockingQueue<DurableBatch> pendingBatches=new LinkedBlockingQueue<>();
        final Map<TransactionId,CompletableFuture<CommitAck>> pending=new ConcurrentHashMap<>();
        final StoreSession session=new StoreSession(WORLD,new Epoch(1),new Epoch(1),new SessionId(UUID.randomUUID()));
        volatile boolean hold,fail,paged,failPageTwo;long sequence;
        volatile CountDownLatch pageTwoEntered,releasePageTwo;int pageTwoCalls;
        public StoreSession session(){return session;}
        public CompletionStage<CommitAck> append(DurableBatch b){
            appendCalls.incrementAndGet();
            if(fail)return CompletableFuture.failedFuture(new IllegalStateException("injected failure"));
            var f=new CompletableFuture<CommitAck>();pending.put(b.transactionId(),f);
            if(hold)pendingBatches.add(b);else force(b);return f;
        }
        void force(DurableBatch b){records.addAll(b.records());pending.remove(b.transactionId()).complete(new CommitAck(b.transactionId(),session.storeGeneration(),new Sequence(++sequence),b.transactionHash(),AckStatus.DURABLE,Optional.empty(),Set.of()));}
        public StoreReadResult view(StoreQuery q){
            var stamp=new ReadStamp(session,new Sequence(sequence),ReadContracts.queryHash(q));
            var all=List.copyOf(records);
            var subset=all;
            boolean more=false;
            Optional<String> next=Optional.empty();
            if(paged){
                int split=all.size()/2;
                if(q.pageCursor().isEmpty()) {
                    subset=all.subList(0,split);more=true;
                    next=Optional.of(ReadContracts.encodeCursor(new StoreReadCursor(stamp,"1")));
                } else {
                    var cursor=ReadContracts.decodeCursor(q.pageCursor().orElseThrow());
                    if(!cursor.stamp().equals(stamp) || !cursor.position().equals("1"))
                        return new StoreReadResult(ReadStatus.STALE,Optional.empty(),Optional.empty(),List.of());
                    pageTwoCalls++;
                    if(failPageTwo)throw new IllegalStateException("TEST_ONLY page two failure");
                    if(pageTwoEntered!=null){pageTwoEntered.countDown();try{if(!releasePageTwo.await(2,TimeUnit.SECONDS))throw new AssertionError("TEST_ONLY page two release timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
                    subset=all.subList(split,all.size());
                }
            }
            var payload=new StoreView(subset,List.of(),new Sequence(sequence),more,next);
            return new StoreReadResult(ReadStatus.AVAILABLE,Optional.of(payload),Optional.of(stamp),List.of());
        }
        public CompletionStage<StoreReadResult> read(StoreQuery q){return CompletableFuture.completedFuture(view(q));}
        public CompletionStage<BlobReadResult> readBlobs(BlobReadRequest r){return CompletableFuture.failedFuture(new UnsupportedOperationException("TEST_ONLY no blobs"));}
    }
    static final class PullBus implements FactBusPort {
        final StoreSession session;
        PullBus(StoreSession session){this.session=session;}
        final List<DirectorEnvelope> messages=new ArrayList<>();int acks;
        public SubscriptionReadResult subscribe(ConsumerSpec c){
            var batch=List.copyOf(messages);
            long scanned=batch.isEmpty()?0:batch.getLast().worldSequence().orElseThrow().value();
            var payload=new Subscription(c,batch,new Sequence(scanned+1),false);
            var stamp=new SubscriptionReadStamp(new ReadStamp(session,new Sequence(1),ReadContracts.queryHash(c)),
                c.nextSequence(),new Sequence(scanned),new Sequence(scanned));
            return new SubscriptionReadResult(ReadStatus.AVAILABLE,Optional.of(payload),Optional.of(stamp),List.of());
        }
        public CompletionStage<SubscriptionReadResult> readSubscription(ConsumerSpec c){return CompletableFuture.completedFuture(subscribe(c));}
        public CompletionStage<ConsumeAck> acknowledge(ConsumerAck a){acks++;return CompletableFuture.completedFuture(new ConsumeAck(a.consumerId(),a.contiguousAckSequence(),AckStatus.DURABLE));}
    }
}
