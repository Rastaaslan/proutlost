package fr.proutlost.director.memory;
import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.bootstrap.DirectorNativeLocationGenerationPlan;
import fr.proutlost.director.integration.locations.*;
import fr.proutlost.director.persistence.*;
import fr.proutlost.director.runtime.ResultTransaction;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Current-cut bounded refresh qualification using unchanged R2 fixture and writer.
 * Actual MemoryReducer regional activity writes REGION+REGION_MEMORY atomically.
 * No PLAYER presence input is included: it would alter selected dependencies.
 * LIVE means liveDependencies=true on isolated J04.25 identities/regions are
 * synthetic aggregate inputs, never25 Minecraft clients or a TPS/load proof.
 * Acceptance is fixed before measurement: >=1 current verified publication
 * during each12s pressure window, plus a successful quiescent final scan. */
public final class LiveMetadataRefreshPressureTests {
    private static final WorldId WORLD=new WorldId(new UUID(0x503030384c495645L,1));
    private static final DimensionId DIM=new DimensionId("minecraft:overworld");
    private static final StoreOptions OPTIONS=new StoreOptions(4_194_400,4_194_304,16_777_216,33_554_432,32,0,128);
    private static final long WINDOW_NS=12_000_000_000L,PHASE_NS=400_000_000L;
    private static final AtomicInteger assertions=new AtomicInteger();
    private static void check(boolean value,String why){assertions.incrementAndGet();if(!value)throw new AssertionError(why);}
    private static <T>T await(CompletionStage<T> future)throws Exception{return future.toCompletableFuture().get(60,TimeUnit.SECONDS);}
    private static UUID id(int namespace,int n){return new UUID(namespace,n);}
    private static AggregateHeader header(UUID id,long revision,long tick){return new AggregateHeader(1,id,new Revision(revision),new Sequence(0),new Tick(tick));}
    private static ConfigView profile(Path file)throws Exception{
        var raw=((JsonValue.ObjectValue)((JsonValue.ObjectValue)CanonicalJsonV1.decode(Files.readAllBytes(file))).values().get("values")).values();var values=new HashMap<String,JsonValue.Number>();raw.forEach((k,v)->values.put(k,(JsonValue.Number)v));
        return new ConfigView(new Revision(1),BlobContent.hash(Files.readAllBytes(file)),Calibration.DEFAULT_FOR_TESTING,values);
    }
    private static void commit(JournalStore store,List<DurableRecord> records,List<BlobContent> blobs)throws Exception{
        var batch=ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),records,blobs);
        check(await(store.append(batch)).ackStatus()==AckStatus.DURABLE,"declared QA J04 append durable");
    }
    private static LocationImport signed(ResourceId source,List<ImportedRegion> regions,List<ImportedLocation> places,List<ImportedAnchor> anchors){
        var draft=new LocationImport(1,WORLD,source,new Revision(1),new Fingerprint("0".repeat(64)),regions,places,anchors);
        return new LocationImport(1,WORLD,source,draft.exportRevision(),StandaloneLocationImporter.digest(draft),regions,places,anchors);
    }
    private static void install(JournalStore store,LocationImport input)throws Exception{
        var prepared=LocationTransfer.prepare(input);var cut=store.metadataSnapshot().orElseThrow();
        for(var part:prepared.pages()){var blob=BlobContent.capture(new ResourceId("proutlost_director:location_transfer_fragment"),1,Base64.getDecoder().decode(part.bytesBase64()));commit(store,List.of(new DurableMetadataSourcePage(prepared.manifest(),part.pageIndex(),blob.reference())),List.of(blob));}
        var plan=new DirectorNativeLocationGenerationPlan(WORLD,input,null,new Tick(1),key->store.metadataOwner(cut,key));
        for(int n=0;n<plan.pageCount();n++){
            var page=plan.planPage(plan.pageKeys(n),Map.of());var dto=new MetadataGenerationPage(LocationTransfer.identity(prepared.manifest()),input.sourceNamespace(),input.exportRevision(),input.exportDigest(),n,plan.pageCount(),page.states(),page.beforeRevisions());
            var blob=BlobContent.capture(new ResourceId("proutlost_director:metadata_generation_page"),1,new ModelCodec<>(MetadataGenerationPage.class).encode(dto));commit(store,List.of(new DurableMetadataStage(prepared.manifest(),n,plan.pageCount(),blob.reference())),List.of(blob));
        }
        commit(store,List.of(new DurableMetadataActivation(prepared.manifest(),Optional.empty(),plan.pageCount())),List.of());
    }
    private record Fixture(List<RegionId> selected,List<RegionId> outside,Set<PlayerId> players,Set<BoundedMetadataProjection.Cell> cells,int sourceLocations,int sourceCombined,int catalogueHeads,int runtimeHeads){}
    private static Fixture fixture(JournalStore store,boolean large)throws Exception{
        List<RegionId> selected=new ArrayList<>(),outside=new ArrayList<>();var players=new HashSet<PlayerId>();var cells=new HashSet<BoundedMetadataProjection.Cell>();int locations,combined,heads,runtime;
        if(!large){
            var regions=new ArrayList<ImportedRegion>();var places=new ArrayList<ImportedLocation>();var anchors=new ArrayList<ImportedAnchor>();
            for(int n=0;n<340;n++){
                var region=new RegionId(id(91,n));var place=new LocationId(id(92,n));var anchor=new AnchorId(id(93,n));var bounds=new ImportBounds(n*128,0,0,n*128+16,128,16);
                regions.add(new ImportedRegion(region,DIM,bounds,Set.of()));places.add(new ImportedLocation(place,"qa_"+n,Set.of(region),DIM,bounds,Set.of()));anchors.add(new ImportedAnchor(anchor,place,DIM,new ImportPosition(n*128+1,64,1),Set.of(new TagId("proutlost:anchor_center"))));
                if(n<25)selected.add(region);if(n>=100&&n<125)outside.add(region);
            }
            install(store,signed(new ResourceId("test:p008_live_1360"),regions,places,anchors));locations=340;combined=1020;heads=1360;runtime=0;
        }else{
            for(int part=0;part<2;part++){
                var region=new RegionId(id(94,part));var places=new ArrayList<ImportedLocation>();
                for(int n=0;n<2048;n++)places.add(new ImportedLocation(new LocationId(id(95+part,n)),"q"+n,Set.of(region),DIM,new ImportBounds(n*64,0,part*64,n*64+8,128,part*64+8),Set.of()));
                install(store,signed(new ResourceId("test:p008_live_4096_"+part),List.of(new ImportedRegion(region,DIM,new ImportBounds(0,0,part*64,2048*64,128,part*64+8),Set.of())),places,List.of()));
            }
            List<DurableRecord> records=new ArrayList<>();for(int n=0;n<50;n++){
                var region=new RegionId(id(98,n));int z=n<25?1000:3000;int x=(n%25)*128;
                records.add(new DurableAggregate(new RegionAggregate(new RegionRecord(header(region.value(),1,1),region,DIM,RegionStatus.ACTIVE,new SupportShape(List.of(new Aabb(DIM,x,0,z,x+16,128,z+16)),List.of(),List.of()),Set.of(),new UnitValue(0),Set.of(),Set.of(),new Revision(1),new RegionPersonality(Map.of()),Set.of(new MemoryId(region.value()))))));
                records.add(new DurableAggregate(new RegionMemoryAggregate(emptyMemory(region,1,1,0))));if(n<25)selected.add(region);else outside.add(region);
            }
            commit(store,records,List.of());locations=4096;combined=4098;heads=4100;runtime=100;
        }
        List<DurableRecord> people=new ArrayList<>();for(int n=0;n<25;n++){
            var player=new PlayerId(id(99,n));players.add(player);int z=large?1000:0;
            cells.add(BoundedMetadataProjection.Demand.cell(new BlockPosition(DIM,n*128+1,64,z+1),16));
            people.add(new DurableAggregate(new PlayerOperationalAggregate(new PlayerOperationalMemory(header(player.value(),1,1),player,new DurationTicks(0),Optional.of(selected.get(n)),Optional.empty(),List.of(),List.of(),Set.of(),new UnitValue(0),Map.of(),Optional.empty(),new Epoch(0)))));
        }
        commit(store,people,List.of());return new Fixture(List.copyOf(selected),List.copyOf(outside),Set.copyOf(players),Set.copyOf(cells),locations,combined,heads,runtime+25);
    }
    private static RegionMemory emptyMemory(RegionId id,long revision,long tick,long counter){
        Map<MemoryComponent,DecayValue> components=counter==0?Map.of():Map.of(MemoryComponent.VISIT,new DecayValue(counter,new Tick(tick),"memory.visit_half_life_ticks",counter,new UnitValue(1)));
        return new RegionMemory(header(id.value(),revision,tick),id,components,new Tick(tick),new Tick(0),Set.of(),Set.of(),new RegionActivityState(Map.of(),Map.of()));
    }
    private static Map<String,JsonValue> selectionFields(RegionRecord region){
        var source=((JsonValue.ObjectValue)CanonicalJsonV1.decode(new ModelCodec<>(RegionRecord.class).encode(region))).values();
        var result=new TreeMap<String,JsonValue>();
        for(var name:List.of("dimension","status","support_shape","anchor_ids","parent_ids","aliases","membership_revision","memory_refs"))
            result.put(name,Objects.requireNonNull(source.get(name),"selection field "+name));
        return Map.copyOf(result);
    }
    private static Map<String,Object> reduceVisit(JournalStore store,MemorySettings settings,RegionId region,long tick,int index)throws Exception{
        var pinned=store.metadataReads().snapshot().orElseThrow();
        var regionKey=new AggregateKey(AggregateKind.REGION,region.value());var memoryKey=new AggregateKey(AggregateKind.REGION_MEMORY,region.value());
        var before=await(ScopedAggregateCapture.load(store,pinned,Set.of(regionKey,memoryKey),2));
        check(before.states().size()==2,"R2 writer reads both actual REGION+REGION_MEMORY bodies at one exact cut");
        var record=((RegionAggregate)before.states().get(regionKey)).value();var memory=((RegionMemoryAggregate)before.states().get(memoryKey)).value();
        var scopes=new ScopeSet(WORLD,Set.of(new ScopeRef(ScopeKind.REGION,region.value())));var view=MemoryProjection.of(scopes,before.states().values());
        check(view.revisions().get(new ScopeRef(ScopeKind.REGION,region.value())).equals(record.header().revision()),"R2 scope CAS is actual REGION revision");
        var now=new Tick(Math.max(tick,Math.max(record.header().updatedTick().value(),memory.header().updatedTick().value())));
        var observation=new DirectorObservation(new ObservationId(id(105,index)),new ResourceId("proutlost_director:activity"),new ScopeRef(ScopeKind.REGION,region.value()),now,Map.of("visit",1.0,"activity",1.0),new UnitValue(1));
        var context=new SnapshotBatch(new RequestId(id(106,index)),scopes,now,view.revisions(),new ObservationContext(Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(),Map.of()),Set.of(),Set.of(),HealthMode.NORMAL);
        var input=new ObservationBatch(List.of(observation.observationId()),List.of(observation),view.revisions(),context,List.of(),List.of(),Map.of());
        var reduction=new MemoryReducer(settings).reduce(view,input,new DirectorClock(now,0,Instant.EPOCH));
        check(reduction.aggregateChanges().size()==2,"real regional visit reducer emits exactly REGION and REGION_MEMORY");
        var nextStates=new HashMap<AggregateKey,AggregateState>();List<DurableRecord> records=new ArrayList<>();
        for(var change:reduction.aggregateChanges()){
            check(change.expectedRevision().equals(Optional.of(before.states().get(change.key()).header().revision())),"each reducer aggregate CAS matches exact authoritative before header");
            nextStates.put(change.key(),change.next());records.add(new DurableAggregate(change.next()));
        }
        var nextRecord=((RegionAggregate)nextStates.get(regionKey)).value();var nextMemory=((RegionMemoryAggregate)nextStates.get(memoryKey)).value();
        check(selectionFields(record).equals(selectionFields(nextRecord)),"actual reducer leaves every regional spatial/membership selection field identical");
        check(nextRecord.header().revision().equals(record.header().revision().plus(1))&&nextMemory.header().revision().equals(memory.header().revision().plus(1)),"actual REGION and REGION_MEMORY revisions both advance");
        check(!nextRecord.personality().equals(record.personality())&&!nextRecord.importance().equals(record.importance()),"actual visit reducer changes personality and importance enrichment");
        var batch=ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),view.revisions(),records,List.of());
        var ack=await(store.append(batch));check(ack.ackStatus()==AckStatus.DURABLE,"atomic real reducer REGION+REGION_MEMORY transaction durable");
        var committed=store.metadataReads().snapshot().orElseThrow();check(committed.cut().equals(ack.durableSequence()),"writer after-cut is exact acknowledged atomic append");
        var actual=await(ScopedAggregateCapture.load(store,committed,Set.of(regionKey,memoryKey),2));
        check(actual.states().equals(nextStates),"both actual post-commit J04 bodies equal complete real reducer output");
        return Map.ofEntries(Map.entry("region",region.value().toString()),Map.entry("beforeCut",pinned.cut().value()),Map.entry("committedCut",committed.cut().value()),Map.entry("beforeRegionRevision",record.header().revision().value()),Map.entry("afterRegionRevision",nextRecord.header().revision().value()),Map.entry("beforeMemoryRevision",memory.header().revision().value()),Map.entry("afterMemoryRevision",nextMemory.header().revision().value()),Map.entry("selectionFieldsBefore",selectionFields(record)),Map.entry("selectionFieldsAfter",selectionFields(nextRecord)),Map.entry("selectionFieldsIdentical",true),Map.entry("personalityChanged",true),Map.entry("importanceBefore",record.importance().value()),Map.entry("importanceAfter",nextRecord.importance().value()),Map.entry("input",CanonicalJsonV1.decode(new ModelCodec<>(ObservationBatch.class).encode(input))),Map.entry("beforeStates",states(before.states())),Map.entry("reducedStates",states(nextStates)),Map.entry("actualCommittedStates",states(actual.states())));
    }
    private static JsonValue json(Object value){
        if(value==null)return JsonValue.Nil.INSTANCE;if(value instanceof JsonValue j)return j;if(value instanceof String s)return new JsonValue.Text(s);if(value instanceof Boolean b)return new JsonValue.Bool(b);
        if(value instanceof Number n)return new JsonValue.Number(new BigDecimal(n.toString()));if(value instanceof Map<?,?> m){var result=new TreeMap<String,JsonValue>();m.forEach((k,v)->result.put(k.toString(),json(v)));return new JsonValue.ObjectValue(result);}
        if(value instanceof Collection<?> c)return new JsonValue.Array(c.stream().map(LiveMetadataRefreshPressureTests::json).toList());throw new IllegalArgumentException("report type "+value.getClass());
    }
    private static void write(Path file,Object value)throws Exception{Files.write(file,CanonicalJsonV1.encode(json(value)),StandardOpenOption.CREATE_NEW);}
    private static void line(Path file,Object value)throws Exception{
        var encoded=CanonicalJsonV1.encode(json(value));var output=Arrays.copyOf(encoded,encoded.length+1);output[encoded.length]=(byte)'\n';Files.write(file,output,StandardOpenOption.APPEND);
    }
    private static JsonValue states(Map<AggregateKey,AggregateState> data){var codec=new ModelCodec<>(AggregateState.class);return new JsonValue.Array(data.entrySet().stream().sorted(Comparator.comparing(e->e.getKey().kind()+":"+e.getKey().id())).map(e->CanonicalJsonV1.decode(codec.encode(e.getValue()))).toList());}
    private static long used(){return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();}
    private static Map<String,Object> measure(Path root,MemorySettings settings,boolean large,boolean updateSelected)throws Exception{
        Files.createDirectories(root);var initial=await(JournalStore.open(root.resolve("store"),WORLD,OPTIONS));Fixture fixture;
        try{fixture=fixture(initial,large);await(initial.checkpoint());}finally{await(initial.closeAsync());}
        var store=await(JournalStore.open(root.resolve("store"),WORLD,OPTIONS));
        try{
            check(store.metadataCachedEntries()==0,"fresh J04 session begins with cold metadata cache");
            var demand=new BoundedMetadataProjection.Demand(fixture.players(),fixture.cells(),16,Set.of());
            var targets=updateSelected?fixture.selected():fixture.outside();check(targets.size()==25,"writer targets25 distinct regions");
            var writerError=new AtomicReference<Throwable>();var writerRecords=Collections.synchronizedList(new ArrayList<Map<String,Object>>());var ready=new CountDownLatch(1);var start=new CountDownLatch(1);var epoch=new AtomicLong();
            var writer=new Thread(()->{ready.countDown();try{
                start.await();long begin=epoch.get();long deadline=begin+WINDOW_NS;int n=0;
                while(begin+n*PHASE_NS<deadline){long due=begin+n*PHASE_NS;while(true){long remaining=due-System.nanoTime();if(remaining<=0)break;TimeUnit.NANOSECONDS.sleep(Math.min(20_000_000L,remaining));}if(System.nanoTime()>=deadline)break;
                    var region=targets.get(n%25);long tick=200+(System.nanoTime()-begin)/50_000_000L;
                    long before=System.nanoTime();var evidence=new TreeMap<String,Object>(reduceVisit(store,settings,region,tick,n));
                    evidence.put("scheduledMs",n*400L);evidence.put("committedMs",(System.nanoTime()-begin)/1_000_000.0);evidence.put("readReduceAppendProofMs",(System.nanoTime()-before)/1_000_000.0);writerRecords.add(Map.copyOf(evidence));n++;
                }
            }catch(Throwable failure){writerError.set(failure);}},"P008-QA-independent-real-reducer-region-pair-writer");
            writer.start();check(ready.await(10,TimeUnit.SECONDS),"owned writer ready");epoch.set(System.nanoTime());long deadline=epoch.get()+WINDOW_NS;start.countDown();
            var port=new DurableMemoryPort(store,settings);List<Map<String,Object>> scans=new ArrayList<>();Set<String> sampled=new HashSet<>();int publications=0,selectedRejects=0,censusOnly=0,raced=0,refreshCalls=0,boundedRefreshes=0,fullScans=0;long peakHeap=used(),peakCache=0;
            BoundedMetadataProjection.View previous=null;var scanTrace=root.resolve("scan-trace.jsonl");Files.createFile(scanTrace);
            try{
                while(System.nanoTime()<deadline){
                    long scanStart=System.nanoTime();var pinned=store.metadataSnapshot().orElseThrow();long pageBefore=store.metadataHydrationPageLoads();
                    boolean refreshRequested=previous!=null;
                    var candidate=refreshRequested?await(BoundedMetadataProjection.refresh(store,previous,pinned,demand,settings)):await(BoundedMetadataProjection.load(store,pinned,demand,settings));long loaded=System.nanoTime();
                    if(refreshRequested)refreshCalls++;previous=candidate;
                    check(candidate.snapshot().session().equals(pinned.session())&&candidate.snapshot().cut().equals(pinned.cut()),"refreshed result keeps authentic requested J04 session and cut");
                    boolean boundedRefresh=refreshRequested&&candidate.examinedRows()<=candidate.states().size();String readPath=boundedRefresh?"bounded-refresh":"full-scan";if(boundedRefresh)boundedRefreshes++;else fullScans++;
                    check(candidate.memory().regionRecords().keySet().equals(Set.copyOf(fixture.selected())),"LIVE scan selects exactly the25 demanded real regions");
                    check(candidate.memory().operationalPlayers().size()==25,"all25 operational identities remain in actual projection");
                    var current=store.metadataSnapshot().orElseThrow();var body=new ScopedAggregateCapture.View(candidate.snapshot(),candidate.states());boolean bodyOnly=body.sameMemoryHeads(store,current,candidate.scopes(),candidate.demand().anchors());boolean full=candidate.currentHeads(store,current);
                    check(!full||bodyOnly,"census can never bypass selected body revisions");boolean published=false;var observed=current;
                    if(full){port.replaceProjection(candidate.scopes(),candidate.states().values());check(port.view(candidate.scopes()).equals(candidate.memory()),"actual DurableMemoryPort publication has exact candidate body map");observed=store.metadataSnapshot().orElseThrow();published=candidate.currentHeads(store,observed);if(!published)raced++;}
                    boolean during=System.nanoTime()<deadline;if(published&&during)publications++;if(!bodyOnly)selectedRejects++;else if(!full)censusOnly++;
                    String type=published?"published":full?"publication-race":bodyOnly?"census-only":"selected-changed";
                    long proofStart=System.nanoTime();var pinnedProof=await(ScopedAggregateCapture.load(store,pinned,candidate.states().keySet(),candidate.states().size()));check(pinnedProof.states().equals(candidate.states()),"every selected aggregate equals actual J04 at the exact pinned scan cut");
                    var observedProof=await(ScopedAggregateCapture.load(store,observed,candidate.states().keySet(),candidate.states().size()));boolean mapsEqual=observedProof.states().equals(candidate.states());
                    boolean observedBodies=body.sameMemoryHeads(store,observed,candidate.scopes(),candidate.demand().anchors());check(mapsEqual==observedBodies,"actual observed-cut record map confirms body revision comparison");
                    if(sampled.add(readPath+"-"+type))write(root.resolve("proof-"+readPath+"-"+type+".json"),Map.of("candidateCut",pinned.cut().value(),"initialObservedCut",current.cut().value(),"observedCut",observed.cut().value(),"candidateStates",states(candidate.states()),"actualPinnedStates",states(pinnedProof.states()),"actualObservedStates",states(observedProof.states()),"sameBodyMap",mapsEqual,"censusAccepted",full,"published",published));
                    var changed=new ArrayList<String>();for(var entry:candidate.states().entrySet()){var revision=store.metadataRevision(observed,entry.getKey());if(!revision.equals(entry.getValue().header().revision())&&changed.size()<8)changed.add(entry.getKey().kind()+":"+entry.getKey().id()+":"+entry.getValue().header().revision().value()+"->"+revision.value());}
                    var scanRow=Map.<String,Object>ofEntries(Map.entry("readPath",readPath),Map.entry("refreshRequested",refreshRequested),Map.entry("startedMs",(scanStart-epoch.get())/1_000_000.0),Map.entry("scanMs",(loaded-scanStart)/1_000_000.0),Map.entry("proofMs",(System.nanoTime()-proofStart)/1_000_000.0),Map.entry("pinnedCut",pinned.cut().value()),Map.entry("observedCut",observed.cut().value()),Map.entry("selectedBodyCurrent",bodyOnly),Map.entry("censusCurrent",full),Map.entry("publicationVerified",published&&during),Map.entry("duringPressure",during),Map.entry("actualMapEqualObserved",mapsEqual),Map.entry("changedSelectedHeaders",changed),Map.entry("examinedRows",candidate.examinedRows()),Map.entry("selectedStates",candidate.states().size()),Map.entry("hydratedPageLoads",store.metadataHydrationPageLoads()-pageBefore));scans.add(scanRow);line(scanTrace,scanRow);
                    peakHeap=Math.max(peakHeap,used());peakCache=Math.max(peakCache,store.metadataCachedEntries());
                }
            }finally{writer.join(60_000);if(writer.isAlive())throw new IllegalStateException("owned writer failed to finish; no other process interrupted");}
            if(writerError.get()!=null)throw new AssertionError("writer failed",writerError.get());
            var quiet=store.metadataSnapshot().orElseThrow();var finalView=await(BoundedMetadataProjection.load(store,quiet,demand,settings));var finalCurrent=store.metadataSnapshot().orElseThrow();check(finalView.currentHeads(store,finalCurrent),"quiescent final scan is publishable at actual latest J04 cut");
            var finalProof=await(ScopedAggregateCapture.load(store,finalCurrent,finalView.states().keySet(),finalView.states().size()));check(finalProof.states().equals(finalView.states()),"quiescent full record map exactly matches authoritative J04");
            var outsideKeys=new HashSet<AggregateKey>();for(var region:fixture.outside())outsideKeys.add(new AggregateKey(AggregateKind.REGION_MEMORY,region.value()));
            var outsideProof=await(ScopedAggregateCapture.load(store,finalCurrent,outsideKeys,25));check(outsideProof.states().values().stream().allMatch(s->s instanceof RegionMemoryAggregate m&&m.value().activity().camps().isEmpty()),"offscope writer creates no CAMP reference dependency");
            check(store.metadataCachedEntries()<=512&&peakCache<=512,"metadata cache remains512");check(writerRecords.size()>=25,"all25 phase targets receive real durable updates");
            double elapsed=(System.nanoTime()-epoch.get())/1_000_000_000.0;var result=new TreeMap<String,Object>();result.put("case",root.getFileName().toString());result.put("large4096Locations",large);result.put("updatesSelectedRegions",updateSelected);result.put("pressureSeconds",12);result.put("totalIncludingProofAndQuiescenceSeconds",elapsed);result.put("writerCommits",writerRecords.size());result.put("writerAlgorithm","actual MemoryReducer regional activity visit+activity; no PLAYER presence input");result.put("writerAggregateKinds",List.of("REGION","REGION_MEMORY"));result.put("everyWriterSelectionFieldUnchanged",writerRecords.stream().allMatch(r->Boolean.TRUE.equals(r.get("selectionFieldsIdentical"))));result.put("writerRegions",targets.size());result.put("perRegionNominalPeriodMs",10000);result.put("phaseMs",400);result.put("sourceLocations",fixture.sourceLocations());result.put("sourceCombinedEntries",fixture.sourceCombined());result.put("coldCatalogueHeads",fixture.catalogueHeads());result.put("runtimeAndPlayerHeads",fixture.runtimeHeads());result.put("scans",scans.size());result.put("verifiedPublicationsDuringPressure",publications);result.put("legitimateSelectedRevisionRejects",selectedRejects);result.put("censusOnlyRejects",censusOnly);result.put("publicationRaces",raced);result.put("quiescentScanPass",true);result.put("sampledHeapUsedBytes",peakHeap);result.put("maxHeapBytes",Runtime.getRuntime().maxMemory());result.put("peakMetadataCacheEntries",peakCache);result.put("criterion",">=1 verified current publication during pressure; quiescent exact scan passes");result.put("verdict",publications>0?"PASS_PUBLICATION_CRITERION":"RED_STARVATION");result.put("refreshCalls",refreshCalls);result.put("boundedRefreshResults",boundedRefreshes);result.put("fullScanResults",fullScans);result.put("scanTraceFile","scan-trace.jsonl");result.put("writerTraceFile","writer-trace.json");write(root.resolve("writer-trace.json"),List.copyOf(writerRecords));
            write(root.resolve("measurement.json"),result);System.out.println("LIVE_REFRESH case="+root.getFileName()+" scans="+scans.size()+" publications="+publications+" bounded_refresh="+boundedRefreshes+" full_scans="+fullScans+" selected_rejects="+selectedRejects+" census_only="+censusOnly+" writer_commits="+writerRecords.size()+" heap_sample="+peakHeap+" verdict="+result.get("verdict"));return result;
        }finally{await(store.closeAsync());}
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=2)throw new IllegalArgumentException("profile and NEW isolated results directory");var root=Path.of(args[1]);if(Files.exists(root))throw new IllegalArgumentException("new QA directory required");Files.createDirectories(root);
        check(Runtime.getRuntime().maxMemory()<=536_870_912L,"launch with heap512MiB maximum");var settings=new MemorySettings(profile(Path.of(args[0])),false);List<Map<String,Object>> results=new ArrayList<>();
        results.add(measure(root.resolve("1360-other25-reducer-no-camps"),settings,false,false));results.add(measure(root.resolve("4096locations-other25-reducer-no-camps"),settings,true,false));results.add(measure(root.resolve("1360-all25-selected-reducer"),settings,false,true));
        var summary=new TreeMap<String,Object>();summary.put("scope","Actual isolated J04 LIVE dependency scan;25PlayerIds/25selectedRegions; noMinecraft clients, TPS, FIXE or production load claim");summary.put("java",System.getProperty("java.version"));summary.put("os",System.getProperty("os.name"));summary.put("availableProcessors",Runtime.getRuntime().availableProcessors());summary.put("assertions",assertions.get());summary.put("criterionDeclaredBeforeMeasurement",">=1 verified current publication during each12s pressure window; otherwise RED with exact reason; no core fix in test phase");summary.put("cases",results);summary.put("productionCodeChanged",false);summary.put("revision","Current-cut bounded refresh experiment; R2 fixture/writer and acceptance unchanged; original R1/R2 frozen unchanged");
        write(root.resolve("P008_LIVE_CURRENT_CUT_REFRESH_QUALIFICATION.json"),summary);boolean accepted=results.stream().allMatch(r->r.get("verdict").equals("PASS_PUBLICATION_CRITERION"));
        System.out.println("LIVE_REFRESH_COMPLETE assertions="+assertions.get()+" result="+(accepted?"PASS":"RED")+" scope=actual_J04_synthetic_not25clients");if(!accepted)throw new AssertionError("publication criterion failed; inspect exact immutable J04 proof maps and measurement traces");
    }
}
