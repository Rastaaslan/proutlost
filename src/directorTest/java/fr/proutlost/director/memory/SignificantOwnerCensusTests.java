package fr.proutlost.director.memory;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.persistence.*;
import fr.proutlost.director.runtime.ResultTransaction;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
/** Declared synthetic generic J04 head-owner update, not a canonical memory mutation. */
public final class SignificantOwnerCensusTests {
    private static int assertions;
    private static <T>T await(CompletionStage<T> stage)throws Exception{return stage.toCompletableFuture().get(30,TimeUnit.SECONDS);}
    private static void check(boolean condition,String why){assertions++;if(!condition)throw new AssertionError(why);}
    private static PlayerSignificantAggregate row(PlayerId player,UUID id,long revision,BlobRef outcome){return new PlayerSignificantAggregate(new PlayerSignificantMemory(
        new AggregateHeader(1,id,new Revision(revision),new Sequence(0),new Tick(revision)),new MemoryId(id),player,new EventInstanceId(new UUID(808,500)),
        new DefinitionId("test:owner_census"),"witness",PerceptionStatus.RECEIVED,outcome,Optional.empty(),Optional.empty(),Set.of(),Set.of(),new Tick(1)));}
    private static void append(JournalStore store,PlayerSignificantAggregate row,List<BlobContent> blobs)throws Exception{
        var ack=await(store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),List.of(new DurableAggregate(row)),blobs)));
        check(ack.ackStatus()==AckStatus.DURABLE,"generic owner update actually accepted by J04");
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("NEW isolated result directory required");
        var path=Path.of(args[0]);if(Files.exists(path))throw new IllegalArgumentException("fresh synthetic directory required");
        var world=new WorldId(new UUID(808,500));var oldOwner=new PlayerId(new UUID(808,501));var newOwner=new PlayerId(new UUID(808,502));
        var other=new PlayerId(new UUID(808,503));var owners=Set.of(oldOwner,newOwner,other);var id=new UUID(808,504);
        var options=new StoreOptions(4_194_400,4_194_304,16_777_216,33_554_432,32,0,128);
        var store=await(JournalStore.open(path,world,options));Map<PlayerId,Sequence> after;
        try{
            var outcome=BlobContent.capture(new ResourceId("test:owner_census_outcome"),1,new byte[]{1});append(store,row(oldOwner,id,1,outcome.reference()),List.of(outcome));
            var pinned=store.metadataSnapshot().orElseThrow();var before=store.metadataSignificantVersions(pinned,owners).orElseThrow();
            append(store,row(newOwner,id,2,outcome.reference()),List.of());
            after=store.metadataSignificantVersions(store.metadataSnapshot().orElseThrow(),owners).orElseThrow();
            check(!after.get(oldOwner).equals(before.get(oldOwner)),"RED_REPRO old owner census must advance when visible head leaves it");
            check(!after.get(newOwner).equals(before.get(newOwner)),"new owner census advances when visible head enters it");
            check(after.get(other).equals(before.get(other)),"unrelated owner remains stable");
            check(store.metadataSignificantVersions(pinned,owners).orElseThrow().equals(before),"old pinned census remains immutable");
            await(store.checkpoint());
        }finally{await(store.closeAsync());}
        var cold=await(JournalStore.open(path,world,options));try{
            check(cold.metadataSignificantVersions(cold.metadataSnapshot().orElseThrow(),owners).orElseThrow().equals(after),"checkpoint/new session reconstructs both owner changes");
        }finally{await(cold.closeAsync());}
        System.out.println("PASS SignificantOwnerCensusTests assertions="+assertions+" scope=generic_synthetic_actual_J04_no_Minecraft");
    }
}
