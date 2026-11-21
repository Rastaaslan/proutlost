package fr.proutlost.director.memory;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.*;
import fr.proutlost.director.bootstrap.DirectorMetadataMemoryReader;
import fr.proutlost.director.persistence.*;
import fr.proutlost.director.runtime.ResultTransaction;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
/** Actual J04 authority checks, not a substitute storage implementation. */
public final class MetadataReadPortIsolationTests {
    private static int assertions;
    private static void check(boolean value,String label){assertions++;if(!value)throw new AssertionError(label);}
    private static <T>T await(CompletionStage<T> future)throws Exception{return future.toCompletableFuture().get(30,TimeUnit.SECONDS);}
    private static void rejects(Runnable action,String label){try{action.run();throw new AssertionError(label);}catch(IllegalArgumentException|IllegalStateException expected){assertions++;}}
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("NEW isolated QA directory required");var root=Path.of(args[0]);if(Files.exists(root))throw new IllegalArgumentException("fresh declared QA directory required");Files.createDirectories(root);
        var world=new WorldId(new UUID(808,801));var options=new StoreOptions(4_194_400,4_194_304,16_777_216,33_554_432,32,0,128);
        var store=await(JournalStore.open(root.resolve("one"),world,options));var other=await(JournalStore.open(root.resolve("other"),world,options));MetadataReadPort read=store.metadataReads();MetadataReadSnapshot pinned;
        try{
            check(!(read instanceof DurableStore),"read capability object exposes no writer cast");check(read.metadataReads()==read&&store.metadataReads()==read,"provider yields same separate read object");
            check(Arrays.stream(read.getClass().getInterfaces()).noneMatch(type->type==DurableStore.class),"readonly implementation does not implement writer interface");
            pinned=read.snapshot().orElseThrow();check(read.owns(pinned)&&!other.metadataReads().owns(pinned),"pinned handle is owned by exactly its storage instance");
            var fake=new MetadataReadSnapshot(){public StoreSession session(){return pinned.session();}public Sequence cut(){return pinned.cut();}public Map<ResourceId,Fingerprint> generations(){return pinned.generations();}public Map<ResourceId,LocationTransferManifest> sources(){return pinned.sources();}};
            check(!read.owns(fake),"copied handle fields cannot forge opaque ownership");
            var id=new UUID(808,802);var key=new AggregateKey(AggregateKind.PLAYER_OPERATIONAL,id);var query=new StoreQuery(world,Set.of(key),new Sequence(0),Optional.empty(),1);
            rejects(()->read.metadataRevision(fake,key),"foreign handle cannot prove absent revision0");
            check(read.metadataKeysPage(fake,Set.of(AggregateKind.PLAYER_OPERATIONAL),Optional.empty(),1).isEmpty(),"foreign discovery is unavailable, never an empty success");
            try{await(read.readMetadata(fake,query));throw new AssertionError("foreign async read accepted");}catch(ExecutionException expected){assertions++;}
            var valid=await(read.readMetadata(pinned,query));ReadContracts.validate(valid,query,pinned.session());check(valid.status()==ReadStatus.AVAILABLE&&valid.page().orElseThrow().aggregates().isEmpty(),"owned pinned missing row is genuinely available empty");
            var service=new DirectorMetadataMemoryReader(store);var scope=new ScopeSet(world,Set.of(new ScopeRef(ScopeKind.PLAYER,id)));var empty=service.empty(pinned,scope);
            check(empty.memory().operationalPlayers().isEmpty()&&empty.currentHeads(pinned),"empty exact scope derives from authoritative revision0");
            var memory=new PlayerOperationalMemory(new AggregateHeader(1,id,new Revision(1),new Sequence(0),new Tick(1)),new PlayerId(id),new DurationTicks(200),Optional.empty(),Optional.empty(),List.of(),List.of(),Set.of(),new UnitValue(0),Map.of(),Optional.empty(),new Epoch(0));
            var batch=ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),List.of(new DurableAggregate(new PlayerOperationalAggregate(memory))),List.of());
            check(await(store.append(batch)).ackStatus()==AckStatus.DURABLE,"underlying owner alone can really append");var current=read.snapshot().orElseThrow();
            check(read.metadataRevision(pinned,key).value()==0&&read.metadataRevision(current,key).value()==1,"pinned read remains old while new handle observes durable row");
            check(!empty.currentHeads(current),"previous proven absence invalidates on real insertion");rejects(()->service.empty(current,scope),"cannot invent empty projection for existing identity");
            var capture=await(service.capture(current,Set.of(key),1));check(capture.states().get(key).equals(new PlayerOperationalAggregate(memory))&&capture.sameHeads(current),"API capture retains exact actual J04 body and current proof");
            var oldCapture=await(service.capture(pinned,Set.of(),1));rejects(()->service.expand(empty,capture,scope),"expansion rejects different pinned cut/handle");
            var foreignService=new DirectorMetadataMemoryReader(store);var foreignCapture=await(foreignService.capture(pinned,Set.of(),1));rejects(()->service.expand(empty,foreignCapture,scope),"expansion rejects foreign service proof even on same store");
            check(service.expand(empty,oldCapture,scope).memory().equals(empty.memory()),"same owned handle expansion preserves exact data");
            var back=read.readback(batch.transactionId()).orElseThrow();check(back.receipt().orElseThrow().ackStatus()==AckStatus.DURABLE&&back.session().equals(store.session()),"readback preserves actual durable receipt without write permission");
            check(read.readback(new TransactionId(UUID.randomUUID())).orElseThrow().receipt().isEmpty(),"readback distinguishes proven absence from unavailable index");
        }finally{await(store.closeAsync());await(other.closeAsync());}
        check(!read.owns(pinned)&&read.snapshot().isEmpty(),"close revokes old opaque read capability");
        rejects(()->read.metadataRevision(pinned,new AggregateKey(AggregateKind.PLAYER_OPERATIONAL,new UUID(808,802))),"revoked handle cannot claim absence or current data");
        check(read.readback(new TransactionId(UUID.randomUUID())).isEmpty(),"closed receipt index is unavailable, not proven absent");
        System.out.println("PASS MetadataReadPortIsolationTests assertions="+assertions+" scope=actual_J04_readonly_opaque_handle_no_Minecraft");
    }
}
