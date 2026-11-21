package fr.proutlost.director.persistence;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.catalog.DefinitionCodec;
import fr.proutlost.director.config.TestingConfig;
import fr.proutlost.director.engine.CandidateGenerator;
import fr.proutlost.director.engine.ConditionEvaluator;
import fr.proutlost.director.runtime.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Real disposable J04 WAL test. Player/region evidence is an explicit TEST_ONLY
 * condition fixture; no Minecraft client, human decision or physical effect is asserted. */
public final class ChainJournalStoreTests {
 private static final WorldId WORLD=new WorldId(id(1));
 private static final RegionId REGION=new RegionId(id(2));
 private static final PlayerId PLAYER=new PlayerId(id(3));
 private static final ChainId CHAIN=new ChainId(id(4));
 private static final DimensionId DIM=new DimensionId("minecraft:overworld");
 private static final ScopeRef WORLD_SCOPE=new ScopeRef(ScopeKind.WORLD,WORLD.value());
 private static final ScopeRef REGION_SCOPE=new ScopeRef(ScopeKind.REGION,REGION.value());
 private static final ScopeRef PLAYER_SCOPE=new ScopeRef(ScopeKind.PLAYER,PLAYER.value());
 private static final StoreOptions OPTIONS=new StoreOptions(1_048_620,2_097_152,4_194_304,16_777_216,32,0,128);
 private static int assertions;
 private static UUID id(long n){return new UUID(0x434153434841494eL,n);}
 private static void check(boolean ok,String why){assertions++;if(!ok)throw new AssertionError(why);}
 private static <T>T await(CompletionStage<T> future)throws Exception{return future.toCompletableFuture().get(15,TimeUnit.SECONDS);}
 private static AggregateHeader header(UUID id,long revision,long tick){return new AggregateHeader(1,id,new Revision(revision),new Sequence(0),new Tick(tick));}
 private static RegionRecord region(long revision){
  return new RegionRecord(header(REGION.value(),revision,revision),REGION,DIM,RegionStatus.ACTIVE,
   new SupportShape(List.of(),List.of(),List.of()),Set.of(),new UnitValue(.5),Set.of(),Set.of(),
   new Revision(1),new RegionPersonality(Map.of()),Set.of());
 }
 private static CommitAck append(JournalStore store,long transaction,List<DurableRecord> records)throws Exception{
  return await(store.append(ResultTransaction.batch(new TransactionId(id(transaction)),
   store.session().storeGeneration(),Map.of(),records,List.of())));
 }
 private static ChainInstance readChain(JournalStore store)throws Exception{
  var query=new StoreQuery(WORLD,Set.of(new AggregateKey(AggregateKind.CHAIN,CHAIN.value())),new Sequence(0),Optional.empty(),1);
  var result=await(store.read(query));ReadContracts.validate(result,query,store.session());
  check(result.status()==ReadStatus.AVAILABLE,"chain typed J04 read available");
  var page=result.page().orElseThrow();check(!page.hasMore()&&page.aggregates().size()==1,"one actual chain row");
  return ((ChainAggregate)page.aggregates().getFirst().state()).value();
 }
 private static List<DurableRecord> history(JournalStore store)throws Exception{
  var query=new StoreQuery(WORLD,Set.of(),new Sequence(0),Optional.empty(),128);
  var result=await(store.read(query));ReadContracts.validate(result,query,store.session());
  check(result.status()==ReadStatus.AVAILABLE&&!result.page().orElseThrow().hasMore(),"complete bounded fixture chronology");
  return result.page().orElseThrow().records();
 }
 private static ChainRunner.Advance committed(ChainRunner runner)throws Exception{
  long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
  while(System.nanoTime()<deadline){var ready=runner.pollCommitted();if(ready.isPresent())return ready.orElseThrow();Thread.sleep(2);}
  throw new AssertionError("real chain ACK timeout");
 }
 private static DirectorFact fact(long n,FactType type){
  return new DirectorFact(new FactId(id(n)),type,List.of(REGION_SCOPE),Optional.empty(),EvidenceStatus.CONFIRMED,
   Map.of("laboratory_fixture",new JsonValue.Bool(true),"human_interaction",new JsonValue.Bool(false),
    "source",new JsonValue.Text("TEST_ONLY_J04_CHAIN_CAS_REGRESSION")),true,Optional.empty());
 }
 private static DecisionInput fixture(ConfigView config,ChainDefinition chain,EventDefinition event,Tick now){
  var region=region(7);var scopes=Set.of(WORLD_SCOPE,REGION_SCOPE,PLAYER_SCOPE);
  var revisions=Map.of(WORLD_SCOPE,new Revision(1),REGION_SCOPE,new Revision(7),PLAYER_SCOPE,new Revision(1));
  var scopeSnapshots=Map.of(
   WORLD_SCOPE,new ScopeSnapshot(WORLD_SCOPE,new Revision(1),Set.of(),Set.of(),Map.of()),
   REGION_SCOPE,new ScopeSnapshot(REGION_SCOPE,new Revision(7),Set.of(SensorKind.REGION_TAGS),Set.of(),Map.of()),
   PLAYER_SCOPE,new ScopeSnapshot(PLAYER_SCOPE,new Revision(1),Set.of(SensorKind.PLAYER_PRESENCE),Set.of(),Map.of()));
  var player=new PlayerSnapshot(PLAYER,new Revision(1),now,PlayerEligibility.ELIGIBLE,
   new PositionSnapshot(new BlockPosition(DIM,0,64,0),now),Set.of(REGION),Set.of(),new DurationTicks(1000),Set.of(),
   new ClientCapabilitySnapshot(1,Set.of(),Map.of(),true),Set.of());
  var context=new ObservationContext(scopeSnapshots,Map.of(PLAYER,player),Map.of(),
   Map.of(REGION,new RegionSnapshot(region,Set.of(new TagId("proutlost:clearing")),Set.of(PLAYER),List.of(),Map.of())),
   Map.of(),Map.of(),List.of(),Map.of());
  var scopeSet=new ScopeSet(WORLD,scopes);
  var snapshot=new SnapshotBatch(new RequestId(id(50)),scopeSet,now,revisions,context,Set.of(),Set.of(new ChunkRef(DIM,0,0)),HealthMode.NORMAL);
  var memory=new MemoryView(scopeSet,revisions,List.of(),List.of(),List.of(),Map.of(REGION,region),Map.of(),Map.of());
  var hash=BlobContent.hash(new byte[]{1});
  var catalogue=new CatalogSnapshot(new Epoch(1),hash,Map.of(event.id(),event),Map.of(chain.id(),chain),Map.of());
  var control=new EngineControlState(1,new Revision(0),now,now,new UnitValue(0),now,Optional.empty(),new UnitValue(.5));
  var world=new DirectorWorldState(header(WORLD.value(),1,now.value()),WORLD,new Epoch(1),now,RunControl.PAUSED,
   HealthMode.READ_ONLY_SAFE,PacingState.REST,GlobalTension.CALM,new ResourceId("fixture:chain_cas"),hash,
   config.provenanceHash(),new Epoch(1),new Sequence(0),control);
  var budgets=new BudgetView(BudgetMath.zero(),BudgetMath.zero(),Map.of());
  return new DecisionInput(snapshot,memory,catalogue,config,List.of(),List.of(),42,
   new DecisionState(world,budgets,new OwnershipView(Map.of()),List.of(),List.of(),List.of(),List.of(),List.of(),List.of()),Optional.empty());
 }
 private static Map<ResourceId,Double> eligible(ConfigView config,ChainDefinition chain,EventDefinition event,ChainTransition transition,Tick now){
  var input=fixture(config,chain,event,now);
  var reference=new DefinitionRef(event.id(),event.contentRevision(),BlobContent.hash(new ModelCodec<>(EventDefinition.class).encode(event)));
  var candidate=new CandidateGenerator.Generated(new CandidateId(id(51)),event,reference,Optional.of(REGION),Optional.empty(),
   new RoleAssignments(Map.of("witness",List.of(PLAYER))),input.context().scopes(),true);
  check(new ConditionEvaluator().evaluate(transition.condition(),candidate,input)==ConditionEvaluator.Truth.TRUE,
   "actual catalog transition condition TRUE on explicit TEST_ONLY captured evidence");
  return Map.of(transition.id(),1.0);
 }
 public static void run(Path folder)throws Exception{
  assertions=0;
  var config=TestingConfig.load(Files.readAllBytes(Path.of("src/directorTest/resources/catalog/DEFAULT_FOR_TESTING.json"))).view();
  var codec=new DefinitionCodec(config);
  var base=Path.of("src/director/resources/data/proutlost_director/director");
  var definition=codec.chain(Files.readAllBytes(base.resolve("chains/candidate/camp_short_arc.json")));
  var event=codec.event(Files.readAllBytes(base.resolve("events/candidate/parallel_steps.json")));
  check(definition.scope()==ScopeKind.REGION&&definition.transitions().size()==3,"exact bundled REGION short arc");
  var first=definition.transitions().get(0);var second=definition.transitions().get(1);
  var ref=new DefinitionRef(definition.id(),definition.contentRevision(),BlobContent.hash(new ModelCodec<>(ChainDefinition.class).encode(definition)));
  var initial=new ChainInstance(header(CHAIN.value(),1,10),CHAIN,ref,definition.mode(),ChainState.WAITING,
   Set.of(definition.entryState()),Set.of(),Set.of(REGION),List.of(),List.of(),Optional.empty(),new UnitValue(1),new Tick(10));
  var firstFact=fact(1001,first.onFact());var secondFact=fact(1002,second.onFact());
  var store=await(JournalStore.open(folder,WORLD,OPTIONS));StoreSession originalSession;ChainInstance afterFirst,afterSecond;
  try{
   check(!store.readOnlySafe(),"real disposable store healthy");
   // Region and chain revisions are separate domains. Updating this existing region
   // seven times must never be compared to CHAIN revision 1 or revision 2.
   for(int revision=1;revision<=7;revision++)check(append(store,100+revision,
    List.of(new DurableAggregate(new RegionAggregate(region(revision))))).ackStatus()==AckStatus.DURABLE,"actual REGION revision "+revision);
   check(append(store,200,List.of(new DurableAggregate(new ChainAggregate(initial)),new DurableFact(firstFact),new DurableFact(secondFact))).ackStatus()==AckStatus.DURABLE,"initial chain and explicit fixture facts durable");
   originalSession=store.session();
   var runner=new ChainRunner(store,definition,initial,REGION_SCOPE);var tick=new Tick(20);
   var proposed=runner.propose(firstFact,eligible(config,definition,event,first,tick),42,tick,new TransactionId(id(201))).orElseThrow();
   check(proposed.nextEvent().isEmpty()&&runner.snapshot().equals(initial),"proposal grants no dispatch before actor accepts ACK");
   var one=committed(runner);afterFirst=one.state();
   check(one.nextEvent().equals(first.eventId())&&afterFirst.header().revision().value()==2,"first branch committed despite REGION revision7");
   check(afterFirst.consumedFactIds().equals(Set.of(firstFact.factId()))&&afterFirst.branchHistory().size()==1,"first fact consumed exactly once");
   var tick2=tick.plus(definition.minimumStepGapTicks().value());
   var proposed2=runner.propose(secondFact,eligible(config,definition,event,second,tick2),43,tick2,new TransactionId(id(202))).orElseThrow();
   check(proposed2.nextEvent().isEmpty(),"second proposal does not dispatch");
   var two=committed(runner);afterSecond=two.state();
   check(two.nextEvent().equals(second.eventId())&&afterSecond.header().revision().value()==3,"second branch has independent CHAIN revision3");
   check(afterSecond.consumedFactIds().equals(Set.of(firstFact.factId(),secondFact.factId()))&&afterSecond.branchHistory().size()==2,"both durable facts retained");
   // Same AggregateKey still enforces exact next revision. Fixing the wrong REGION
   // CAS must not permit a stale CHAIN revision2 to overwrite committed revision3.
   check(append(store,203,List.of(new DurableAggregate(new ChainAggregate(afterFirst)))).ackStatus()==AckStatus.REJECTED,"stale CHAIN AggregateKey revision rejected");
   check(readChain(store).equals(afterSecond)&&!store.readOnlySafe(),"stale append did not change last committed chain");
   check(history(store).stream().noneMatch(r->r instanceof DurablePrepare||r instanceof DurableResult),"branch selection never fabricates a physical dispatch");
  }finally{await(store.closeAsync());}
  store=await(JournalStore.open(folder,WORLD,OPTIONS));
  try{
   check(store.session().bootEpoch().compareTo(originalSession.bootEpoch())>0,"same WAL reopened in new boot");
   var restored=readChain(store);check(restored.equals(afterSecond)&&restored.chainId().equals(CHAIN),"same chain identity/history recovered");
   var rows=history(store);check(rows.stream().filter(DurableFact.class::isInstance).map(DurableFact.class::cast).map(DurableFact::value).filter(f->f.equals(firstFact)||f.equals(secondFact)).count()==2,"same exact durable fixture facts recovered");
   var runner=new ChainRunner(store,definition,restored,REGION_SCOPE);
   check(runner.pollCommitted().isEmpty(),"recovery does not automatically dispatch remembered next event");
   var later=restored.nextEvaluationTick().plus(1);
   check(runner.propose(firstFact,Map.of(first.id(),1.0),44,later,new TransactionId(id(204))).isEmpty(),"consumed first fact cannot replay");
   check(runner.propose(secondFact,Map.of(second.id(),1.0),45,later,new TransactionId(id(205))).isEmpty(),"consumed second fact cannot replay");
   check(history(store).stream().noneMatch(r->r instanceof DurablePrepare||r instanceof DurableResult),"reboot remains metadata only without an explicit event driver");
  }finally{await(store.closeAsync());}
  initialBranch(folder.resolveSibling(folder.getFileName()+"-causal-first-branch"),config,definition,event,first);
  System.out.println("PASS J04 real chain REGION/CHAIN CAS, two catalog transitions and same-WAL recovery assertions="+assertions+"; TEST_ONLY evidence, no physical/client qualification");
 }
 /** Exercise actual filesystem ACKs for the creation path. Test calibration is
  * retained; this does not approve fixture definitions or qualify native production. */
 private static void initialBranch(Path folder,ConfigView config,ChainDefinition definition,EventDefinition event,ChainTransition transition)throws Exception{
  var chain=new ChainId(id(5000));var tick=new Tick(100);var source=fact(5001,transition.onFact());
  var created=new DirectorFact(new FactId(id(5002)),new FactType("test:causal_chain_created"),List.of(WORLD_SCOPE,REGION_SCOPE),
   Optional.empty(),EvidenceStatus.CONFIRMED,Map.of("source_fact",new JsonValue.Text(source.factId().value().toString())),true,Optional.empty());
  check(!ChainRunner.productionSource(source),"legacy fact without producer mode cannot activate production chain");
  var attested=new HashMap<String,JsonValue>();attested.put("provider_mode",new JsonValue.Text(ProviderMode.PRODUCTION.name()));
  var declared=new DirectorFact(source.factId(),source.factType(),source.subjectRefs(),source.eventId(),source.evidenceStatus(),attested,true,Optional.empty());
  check(ChainRunner.productionSource(declared),"positive PRODUCTION metadata accepted by pure provenance guard, no operator approval inferred");
  attested.put("provider_mode",new JsonValue.Text(ProviderMode.TEST_ONLY.name()));
  check(!ChainRunner.productionSource(new DirectorFact(source.factId(),source.factType(),source.subjectRefs(),source.eventId(),source.evidenceStatus(),attested,true,Optional.empty())),"TEST_ONLY producer cannot enter production chain");
  attested.put("provider_mode",new JsonValue.Text(ProviderMode.PRODUCTION.name()));attested.put("manual_lab",new JsonValue.Bool(true));
  check(!ChainRunner.productionSource(new DirectorFact(source.factId(),source.factType(),source.subjectRefs(),source.eventId(),source.evidenceStatus(),attested,true,Optional.empty())),"conflicting manual LAB evidence stays refused");
  var key=new AggregateKey(AggregateKind.CHAIN,chain.value());var session=(StoreSession)null;
  var store=await(JournalStore.open(folder,WORLD,OPTIONS));
  try{
   session=store.session();check(append(store,5003,List.of(new DurableFact(source))).ackStatus()==AckStatus.DURABLE,"causal source committed before chain activation");
   var runner=ChainRunner.initial(store,definition,chain,REGION_SCOPE,Set.of(REGION),tick);
   check(runner.snapshot().header().revision().value()==0,"initial chain remains unpublished revision zero");
   var query=new StoreQuery(WORLD,Set.of(key),new Sequence(0),Optional.empty(),1);
   check(await(store.read(query)).page().orElseThrow().aggregates().isEmpty(),"creating in-memory runner does not persist empty chain");
   var undurable=new DirectorFact(source.factId(),source.factType(),source.subjectRefs(),source.eventId(),source.evidenceStatus(),source.payload(),false,source.supersedesFactId());
   check(runner.propose(undurable,Map.of(transition.id(),1.0),1,tick,new TransactionId(id(5004))).isEmpty(),"uncommitted evidence cannot create chain");
   var foreign=new DirectorFact(new FactId(id(5005)),source.factType(),List.of(new ScopeRef(ScopeKind.REGION,id(5006))),Optional.empty(),EvidenceStatus.CONFIRMED,Map.of(),true,Optional.empty());
   check(runner.propose(foreign,Map.of(transition.id(),1.0),1,tick,new TransactionId(id(5007))).isEmpty(),"another region cannot create this lineage");
   var scores=eligible(config,definition,event,transition,tick);
   var input=fixture(config,definition,event,tick);
   check(new ConditionEvaluator().evaluateScope(transition.condition(),Optional.of(REGION),new RoleAssignments(Map.of("participants",List.of(PLAYER))),
    input.context().scopes(),input)==ConditionEvaluator.Truth.TRUE,"eventless chain evaluation reuses exact condition evaluator and captured scopes");
   var proposal=runner.propose(source,scores,123,tick,new TransactionId(id(5008)),next->List.of(new DurableFact(created))).orElseThrow();
   check(proposal.nextEvent().isEmpty()&&runner.snapshot().header().revision().value()==0,"no executable child or published branch before ACK polling");
   var committed=committed(runner);
   check(committed.state().header().revision().value()==1&&committed.state().consumedFactIds().equals(Set.of(source.factId())),"creation and source consumption commit as first revision one");
   check(committed.nextEvent().equals(transition.eventId()),"child is offered only by exact committed ACK");
   var rows=history(store);check(rows.stream().anyMatch(r->r instanceof DurableFact d&&d.value().equals(created)),"creation marker shares durable first branch batch");
   check(runner.propose(source,scores,123,committed.state().nextEvaluationTick(),new TransactionId(id(5009))).isEmpty(),"same causal source cannot advance chain twice");
   check(rows.stream().noneMatch(r->r instanceof DurablePrepare||r instanceof DurableResult),"activation creates no physical event or fake completion");
  }finally{await(store.closeAsync());}
  store=await(JournalStore.open(folder,WORLD,OPTIONS));
  try{
   check(store.session().bootEpoch().compareTo(session.bootEpoch())>0,"created chain reopens under fresh WAL session");
   var query=new StoreQuery(WORLD,Set.of(key),new Sequence(0),Optional.empty(),1);var read=await(store.read(query));ReadContracts.validate(read,query,store.session());
   var restored=((ChainAggregate)read.page().orElseThrow().aggregates().getFirst().state()).value();
   check(restored.header().revision().value()==1&&restored.chainId().equals(chain)&&restored.consumedFactIds().contains(source.factId()),"exact first branch and source survive cold reopen");
   check(new ChainRunner(store,definition,restored,REGION_SCOPE).pollCommitted().isEmpty(),"cold reopen never invents a fresh dispatch ACK");
  }finally{await(store.closeAsync());}
 }
 public static void main(String[] args)throws Exception{run(Path.of(args[0]));}
 private ChainJournalStoreTests(){}
}
