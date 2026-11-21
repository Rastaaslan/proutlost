package fr.proutlost.director.persistence;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.integration.locations.LocationTransfer;
import fr.proutlost.director.integration.locations.StandaloneLocationImporter;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real J04 disk qualification of synthetic native metadata contracts.
 * Worldless data is not a canonical Contra export, a physical survey, or a Minecraft reception.
 */
public final class MetadataGenerationStoreTests {
    private MetadataGenerationStoreTests() {}
    private static final WorldId WORLD=new WorldId(new UUID(8008,1));
    private static final DimensionId DIM=new DimensionId("minecraft:overworld");
    private static final ResourceId SOURCE=new ResourceId("fixture:native_metadata");
    private static final ResourceId OTHER=new ResourceId("fixture:other_metadata");
    private static final StoreOptions OPTIONS=new StoreOptions(1_048_620,4_194_304,16_777_216,33_554_432,64,0,128);
    private static final ModelCodec<DurableRecord> RECORD=new ModelCodec<>(DurableRecord.class);
    private static final ModelCodec<MetadataGenerationPage> PAGE=new ModelCodec<>(MetadataGenerationPage.class);
    private static int assertions;
    private static int transactions;
    private static boolean archiveProbe;
    private static void check(boolean condition,String label){assertions++;if(!condition)throw new AssertionError(label);}
    private static <T>T await(CompletionStage<T> pending)throws Exception{return pending.toCompletableFuture().get(30,TimeUnit.SECONDS);}
    private static JournalStore open(Path root)throws Exception{return await(JournalStore.open(root,WORLD,OPTIONS));}
    private static void close(JournalStore store)throws Exception{await(store.closeAsync());}
    private static UUID id(int namespace,int index){return new UUID(8100+namespace,index);}
    private static LocationImport source(int count,int namespace,long revision,ResourceId owner){
        var locations=new ArrayList<ImportedLocation>();
        for(int i=0;i<count;i++)locations.add(new ImportedLocation(new LocationId(id(namespace,i)),"synthetic-"+i,
            Set.of(),DIM,new ImportBounds(i,0,i,i+1,2,i+1),Set.of()));
        var draft=new LocationImport(1,WORLD,owner,new Revision(revision),new Fingerprint("0".repeat(64)),List.of(),locations,List.of());
        return new LocationImport(1,WORLD,owner,draft.exportRevision(),StandaloneLocationImporter.digest(draft),List.of(),locations,List.of());
    }
    private static LocationAggregate state(ImportedLocation location,LocationImport source,long revision,boolean active){
        var b=location.bounds();
        return new LocationAggregate(new LocationRecord(new AggregateHeader(1,location.locationId().value(),new Revision(revision),new Sequence(0),new Tick(1)),
            location.locationId(),source.sourceNamespace(),location.externalId(),location.dimension(),
            new Aabb(location.dimension(),b.minX(),b.minY(),b.minZ(),b.maxXExclusive(),b.maxYExclusive(),b.maxZExclusive()),
            location.tags(),Set.of(),source.exportRevision(),active));
    }
    private static DurableBatch batch(JournalStore store,DurableRecord record,List<BlobContent> blobs,String identity){
        return new DurableBatch(new TransactionId(UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8))),store.session().storeGeneration(),Map.of(),List.of(record),blobs,Optional.empty(),BlobContent.hash(RECORD.encode(record)));
    }
    private static CommitAck commit(JournalStore store,DurableRecord record,List<BlobContent> blobs)throws Exception{
        return await(store.append(batch(store,record,blobs,"synthetic-native-metadata-tx-"+(++transactions))));
    }
    private static void durable(JournalStore store,DurableRecord record,List<BlobContent> blobs,String label)throws Exception{
        check(commit(store,record,blobs).ackStatus()==AckStatus.DURABLE,label);
    }
    private record Generation(LocationImport source,LocationTransfer.Prepared transfer,List<MetadataGenerationPage> pages){}
    private static Generation generation(LocationImport source,long beforeRevision,List<ImportedLocation> retired){
        var prepared=LocationTransfer.prepare(source);var states=new ArrayList<AggregateState>();
        for(var location:source.locations())states.add(state(location,source,beforeRevision+1,true));
        for(var location:retired)states.add(state(location,source,beforeRevision+1,false));
        int pageCount=Math.max(1,(states.size()+95)/96);var pages=new ArrayList<MetadataGenerationPage>();
        for(int i=0;i<pageCount;i++){
            var rows=states.subList(i*96,Math.min(states.size(),(i+1)*96));var before=new HashMap<AggregateKey,Revision>();
            for(var row:rows)before.put(row.key(),new Revision(beforeRevision));
            pages.add(new MetadataGenerationPage(LocationTransfer.identity(prepared.manifest()),source.sourceNamespace(),source.exportRevision(),source.exportDigest(),i,pageCount,rows,before));
        }
        return new Generation(source,prepared,List.copyOf(pages));
    }
    private static void raw(JournalStore store,Generation generation)throws Exception{
        var fragments=new ArrayList<>(generation.transfer().pages());Collections.reverse(fragments);
        for(var page:fragments){
            byte[] bytes=Base64.getDecoder().decode(page.bytesBase64());
            var blob=BlobContent.capture(new ResourceId("proutlost_director:location_transfer_fragment"),1,bytes);
            durable(store,new DurableMetadataSourcePage(generation.transfer().manifest(),page.pageIndex(),blob.reference()),List.of(blob),"raw source fragment durable");
        }
    }
    private static void page(JournalStore store,Generation generation,int index)throws Exception{
        var plan=generation.pages().get(index);var blob=BlobContent.capture(new ResourceId("proutlost_director:metadata_generation_page"),1,PAGE.encode(plan));
        durable(store,new DurableMetadataStage(generation.transfer().manifest(),index,generation.pages().size(),blob.reference()),List.of(blob),"hidden metadata page durable");
    }
    private static void stage(JournalStore store,Generation generation)throws Exception{
        raw(store,generation);for(int i=0;i<generation.pages().size();i++)page(store,generation,i);
    }
    private static DurableMetadataActivation activation(Generation generation,Optional<Fingerprint> previous){
        return new DurableMetadataActivation(generation.transfer().manifest(),previous,generation.pages().size());
    }
    private static void activate(JournalStore store,Generation generation,Optional<Fingerprint> previous)throws Exception{
        durable(store,activation(generation,previous),List.of(),"whole generation activation durable");
    }
    private static JournalStore.MetadataSnapshot snapshot(JournalStore store){return store.metadataSnapshot().orElseThrow();}
    private static List<AggregateKey> keys(JournalStore store,JournalStore.MetadataSnapshot snapshot){
        var keys=new ArrayList<AggregateKey>();Optional<AggregateKey> after=Optional.empty();
        do{
            var page=store.metadataKeysPage(snapshot,EnumSet.allOf(AggregateKind.class),after,96).orElseThrow();
            check(page.keys().size()<=96,"discovery page bounded");keys.addAll(page.keys());after=page.next();
        }while(after.isPresent());
        check(new HashSet<>(keys).size()==keys.size(),"discovery no duplicate keys");return List.copyOf(keys);
    }
    private static Map<AggregateKey,AggregateState> read(JournalStore store,JournalStore.MetadataSnapshot snapshot,List<AggregateKey> keys)throws Exception{
        var result=new HashMap<AggregateKey,AggregateState>();
        for(int i=0;i<keys.size();i+=96){
            var selected=Set.copyOf(keys.subList(i,Math.min(keys.size(),i+96)));
            var query=new StoreQuery(WORLD,selected,new Sequence(0),Optional.empty(),96);
            var page=await(store.readMetadata(snapshot,query));ReadContracts.validate(page,query,snapshot.session());
            check(page.status()==ReadStatus.AVAILABLE,"bounded pinned metadata read available");
            var value=page.page().orElseThrow();check(!value.hasMore(),"selected exact key page complete");
            check(value.lastDurableSequence().equals(snapshot.cut()),"each page at same captured cut");
            value.aggregates().forEach(row->result.put(row.state().key(),row.state()));
        }
        return Map.copyOf(result);
    }
    private static void groupedHydration(Path root)throws Exception{
        var fixture=generation(source(120,17,1,SOURCE),0,List.of());JournalStore store=open(root);
        stage(store,fixture);activate(store,fixture,Optional.empty());var pinned=snapshot(store);
        var selected=Set.copyOf(keys(store,pinned).subList(0,96));var query=new StoreQuery(WORLD,selected,new Sequence(0),Optional.empty(),96);
        long before=store.metadataHydrationPageLoads();var page=await(store.readMetadata(pinned,query));
        ReadContracts.validate(page,query,pinned.session());long loaded=store.metadataHydrationPageLoads()-before;
        check(page.status()==ReadStatus.AVAILABLE&&page.page().orElseThrow().aggregates().size()==96,"grouped cold hydration exact 96 rows");
        check(loaded>0&&loaded<=fixture.pages().size(),"each distinct immutable page decoded once per read");
        check(store.metadataCachedEntries()<=512,"grouped decode preserves strict cache bound");
        before=store.metadataHydrationPageLoads();var repeated=await(store.readMetadata(pinned,query));
        check(repeated.equals(page)&&store.metadataHydrationPageLoads()==before,"cached exact repeat performs no page IO");
        await(store.checkpoint());close(store);store=open(root);pinned=snapshot(store);
        query=new StoreQuery(WORLD,selected,new Sequence(0),Optional.empty(),96);before=store.metadataHydrationPageLoads();
        page=await(store.readMetadata(pinned,query));ReadContracts.validate(page,query,pinned.session());
        long coldLoaded=store.metadataHydrationPageLoads()-before;
        check(page.status()==ReadStatus.AVAILABLE&&page.page().orElseThrow().aggregates().size()==96&&coldLoaded>0&&coldLoaded<=fixture.pages().size(),"new JVM-session cache miss is grouped and complete");
        System.out.println("GROUPED_HYDRATION rows=96 distinct_page_loads="+loaded+" cold_page_loads="+coldLoaded+" cache="+store.metadataCachedEntries());close(store);
    }
    private static void multiPageAndCold(Path root)throws Exception{
        var large=generation(source(120,0,1,SOURCE),0,List.of());check(large.pages().size()>1,"metadata exceeds native 96-entry envelope");
        check(large.transfer().pages().size()>1,"raw source exceeds wire page");
        JournalStore store=open(root);check(!store.readOnlySafe(),"J04 opened writable");
        raw(store,large);page(store,large,0);
        check(snapshot(store).generations().isEmpty(),"incomplete generation never active");
        check(keys(store,snapshot(store)).isEmpty(),"incomplete metadata hidden from real index");
        check(commit(store,activation(large,Optional.empty()),List.of()).ackStatus()==AckStatus.REJECTED,"activation refuses missing metadata page");
        close(store);store=open(root);check(!store.readOnlySafe(),"incomplete staged generation reopens healthy");
        check(keys(store,snapshot(store)).isEmpty(),"cold incomplete metadata remains invisible");
        page(store,large,1);activate(store,large,Optional.empty());var pinned=snapshot(store);
        check(keys(store,pinned).size()==120,"full generation visible through J04 index");
        var current=read(store,pinned,keys(store,pinned));check(current.size()==120,"all staged heads read through real J04");
        check(await(store.readMetadataSource(pinned,SOURCE)).orElseThrow().equals(large.source()),"exact original source retained by J04");
        for(var key:current.keySet())check(store.metadataOwner(pinned,key).orElseThrow().equals(SOURCE),"historical owner is source");
        check(commit(store,activation(large,Optional.empty()),List.of()).ackStatus()==AckStatus.DURABLE,"activation replay idempotent");
        await(store.checkpoint());close(store);store=open(root);
        var cold=snapshot(store);check(keys(store,cold).size()==120,"all heads recovered after checkpoint restart");
        check(read(store,cold,keys(store,cold)).equals(current),"cold real head contents exact");
        check(await(store.readMetadataSource(cold,SOURCE)).orElseThrow().equals(large.source()),"source bytes cold exact");
        close(store);
    }
    private static void boundedAndReplacement(Path root)throws Exception{
        JournalStore store=open(root);var large=generation(source(600,1,1,SOURCE),0,List.of());stage(store,large);activate(store,large,Optional.empty());
        var old=snapshot(store);var oldKeys=keys(store,old);check(oldKeys.size()==600,"more than cache capacity discoverable");
        var oldStates=read(store,old,oldKeys);check(oldStates.size()==600,"all 600 heads hydrated in bounded batches");
        check(store.metadataCachedEntries()<=512,"metadata cache never exceeds 512 states");
        var other=generation(source(2,2,1,OTHER),0,List.of());stage(store,other);activate(store,other,Optional.empty());
        var replacement=generation(source(80,1,2,SOURCE),1,large.source().locations().subList(80,600));stage(store,replacement);
        check(snapshot(store).generations().get(SOURCE).equals(LocationTransfer.identity(large.transfer().manifest())),"old generation stays active through replacement staging");
        activate(store,replacement,Optional.of(LocationTransfer.identity(large.transfer().manifest())));
        var now=snapshot(store);var all=read(store,now,keys(store,now));check(all.size()==602,"other source and retired identity heads retained");
        long active=all.values().stream().filter(s->s instanceof LocationAggregate l&&l.value().active()).count();
        check(active==82,"entire source replacement activates 80 and preserves other two");
        check(read(store,old,oldKeys).equals(oldStates),"pinned old snapshot never mixes generations after activation");
        check(await(store.readMetadataSource(old,SOURCE)).orElseThrow().equals(large.source()),"pinned old source kept readable");
        check(await(store.readMetadataSource(now,SOURCE)).orElseThrow().equals(replacement.source()),"current original replacement source exact");
        check(store.metadataCachedEntries()<=512,"cache bound survives new and old snapshot hydration");
        await(store.checkpoint());close(store);store=open(root);now=snapshot(store);
        check(read(store,now,keys(store,now)).equals(all),"replacement and other source survive restart");
        check(await(store.readMetadataSource(now,OTHER)).orElseThrow().equals(other.source()),"unrelated source original retained cold");
        close(store);
    }
    private static void conflicts(Path root)throws Exception{
        JournalStore store=open(root);var proposed=generation(source(2,3,1,SOURCE),0,List.of());raw(store,proposed);page(store,proposed,0);
        var p=proposed.pages().getFirst();var blob=BlobContent.capture(new ResourceId("proutlost_director:metadata_generation_page"),1,PAGE.encode(p));
        var record=new DurableMetadataStage(proposed.transfer().manifest(),0,1,blob.reference());
        var exact=batch(store,record,List.of(blob),"exact-page-replay");var receipt=await(store.append(exact));
        check(receipt.ackStatus()==AckStatus.DURABLE&&await(store.append(exact)).equals(receipt),"same stage transaction exact original ACK");
        var changedState=state(proposed.source().locations().getFirst(),proposed.source(),1,false);
        var changedPage=new MetadataGenerationPage(p.generation(),p.sourceNamespace(),p.exportRevision(),p.exportDigest(),0,1,List.of(changedState,p.states().get(1)),p.beforeRevisions());
        var changedBlob=BlobContent.capture(new ResourceId("proutlost_director:metadata_generation_page"),1,PAGE.encode(changedPage));
        check(commit(store,new DurableMetadataStage(proposed.transfer().manifest(),0,1,changedBlob.reference()),List.of(changedBlob)).ackStatus()==AckStatus.REJECTED,"contradictory duplicate page refused");
        durable(store,new DurableAggregate(state(proposed.source().locations().getFirst(),proposed.source(),1,true)),List.of(),"concurrent real aggregate mutation committed");
        check(commit(store,activation(proposed,Optional.empty()),List.of()).ackStatus()==AckStatus.REJECTED,"activation compares current exact per-key revisions");
        check(snapshot(store).generations().isEmpty(),"CAS refusal never activates partial source");
        check(keys(store,snapshot(store)).size()==1,"CAS refusal preserves independently committed aggregate");
        close(store);
        store=open(root.resolve("missing-source"));var missing=generation(source(2,4,1,SOURCE),0,List.of());page(store,missing,0);
        check(commit(store,activation(missing,Optional.empty()),List.of()).ackStatus()==AckStatus.REJECTED,"activation requires original complete source bytes");
        close(store);
    }
    private static void extraGuardAndBounds(Path root)throws Exception{
        JournalStore store=open(root);var proposed=generation(source(1,11,1,SOURCE),0,List.of());
        var guardSource=source(1,12,1,OTHER);var guard=state(guardSource.locations().getFirst(),guardSource,1,true);
        durable(store,new DurableAggregate(guard),List.of(),"real prerequisite head committed");
        var base=proposed.pages().getFirst();var guards=new HashMap<>(base.beforeRevisions());guards.put(guard.key(),new Revision(1));
        var guarded=new MetadataGenerationPage(base.generation(),base.sourceNamespace(),base.exportRevision(),base.exportDigest(),0,1,base.states(),guards);
        proposed=new Generation(proposed.source(),proposed.transfer(),List.of(guarded));stage(store,proposed);
        durable(store,new DurableAggregate(state(guardSource.locations().getFirst(),guardSource,2,true)),List.of(),"unchanged prerequisite independently advanced");
        check(commit(store,activation(proposed,Optional.empty()),List.of()).ackStatus()==AckStatus.REJECTED,"extra unchanged prerequisite CAS checked at activation");
        var current=snapshot(store);check(current.generations().isEmpty(),"failed extra guard does not expose candidate");
        var actual=read(store,current,List.of(guard.key()));check(actual.get(guard.key()).header().revision().value()==2,"guard remains owned by actual concurrent writer");
        try{store.metadataKeysPage(current,EnumSet.allOf(AggregateKind.class),Optional.empty(),513);throw new AssertionError("oversized metadata key page accepted");}
        catch(IllegalArgumentException expected){assertions++;}
        var tooMany=generation(source(97,13,1,SOURCE),0,List.of());var rows=new ArrayList<AggregateState>();var revisions=new HashMap<AggregateKey,Revision>();
        for(var page:tooMany.pages()){rows.addAll(page.states());revisions.putAll(page.beforeRevisions());}
        try{new MetadataGenerationPage(LocationTransfer.identity(tooMany.transfer().manifest()),SOURCE,new Revision(1),tooMany.source().exportDigest(),0,1,rows,revisions);throw new AssertionError("97-entry native metadata page accepted");}
        catch(IllegalArgumentException expected){assertions++;}
        close(store);
    }
    private static DirectorFact legacyFact(LocationImport source){
        String identity="director-location-import-v1/"+WORLD.value()+"/"+source.sourceNamespace().value()+"/"+source.exportRevision().value()+"/"+source.exportDigest().value();
        return new DirectorFact(new FactId(UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8))),new FactType("proutlost_director:location_import_applied"),
            List.of(new ScopeRef(ScopeKind.WORLD,WORLD.value())),Optional.empty(),EvidenceStatus.CONFIRMED,Map.of(
                "source_namespace",new JsonValue.Text(source.sourceNamespace().value()),
                "export_revision",new JsonValue.Text(Long.toString(source.exportRevision().value())),
                "export_digest",new JsonValue.Text(source.exportDigest().value()),
                "location_import_base64",new JsonValue.Text(Base64.getEncoder().encodeToString(new ModelCodec<>(LocationImport.class).encode(source))),
                "tick",new JsonValue.Text("1"),"operator_authored",new JsonValue.Bool(true)),true,Optional.empty());
    }
    private static void legacyMigration(Path root)throws Exception{
        JournalStore store=open(root);var legacy=source(2,14,1,SOURCE);var records=new ArrayList<DurableRecord>();
        for(var location:legacy.locations())records.add(new DurableAggregate(state(location,legacy,1,true)));records.add(new DurableFact(legacyFact(legacy)));
        var first=new DurableBatch(new TransactionId(id(14,900)),store.session().storeGeneration(),Map.of(),records,List.of(),Optional.empty(),BlobContent.hash("legacy-migration".getBytes(StandardCharsets.UTF_8)));
        check(await(store.append(first)).ackStatus()==AckStatus.DURABLE,"actual legacy APPLIED fact and heads durable");
        var old=snapshot(store);check(old.generations().isEmpty(),"legacy fact is not fabricated generation activation");
        check(await(store.readMetadataSource(old,SOURCE)).orElseThrow().equals(legacy),"authoritative legacy source recovered from J04 fact");
        for(var location:legacy.locations())check(store.metadataOwner(old,new AggregateKey(AggregateKind.LOCATION,location.locationId().value())).orElseThrow().equals(SOURCE),"legacy UUID source reservation imported");
        var foreign=generation(source(2,14,1,OTHER),1,List.of());stage(store,foreign);
        check(commit(store,activation(foreign,Optional.empty()),List.of()).ackStatus()==AckStatus.REJECTED,"another source cannot take legacy UUIDs");
        var successor=generation(source(2,14,2,SOURCE),1,List.of());stage(store,successor);activate(store,successor,Optional.empty());
        var current=snapshot(store);check(current.generations().get(SOURCE).equals(LocationTransfer.identity(successor.transfer().manifest())),"legitimate generation replaces exact legacy source");
        check(await(store.readMetadataSource(old,SOURCE)).orElseThrow().equals(legacy),"legacy pinned source remains exact after upgrade");
        await(store.checkpoint());close(store);store=open(root);current=snapshot(store);
        check(await(store.readMetadataSource(current,SOURCE)).orElseThrow().equals(successor.source()),"upgraded exact source survives cold J04 recovery");
        close(store);
    }
    private static void coverageAndCancellation(Path root)throws Exception{
        JournalStore store=open(root.resolve("coverage"));var complete=generation(source(2,15,1,SOURCE),0,List.of());var first=complete.pages().getFirst();
        var only=first.states().getFirst();var incomplete=new MetadataGenerationPage(first.generation(),first.sourceNamespace(),first.exportRevision(),first.exportDigest(),0,1,List.of(only),Map.of(only.key(),new Revision(0)));
        var missing=new Generation(complete.source(),complete.transfer(),List.of(incomplete));stage(store,missing);
        check(commit(store,activation(missing,Optional.empty()),List.of()).ackStatus()==AckStatus.REJECTED,"source identity alone cannot activate missing native states");
        check(keys(store,snapshot(store)).isEmpty(),"incomplete semantic coverage leaves all heads hidden");close(store);
        store=open(root.resolve("cancellation"));var abandoned=generation(source(2,16,1,SOURCE),0,List.of());stage(store,abandoned);
        durable(store,new DurableMetadataCancellation(abandoned.transfer().manifest()),List.of(),"explicit hidden proposal cancellation durable");
        check(commit(store,activation(abandoned,Optional.empty()),List.of()).ackStatus()==AckStatus.REJECTED,"cancelled proposal cannot activate");
        check(keys(store,snapshot(store)).isEmpty(),"cancellation leaves no visible metadata");
        var corrected=generation(source(2,16,2,SOURCE),0,List.of());stage(store,corrected);activate(store,corrected,Optional.empty());
        check(keys(store,snapshot(store)).size()==2,"different traced generation may follow explicit cancellation");
        check(commit(store,new DurableMetadataCancellation(corrected.transfer().manifest()),List.of()).ackStatus()==AckStatus.REJECTED,"cancellation cannot undo active generation");
        close(store);store=open(root.resolve("cancellation"));
        check(keys(store,snapshot(store)).size()==2,"active corrected generation and cancellation fence survive restart");
        check(commit(store,activation(abandoned,Optional.empty()),List.of()).ackStatus()==AckStatus.REJECTED,"cold cancellation cannot revive historical proposal");close(store);
    }
    private static RegionRecord region(ImportedRegion original,long revision,RegionStatus status){
        var b=original.bounds();
        return new RegionRecord(new AggregateHeader(1,original.regionId().value(),new Revision(revision),new Sequence(0),new Tick(1)),original.regionId(),DIM,status,
            new SupportShape(List.of(new Aabb(DIM,b.minX(),b.minY(),b.minZ(),b.maxXExclusive(),b.maxYExclusive(),b.maxZExclusive())),List.of(),List.of()),
            Set.of(),new UnitValue(0),Set.of(),Set.of(),new Revision(revision),new RegionPersonality(Map.of()),Set.of(new MemoryId(original.regionId().value())));
    }
    private static EntityOwnershipAggregate entity(int index,EntityPhase phase,OwnershipDisposition disposition,ArrivalPolicy arrival){
        return new EntityOwnershipAggregate(new EntityOwnershipRecord(new AggregateHeader(1,id(18,index),new Revision(1),new Sequence(0),new Tick(1)),
            new OwnerToken(id(18,100+index)),id(18,200+index),new EventInstanceId(id(18,300+index)),new CohortId(id(18,400+index)),"synthetic-slot-"+index,
            phase,disposition,new ChunkRef(DIM,100,100),new Tick(1),arrival,CleanupReason.COMPLETED,Optional.empty(),new Epoch(1)));
    }
    private static OwnershipAggregate ownership(int index,OwnershipDisposition disposition){
        return new OwnershipAggregate(new OwnershipRecord(new AggregateHeader(1,id(19,index),new Revision(1),new Sequence(0),new Tick(1)),
            new ResourceKey(OwnershipDomain.BLOCK,DIM,"synthetic-budget-resource-"+index),Set.of(new OwnerToken(id(19,100+index))),new Epoch(1),disposition,
            new MutationId(id(19,200+index)),new Fingerprint("0".repeat(64)),new Epoch(0),Optional.empty()));
    }
    private static void budgetHeads(Path root)throws Exception{
        JournalStore store=open(root);var regions=List.of(new ImportedRegion(new RegionId(id(17,0)),DIM,new ImportBounds(0,0,0,16,16,16),Set.of()),
            new ImportedRegion(new RegionId(id(17,1)),DIM,new ImportBounds(32,0,32,48,16,48),Set.of()));
        var draft=new LocationImport(1,WORLD,SOURCE,new Revision(1),new Fingerprint("0".repeat(64)),regions,List.of(),List.of());
        var source=new LocationImport(1,WORLD,SOURCE,draft.exportRevision(),StandaloneLocationImporter.digest(draft),regions,List.of(),List.of());
        var transfer=LocationTransfer.prepare(source);var states=new ArrayList<AggregateState>();var before=new HashMap<AggregateKey,Revision>();
        for(var row:regions){
            var actual=new RegionAggregate(region(row,1,RegionStatus.ACTIVE));states.add(actual);before.put(actual.key(),new Revision(0));
            var memory=new RegionMemoryAggregate(new RegionMemory(new AggregateHeader(1,row.regionId().value(),new Revision(1),new Sequence(0),new Tick(1)),row.regionId(),Map.of(),new Tick(0),new Tick(0),Set.of(),Set.of(),new RegionActivityState(Map.of(),Map.of())));
            states.add(memory);before.put(memory.key(),new Revision(0));
        }
        var page=new MetadataGenerationPage(LocationTransfer.identity(transfer.manifest()),SOURCE,new Revision(1),source.exportDigest(),0,1,states,before);
        var generation=new Generation(source,transfer,List.of(page));stage(store,generation);activate(store,generation,Optional.empty());
        var initial=snapshot(store);var budget=store.metadataBudgetSnapshot(initial).orElseThrow();
        check(budget.activeRegions().size()==2,"cold activated region heads counted without metadata hydration");
        durable(store,new DurableAggregate(new RegionAggregate(region(regions.get(0),2,RegionStatus.DORMANT))),List.of(),"actual normal DORMANT region update durable");
        check(store.metadataBudgetSnapshot(snapshot(store)).orElseThrow().activeRegions().size()==1,"normal DORMANT head overrides cold ACTIVE budget head");
        durable(store,new DurableAggregate(new RegionAggregate(region(regions.get(1),2,RegionStatus.ARCHIVED))),List.of(),"actual normal ARCHIVED region update durable");
        check(store.metadataBudgetSnapshot(snapshot(store)).orElseThrow().activeRegions().isEmpty(),"normal ARCHIVED head overrides cold ACTIVE budget head");
        durable(store,new DurableAggregate(entity(0,EntityPhase.PRESENT,OwnershipDisposition.DIRECTOR_ACTIVE,ArrivalPolicy.SETTLE_WORLD)),List.of(),"nonselected persistent entity durable");
        durable(store,new DurableAggregate(entity(1,EntityPhase.PRESENT,OwnershipDisposition.DIRECTOR_ACTIVE,ArrivalPolicy.CLEANUP_OWNED)),List.of(),"nonselected temporary entity durable");
        durable(store,new DurableAggregate(entity(2,EntityPhase.REMOVED,OwnershipDisposition.RELEASED,ArrivalPolicy.SETTLE_WORLD)),List.of(),"removed entity durable");
        durable(store,new DurableAggregate(ownership(0,OwnershipDisposition.DIRECTOR_ACTIVE)),List.of(),"nonselected active ownership durable");
        durable(store,new DurableAggregate(ownership(1,OwnershipDisposition.RELEASED)),List.of(),"released ownership durable");
        var current=snapshot(store);budget=store.metadataBudgetSnapshot(current).orElseThrow();
        check(budget.liveEntities()==2&&budget.persistentEntities()==1&&budget.mutationsInFlight()==1,"budget counts nonselected operational heads and excludes removed/released");
        check(store.metadataBudgetSnapshot(initial).orElseThrow().activeRegions().size()==2,"budget remains coherent in pinned earlier snapshot");
        await(store.checkpoint());
        if(archiveProbe){
            var key=new AggregateKey(AggregateKind.REGION,regions.getFirst().regionId().value());
            var read=await(store.readArchivedIndexed(new StoreQuery(WORLD,Set.of(key),new Sequence(0),Optional.empty(),16)));
            var otherQuery=new StoreQuery(WORLD,Set.of(new AggregateKey(AggregateKind.EVENT,id(90,0))),new Sequence(0),Optional.empty(),16);
            var other=await(store.readArchivedIndexed(otherQuery));ReadContracts.validate(other,otherQuery,store.session());
            boolean healthy=!store.readOnlySafe();close(store);
            System.out.println("ACTUAL_ARCHIVED_METADATA_READ="+read.status()+" EVENT_READ="+other.status()+" WRITABLE="+healthy);
            check(other.status()==ReadStatus.AVAILABLE&&other.page().orElseThrow().aggregates().isEmpty(),"nonmetadata archive retains verified exact empty query");
            check(read.status()==ReadStatus.NOT_RESIDENT,"unsupported legacy archive representation is NOT_RESIDENT");
            check(healthy,"bounded archive refusal never fences healthy native store");return;
        }
        close(store);store=open(root);budget=store.metadataBudgetSnapshot(snapshot(store)).orElseThrow();
        check(budget.activeRegions().isEmpty()&&budget.liveEntities()==2&&budget.persistentEntities()==1&&budget.mutationsInFlight()==1,"cold budget matches actual latest heads");close(store);
    }
    private static void corruption(Path root)throws Exception{
        JournalStore store=open(root);var proposed=generation(source(2,5,1,SOURCE),0,List.of());stage(store,proposed);
        var page=proposed.pages().getFirst();var blob=BlobContent.capture(new ResourceId("proutlost_director:metadata_generation_page"),1,PAGE.encode(page));close(store);
        Path path=root.resolve("blobs").resolve(blob.reference().hash().value()+".blob");byte[] bytes=Files.readAllBytes(path);bytes[0]^=1;Files.write(path,bytes);
        store=open(root);check(store.readOnlySafe(),"cold corrupted staged J04 blob fences store");close(store);
    }
    private static Generation crashOld(){return generation(source(2,6,1,SOURCE),0,List.of());}
    private static Generation crashNext(){return generation(source(120,6,2,SOURCE),0,List.of());}
    private static void crashChild(Path root,String point)throws Exception{
        var old=crashOld();var next=crashNext();var armed=new AtomicBoolean();
        var store=await(JournalStore.open(root,WORLD,OPTIONS,seen->{if(armed.get()&&seen.equals(point))Runtime.getRuntime().halt(72);}));
        stage(store,old);activate(store,old,Optional.empty());
        // Replacement reuses first two identities: exact revisions are carried per key.
        var rows=new ArrayList<AggregateState>();var before=new HashMap<AggregateKey,Revision>();
        for(int i=0;i<next.source().locations().size();i++){
            var row=state(next.source().locations().get(i),next.source(),i<2?2:1,true);rows.add(row);before.put(row.key(),new Revision(i<2?1:0));
        }
        var pages=new ArrayList<MetadataGenerationPage>();int count=(rows.size()+95)/96;
        for(int i=0;i<count;i++){
            var selected=rows.subList(i*96,Math.min(rows.size(),(i+1)*96));var guards=new HashMap<AggregateKey,Revision>();
            selected.forEach(s->guards.put(s.key(),before.get(s.key())));
            pages.add(new MetadataGenerationPage(LocationTransfer.identity(next.transfer().manifest()),SOURCE,next.source().exportRevision(),next.source().exportDigest(),i,count,selected,guards));
        }
        next=new Generation(next.source(),next.transfer(),pages);stage(store,next);armed.set(true);
        await(store.append(batch(store,activation(next,Optional.of(LocationTransfer.identity(old.transfer().manifest()))),List.of(),"crash-activation-fixed")));
        throw new AssertionError("crash point not reached "+point);
    }
    private static void crashInspect(Path root)throws Exception{
        var store=open(root);check(!store.readOnlySafe(),"fresh JVM recovery after abrupt QA halt healthy");
        var snap=snapshot(store);var observed=read(store,snap,keys(store,snap));
        check(observed.size()==2||observed.size()==120,"abrupt death exposes complete old or complete new heads only");
        var retained=await(store.readMetadataSource(snap,SOURCE)).orElseThrow();check(retained.equals(crashOld().source())||retained.equals(crashNext().source()),"recovered source matches complete old/new generation");
        check(observed.size()==retained.locations().size(),"recovered visible head count matches the active source");
        for(int i=0;i<retained.locations().size();i++){
            var location=retained.locations().get(i);var key=new AggregateKey(AggregateKind.LOCATION,location.locationId().value());
            long revision=retained.exportRevision().value()==1?1:i<2?2:1;
            check(state(location,retained,revision,true).equals(observed.get(key)),"recovered actual head contents belong entirely to the active generation");
        }
        var old=crashOld();var next=crashNext();
        check(await(store.append(batch(store,activation(next,Optional.of(LocationTransfer.identity(old.transfer().manifest()))),List.of(),"crash-activation-fixed"))).ackStatus()==AckStatus.DURABLE,"same activation transaction resumes idempotently after abrupt death");
        check(keys(store,snapshot(store)).size()==120,"resumed generation fully visible from new JVM");close(store);
        System.out.println("PASS fresh-JVM crash inspect assertions="+assertions);
    }
    private static void abruptJvmRecovery(Path root)throws Exception{
        String java=Path.of(System.getProperty("java.home"),"bin","java").toString();int points=0;
        for(String point:List.of("BLOBS_FORCED","TX_COMMIT","WAL_FORCED","BEFORE_ACK")){
            Path directory=root.resolve(point);Files.createDirectories(directory);
            var child=new ProcessBuilder(java,"-Xmx192m","-XX:ActiveProcessorCount=1","-cp",System.getProperty("java.class.path"),MetadataGenerationStoreTests.class.getName(),"crash-child",directory.toString(),point).redirectErrorStream(true).start();
            boolean ended=child.waitFor(40,TimeUnit.SECONDS);if(!ended)child.destroyForcibly();String childLog=new String(child.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
            check(ended&&child.exitValue()==72,"real abrupt child JVM death at "+point+" "+childLog);
            var inspect=new ProcessBuilder(java,"-Xmx192m","-XX:ActiveProcessorCount=1","-cp",System.getProperty("java.class.path"),MetadataGenerationStoreTests.class.getName(),"crash-inspect",directory.toString()).redirectErrorStream(true).start();
            boolean inspected=inspect.waitFor(40,TimeUnit.SECONDS);if(!inspected)inspect.destroyForcibly();String inspectLog=new String(inspect.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
            check(inspected&&inspect.exitValue()==0,"independent new JVM inspects native heads at "+point+" "+inspectLog);points++;
        }
        System.out.println("ABRUPT_METADATA_JVM_POINTS="+points+" independent_inspection_JVMs="+points);
    }

    private static void snapshotReuse(Path root)throws Exception{
        JournalStore store=open(root);var ports=store.metadataReads();
        var empty=snapshot(store);check(empty==snapshot(store),"unchanged committed cut avoids rebuilding metadata identities");
        check(ports.owns(empty),"cached snapshot retains separate read capability");
        var fixture=generation(source(3,71,1,SOURCE),0,List.of());stage(store,fixture);
        var staged=snapshot(store);check(staged!=empty&&staged.cut().value()>empty.cut().value(),"hidden staging changes pinned cut");
        check(empty.generations().isEmpty()&&keys(store,empty).isEmpty(),"old pinned cut remains empty after new staging");
        activate(store,fixture,Optional.empty());var active=snapshot(store);
        check(active!=staged&&active.cut().value()>staged.cut().value(),"activation invalidates cached cut");
        check(active.generations().get(SOURCE).equals(LocationTransfer.identity(fixture.transfer().manifest())),"new cached cut contains verified active generation identity");
        check(active.sources().get(SOURCE).equals(fixture.transfer().manifest()),"new cached cut retains exact source manifest");
        check(keys(store,active).size()==3&&keys(store,staged).isEmpty(),"activation cannot expose new heads through old cut");
        var before=read(store,active,keys(store,active));
        for(int i=0;i<256;i++)check(snapshot(store)==active,"same-cut repeated readers reuse immutable snapshot");
        check(read(store,active,keys(store,active)).equals(before),"same-cut reuse preserves exact selected body contents");
        check(ports.owns(empty)&&ports.owns(staged)&&ports.owns(active),"all earlier pinned cuts remain valid before revocation");
        close(store);
        check(store.metadataSnapshot().isEmpty()&&!ports.owns(active),"close revokes cached and previously pinned handles");
        store=open(root);var cold=snapshot(store);
        check(!cold.session().equals(active.session())&&cold!=active,"new JVM-session reconstruction never reuses old handle");
        check(!store.metadataReads().owns(active),"foreign previous store handle remains revoked");
        check(cold.generations().equals(active.generations())&&keys(store,cold).size()==3,"cold recovery reconstructs same verified metadata generation");
        close(store);
        System.out.println("PASS MetadataSnapshotReuse scope=real_J04_disk_pinned_cuts_and_revocation_not_native_performance");
    }


    /** Counts traversals of the genuine warmed J04 hydration map. Values are
     * untouched; this observation grants no storage or execution authority. */
    private static final class TraversalObservedMap extends AbstractMap<Object,Object>{
        private final Map<?,?> delegate;int traversals;
        TraversalObservedMap(Map<?,?> delegate){this.delegate=delegate;}
        @Override public int size(){return delegate.size();}
        @Override public Object get(Object key){return delegate.get(key);}
        @SuppressWarnings({"unchecked","rawtypes"})
        @Override public Set<Map.Entry<Object,Object>> entrySet(){traversals++;return (Set)delegate.entrySet();}
    }
    private static void residentReadCacheIsolation(Path root,boolean optimized)throws Exception{
        JournalStore store=open(root);
        try{
            var fixture=generation(source(4,191,1,SOURCE),0,List.of());
            stage(store,fixture);activate(store,fixture,Optional.empty());var pinned=snapshot(store);
            var selected=Set.of(keys(store,pinned).getFirst());
            var coldQuery=new StoreQuery(WORLD,selected,new Sequence(0),Optional.empty(),1);
            var cold=await(store.readMetadata(pinned,coldQuery));ReadContracts.validate(cold,coldQuery,pinned.session());
            check(cold.status()==ReadStatus.AVAILABLE&&cold.page().orElseThrow().aggregates().size()==1,"genuine cold row warmed");
            var field=JournalStore.class.getDeclaredField("metadataCache");field.setAccessible(true);
            var genuine=(Map<?,?>)field.get(store);check(!genuine.isEmpty(),"actual metadata cache nonempty");
            var observed=new TraversalObservedMap(genuine);field.set(store,observed);
            var worldKey=new AggregateKey(AggregateKind.WORLD,WORLD.value());
            var worldQuery=new StoreQuery(WORLD,Set.of(worldKey),new Sequence(0),Optional.empty(),1);
            for(int i=0;i<12;i++){
                var result=store.view(worldQuery);ReadContracts.validate(result,worldQuery,pinned.session());
                check(result.status()==ReadStatus.AVAILABLE&&result.page().orElseThrow().aggregates().isEmpty(),"unrelated absent operational head keeps exact empty result");
                check(result.page().orElseThrow().lastDurableSequence().equals(pinned.cut()),"resident result retains exact durable cut");
            }
            int traversals=observed.traversals;
            System.out.println("RESIDENT_ONLY_COLD_CACHE_TRAVERSALS="+traversals+" optimized="+optimized);
            check(optimized?traversals==0:traversals>0,"unrelated cold cache traversal counterexample or correction");
            var async=await(store.readMetadata(pinned,worldQuery));ReadContracts.validate(async,worldQuery,pinned.session());
            check(async.status()==ReadStatus.AVAILABLE,"asynchronous resident-only query retains semantics");
            if(optimized)check(field.get(store)==observed&&observed.traversals==0,"resident async read neither walks nor clears warmed cold cache");
            long pageLoads=store.metadataHydrationPageLoads();
            var stillWarm=store.view(coldQuery);ReadContracts.validate(stillWarm,coldQuery,pinned.session());
            check(stillWarm.status()==ReadStatus.AVAILABLE&&stillWarm.page().orElseThrow().aggregates().equals(cold.page().orElseThrow().aggregates()),"cold head remains immediately available and exact");
            check(store.metadataHydrationPageLoads()==pageLoads,"resident reads do not force new cold IO");
            var mixedKeys=new HashSet<>(selected);mixedKeys.add(worldKey);
            var mixedQuery=new StoreQuery(WORLD,mixedKeys,new Sequence(0),Optional.empty(),2);
            var mixed=await(store.readMetadata(pinned,mixedQuery));ReadContracts.validate(mixed,mixedQuery,pinned.session());
            check(mixed.status()==ReadStatus.AVAILABLE&&mixed.page().orElseThrow().aggregates().equals(cold.page().orElseThrow().aggregates()),"mixed cold and operational query retains complete cold path");
            check(store.metadataCachedEntries()<=512&&store.metadataCachedEntries()>0,"existing hydration cache limits and contents retained");
            var future=new StoreQuery(WORLD,Set.of(worldKey),new Sequence(pinned.cut().value()+1),Optional.empty(),1);
            check(store.view(future).status()==ReadStatus.NOT_RESIDENT,"future sequence never made current by fast path");
            var wrong=new StoreQuery(new WorldId(new UUID(999,999)),Set.of(worldKey),new Sequence(0),Optional.empty(),1);
            check(store.view(wrong).status()==ReadStatus.UNAVAILABLE,"foreign world remains unavailable");
            var stale=new StoreQuery(WORLD,Set.of(worldKey),new Sequence(0),Optional.of("invalid"),1);
            check(store.view(stale).status()==ReadStatus.STALE,"invalid cursor stays stale");
        }finally{close(store);}
        check(store.metadataSnapshot().isEmpty(),"closed session revokes snapshot even with warmed resident and cold reads");
    }

    public static void main(String[] args)throws Exception{
        if(args.length>0&&Set.of("resident-cache-isolation","resident-cache-cost-before").contains(args[0])){
            residentReadCacheIsolation(Path.of(args[1]),args[0].equals("resident-cache-isolation"));
            System.out.println("PASS ResidentReadCacheIsolation assertions="+assertions+" scope=REAL_J04_SYNTHETIC_METADATA_NOT_NATIVE_ACCEPTANCE");return;
        }

        if(args.length>0&&args[0].equals("hydration-only")){groupedHydration(Path.of(args[1]));System.out.println("PASS GroupedMetadataHydration assertions="+assertions);return;}
        if(args.length>0&&args[0].equals("archive-race")){archiveProbe=true;budgetHeads(Path.of(args[1]));System.out.println("PASS ArchivedMetadataIndexFence assertions="+assertions);return;}
        if(args.length>0&&args[0].equals("budget-only")){budgetHeads(Path.of(args[1]));System.out.println("PASS MetadataBudget assertions="+assertions);return;}
        if(args.length>0&&args[0].equals("crash-child")){crashChild(Path.of(args[1]),args[2]);return;}
        if(args.length>0&&args[0].equals("crash-inspect")){crashInspect(Path.of(args[1]));return;}
        Path root=args.length==1?Path.of(args[0]):Files.createTempDirectory("P008-J04-metadata-synthetic-");Files.createDirectories(root);
        snapshotReuse(root.resolve("snapshot-reuse"));multiPageAndCold(root.resolve("multi-page"));boundedAndReplacement(root.resolve("bounded-replacement"));
        conflicts(root.resolve("conflicts"));extraGuardAndBounds(root.resolve("guards-bounds"));legacyMigration(root.resolve("legacy"));coverageAndCancellation(root.resolve("coverage-cancellation"));budgetHeads(root.resolve("budget"));corruption(root.resolve("corruption"));abruptJvmRecovery(root.resolve("abrupt-jvm"));
        System.out.println("PASS MetadataGenerationStoreTests assertions="+assertions+" scope=ACTUAL_J04_SYNTHETIC_METADATA_NOT_MINECRAFT_OR_CANONICAL_D0");
    }
}
