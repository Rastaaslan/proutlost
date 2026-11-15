package fr.proutlost.director.adapter.neoforge.server;

import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.permission.CommitPermit;
import fr.proutlost.director.bootstrap.*;
import fr.proutlost.director.persistence.*;
import fr.proutlost.director.runtime.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.*;

/** Actual EventLifecycle + real disposable JournalStore, actual PREPARE authority,
 * hydration and ACKs. All targets/geometry are explicit TEST_ONLY DTO fixtures.
 * No effect binding/apply, client receipt, original fact or production world is touched. */
@GameTestHolder("proutlost_director")
@PrefixGameTestTemplate(false)
public final class DirectorNativePerceptionColdEventGameTests {
 private DirectorNativePerceptionColdEventGameTests(){}
 private static final Set<MinecraftServer> ACTIVE=Collections.newSetFromMap(new IdentityHashMap<>());
 public static boolean active(MinecraftServer server){return ACTIVE.contains(server);}
 private static final StoreOptions OPTIONS=new StoreOptions(1_048_620,2_097_152,4_194_304,16_777_216,32,0,128);
 private static final FactType END=new FactType("proutlost_director:lab_perception_end");
 private enum Mode{ORDINARY_UNCERTAIN,RESERVED_UNCERTAIN,SPENT_SOUND}
 private static final class Probe{
  final MinecraftServer server;final WorldId world;final CatalogSnapshot catalog;final EventDefinition definition;
  final ExecutionPlan plan;final SnapshotBatch snapshot;final Mode mode;final Tick tick=new Tick(20500);
  final CompletableFuture<JournalStore> opening;final BlobContent context,intentBlob;final EventInstance initial;
  final List<DurableRecord> backgroundRows=new ArrayList<>();final List<BlobContent> backgroundBlobs=new ArrayList<>();
  final Set<EventInstanceId> backgroundEvents=new HashSet<>();
  JournalStore store;DirectorNativeEventLifecycle lifecycle;CommitPermit.Authority authority;
  PrepareRecord prepare;UniqueUseRecord expectedUnique;ResultRecord seededResult;Sequence prepareCut;
  CompletableFuture<CommitAck> ack;CompletableFuture<Void> closing;long endCut;int stage;
  Probe(GameTestHelper helper,Mode mode){
   this.mode=mode;server=helper.getLevel().getServer();
   var scene=DirectorNativeJ02J03SelectedSafeMicroGameTests.select(helper);
   snapshot=scene.input().context();world=snapshot.scopes().worldId();
   var p=scene.proposal().selectedPlan().orElseThrow().effects().getFirst().expectedFootprint();
   var a=new BlockPosition(p.dimension(),0,65,0);var b=new BlockPosition(p.dimension(),8,65,0);
   catalog=new DirectorNativeCatalogueCatalog(scene.input().config(),List.of(new RouteSegment(a,b,.5))).snapshot();
   definition=catalog.events().get(new DefinitionId("proutlost_director:candidate/"+(mode==Mode.ORDINARY_UNCERTAIN?"safe_micro":"parallel_steps")));
   var event=new EventInstanceId(UUID.randomUUID());var effects=new ArrayList<EffectIntent>();
   for(var step:definition.steps())effects.add(new EffectIntent(DirectorNativeCatalogueExecution.operation(event,step.id()),
     step.primitive(),1,step.args(),p,event));
   var ref=new DefinitionRef(definition.id(),definition.contentRevision(),BlobContent.hash(new ModelCodec<>(EventDefinition.class).encode(definition)));
   plan=new ExecutionPlan(new CandidateId(UUID.randomUUID()),ref,effects,scene.proposal().selectedPlan().orElseThrow().roles(),
     snapshot.scopes(),new ReadSet(Map.of(),Map.of(),Map.of()));
   new CatalogueEventLedger.Selection(definition,plan);
   context=context(plan);
   intentBlob=BlobContent.capture(new ResourceId("proutlost_director:perception_intent_v1"),1,
     new ModelCodec<>(EffectIntent.class).encode(effects.getFirst()));
   var steps=new TreeMap<String,StepState>();definition.steps().forEach(s->steps.put(s.id(),StepState.WAITING));
   initial=new EventInstance(new AggregateHeader(1,event.value(),new Revision(1),new Sequence(0),tick),event,ref,
    catalog.generation(),EventState.PROPOSED,"EXPLICIT_TEST_ONLY_COLD_FIXTURE",plan.roles(),Set.of(),context.reference(),
    steps,Set.of(),Set.of(),Set.of(),Optional.empty(),Set.of(),Optional.empty(),new DurationTicks(0),
    definition.timing().maxDurationTicks(),definition.recovery().mode(),Optional.empty());
   // Forty additional real context blobs/PROPOSED heads exercise the unchanged
   // queue32 cold join. Their explicit TEST_ONLY prestart cancellations have no
   // PREPARE/RESULT, physical effect, client receipt or manufactured success.
   if(mode==Mode.ORDINARY_UNCERTAIN)for(int i=0;i<40;i++){
    var id=new EventInstanceId(UUID.randomUUID());var childEffects=new ArrayList<EffectIntent>();
    for(var step:definition.steps())childEffects.add(new EffectIntent(DirectorNativeCatalogueExecution.operation(id,step.id()),
      step.primitive(),1,step.args(),p,id));
    var childPlan=new ExecutionPlan(new CandidateId(UUID.randomUUID()),ref,childEffects,plan.roles(),snapshot.scopes(),plan.readSet());
    new CatalogueEventLedger.Selection(definition,childPlan);
    var blob=context(childPlan);backgroundBlobs.add(blob);backgroundEvents.add(id);
    var child=new EventInstance(new AggregateHeader(1,id.value(),new Revision(1),new Sequence(0),tick),id,ref,
      catalog.generation(),EventState.PROPOSED,"EXPLICIT_TEST_ONLY_PRESTART_CANCEL_FIXTURE",childPlan.roles(),Set.of(),blob.reference(),
      steps,Set.of(),Set.of(),Set.of(),Optional.empty(),Set.of(),Optional.empty(),new DurationTicks(0),
      definition.timing().maxDurationTicks(),definition.recovery().mode(),Optional.empty());
    var abort=new DirectorFact(new FactId(UUID.nameUUIDFromBytes(("catalogue-aborted-v1\0"+id.value()).getBytes(StandardCharsets.UTF_8))),
      new FactType("proutlost_director:catalogue_event_aborted"),List.copyOf(childPlan.scopes().scopes()),Optional.of(id),
      EvidenceStatus.CONFIRMED,Map.of("source",text("CATALOGUE_SELECTED"),"definition",text(definition.id().value()),
        "provider_mode",text(ProviderMode.TEST_ONLY),"reason",text("EXPLICIT_TEST_ONLY_PRESTART_ABORT"),
        "no_driver_start",new JsonValue.Bool(true),"no_physical_effect",new JsonValue.Bool(true)),true,Optional.empty());
    backgroundRows.add(new DurableAggregate(new EventAggregate(child)));backgroundRows.add(new DurableFact(abort));
   }
   var path=server.getWorldPath(LevelResource.ROOT).resolve("director-perception-cold-metadata-test-only").resolve(event.value().toString());
   opening=JournalStore.open(path,world,OPTIONS).toCompletableFuture();
  }
  private BlobContent context(ExecutionPlan actualPlan){
   return BlobContent.capture(DirectorNativeEventLifecycle.CONTEXT,1,CanonicalJsonV1.encode(new JsonValue.ObjectValue(Map.of(
    "source",text("ACTUAL_AUDITED_CATALOGUE_SELECTION"),"world",text(world.value()),"audit_decision",text(UUID.randomUUID()),
    "catalog_generation",text(catalog.generation().value()),"definition",text(encoded(EventDefinition.class,definition)),
    "plan",text(encoded(ExecutionPlan.class,actualPlan)),"snapshot",text(encoded(SnapshotBatch.class,snapshot))))));
  }
  private static JsonValue.Text text(Object x){return new JsonValue.Text(x.toString());}
  private static <T> String encoded(Class<T> type,T value){return Base64.getEncoder().encodeToString(new ModelCodec<>(type).encode(value));}
  private StoreReadResult all(){
   var query=new StoreQuery(world,Set.of(),new Sequence(0),Optional.empty(),512);
   var read=store.view(query);ReadContracts.validate(read,query,store.session());return read;
  }
  private RestoredAggregate projected(AggregateKey key){
   var query=new StoreQuery(world,Set.of(key),new Sequence(0),Optional.empty(),1);
   var read=store.view(query);ReadContracts.validate(read,query,store.session());
   if(read.status()!=ReadStatus.AVAILABLE||read.page().orElseThrow().hasMore()||read.page().orElseThrow().aggregates().size()!=1)
    throw new IllegalStateException("actual private fixture aggregate unavailable");
   var row=read.page().orElseThrow().aggregates().getFirst();
   if(!row.state().key().equals(key)||row.committedSequence().compareTo(read.page().orElseThrow().lastDurableSequence())>0)
    throw new IllegalStateException("actual private fixture aggregate identity/cut changed");
   return row;
  }
  private RestoredAggregate eventHead(){return projected(new AggregateKey(AggregateKind.EVENT,initial.instanceId().value()));}
  private EventInstance head(){return ((EventAggregate)eventHead().state()).value();}
  private RestoredAggregate uniqueHead(){return projected(new AggregateKey(AggregateKind.UNIQUE,expectedUnique.header().id()));}
  private long coveredCut(){
   var status=lifecycle.status();var marker="covered_cut=";int begin=status.indexOf(marker);
   if(begin<0)throw new IllegalStateException("actual EVENT reader coverage missing");
   begin+=marker.length();int end=begin;while(end<status.length()&&Character.isDigit(status.charAt(end)))end++;
   return Long.parseLong(status.substring(begin,end));
  }
  private void durable(GameTestHelper h){
   h.assertTrue(ack.isDone()&&!ack.isCompletedExceptionally()&&ack.getNow(null).ackStatus()==AckStatus.DURABLE,"actual fixture J04 ACK pending/not durable");
  }
  void closeOnFailure(){
   if(closing!=null)return;
   try{
    if(store!=null)closing=store.closeAsync().toCompletableFuture();
    else opening.whenComplete((opened,error)->{if(opened!=null)opened.closeAsync();});
   }catch(RuntimeException closeFailure){System.out.println("DIRECTOR_NATIVE_PERCEPTION_COLD_TEST_PRIVATE_CLOSE_FAILED "+closeFailure);}
  }
  void advance(GameTestHelper h){
   if(!server.isSameThread())throw new IllegalStateException("actual server actor");
   switch(stage){
    case 0->{
     if(!opening.isDone())return;store=opening.join();
     var rows=new ArrayList<DurableRecord>();rows.add(new DurableAggregate(new EventAggregate(initial)));rows.addAll(backgroundRows);
     var blobs=new ArrayList<BlobContent>();blobs.add(context);blobs.addAll(backgroundBlobs);
     ack=store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),
       rows,blobs)).toCompletableFuture();stage=1;
    }
    case 1->{
     if(!ack.isDone())return;durable(h);
     var intent=plan.effects().getFirst();var read=new ReadSet(Map.of(),Map.of(),Map.of());
     var reservation=new ReservationToken(new ReservationId(UUID.randomUUID()),world,initial.instanceId(),new Epoch(1),tick.plus(1000),List.of());
     var slice=new PreparedSlice(new SliceId(UUID.randomUUID()),intent.operationToken(),intent.expectedFootprint(),read,List.of(intent),BudgetMath.zero());
     List<UniqueUseRecord> unique=mode!=Mode.ORDINARY_UNCERTAIN?List.of(new UniqueUseRecord(
       new AggregateHeader(1,UUID.randomUUID(),new Revision(1),new Sequence(0),tick),definition.id(),UniquenessScope.ONCE_PER_PLAYER,
       plan.roles().roles().values().iterator().next().getFirst().value(),UniqueStatus.RESERVED,Optional.of(reservation),
       initial.instanceId(),Optional.empty(),Set.of(plan.roles().roles().values().iterator().next().getFirst().value()))):List.of();
     if(!unique.isEmpty())expectedUnique=unique.getFirst(); // PREPARE embeds/indexes revision1; do not prewrite it.
     prepare=new PrepareRecord(new TransactionId(UUID.randomUUID()),intent.operationToken(),reservation.generation(),read,reservation.expiryTick(),
       slice,reservation,new ValidationResult(true,List.of(),read),unique,Set.of(NoninvertibleEffect.SOUND_EXPOSURE),List.of(),
       new EffectBlobs(Set.of(),intentBlob.reference(),intentBlob.reference(),Map.of(),Map.of()));
     authority=new CommitPermit.Authority(store,4);
     authority.submit(new DurableBatch(prepare.transactionId(),store.session().storeGeneration(),read.revisions(),List.of(new DurablePrepare(prepare)),
       List.of(intentBlob),Optional.empty(),CommitPermit.Authority.prepareHash(prepare)),
       new LiveValidationSnapshot(read,intent.expectedFootprint().loadedChunks(),HealthMode.NORMAL,tick,reservation.generation()));
     stage=2;
    }
    case 2->{
     var intent=plan.effects().getFirst();
     var permission=authority.poll(prepare.transactionId(),new LiveValidationSnapshot(prepare.readSet(),
       intent.expectedFootprint().loadedChunks(),HealthMode.NORMAL,tick,prepare.reservationGeneration()));
     if(permission.status()==fr.proutlost.director.api.permission.PermitResult.Status.PENDING)return;
     h.assertTrue(permission.status()==fr.proutlost.director.api.permission.PermitResult.Status.GRANTED,"actual forced private PREPARE ACK grants its exact authority");
     prepareCut=permission.permit().orElseThrow().prepareSequence();
     // Explicit RESULT DTO inputs only: no native binding/send or fabricated client receipt.
     var actual=new SliceResult(mode==Mode.SPENT_SOUND?Outcome.SUCCEEDED:Outcome.DELIVERY_UNCERTAIN,
       mode==Mode.SPENT_SOUND?Set.of(prepare.operationToken()):Set.of(),Map.of(),0,Set.of(),Set.of(),List.of());
     seededResult=new ResultRecord(prepare.transactionId(),prepare.operationToken(),actual,Set.of(),List.of(),List.of());
     var end=new DirectorFact(new FactId(UUID.randomUUID()),END,List.of(new ScopeRef(ScopeKind.WORLD,world.value())),
       Optional.of(initial.instanceId()),EvidenceStatus.CONFIRMED,Map.of("reason",text("RESTART_CLEANUP_NO_REPLAY")),true,Optional.empty());
     var rows=new ArrayList<DurableRecord>();rows.add(new DurableResult(seededResult));rows.add(new DurableFact(end));
     var blobs=new ArrayList<BlobContent>();
     if(expectedUnique!=null){
      var latest=uniqueHead();var old=((UniqueAggregate)latest.state()).value();
      h.assertTrue(old.equals(expectedUnique)&&old.header().revision().value()==1&&latest.committedSequence().equals(prepareCut),
       "embedded RESERVED revision1 must be the actual latest UNIQUE head at the exact PREPARE COMMIT");
      if(mode==Mode.SPENT_SOUND){
       var evidence=BlobContent.capture(new ResourceId("proutlost_director:catalogue_effect_evidence_v1"),1,
         new ModelCodec<>(ResultRecord.class).encode(seededResult));var header=old.header();
       expectedUnique=new UniqueUseRecord(new AggregateHeader(header.schemaVersion(),header.id(),header.revision().plus(1),
         header.lastSequence(),tick),old.definitionId(),old.scopeKind(),old.canonicalScopeId(),UniqueStatus.SPENT_POSSIBLE,
         Optional.empty(),old.instanceId(),Optional.of(evidence.reference()),old.originScopeIds());
       rows.add(new DurableUnique(expectedUnique));blobs.add(evidence);
      }
     }
     // UNIQUE has no ScopeRef CAS: its real latest header+1 is fenced atomically by StoreProjection.
     // This batch also uses the actual EVENT scope CAS and commits RESULT/END/SPENT evidence together.
     ack=store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),
       Map.of(new ScopeRef(ScopeKind.EVENT,initial.instanceId().value()),head().header().revision()),rows,blobs)).toCompletableFuture();stage=3;
    }
    case 3->{
     if(!ack.isDone())return;durable(h);endCut=ack.getNow(null).durableSequence().value();
     if(expectedUnique!=null){
      var unique=uniqueHead();
      h.assertTrue(((UniqueAggregate)unique.state()).value().equals(expectedUnique)&&
        unique.committedSequence().value()==(mode==Mode.SPENT_SOUND?endCut:prepareCut.value()),
        "actual RESULT ACK retains exact RESERVED or atomically commits revision2 SPENT_POSSIBLE");
      if(mode==Mode.SPENT_SOUND)h.assertTrue(ack.getNow(null).forcedBlobs().contains(expectedUnique.consumptionEvidence().orElseThrow()),
        "the same actual RESULT ACK forces its exact spend-evidence bytes");
     }
     lifecycle=new DirectorNativeEventLifecycle(server,world,store,catalog,()->tick);
     var query=new StoreQuery(world,Set.of(),new Sequence(0),Optional.empty(),512);var read=store.view(query);
     h.assertTrue(lifecycle.accept(query,read),"actual J04 source page accepted");
     // accept() has no hydrated definition yet: technical END must survive its FactId/cut buffer.
     h.assertTrue(head().state()==EventState.PROPOSED,"no state authority before recovery/hydration and ACK");
     stage=4;
    }
    case 4->{
     lifecycle.poll();h.assertFalse(lifecycle.failed(),"actual EventLifecycle must not reject its metadata batch");
     var current=head();if(current.state()!=EventState.PARTIAL||lifecycle.recovering())return;
     for(var id:backgroundEvents)if(((EventAggregate)projected(new AggregateKey(AggregateKind.EVENT,id.value())).state()).value().state()!=EventState.ABORTED_SAFE)return;
     h.assertTrue(backgroundEvents.size()==(mode==Mode.ORDINARY_UNCERTAIN?40:0),"the actual recovery fixture joins forty-one contexts with queue32 unchanged");
     h.assertTrue(!lifecycle.recovering()&&coveredCut()>=endCut&&eventHead().committedSequence().value()>endCut,
      "terminal EVENT COMMIT must follow END ACK and the real contiguous reader must cover its exact cut");
     h.assertTrue(current.outcome().equals(Optional.of(mode==Mode.SPENT_SOUND?Outcome.PARTIAL:Outcome.DELIVERY_UNCERTAIN)),
      "terminal PARTIAL retains actual successful-send or uncertain RESULT evidence");
     h.assertTrue(current.phase().equals(mode==Mode.SPENT_SOUND?"LAB_TEST_ONLY_PERCEPTION_RESTART_CLEANUP_PARTIAL_EFFECT":
       "LAB_TEST_ONLY_PERCEPTION_RESTART_CLEANUP_DELIVERY_UNCERTAIN"),"cold perception closure keeps its exact evidence-specific phase");
     h.assertTrue(current.effectRefs().equals(mode==Mode.SPENT_SOUND?Set.of(new EffectId(prepare.operationToken().value())):Set.of())&&
       current.mutationRefs().isEmpty()&&current.stepStates().values().stream().noneMatch(v->v==StepState.CONFIRMED),
      "successful-send APPLIED is retained without inventing a renderer receipt, mutation or confirmed step");
     h.assertTrue(current.stepStates().get(definition.steps().getFirst().id())==(mode==Mode.SPENT_SOUND?StepState.APPLIED:StepState.PREPARED),
      "the exact first sound RESULT determines APPLIED versus merely PREPARED");
     if(expectedUnique!=null){
      var unique=uniqueHead();
      h.assertTrue(((UniqueAggregate)unique.state()).value().equals(expectedUnique)&&
        unique.committedSequence().value()==(mode==Mode.SPENT_SOUND?endCut:prepareCut.value()),
        "cold terminalization preserves the exact current UNIQUE head without release, confirmation or reset");
     }
     var records=all().page().orElseThrow().records();
     var states=records.stream().filter(DurableAggregate.class::isInstance).map(DurableAggregate.class::cast)
       .map(DurableAggregate::state).filter(EventAggregate.class::isInstance).map(EventAggregate.class::cast).map(EventAggregate::value)
       .filter(e->e.instanceId().equals(initial.instanceId())).toList();
     h.assertTrue(states.stream().map(EventInstance::state).toList().equals(List.of(EventState.PROPOSED,EventState.RESERVED,EventState.PREPARING,
       EventState.READY,EventState.RUNNING,EventState.RESOLVING,EventState.PARTIAL)),"actual unique legal EVENT arcs committed one per ACK");
     for(int i=1;i<states.size();i++)h.assertTrue(StateTransitions.allows(states.get(i-1).state(),states.get(i).state())&&
       states.get(i).header().revision().value()==states.get(i-1).header().revision().value()+1,"actual J04 EVENT revision/arc continuity");
     h.assertTrue(records.stream().filter(DurableResult.class::isInstance).map(DurableResult.class::cast)
       .allMatch(r->r.value().equals(seededResult)),"the exact original matching RESULT is never replayed or rewritten");
     h.assertTrue(records.stream().filter(DurablePrepare.class::isInstance).count()==1&&records.stream().filter(DurableResult.class::isInstance).count()==1,
      "no second execution or PREPARE/RESULT replay");
     h.assertTrue(records.stream().filter(DurableFact.class::isInstance).map(DurableFact.class::cast)
       .filter(f->f.value().eventId().equals(Optional.of(initial.instanceId()))).allMatch(f->f.value().factType().equals(END))&&
       records.stream().noneMatch(DurableOutbox.class::isInstance),"no original success/no-effect/outbox manufactured");
     h.assertTrue(records.stream().filter(DurableFact.class::isInstance).map(DurableFact.class::cast)
       .filter(f->f.value().eventId().filter(backgroundEvents::contains).isPresent()).count()==backgroundEvents.size(),
       "background cancellations remain the exact original input facts, without completion/replay facts");
     closing=store.closeAsync().toCompletableFuture();stage=5;
    }
    case 5->{if(closing.isDone()){h.assertFalse(closing.isCompletedExceptionally(),"disposable test store closes");stage=6;}}
    case 6->{}
    default->throw new IllegalStateException("cold metadata fixture stage");
   }
  }
 }
 @GameTest(templateNamespace="proutlost_director",template="smoke_empty",timeoutTicks=12000)
 public static void uncertainPerceptionRecoveryUsesActualEventArcsAndPreservesReservedUnique(GameTestHelper helper){
  helper.assertTrue(Boolean.getBoolean("proutlost.director.nativeLab"),"explicit TEST_ONLY native LAB JVM required");
  var ordinary=new Probe(helper,Mode.ORDINARY_UNCERTAIN);var reserved=new Probe(helper,Mode.RESERVED_UNCERTAIN);
  var spent=new Probe(helper,Mode.SPENT_SOUND);ACTIVE.add(helper.getLevel().getServer());
  final RuntimeException[] failure={null};
  helper.succeedWhen(()->{
   if(failure[0]==null)try{ordinary.advance(helper);reserved.advance(helper);spent.advance(helper);}
   catch(RuntimeException unexpected){
    failure[0]=unexpected;ACTIVE.remove(helper.getLevel().getServer());
    ordinary.closeOnFailure();reserved.closeOnFailure();spent.closeOnFailure();
   }
   helper.assertFalse(failure[0]!=null,"latched actual cold metadata test failure: "+failure[0]);
   if(ordinary.stage==6&&reserved.stage==6&&spent.stage==6)ACTIVE.remove(helper.getLevel().getServer());
   helper.assertTrue(ordinary.stage==6&&reserved.stage==6&&spent.stage==6,"actual three-mode lifecycle/ACK fixtures pending");
  });
 }
}
