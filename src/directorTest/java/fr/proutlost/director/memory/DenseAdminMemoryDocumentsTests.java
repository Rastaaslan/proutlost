package fr.proutlost.director.memory;
import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.bootstrap.DirectorMetadataMemoryReader;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.admin.server.*;
import fr.proutlost.director.admin.client.*;
import fr.proutlost.director.adapter.neoforge.network.WireCodec;
import fr.proutlost.director.bootstrap.DirectorNativeLocationGenerationPlan;
import fr.proutlost.director.integration.locations.*;
import fr.proutlost.director.persistence.*;
import fr.proutlost.director.runtime.ResultTransaction;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
/** Declared synthetic exact-region J07 over actual cold J04 generations.
 * Tests server wire DTOs/client state, not rendered Minecraft or Contra. */
public final class DenseAdminMemoryDocumentsTests {
    private static final WorldId WORLD=new WorldId(new UUID(0x503030384a303744L,1));
    private static final DimensionId DIM=new DimensionId("minecraft:overworld");
    private static final ResourceId SOURCE=new ResourceId("test:p008_dense_admin");
    private static final StoreOptions OPTIONS=new StoreOptions(4_194_400,4_194_304,16_777_216,33_554_432,32,0,128);
    private static int checks;
    private static UUID id(int namespace,int index){return new UUID(namespace,index);}
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static <T>T await(CompletionStage<T> pending)throws Exception{return pending.toCompletableFuture().get(60,TimeUnit.SECONDS);}
    private static AggregateHeader header(UUID id,long revision){return new AggregateHeader(1,id,new Revision(revision),new Sequence(0),new Tick(revision));}
    private static ConfigView profile(Path file)throws Exception{
        var raw=((JsonValue.ObjectValue)((JsonValue.ObjectValue)CanonicalJsonV1.decode(Files.readAllBytes(file))).values().get("values")).values();var values=new HashMap<String,JsonValue.Number>();
        raw.forEach((k,v)->values.put(k,(JsonValue.Number)v));return new ConfigView(new Revision(1),BlobContent.hash(Files.readAllBytes(file)),Calibration.DEFAULT_FOR_TESTING,values);
    }
    private static DirectorWorldState initialWorld(JournalStore store,ConfigView config){return new DirectorWorldState(header(WORLD.value(),1),WORLD,store.session().storeGeneration(),new Tick(1),RunControl.PAUSED,HealthMode.NORMAL,PacingState.REST,GlobalTension.CALM,
        new ResourceId("test:p008_dense_admin_profile"),config.provenanceHash(),config.provenanceHash(),store.session().bootEpoch(),new Sequence(0),
        new EngineControlState(1,new Revision(0),new Tick(0),new Tick(0),new UnitValue(0),new Tick(0),Optional.empty(),new UnitValue(.5)));}
    private static DirectorWorldState nextWorld(DirectorWorldState before){return new DirectorWorldState(header(before.worldId().value(),before.header().revision().value()+1),before.worldId(),before.storeGeneration(),before.simTick().plus(1),before.runControl(),before.healthMode(),before.pacingState(),before.tension(),before.intensityProfileId(),before.catalogHash(),before.configHash(),before.bootEpoch(),before.lastCheckpointSeq(),before.engineControl());}
    private static void durable(JournalStore store,DurableRecord record,List<BlobContent> blobs)throws Exception{
        var batch=ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),List.of(record),blobs);
        check(await(store.append(batch)).ackStatus()==AckStatus.DURABLE,"actual synthetic J04 transaction durable");
    }
    private static LocationImport source(){
        var region=new RegionId(id(71,1));var location=new LocationId(id(72,1));var bounds=new ImportBounds(0,0,0,600*128,256,32);var anchors=new ArrayList<ImportedAnchor>();
        for(int n=0;n<600;n++)anchors.add(new ImportedAnchor(new AnchorId(id(73,n)),location,DIM,new ImportPosition(n*128+1,64,1),Set.of(new TagId("proutlost:anchor_center"))));
        var draft=new LocationImport(1,WORLD,SOURCE,new Revision(1),new Fingerprint("0".repeat(64)),List.of(new ImportedRegion(region,DIM,bounds,Set.of(new TagId("proutlost:structure")))),
            List.of(new ImportedLocation(location,"dense_admin",Set.of(region),DIM,bounds,Set.of())),anchors);
        return new LocationImport(1,WORLD,SOURCE,draft.exportRevision(),StandaloneLocationImporter.digest(draft),draft.regions(),draft.locations(),draft.anchors());
    }
    private static void install(JournalStore store,LocationImport input)throws Exception{
        var prepared=LocationTransfer.prepare(input);var snapshot=store.metadataSnapshot().orElseThrow();
        check(prepared.manifest().entryCount()==602&&prepared.manifest().sourceBytes()<=LocationTransfer.SOURCE_BYTES,"legal multi-page source contains600anchors and602combined entries");
        for(var page:prepared.pages()){
            var blob=BlobContent.capture(new ResourceId("proutlost_director:location_transfer_fragment"),1,Base64.getDecoder().decode(page.bytesBase64()));
            durable(store,new DurableMetadataSourcePage(prepared.manifest(),page.pageIndex(),blob.reference()),List.of(blob));
        }
        var plan=new DirectorNativeLocationGenerationPlan(WORLD,input,null,new Tick(1),key->store.metadataOwner(snapshot,key));
        for(int n=0;n<plan.pageCount();n++){
            var page=plan.planPage(plan.pageKeys(n),Map.of());var dto=new MetadataGenerationPage(LocationTransfer.identity(prepared.manifest()),SOURCE,input.exportRevision(),input.exportDigest(),n,plan.pageCount(),page.states(),page.beforeRevisions());
            var blob=BlobContent.capture(new ResourceId("proutlost_director:metadata_generation_page"),1,new ModelCodec<>(MetadataGenerationPage.class).encode(dto));
            durable(store,new DurableMetadataStage(prepared.manifest(),n,plan.pageCount(),blob.reference()),List.of(blob));
        }
        durable(store,new DurableMetadataActivation(prepared.manifest(),Optional.empty(),plan.pageCount()),List.of());
    }
    private static AdminQuery query(SessionId actor,Optional<String> cursor,Optional<Revision> expected){return new AdminQuery(actor,new RequestId(UUID.randomUUID()),AdminPanel.MEMORY,new RegionFilter(Optional.of(new RegionId(id(71,1))),Set.of()),cursor,expected,1);}
    private static AdminReadResult until(ColdAdminMemoryReader reader,JournalStore store,AdminQuery query,ActorIdentity actor,DirectorWorldState world,MemorySettings settings)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);AdminReadResult result;
        do{result=reader.query(query,actor,world,store.metadataSnapshot().orElseThrow().cut(),settings,world.simTick().plus(1000));if(result.status()==AdminStatus.SUCCEEDED){
            if(query.cursor().isEmpty())check(result.snapshot().orElseThrow().capturedTick().equals(world.simTick().plus(1000)),"actual actor capture time differs from last durable WORLD control");
            return result;}Thread.sleep(2);}while(System.nanoTime()<end);
        throw new AssertionError("dense exact read never available: "+result);
    }
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[1]);if(Files.exists(root))throw new IllegalArgumentException("fresh isolated synthetic directory required");Files.createDirectories(root);
        var config=profile(Path.of(args[0]));var settings=new MemorySettings(config,false);var store=await(JournalStore.open(root.resolve("store"),WORLD,OPTIONS));
        try{
            var world=initialWorld(store,config);durable(store,new DurableAggregate(new WorldAggregate(world)),List.of());install(store,source());await(store.checkpoint());await(store.closeAsync());
            store=await(JournalStore.open(root.resolve("store"),WORLD,OPTIONS));
            // Actual boot identity is required; only the fixture WORLD boot is refreshed.
            world=new DirectorWorldState(header(WORLD.value(),2),WORLD,store.session().storeGeneration(),new Tick(2),world.runControl(),world.healthMode(),world.pacingState(),world.tension(),world.intensityProfileId(),world.catalogHash(),world.configHash(),store.session().bootEpoch(),world.lastCheckpointSeq(),world.engineControl());
            durable(store,new DurableAggregate(new WorldAggregate(world)),List.of());
            var actorId=new SessionId(id(74,1));var actor=new ActorIdentity(actorId,ActorKind.SERVER,Optional.empty(),Optional.empty(),4,true);var reader=new ColdAdminMemoryReader(store,new DirectorMetadataMemoryReader(store),WORLD,8,32,22000);
            var network=new SessionIdentity(WORLD,store.session().bootEpoch(),new Epoch(1),DIM,new PlayerId(id(75,1)));var page=new AdminPageState();page.reset(network);
            var request=query(actorId,Optional.empty(),Optional.empty());
            check(reader.query(request,actor,world,store.metadataSnapshot().orElseThrow().cut(),settings).status()==AdminStatus.DEFERRED,"first600anchor exact read is async, never falseempty success");
            var result=until(reader,store,request,actor,world,settings);var pinned=result.snapshot().orElseThrow();
            check(pinned.capturedTick().equals(world.simTick().plus(1000)),"dense snapshot uses supplied real capture time");
            check(result.data().getFirst().data() instanceof MemoryDocumentPageData,"whole immutable RegionRecord is fragmented rather than truncated/oversize");
            commandReference(actor,result);
            Set<AnchorId> anchors=new HashSet<>();Set<LocationId> locations=new HashSet<>();Set<RegionMemoryDetailData.Section> sections=new HashSet<>();int packets=0,fragments=0,documentRows=0,automaticContinuations=0;var firstFragments=new ArrayList<AdminSnapshot>();
            do{
                packets++;var packet=AdminFacade.snapshot(request,result,NativeReadCommandFence.supported());
                check(new WireCodec<>(AdminSnapshot.class,24572).encode(packet).length<=22000,"each actual network DTO respects22k and wire24576 ceilings");
                if(packet.rows().getFirst().data() instanceof MemoryDocumentPageData fragment){fragments++;if(fragment.documentIndex()==0)firstFragments.add(packet);
                    var lines=AdminDetails.lines(packet.rows().getFirst(),10);check(lines.stream().noneMatch(s->s.contains("bytes_base64")||s.contains(fragment.bytesBase64())),"rendered incomplete fragment has progress, no rawbase64");}
                page.begin(request);check(page.receive(network,packet),"client request/session/cut fragment correlation accepted");
                if(page.documentContinuation().isPresent()){
                    automaticContinuations++;check(page.documentContinuation().equals(result.nextCursor()),"incomplete document exposes only its exact automatic transport continuation");
                }
                var visible=page.snapshot().orElseThrow().rows().getFirst();
                if(visible.data() instanceof RegionMemoryDetailData data){documentRows++;sections.add(data.section());
                    check(page.documentContinuation().isEmpty()&&!page.assemblingDocument(),"automatic transport stops at each verified semantic section");
                    data.region().ifPresent(r->check(r.anchorIds().size()==600,"decoded immutable RegionRecord retains every600IDs"));
                    for(var a:data.anchors())check(anchors.add(a.anchorId()),"all anchor bodies are delivered once across semantic pages");
                    for(var l:data.locations())check(locations.add(l.locationId()),"all linked locations delivered once");
                    check(data.anchors().size()<=96&&data.locations().size()<=96,"semantic page body count bounded96");
                    if(data.section()==RegionMemoryDetailData.Section.ANCHORS)check(AdminDetails.lines(visible,400).stream().filter(s->s.startsWith("Ancre ")).count()==data.anchors().size(),"all anchor IDs of current semantic page are rendered before detailed truncation");
                }
                if(result.nextCursor().isEmpty())break;
                if(packets==1){world=nextWorld(world);durable(store,new DurableAggregate(new WorldAggregate(world)),List.of());}
                var priorCursor=result.nextCursor();request=query(actorId,priorCursor,Optional.of(pinned.snapshotRevision()));
                var next=reader.query(request,actor,world,store.metadataSnapshot().orElseThrow().cut(),settings,new Tick(5000+packets));
                check(next.status()==AdminStatus.SUCCEEDED&&next.snapshot().orElseThrow().equals(pinned),"cursor remains pinned across unrelated WORLD revision, no cut mixing");
                check(reader.query(request,actor,world,store.metadataSnapshot().orElseThrow().cut(),settings).status()==AdminStatus.DEFERRED,"cursor consumed once");
                var otherId=new SessionId(id(74,2));var other=new ActorIdentity(otherId,ActorKind.SERVER,Optional.empty(),Optional.empty(),4,true);
                check(reader.query(query(otherId,priorCursor,Optional.of(pinned.snapshotRevision())),other,world,store.metadataSnapshot().orElseThrow().cut(),settings).status()!=AdminStatus.SUCCEEDED,"foreign session cannot consume retained document");
                result=next;
            }while(packets<200);
            check(anchors.size()==600&&locations.size()==1&&sections.containsAll(EnumSet.allOf(RegionMemoryDetailData.Section.class)),"full exact region and all600ancres are usable through pagination");
            check(automaticContinuations==packets-documentRows,"transport fragment continuations do not add semantic pagination clicks");
            check(fragments>0&&documentRows>=10&&store.metadataCachedEntries()<=512,"client completed whole documents while J04 metadata cache remains512");
            check(firstFragments.size()>=2,"oversize base record spans multiple realpackets");clientFailures(network,actorId,firstFragments);
            request=query(actorId,Optional.empty(),Optional.empty());result=until(reader,store,request,actor,world,settings);
            var oldCursor=result.nextCursor();var original=source().anchors().getLast();var cold=new AnchorRecord(header(original.anchorId().value(),2),original.anchorId(),Optional.of(original.locationId()),Set.of(new RegionId(id(71,1))),DIM,
                new BlockPosition(DIM,original.position().x(),original.position().y(),original.position().z()),"region.camp_radius_blocks",Set.of(new TagId("proutlost:dungeon")),AnchorOrigin.IMPORTED,EvidenceStatus.CONFIRMED,false);
            durable(store,new DurableAggregate(new AnchorAggregate(cold)),List.of());
            check(reader.query(query(actorId,oldCursor,Optional.of(result.snapshot().orElseThrow().snapshotRevision())),actor,world,store.metadataSnapshot().orElseThrow().cut(),settings).status()==AdminStatus.DEFERRED,"changed cold anchor invalidates all old dense cursor bytes");
            var refreshedQuery=query(actorId,Optional.empty(),Optional.empty());var refreshed=until(reader,store,refreshedQuery,actor,world,settings);
            var refreshedAssembler=new MemoryDocumentAssembler();boolean tagsSeen=false,inactiveSeen=false;int refreshedPackets=0;
            do{
                refreshedPackets++;var packet=AdminFacade.snapshot(refreshedQuery,refreshed,NativeReadCommandFence.supported());
                var semantic=refreshedAssembler.accept(packet,packet.rows().getFirst());
                if(semantic.isPresent()&&semantic.get().data() instanceof RegionMemoryDetailData data){
                    if(data.section()==RegionMemoryDetailData.Section.TAGS){tagsSeen=true;check(!data.tags().contains(new TagId("proutlost:dungeon")),"inactive-only tag omitted from current regional TAGS fold");}
                    if(data.anchors().stream().anyMatch(a->a.anchorId().equals(cold.anchorId())&&!a.active()&&a.tags().contains(new TagId("proutlost:dungeon"))))inactiveSeen=true;
                }
                if(refreshed.nextCursor().isEmpty())break;
                refreshedQuery=query(actorId,refreshed.nextCursor(),Optional.of(refreshed.snapshot().orElseThrow().snapshotRevision()));
                refreshed=reader.query(refreshedQuery,actor,world,store.metadataSnapshot().orElseThrow().cut(),settings);
                check(refreshed.status()==AdminStatus.SUCCEEDED,"updated dense read remains natively pageable");
            }while(refreshedPackets<200);
            check(tagsSeen&&inactiveSeen,"inactive historical anchor body retained while its tag is not asserted current");
            var taggedQuery=new AdminQuery(actorId,new RequestId(UUID.randomUUID()),AdminPanel.MEMORY,new RegionFilter(Optional.of(new RegionId(id(71,1))),Set.of(new TagId("proutlost:structure"))),Optional.empty(),Optional.empty(),1);
            var taggedResult=until(reader,store,taggedQuery,actor,world,settings);var taggedPacket=AdminFacade.snapshot(taggedQuery,taggedResult,NativeReadCommandFence.supported());
            check(fr.proutlost.director.adapter.neoforge.network.AdminQueryReplyFence.read(taggedQuery,()->Optional.of(taggedPacket)).snapshot().isPresent(),"LAB final read fence accepts indexed exact-region tag filter");
            var inactiveQuery=new AdminQuery(actorId,new RequestId(UUID.randomUUID()),AdminPanel.MEMORY,new RegionFilter(Optional.of(new RegionId(id(71,1))),Set.of(new TagId("proutlost:dungeon"))),Optional.empty(),Optional.empty(),1);
            var inactiveResult=until(reader,store,inactiveQuery,actor,world,settings);
            check(inactiveResult.data().isEmpty()&&inactiveResult.snapshot().isPresent(),"inactive-only tag query returns authoritative no match, not false regional current tag");
            var denied=new ActorIdentity(actorId,ActorKind.SERVER,Optional.empty(),Optional.empty(),3,true);
            check(reader.query(query(actorId,Optional.empty(),Optional.empty()),denied,world,store.metadataSnapshot().orElseThrow().cut(),settings).data().isEmpty(),"OP permission loss reveals no cached data");
            reader.disconnect(actorId);check(reader.query(query(actorId,oldCursor,Optional.empty()),actor,world,store.metadataSnapshot().orElseThrow().cut(),settings).status()==AdminStatus.DEFERRED,"disconnect releases document cursors");
            reader.close();check(reader.query(query(actorId,Optional.empty(),Optional.empty()),actor,world,store.metadataSnapshot().orElseThrow().cut(),settings).status()!=AdminStatus.SUCCEEDED,"close revokes retained documents");
            System.out.println("DENSE_J07 packets="+packets+" fragments="+fragments+" automatic_continuations="+automaticContinuations+" semantic_pages="+documentRows+" anchors="+anchors.size()+" locations="+locations.size()+" cache="+store.metadataCachedEntries()+" wire_version=1.1.0 matched_pack_required=true");
        }finally{await(store.closeAsync());}
        System.out.println("PASS DenseAdminMemoryDocumentsTests assertions="+checks+" scope=synthetic_actual_cold_J04_wire_client_state_no_Minecraft_render_no_Contra_no_RUN7");
    }
    private static void commandReference(ActorIdentity actor,AdminReadResult read){
        var request=new RequestId(UUID.randomUUID());var stamp=read.snapshot().orElseThrow();
        var command=new AdminCommand(actor.session(),request,stamp.snapshotRevision(),AdminOperation.REGION,new RegionInspectionPayload(new RegionId(id(71,1))));
        var snapshot=new AdminSnapshot(request,stamp.snapshotRevision(),stamp.capturedTick(),stamp.lastDurableSequence(),read.data(),read.nextCursor(),NativeReadCommandFence.supported());
        var reply=NativeReadCommandFence.answer(command,Optional.of(snapshot));
        check(reply.status()==AdminStatus.DEFERRED&&reply.resultRef().filter(ref->ref.id().equals(id(71,1))).isPresent(),"native REGION command returns explicit incomplete reference, never fullsuccess");
        var state=new AdminCommandState(4);state.reset(actor.session());state.begin(command);check(state.receive(actor.session(),reply),"paged command receipt correlated to exact original command");
        var destination=state.resultQuery(request,new RequestId(UUID.randomUUID()),1);
        check(destination.filter(query->query.panel()==AdminPanel.MEMORY&&query.filter().equals(new RegionFilter(Optional.of(new RegionId(id(71,1))),Set.of()))).isPresent(),"Ouvrir navigates exact readonly pagination target");
        check(state.dismiss(request),"completed reference receipt is dismissible without abandoning an uncertain mutation");
        var write=new AdminCommand(actor.session(),new RequestId(UUID.randomUUID()),stamp.snapshotRevision(),AdminOperation.PAUSE,new ControlPayload(RunControl.PAUSED));state.begin(write);
        var fake=new AdminCommandResult(write.requestId(),AdminStatus.DEFERRED,reply.reasonCodes(),reply.newRevision(),reply.resultRef());state.receive(actor.session(),fake);
        check(state.resultQuery(write.requestId(),new RequestId(UUID.randomUUID()),1).isEmpty()&&!state.dismiss(write.requestId()),"paged readonly marker cannot dismiss or navigate uncertain modifying command");
        var result=new AdminResult(AdminStatus.DEFERRED,request,stamp.snapshotRevision(),Optional.of(read.data().getFirst()),
            Optional.of(new SystemError(fr.proutlost.director.api.error.ReasonCode.BUDGET_EXHAUSTED,Severity.INFO,false,Optional.empty(),Set.of(),new CorrelationId(request.value()),SafeState.UNCHANGED)));
        var lines=new ArrayList<String>();fr.proutlost.director.adapter.neoforge.server.commands.CommandResultOutput.deliver(command,result,actor,lines::add);
        check(lines.stream().anyMatch(line->line.contains("Lecture incomplète"))&&lines.stream().anyMatch(line->line.contains("F8"))
            &&lines.stream().noneMatch(line->line.contains("bytesBase64")||line.contains(((MemoryDocumentPageData)read.data().getFirst().data()).bytesBase64())),"CLI shows paginated reference instructions and no fragmentcontent");
    }
    private static void clientFailures(SessionIdentity network,SessionId actor,List<AdminSnapshot> fragments){
        var assembler=new MemoryDocumentAssembler();var first=fragments.getFirst();assembler.accept(first,first.rows().getFirst());
        var next=fragments.get(1);var mixed=new AdminSnapshot(next.requestId(),next.snapshotRevision(),next.capturedTick(),next.lastDurableSequence().plus(1),next.rows(),next.nextCursor(),next.capabilities());
        try{assembler.accept(mixed,mixed.rows().getFirst());throw new AssertionError("mixedcut accepted");}catch(IllegalArgumentException expected){checks++;}
        assembler.clear();assembler.accept(first,first.rows().getFirst());try{assembler.accept(first,first.rows().getFirst());throw new AssertionError("duplicatefragment accepted");}catch(IllegalArgumentException expected){checks++;}
        assembler.clear();for(int n=0;n<fragments.size();n++){
            var packet=fragments.get(n);var row=packet.rows().getFirst();if(n==fragments.size()-1){var data=(MemoryDocumentPageData)row.data();byte[] bytes=Base64.getDecoder().decode(data.bytesBase64());bytes[bytes.length-1]^=1;
                row=new AdminRow(row.scope(),row.revision(),row.capturedTick(),new MemoryDocumentPageData(data.documentHash(),data.documentIndex(),data.documentCount(),data.documentBytes(),data.byteOffset(),Base64.getEncoder().encodeToString(bytes)));
                try{assembler.accept(packet,row);throw new AssertionError("corruptdocument accepted");}catch(IllegalArgumentException expected){checks++;}
            }else assembler.accept(packet,row);
        }
        var state=new AdminPageState();state.reset(network);var query=query(actor,Optional.empty(),Optional.empty());state.begin(query);
        var response=new AdminSnapshot(query.requestId(),first.snapshotRevision(),first.capturedTick(),first.lastDurableSequence(),first.rows(),first.nextCursor(),first.capabilities());
        check(state.receive(network,response),"partial document accepted for current connection");
        check(state.documentContinuation().equals(response.nextCursor()),"only an unfinished document schedules automatic transport continuation");
        state.begin(query(actor,response.nextCursor(),Optional.of(response.snapshotRevision())));
        check(state.pending()&&state.documentContinuation().isEmpty(),"one in-flight query suppresses a second automatic continuation");
        state.reset(new SessionIdentity(network.worldId(),network.bootEpoch(),network.connectionEpoch().plus(1),network.dimension(),network.playerId()));
        check(state.snapshot().isEmpty(),"reconnection drops partial byte assembly and visible data");
    }
}
