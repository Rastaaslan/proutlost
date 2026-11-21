package fr.proutlost.director.persistence;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.bootstrap.DirectorNativeLocationGenerationPlan;
import fr.proutlost.director.config.TestingConfig;
import fr.proutlost.director.integration.locations.*;
import fr.proutlost.director.memory.*;
import fr.proutlost.director.runtime.ResultTransaction;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Declared synthetic catalogue; real J04 and the exact J01 reducer/write shape.
 * No Minecraft, canonical Contra, human or production-load receipt is implied.
 */
final class MetadataAttemptResumeFixture {
    static final WorldId WORLD=new WorldId(new UUID(88593,1));
    static final ResourceId SOURCE=new ResourceId("fixture:attempt_resume");
    static final ResourceId OTHER=new ResourceId("fixture:attempt_other");
    static final DimensionId DIM=new DimensionId("minecraft:overworld");
    static final Tick ORIGINAL_CLOCK=new Tick(88593);
    static final StoreOptions OPTIONS=new StoreOptions(1_048_620,4_194_304,16_777_216,33_554_432,64,0,128);
    static final ModelCodec<MetadataGenerationPage> PAGE=new ModelCodec<>(MetadataGenerationPage.class);
    static int assertions;
    private MetadataAttemptResumeFixture(){}
    static void check(boolean value,String label){assertions++;if(!value)throw new AssertionError(label);}
    static <T>T await(CompletionStage<T> pending)throws Exception{return pending.toCompletableFuture().get(30,TimeUnit.SECONDS);}
    static JournalStore open(Path root)throws Exception{return await(JournalStore.open(root,WORLD,OPTIONS));}
    static void close(JournalStore store)throws Exception{await(store.closeAsync());}
    static RegionId regionId(int index){return new RegionId(new UUID(88594,index+1));}
    static LocationImport source(ResourceId source,long revision){
        List<ImportedRegion> regions=new ArrayList<>();int count=source.equals(SOURCE)?100:1;
        for(int n=0;n<count;n++){
            var id=source.equals(SOURCE)?regionId(n):new RegionId(new UUID(88595,n+1));int x=n*8;
            regions.add(new ImportedRegion(id,DIM,new ImportBounds(x,0,0,x+4,8,4),Set.of()));
        }
        var raw=new LocationImport(1,WORLD,source,new Revision(revision),new Fingerprint("0".repeat(64)),regions,List.of(),List.of());
        return new LocationImport(1,WORLD,source,raw.exportRevision(),StandaloneLocationImporter.digest(raw),regions,List.of(),List.of());
    }
    static CommitAck append(JournalStore store,List<DurableRecord> records,List<BlobContent> blobs,Map<ScopeRef,Revision> expected)throws Exception{
        return await(store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),expected,records,blobs)));
    }
    static CommitAck durable(JournalStore store,DurableRecord record,List<BlobContent> blobs,String label)throws Exception{
        var ack=append(store,List.of(record),blobs,Map.of());check(ack.ackStatus()==AckStatus.DURABLE&&ack.durableSequence().value()>0,label);return ack;
    }
    static void rejected(CommitAck ack,String label){check(ack.ackStatus()==AckStatus.REJECTED&&ack.durableSequence().value()==0,label);}
    static LocationTransfer.Prepared prepare(long revision){return LocationTransfer.prepare(source(SOURCE,revision));}
    static void sourcePages(JournalStore store,LocationTransfer.Prepared transfer)throws Exception{
        for(var raw:transfer.pages()){
            var blob=BlobContent.capture(new ResourceId("proutlost_director:location_transfer_fragment"),1,Base64.getDecoder().decode(raw.bytesBase64()));
            durable(store,new DurableMetadataSourcePage(transfer.manifest(),raw.pageIndex(),blob.reference()),List.of(blob),"actual complete source fragment durable");
        }
    }
    static Map<AggregateKey,AggregateState> actual(JournalStore store,JournalStore.MetadataSnapshot snapshot,List<AggregateKey> keys)throws Exception{
        check(keys.size()<=96,"head hydration request remains <=96");
        var query=new StoreQuery(WORLD,Set.copyOf(keys),new Sequence(0),Optional.empty(),96);
        var read=await(store.readMetadata(snapshot,query));ReadContracts.validate(read,query,snapshot.session());
        check(read.status()==ReadStatus.AVAILABLE&&!read.page().orElseThrow().hasMore(),"actual requested metadata heads available at one pinned cut");
        var values=new HashMap<AggregateKey,AggregateState>();for(var row:read.page().orElseThrow().aggregates())values.put(row.state().key(),row.state());return values;
    }
    static List<MetadataGenerationPage> plan(JournalStore store,LocationImport input,LocationImport previous,Tick tick)throws Exception{
        var snapshot=store.metadataSnapshot().orElseThrow();
        var planner=new DirectorNativeLocationGenerationPlan(WORLD,input,previous,tick,k->store.metadataOwner(snapshot,k));
        List<MetadataGenerationPage> pages=new ArrayList<>();var manifest=LocationTransfer.prepare(input).manifest();
        for(int n=0;n<planner.pageCount();n++){
            var keys=planner.pageKeys(n);var planned=planner.planPage(keys,actual(store,snapshot,keys));
            pages.add(new MetadataGenerationPage(LocationTransfer.identity(manifest),input.sourceNamespace(),input.exportRevision(),input.exportDigest(),n,planner.pageCount(),planned.states(),planned.beforeRevisions()));
        }
        return List.copyOf(pages);
    }
    static BlobContent pageBlob(MetadataGenerationPage page){return BlobContent.capture(new ResourceId("proutlost_director:metadata_generation_page"),1,PAGE.encode(page));}
    static DurableMetadataStage stage(LocationTransferManifest manifest,MetadataGenerationPage page,BlobContent blob){return new DurableMetadataStage(manifest,page.pageIndex(),page.pageCount(),blob.reference());}
    static LocationTransferManifest activateInitial(JournalStore store,LocationImport input,Tick tick)throws Exception{
        var transfer=LocationTransfer.prepare(input);sourcePages(store,transfer);
        var pages=plan(store,input,null,tick);for(var page:pages){var blob=pageBlob(page);durable(store,stage(transfer.manifest(),page,blob),List.of(blob),"legacy epoch0 metadata stage durable");}
        durable(store,new DurableMetadataActivation(transfer.manifest(),Optional.empty(),pages.size()),List.of(),"legacy epoch0 complete source activation durable");return transfer.manifest();
    }
    static void seedSourceCrash(Path root)throws Exception{
        var store=open(root);activateInitial(store,source(SOURCE,1),new Tick(33218));activateInitial(store,source(OTHER,1),new Tick(31813));
        var next=prepare(2);check(next.pages().size()>1,"declared source actually exceeds one transport page");
        durable(store,new DurableFact(MetadataPreparationClock.fact(next.manifest(),ORIGINAL_CLOCK)),List.of(),"R2 original88593 declaration durable before any metadata stage");
        sourcePages(store,next);check(store.stagedMetadata(SOURCE).orElseThrow().pages().isEmpty(),"source-only crash boundary has no R2 metadata page");
        oldActive(store);check(store.metadataPreparationTick(next.manifest()).orElseThrow().equals(ORIGINAL_CLOCK),"original clock exact88593");
        System.out.println("SOURCE_CRASH_DURABLE pid="+ProcessHandle.current().pid()+" originalClock=88593 sourcePages="+next.pages().size()+" currentMetadataPages=0");
        System.out.flush();Runtime.getRuntime().halt(77);
    }
    static MemoryView selectedMemory(JournalStore store,RegionId region)throws Exception{
        var states=actual(store,store.metadataSnapshot().orElseThrow(),List.of(new AggregateKey(AggregateKind.REGION,region.value()),new AggregateKey(AggregateKind.REGION_MEMORY,region.value())));
        check(states.size()==2,"selected real REGION and REGION_MEMORY bodies exist");return MemoryProjection.of(new ScopeSet(WORLD,Set.of(new ScopeRef(ScopeKind.REGION,region.value()))),states.values());
    }
    static RegionMemory visit(JournalStore store,Path profile,int index,long tick)throws Exception{
        var id=regionId(index);var before=selectedMemory(store,id);var settings=new MemorySettings(TestingConfig.load(Files.readAllBytes(profile)).view(),false);
        var observation=new DirectorObservation(new ObservationId(new UUID(tick,index)),new ResourceId("proutlost_director:activity"),new ScopeRef(ScopeKind.REGION,id.value()),new Tick(tick),Map.of("visit",1.0),new UnitValue(1));
        var snapshot=new SnapshotBatch(new RequestId(new UUID(tick,index+100)),before.scopes(),new Tick(tick),before.revisions(),new ObservationContext(Map.of(),Map.of(),Map.of(),Map.of(),before.anchors(),before.locations(),List.of(),Map.of()),Set.of(),Set.of(),HealthMode.NORMAL);
        var input=new ObservationBatch(List.of(observation.observationId()),List.of(observation),before.revisions(),snapshot,List.of(),List.of(),Map.of());
        var reduction=new MemoryReducer(settings).reduce(before,input,new DirectorClock(new Tick(tick),0,Instant.EPOCH));
        check(reduction.aggregateChanges().size()==2&&reduction.counterDeltas().size()==1&&reduction.proposedFacts().isEmpty(),"actual J01 MemoryReducer produces exact region+memory changes without author facts");
        // Mirror DirectorObservationWalIngestion: counter deltas are informational;
        // persist its exact aggregate changes with REGION scope CAS, not a second delta.
        List<DurableRecord> records=new ArrayList<>();Map<ScopeRef,Revision> guards=new HashMap<>();
        for(var change:reduction.aggregateChanges()){
            if(change.next() instanceof RegionAggregate region)guards.put(new ScopeRef(ScopeKind.REGION,region.value().regionId().value()),change.expectedRevision().orElse(new Revision(0)));
            records.add(new DurableAggregate(change.next()));
        }
        var ack=append(store,records,List.of(),guards);check(ack.ackStatus()==AckStatus.DURABLE,"actual J01-shaped reduction transaction durable");
        var after=selectedMemory(store,id);var memory=after.regions().getFirst();
        check(after.regionRecords().get(id).header().updatedTick().value()==tick&&memory.header().updatedTick().value()==tick,"real reducer persisted both region and memory updated tick"+tick);
        check(memory.components().get(MemoryComponent.VISIT).evidenceCount()==before.regions().getFirst().components().getOrDefault(MemoryComponent.VISIT,new DecayValue(0,new Tick(0),"memory.visit_half_life_ticks",0,new UnitValue(0))).evidenceCount()+1,"actual VISIT evidence genuinely advances");
        System.out.println("REAL_J01_VISIT pid="+ProcessHandle.current().pid()+" regionIndex="+index+" regionRevision="+after.regionRecords().get(id).header().revision().value()+" memoryRevision="+memory.header().revision().value()+" updatedTick="+tick);return memory;
    }
    static void oldActive(JournalStore store)throws Exception{
        var snapshot=store.metadataSnapshot().orElseThrow();check(snapshot.generations().get(SOURCE).equals(LocationTransfer.identity(prepare(1).manifest())),"R1 stays active while newer attempt remains incomplete");
        check(await(store.readMetadataSource(snapshot,SOURCE)).orElseThrow().equals(source(SOURCE,1)),"readback exact old active source R1");
        check(await(store.readMetadataSource(snapshot,OTHER)).orElseThrow().equals(source(OTHER,1)),"independent other source remains exact");
    }
    static void staleClockRefused(JournalStore store)throws Exception{
        try{plan(store,source(SOURCE,2),source(SOURCE,1),ORIGINAL_CLOCK);throw new AssertionError("old planner unexpectedly accepts timestamp regression");}
        catch(IllegalArgumentException refusal){check(refusal.getMessage().contains("generation context ahead of logical clock"),"old frozen88593 proposal is explicitly refused against genuine newer REGION/MEMORY");}
    }
}
