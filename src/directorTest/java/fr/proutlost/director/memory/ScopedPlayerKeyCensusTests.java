package fr.proutlost.director.memory;
import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.persistence.*;
import fr.proutlost.director.runtime.ResultTransaction;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
/** Actual J04 scoped-current proof differs from exact operational-key-set reuse.
 * Synthetic neutral QA only; no Minecraft, author or performance claim. */
public final class ScopedPlayerKeyCensusTests {
 private static int assertions;
 private static final WorldId WORLD=new WorldId(new UUID(808,2100));
 private static final DimensionId DIM=new DimensionId("minecraft:overworld");
 private static final RegionId REGION=new RegionId(new UUID(808,2101));
 private static final PlayerId KNOWN=new PlayerId(new UUID(808,2102)),FOREIGN=new PlayerId(new UUID(808,2103)),REQUESTED=new PlayerId(new UUID(808,2104));
 private static void check(boolean value,String why){assertions++;if(!value)throw new AssertionError(why);}
 private static <T>T await(CompletionStage<T> value)throws Exception{return value.toCompletableFuture().get(30,TimeUnit.SECONDS);}
 private static AggregateHeader header(UUID id,long rev){return new AggregateHeader(1,id,new Revision(rev),new Sequence(0),new Tick(rev));}
 private static PlayerOperationalAggregate player(PlayerId id,long rev,Optional<RegionId> last){return new PlayerOperationalAggregate(new PlayerOperationalMemory(header(id.value(),rev),id,new DurationTicks(rev),last,Optional.empty(),List.of(),List.of(),Set.of(),new UnitValue(0),Map.of(),Optional.empty(),new Epoch(0)));}
 private static void append(JournalStore store,List<AggregateState> rows)throws Exception{
  check(await(store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),rows.stream().map(row->(DurableRecord)new DurableAggregate(row)).toList(),List.of()))).ackStatus()==AckStatus.DURABLE,"real J04 row durable");
 }
 private static ConfigView profile(Path path)throws Exception{
  var raw=((JsonValue.ObjectValue)((JsonValue.ObjectValue)CanonicalJsonV1.decode(Files.readAllBytes(path))).values().get("values")).values();var values=new HashMap<String,JsonValue.Number>();raw.forEach((k,v)->values.put(k,(JsonValue.Number)v));return new ConfigView(new Revision(1),BlobContent.hash(Files.readAllBytes(path)),Calibration.DEFAULT_FOR_TESTING,values);
 }
 private static BoundedMetadataProjection.View load(JournalStore store,BoundedMetadataProjection.Demand demand,MemorySettings settings)throws Exception{return await(BoundedMetadataProjection.load(store,store.metadataSnapshot().orElseThrow(),demand,settings));}
 private static BoundedMetadataProjection.View refresh(JournalStore store,BoundedMetadataProjection.View old,BoundedMetadataProjection.Demand demand,MemorySettings settings)throws Exception{return await(BoundedMetadataProjection.refresh(store,old,store.metadataSnapshot().orElseThrow(),demand,settings));}
 public static void main(String[] args)throws Exception{
  if(args.length!=2)throw new IllegalArgumentException("profile NEW isolated directory required");var path=Path.of(args[1]);if(Files.exists(path))throw new IllegalArgumentException("fresh QA directory required");var settings=new MemorySettings(profile(Path.of(args[0])),false);
  var store=await(JournalStore.open(path,WORLD,new StoreOptions(4_194_400,4_194_304,16_777_216,33_554_432,32,0,128)));try{
   var region=new RegionAggregate(new RegionRecord(header(REGION.value(),1),REGION,DIM,RegionStatus.ACTIVE,new SupportShape(List.of(new Aabb(DIM,10000,0,0,10016,128,16)),List.of(),List.of()),Set.of(),new UnitValue(0),Set.of(),Set.of(),new Revision(1),new RegionPersonality(Map.of()),Set.of(new MemoryId(REGION.value()))));
   var memory=new RegionMemoryAggregate(new RegionMemory(header(REGION.value(),1),REGION,Map.of(),new Tick(0),new Tick(0),Set.of(),Set.of(),new RegionActivityState(Map.of(),Map.of())));
   append(store,List.of(region,memory,player(KNOWN,1,Optional.empty())));
   var demand=new BoundedMetadataProjection.Demand(Set.of(KNOWN),Set.of(),16,Set.of());var selected=load(store,demand,settings);
   append(store,List.of(player(FOREIGN,1,Optional.empty())));var current=store.metadataSnapshot().orElseThrow();
   check(selected.currentHeads(store,current),"new unrelated operational player does not invalidate current scoped bodies or tag census");
   check(!selected.scopes().scopes().contains(new ScopeRef(ScopeKind.PLAYER,FOREIGN.value()))&&!selected.scopes().scopes().contains(new ScopeRef(ScopeKind.WORLD,WORLD.value())),"omitted unrelated player is outside covered scopes; no false WORLD absence");
   check(!selected.anchorCoverage().orElseThrow().sameSelection(store,current),"same view cannot authorize exact key-set reuse after new operational key");
   selected=refresh(store,selected,demand,settings);
   check(selected.memory().operationalPlayers().size()==2&&selected.examinedRows()>selected.states().size(),"refresh performs real full scan and includes newly known disconnected cache head");
   var wanted=new BoundedMetadataProjection.Demand(Set.of(KNOWN,REQUESTED),Set.of(),16,Set.of());selected=load(store,wanted,settings);
   check(selected.scopes().scopes().contains(new ScopeRef(ScopeKind.PLAYER,REQUESTED.value()))&&!selected.states().containsKey(new AggregateKey(AggregateKind.PLAYER_OPERATIONAL,REQUESTED.value())),"requested missing player has explicit primary absence proof");
   append(store,List.of(player(REQUESTED,1,Optional.empty())));check(!selected.currentHeads(store,store.metadataSnapshot().orElseThrow()),"requested player absence-to-presence remains fenced despite unrelated-player exception");
   selected=refresh(store,selected,wanted,settings);check(selected.memory().operationalPlayers().size()==3&&selected.currentHeads(store,store.metadataSnapshot().orElseThrow()),"new requested player is read before becoming current");
   append(store,List.of(player(KNOWN,2,Optional.empty())));var next=store.metadataSnapshot().orElseThrow();
   check(!selected.currentHeads(store,next),"existing selected player body revision remains exact");
   check(selected.anchorCoverage().orElseThrow().sameSelection(store,next),"unchanged key and last-region semantics permit bounded body refresh");
   selected=refresh(store,selected,wanted,settings);check(selected.examinedRows()==selected.states().size()&&selected.currentHeads(store,store.metadataSnapshot().orElseThrow()),"selected player counter update refreshed without world rescan");
   append(store,List.of(player(KNOWN,3,Optional.of(REGION))));next=store.metadataSnapshot().orElseThrow();
   check(!selected.currentHeads(store,next)&&!selected.anchorCoverage().orElseThrow().sameSelection(store,next),"selected last-region change invalidates body and key-selection proof");
   selected=refresh(store,selected,wanted,settings);check(selected.memory().regionRecords().containsKey(REGION)&&selected.examinedRows()>selected.states().size(),"new selected last-region reference expands scope through complete scan");
   check(store.metadataCachedEntries()<=512,"cache512 unchanged");
  }finally{await(store.closeAsync());}
  System.out.println("PASS ScopedPlayerKeyCensusTests assertions="+assertions+" scope=synthetic_actual_J04_no_Minecraft");
 }
}
