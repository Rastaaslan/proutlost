package fr.proutlost.director.persistence;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.model.PhysicalCatalogModels.*;
import fr.proutlost.director.bootstrap.PhysicalCatalogJournalAdmission;
import fr.proutlost.director.runtime.ResultTransaction;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** Proposed real J04 metadata tests. NOT a D0 semantic, Minecraft or fullpack gate.
 * The explicitly TEST_ONLY compiler below validates correlation only, so it must
 * never be registered in production. Source bytes intentionally exceed64KiB.
 */
public final class PhysicalCatalogJournalAdmissionTests {
    private static final WorldId WORLD=new WorldId(new UUID(0x504859534943414cL,1));
    private static final ScopeRef WORLD_SCOPE=new ScopeRef(ScopeKind.WORLD,WORLD.value());
    private static final StoreOptions OPTIONS=new StoreOptions(1_048_620,2_097_152,4_194_304,16_777_216,32,0,128);
    private static int assertions;
    private static final AtomicLong foreignIds=new AtomicLong();
    private static void check(boolean value,String why){assertions++;if(!value)throw new AssertionError(why);}
    private static <T>T await(CompletionStage<T> stage)throws Exception{return stage.toCompletableFuture().get(15,TimeUnit.SECONDS);}
    private static void rejects(Runnable run,String why){try{run.run();throw new AssertionError(why);}catch(IllegalArgumentException expected){assertions++;}}
    private static Document document(byte fill){return document(fill,"immutable_revision_a");}
    private static Document document(byte fill,String revision){
        byte[] bytes=new byte[96*1024];Arrays.fill(bytes,fill);
        var source=BlobContent.capture(PhysicalCatalogFacts.SOURCE_CODEC,1,bytes);
        var blank=new Binding(WORLD,new ResourceId("test:source"),new ResourceId("test:build"),source.reference().hash(),new Fingerprint("0".repeat(64)),Map.of(),Map.of(),List.of());
        var binding=new Binding(WORLD,blank.sourceWorld(),blank.sourceBuild(),blank.canonicalSourceSha(),PhysicalCatalogFacts.bindingDigest(blank),Map.of(),Map.of(),List.of());
        var key=new Key(WORLD,binding.sourceWorld(),binding.sourceBuild(),revision,BlobContent.hash(new byte[]{fill}),source.reference().hash(),binding.bindingSha());
        return new Document(key,binding,source);
    }
    private static void world(JournalStore store)throws Exception{
        var header=new AggregateHeader(1,WORLD.value(),new Revision(1),new Sequence(0),new Tick(0));
        var engine=new EngineControlState(1,new Revision(0),new Tick(0),new Tick(0),new UnitValue(0),new Tick(0),Optional.empty(),new UnitValue(.5));
        var hash=BlobContent.hash(new byte[]{1});
        var state=new DirectorWorldState(header,WORLD,store.session().storeGeneration(),new Tick(0),RunControl.PAUSED,
            HealthMode.NORMAL,PacingState.REST,GlobalTension.CALM,new ResourceId("test:physical_metadata"),hash,hash,
            store.session().bootEpoch(),new Sequence(0),engine);
        var batch=ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),List.of(new DurableAggregate(new WorldAggregate(state))),List.of());
        check(await(store.append(batch)).ackStatus()==AckStatus.DURABLE,"actual TEST_ONLY world row durable");
    }
    private record Harness(PhysicalCatalogJournalAdmission runtime,AtomicReference<PhysicalCatalogJournalAdmission.Publication> published,
                           AtomicReference<Object> lane,AtomicBoolean authority,SessionId actor,Set<Binding> issued) {}
    private static Harness harness(JournalStore store,Executor worker){
        var publication=new AtomicReference<PhysicalCatalogJournalAdmission.Publication>();var lane=new AtomicReference<Object>();
        var authorized=new AtomicBoolean(true);SessionId actor=new SessionId(UUID.randomUUID());Thread thread=Thread.currentThread();
        Set<Binding> issued=new HashSet<>();
        PhysicalCatalogJournalAdmission.Compiler fixtureCompiler=document->{
            var binding=BlobContent.capture(PhysicalCatalogFacts.BINDING_CODEC,1,new ModelCodec<>(Binding.class).encode(document.binding()));
            return new PhysicalCatalogJournalAdmission.Prepared(PhysicalCatalogFacts.projectionDigest(document.key(),document.completeSource().reference(),binding.reference()),publication::set);
        };
        var runtime=new PhysicalCatalogJournalAdmission(store,()->{if(Thread.currentThread()!=thread)throw new IllegalStateException("TEST actor thread");},
            id->authorized.get()&&actor.equals(id),(binding,use)->{
                if(use!=PhysicalCatalogJournalAdmission.BindingUse.RECOVERY&&!issued.contains(binding))throw new IllegalArgumentException("binding not issued by TEST owner");
                var actual=store.residentAggregateKeys(EnumSet.allOf(AggregateKind.class),4096).orElseThrow(()->new IllegalArgumentException("actual owner index unavailable"));
                var reserved=actual.keys().stream().map(AggregateKey::id).collect(java.util.stream.Collectors.toSet());
                if(binding.regions().values().stream().anyMatch(id->reserved.contains(id.value()))
                    ||binding.locations().values().stream().anyMatch(id->reserved.contains(id.value()))
                    ||binding.anchors().stream().anyMatch(a->reserved.contains(a.directorAnchor().value())))throw new IllegalArgumentException("binding collides with actual core/G0 identity");
            },()->lane.get()==null,owner->lane.get()==owner||lane.compareAndSet(null,owner),
            owner->{if(!lane.compareAndSet(owner,null))throw new IllegalStateException("TEST lane owner");},
            ()->Map.of(WORLD_SCOPE,new Revision(1)),worker,fixtureCompiler);
        return new Harness(runtime,publication,lane,authorized,actor,issued);
    }
    private static void until(Harness h,BooleanSupplier condition)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<end){h.runtime.tick();if(condition.getAsBoolean())return;Thread.sleep(2);}
        throw new AssertionError("metadata runtime timeout: "+h.runtime.diagnostic());
    }
    private static long manifests(JournalStore store)throws Exception{
        Optional<String> cursor=Optional.empty();long count=0;
        do{var query=new StoreQuery(WORLD,Set.of(),new Sequence(0),cursor,256);var result=await(store.read(query));
            ReadContracts.validate(result,query,store.session());check(result.status()==ReadStatus.AVAILABLE,"actual index page available");
            var page=result.page().orElseThrow();count+=page.records().stream().filter(r->r instanceof DurableFact f&&f.value().factType().equals(PhysicalCatalogFacts.TYPE)).count();cursor=page.nextCursor();
        }while(cursor.isPresent());return count;
    }
    private static BlobReadResult read(Harness h,Readback readback)throws Exception{
        var future=h.runtime.readSource(h.actor,readback).toCompletableFuture();until(h,future::isDone);return await(future);
    }
    private static PhysicalCatalogFacts.Manifest described(Document d){
        var binding=BlobContent.capture(PhysicalCatalogFacts.BINDING_CODEC,1,new ModelCodec<>(Binding.class).encode(d.binding()));
        return new PhysicalCatalogFacts.Manifest(1,d.key(),d.completeSource().reference(),binding.reference(),
            PhysicalCatalogFacts.projectionDigest(d.key(),d.completeSource().reference(),binding.reference()),1,Optional.empty());
    }
    private static DurableBatch foreignBatch(JournalStore store,long batch,int count){
        List<DurableRecord> records=new ArrayList<>();
        for(int i=0;i<count;i++)records.add(new DurableFact(new DirectorFact(new FactId(new UUID(0x554e52454c415445L,foreignIds.incrementAndGet())),
            new FactType("test:unrelated_append"),List.of(WORLD_SCOPE),Optional.empty(),EvidenceStatus.CONFIRMED,
            Map.of("test_only",new JsonValue.Bool(true),"batch",new JsonValue.Text(Long.toString(batch))),true,Optional.empty())));
        return ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),records,List.of());
    }
    private static void untilWithConcurrentAppends(Harness h,JournalStore store,BooleanSupplier done,long batchBase)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);int iteration=0;
        while(System.nanoTime()<end){
            check(await(store.append(foreignBatch(store,batchBase+iteration,1))).ackStatus()==AckStatus.DURABLE,"concurrent unrelated append really durable");
            h.runtime.tick();if(done.getAsBoolean())return;iteration++;Thread.sleep(2);
        }
        throw new AssertionError("retained metadata scan starved under actual append churn: "+h.runtime.diagnostic());
    }
    private static void pendingBodyAndActualOwnerCollision(Path root,Executor worker)throws Exception{
        var store=await(JournalStore.open(root,WORLD,OPTIONS));world(store);var h=harness(store,worker);until(h,h.runtime::ready);var d=document((byte)'p');
        check(h.runtime.submit(h.actor,d).state()==State.PENDING,"unissued input can compile without gaining authority");
        until(h,()->h.runtime.poll(h.actor,d.key()).state()==State.REFUSED);
        check(manifests(store)==0,"SessionId alone never attests source binding");
        h.issued.add(d.binding());h.runtime.submit(h.actor,d);
        var badBinding=new Binding(WORLD,d.key().sourceWorld(),d.key().sourceBuild(),d.key().canonicalSourceSha(),d.key().bindingSha(),
            Map.of(new ResourceId("test:tampered"),new RegionId(new UUID(0,800))),Map.of(),List.of());
        check(h.runtime.submit(h.actor,new Document(d.key(),badBinding,d.completeSource())).state()==State.REFUSED,
            "same pending Key with altered Binding body refuses immediately");
        until(h,()->h.runtime.poll(h.actor,d.key()).state()==State.DURABLE);
        check(h.published.get().binding().equals(d.binding()),"only original pending binding becomes durable");
        var source=document((byte)'f',"new_foreign_revision");
        var blank=new Binding(WORLD,source.key().sourceWorld(),source.key().sourceBuild(),source.key().canonicalSourceSha(),new Fingerprint("0".repeat(64)),
            Map.of(new ResourceId("test:foreign_core"),new RegionId(new UUID(0,900))),Map.of(),List.of());
        var frozen=new Binding(WORLD,blank.sourceWorld(),blank.sourceBuild(),blank.canonicalSourceSha(),PhysicalCatalogFacts.bindingDigest(blank),blank.regions(),Map.of(),List.of());
        var key=new Key(WORLD,frozen.sourceWorld(),frozen.sourceBuild(),source.key().sourceRevision(),source.key().sourceContentDigest(),source.key().canonicalSourceSha(),frozen.bindingSha());
        var candidate=new Document(key,frozen,source.completeSource());h.issued.add(frozen);h.runtime.submit(h.actor,candidate);
        var id=new RegionId(new UUID(0,900));var header=new AggregateHeader(1,id.value(),new Revision(1),new Sequence(0),new Tick(0));
        var region=new RegionRecord(header,id,new DimensionId("minecraft:overworld"),RegionStatus.ACTIVE,
            new SupportShape(List.of(),List.of(),List.of()),Set.of(),new UnitValue(.5),Set.of(),Set.of(),new Revision(1),new RegionPersonality(Map.of()),Set.of());
        check(await(store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),
            List.of(new DurableAggregate(new RegionAggregate(region))),List.of()))).ackStatus()==AckStatus.DURABLE,"actual foreign G0 identity arrived after grant");
        until(h,()->h.runtime.poll(h.actor,key).state()==State.REFUSED);
        check(manifests(store)==1,"fresh actual core/G0 collision refused before second physical WAL manifest");
        h.runtime.close();await(store.closeAsync());
    }
    private static void retainedSnapshotAndExplicitOrder(Path root,Executor worker)throws Exception{
        var store=await(JournalStore.open(root,WORLD,OPTIONS));world(store);
        check(await(store.append(foreignBatch(store,100,800))).ackStatus()==AckStatus.DURABLE,"more than one real sparse recovery page");
        var h=harness(store,worker);untilWithConcurrentAppends(h,store,h.runtime::ready,200);
        var first=document((byte)'a',"chronological_first");Document last=null;
        for(int i=0;i<1024;i++){
            var candidate=document((byte)'b',"chronological_second_"+i);
            if(PhysicalCatalogFacts.factId(described(candidate)).compareTo(PhysicalCatalogFacts.factId(described(first)))<0){last=candidate;break;}
        }
        check(last!=null,"second chronological fact deliberately sorts BEFORE first FactId");final Document second=last;
        h.issued.add(first.binding());h.issued.add(second.binding());h.runtime.submit(h.actor,first);
        untilWithConcurrentAppends(h,store,()->h.runtime.poll(h.actor,first.key()).state()==State.DURABLE,300);
        h.runtime.submit(h.actor,second);untilWithConcurrentAppends(h,store,()->h.runtime.poll(h.actor,second.key()).state()==State.DURABLE,400);
        h.runtime.close();await(store.closeAsync());
        var cold=await(JournalStore.open(root,WORLD,OPTIONS));var recovered=harness(cold,worker);
        untilWithConcurrentAppends(recovered,cold,recovered.runtime::ready,500);
        check(Arrays.equals(recovered.published.get().source(),second.completeSource().bytes()),"durable source ordinal/link restores chronology, not FactId sort");
        check(recovered.runtime.poll(recovered.actor,first.key()).state()==State.DURABLE&&manifests(cold)==2,"both exact revisions recovered once under live append churn");
        recovered.runtime.close();await(cold.closeAsync());
    }
    private static void delayedReadRevocationAndClose(Path root,Executor worker)throws Exception{
        AtomicBoolean armed=new AtomicBoolean(false);CountDownLatch blocked=new CountDownLatch(1),release=new CountDownLatch(1);
        var store=await(JournalStore.open(root,WORLD,OPTIONS,point->{
            if(armed.get()&&point.equals("BEFORE_WAL_WRITE")){
                blocked.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IOException("TEST delayed writer deadline");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
            }
        }));
        world(store);var h=harness(store,worker);until(h,h.runtime::ready);var d=document((byte)'r');h.issued.add(d.binding());h.runtime.submit(h.actor,d);
        until(h,()->h.runtime.poll(h.actor,d.key()).state()==State.DURABLE);var readback=h.runtime.poll(h.actor,d.key()).readback().orElseThrow();
        armed.set(true);var blockedAppend=store.append(foreignBatch(store,600,1));check(blocked.await(10,TimeUnit.SECONDS),"real J04 writer deliberately delayed");
        var delayed=h.runtime.readSource(h.actor,readback).toCompletableFuture();check(!delayed.isDone(),"writer future never exposed before actor delivery");
        h.authority.set(false);release.countDown();await(blockedAppend);until(h,delayed::isDone);
        check(await(delayed).status()==AckStatus.REJECTED&&await(delayed).contents().isEmpty(),"revocation during actual queued IO delivers no durable bytes");
        h.authority.set(true);var closingRead=h.runtime.readSource(h.actor,readback).toCompletableFuture();h.runtime.close();
        check(await(closingRead).status()==AckStatus.REJECTED&&await(closingRead).contents().isEmpty(),"close rejects every undelivered source read");
        await(store.closeAsync());
    }
    private static void refsAndExplicitAnchorIdentities(){
        var d=document((byte)'x');var binding=BlobContent.capture(PhysicalCatalogFacts.BINDING_CODEC,1,new ModelCodec<>(Binding.class).encode(d.binding()));
        var manifest=new PhysicalCatalogFacts.Manifest(1,d.key(),d.completeSource().reference(),binding.reference(),PhysicalCatalogFacts.projectionDigest(d.key(),d.completeSource().reference(),binding.reference()),1,Optional.empty());
        var fact=PhysicalCatalogFacts.fact(manifest);
        check(PhysicalCatalogFacts.manifest(fact).equals(manifest),"closed manifest codec roundtrip");
        check(DurableBlobReferences.collect(List.of(new DurableFact(fact))).equals(Set.of(d.completeSource().reference(),binding.reference())),"walker sees BOTH durable source and binding refs");
        check(d.completeSource().reference().bytes()>65536,"fixture really exceeds single JSON string ceiling");
        var a=new AnchorBinding(new ResourceId("test:a"),new ResourceId("test:center"),new AnchorId(new UUID(0,3)));
        var b=new AnchorBinding(new ResourceId("test:b"),new ResourceId("test:center"),new AnchorId(new UUID(0,4)));
        var explicit=new Binding(WORLD,d.key().sourceWorld(),d.key().sourceBuild(),d.key().canonicalSourceSha(),d.key().bindingSha(),Map.of(),Map.of(),List.of(a,b));
        check(explicit.anchors().size()==2,"same source anchor name in different locations remains distinct");
        rejects(()->new Binding(WORLD,d.key().sourceWorld(),d.key().sourceBuild(),d.key().canonicalSourceSha(),d.key().bindingSha(),Map.of(),Map.of(),List.of(a,a)),"same local anchor cannot be bound twice");
        rejects(()->new Binding(WORLD,d.key().sourceWorld(),d.key().sourceBuild(),d.key().canonicalSourceSha(),d.key().bindingSha(),Map.of(new ResourceId("test:r"),new RegionId(WORLD.value())),Map.of(),List.of()),"world UUID cannot become region UUID");
    }
    private static void completeAndCold(Path root,Executor worker)throws Exception{
        var store=await(JournalStore.open(root,WORLD,OPTIONS));world(store);var h=harness(store,worker);var d=document((byte)'a');
        until(h,()->h.runtime.ready());h.issued.add(d.binding());check(h.runtime.submit(h.actor,d).state()==State.PENDING,"submit is nonblocking pending");
        check(h.published.get()==null,"no publication on input acceptance");
        until(h,()->h.runtime.poll(h.actor,d.key()).state()==State.DURABLE);
        var first=h.runtime.poll(h.actor,d.key()).readback().orElseThrow();
        check(h.published.get()!=null&&Arrays.equals(h.published.get().source(),d.completeSource().bytes()),"publication holds exact independently read blob");
        check(h.lane.get()==null,"actual lane released after readback");
        check(read(h,first).contents().getFirst().equals(d.completeSource()),"readSource uses real J04 reader and actor delivery");
        check(h.runtime.submit(h.actor,d).state()==State.DURABLE&&manifests(store)==1,"duplicate input never adds manifest");
        var conflict=document((byte)'b');h.issued.add(conflict.binding());check(h.runtime.submit(h.actor,conflict).state()==State.PENDING,"conflict compilation remains nonblocking");
        until(h,()->h.runtime.poll(h.actor,conflict.key()).state()==State.REFUSED);
        check(manifests(store)==1,"same revision changed bytes refuses before WAL");
        h.authority.set(false);check(h.runtime.poll(h.actor,d.key()).state()==State.STALE_SESSION,"revoked actor cannot poll durable data");h.authority.set(true);
        h.runtime.close();await(store.closeAsync());
        var reopened=await(JournalStore.open(root,WORLD,OPTIONS));var next=harness(reopened,worker);until(next,()->next.runtime.ready());
        var cold=next.runtime.poll(next.actor,d.key()).readback().orElseThrow();
        check(!first.session().equals(cold.session()),"reopen has actual different StoreSession");
        check(cold.completeSource().equals(first.completeSource())&&cold.projectionDigest().equals(first.projectionDigest()),"cold index rebuild retains exact blob/projection pins");
        check(Arrays.equals(next.published.get().source(),d.completeSource().bytes())&&manifests(reopened)==1,"cold rebuild without reimport or duplicate");
        check(read(next,first).status()==AckStatus.REJECTED,"stale previous-session readback refuses");
        next.runtime.close();await(reopened.closeAsync());
    }
    private static void loss(Path root,String point,boolean committed,Executor worker)throws Exception{
        AtomicBoolean armed=new AtomicBoolean(false),tripped=new AtomicBoolean(false);
        var store=await(JournalStore.open(root,WORLD,OPTIONS,at->{if(armed.get()&&point.equals(at)&&tripped.compareAndSet(false,true))throw new IOException("TEST_ONLY_METADATA_IO_LOSS_"+point);}));
        world(store);var h=harness(store,worker);until(h,()->h.runtime.ready());var d=document((byte)'z');armed.set(true);
        h.issued.add(d.binding());h.runtime.submit(h.actor,d);until(h,()->h.runtime.poll(h.actor,d.key()).state()==State.FAILED_SAFE);
        check(tripped.get()&&h.published.get()==null,"actual J04 loss cannot publish success");await(store.closeAsync());
        var reopened=await(JournalStore.open(root,WORLD,OPTIONS));var recovered=harness(reopened,worker);until(recovered,()->recovered.runtime.ready());
        if(committed)check(recovered.runtime.poll(recovered.actor,d.key()).state()==State.DURABLE,"post-force lost ACK recovers actual durable import");
        else{
            check(recovered.runtime.poll(recovered.actor,d.key()).state()==State.REFUSED,"pre-WAL loss stays unknown");
            recovered.issued.add(d.binding());check(recovered.runtime.submit(recovered.actor,d).state()==State.PENDING,"same immutable key retries only after real recovery");
            until(recovered,()->recovered.runtime.poll(recovered.actor,d.key()).state()==State.DURABLE);
        }
        check(manifests(reopened)==1,"IO recovery retains exactly one committed manifest");recovered.runtime.close();await(reopened.closeAsync());
    }
    private static void ownedLaneOnly(Path root,Executor worker)throws Exception{
        var store=await(JournalStore.open(root,WORLD,OPTIONS));world(store);
        var h=harness(store,worker);until(h,h.runtime::ready);var d=document((byte)'o');h.issued.add(d.binding());
        h.runtime.submit(h.actor,d);until(h,()->h.runtime.poll(h.actor,d.key()).state()==State.DURABLE);
        check(h.lane.get()==null&&h.published.get()!=null,"catalogue progresses and releases its own busy lane");h.runtime.close();
        var lane=new AtomicReference<Object>();var published=new AtomicBoolean();Thread actor=Thread.currentThread();
        var binding=BlobContent.capture(PhysicalCatalogFacts.BINDING_CODEC,1,new ModelCodec<>(Binding.class).encode(d.binding()));
        var adapters=BlobContent.capture(PhysicalMetadataFacts.ADAPTERS_CODEC,1,new byte[]{'{','}'});
        var index=new PhysicalCatalogConfiguration(1,d.key(),d.completeSource().reference(),binding.reference(),adapters.reference(),BlobContent.hash(new byte[]{1}),"test_only_receipt.json");
        var journal=new fr.proutlost.director.bootstrap.PhysicalMetadataJournal(store,
            ()->{if(Thread.currentThread()!=actor)throw new IllegalStateException("TEST actor thread");},
            (i,recovery)->{if(!i.equals(index))throw new IllegalArgumentException("TEST configuration mismatch");},
            ()->lane.get()==null,owner->lane.compareAndSet(null,owner),owner->{if(!lane.compareAndSet(owner,null))throw new IllegalStateException("TEST lane owner");},
            ()->Map.of(WORLD_SCOPE,new Revision(1)),worker,(i,bytes)->new fr.proutlost.director.bootstrap.PhysicalMetadataJournal.Prepared(commit->published.set(true)),
            i->fr.proutlost.director.bootstrap.PhysicalMetadataJournal.RecoveryUse.HYDRATE);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!journal.ready()&&System.nanoTime()<deadline){journal.tick();Thread.sleep(2);}
        check(journal.ready(),"configuration recovery ready");
        journal.submit(index,List.of(d.completeSource(),binding,adapters));
        while(journal.state(index)==fr.proutlost.director.bootstrap.PhysicalMetadataJournal.State.PENDING&&System.nanoTime()<deadline){journal.tick();Thread.sleep(2);}
        check(journal.state(index)==fr.proutlost.director.bootstrap.PhysicalMetadataJournal.State.DURABLE&&published.get()&&lane.get()==null,"configuration progresses and releases its own busy lane");
        journal.close();await(store.closeAsync());
    }
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("director-physical-catalog-j04-TEST-ONLY-");ExecutorService worker=Executors.newSingleThreadExecutor();
        try{if(args.length==1&&args[0].equals("--owned-lane-only")){ownedLaneOnly(root.resolve("owned-lane"),worker);System.out.println("OWNED_METADATA_AND_CATALOGUE_LANE_PASS assertions="+assertions+" full_suite=false");return;}
            refsAndExplicitAnchorIdentities();completeAndCold(root.resolve("complete"),worker);
            pendingBodyAndActualOwnerCollision(root.resolve("binding-owner"),worker);
            retainedSnapshotAndExplicitOrder(root.resolve("concurrent-history"),worker);
            delayedReadRevocationAndClose(root.resolve("read-authority"),worker);
            loss(root.resolve("before-wal"),"BEFORE_WAL_WRITE",false,worker);
            loss(root.resolve("after-force"),"BEFORE_ACK",true,worker);
            System.out.println("PHYSICAL_CATALOG_J04_TECHNICAL_PASS assertions="+assertions+" minecraft=false d0_semantics=false cold_minecraft=false fullpack=false");
        }finally{worker.shutdown();worker.awaitTermination(15,TimeUnit.SECONDS);}
    }
}
