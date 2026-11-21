package fr.proutlost.director.persistence;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.runtime.ResultTransaction;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
/** Actual J04 retention/readback, including unlisted owned mutations and cold reopen. */
public final class PhysicalEventRetentionTests {
 private static int assertions;
 private static final StoreOptions OPTIONS=new StoreOptions(1048620,2097152,4194304,16777216,32,0,128);
 private static final BlobContent CONTEXT=BlobContent.capture(new ResourceId("fixture:retention"),1,new byte[]{1});
 private static <T>T await(CompletionStage<T> s)throws Exception{return s.toCompletableFuture().get(20,TimeUnit.SECONDS);}
 private static void check(boolean yes,String why){assertions++;if(!yes)throw new AssertionError(why);}
 private static AggregateHeader head(UUID id,long rev){return new AggregateHeader(1,id,new Revision(rev),new Sequence(0),new Tick(rev));}
 private static EventInstance event(EventInstanceId id,Outcome outcome,Set<MutationId> refs){return new EventInstance(head(id.value(),1),id,
  new DefinitionRef(new DefinitionId("fixture:retention"),new Revision(1),CONTEXT.reference().hash()),new Epoch(1),EventState.PARTIAL,"RETENTION_FIXTURE",
  new RoleAssignments(Map.of()),Set.of(),CONTEXT.reference(),Map.of(),Set.of(),Set.of(),refs,Optional.empty(),Set.of(),Optional.of(new Tick(0)),new DurationTicks(1),new DurationTicks(1),RecoveryMode.CLEANUP,Optional.of(outcome));}
 private static MutationRecord mutation(EventInstanceId event,MutationId id,OwnerToken token,MutationState state,long rev){return new MutationRecord(head(id.value(),rev),id,event,
  new SliceId(UUID.randomUUID()),state,new PrimitiveId("fixture:owned_block"),1,new Footprint(new DimensionId("minecraft:overworld"),Set.of(),Set.of(),List.of()),
  CONTEXT.reference(),CONTEXT.reference(),token,new Epoch(1),new ReadSet(Map.of(),Map.of(),Map.of()),Set.of(),List.of(),Optional.empty(),new Sequence(0));}
 private static OwnershipRecord owner(UUID id,MutationId mutation,OwnerToken token,OwnershipDisposition disposition,long rev){return new OwnershipRecord(head(id,rev),
  new ResourceKey(OwnershipDomain.BLOCK,new DimensionId("minecraft:overworld"),"0,64,0"),Set.of(token),new Epoch(1),disposition,mutation,CONTEXT.reference().hash(),new Epoch(0),Optional.empty());}
 private static void append(JournalStore store,List<DurableRecord> rows)throws Exception{var result=await(store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),rows,List.of(CONTEXT))));check(result.ackStatus()==AckStatus.DURABLE,"actual J04 rows durable");}
 private static PhysicalEventReadback read(JournalStore s,EventInstanceId e){return s.physicalEventReadback(e,4096).orElseThrow();}
 public static void main(String[] args)throws Exception{
  var directory=Files.createTempDirectory("director-physical-retention-");var world=new WorldId(UUID.randomUUID());
  var store=await(JournalStore.open(directory,world,OPTIONS));var uncertain=new EventInstanceId(UUID.randomUUID());
  append(store,List.of(new DurableAggregate(new EventAggregate(event(uncertain,Outcome.DELIVERY_UNCERTAIN,Set.of())))));
  check(!read(store,uncertain).released(),"DELIVERY_UNCERTAIN terminal retains pin");
  var missing=new EventInstanceId(UUID.randomUUID());var missingMutation=new MutationId(UUID.randomUUID());
  append(store,List.of(new DurableAggregate(new EventAggregate(event(missing,Outcome.PARTIAL,Set.of(missingMutation))))));
  check(!read(store,missing).released(),"terminal missing referenced mutation retains pin");
  var owned=new EventInstanceId(UUID.randomUUID());var mutation=new MutationId(UUID.randomUUID());var token=new OwnerToken(UUID.randomUUID());var ownerId=UUID.randomUUID();
  append(store,List.of(new DurableAggregate(new EventAggregate(event(owned,Outcome.PARTIAL,Set.of()))),
   new DurableAggregate(new MutationAggregate(mutation(owned,mutation,token,MutationState.COMMITTED,1))),new DurableOwnership(owner(ownerId,mutation,token,OwnershipDisposition.DIRECTOR_ACTIVE,1))));
  check(read(store,owned).mutations().size()==1&&!read(store,owned).released(),"actual unlisted owned mutation retained despite terminal head");
  check(store.physicalEventReadback(owned,1).isEmpty(),"bounded unknown never truncates to successful cleanup");
  var session=store.session();await(store.closeAsync());store=await(JournalStore.open(directory,world,OPTIONS));
  check(!store.session().bootEpoch().equals(session.bootEpoch()),"actual cold boot changes session");
  check(!read(store,owned).released()&&!read(store,uncertain).released(),"cold boot retains owned and uncertain outcomes");
  append(store,List.of(new DurableAggregate(new MutationAggregate(mutation(owned,mutation,token,MutationState.ROLLED_BACK,2)))));
  check(!read(store,owned).released(),"rollback label with active owner cannot retire");
  append(store,List.of(new DurableOwnership(owner(ownerId,mutation,token,OwnershipDisposition.RELEASED,2))));
  check(read(store,owned).released(),"actual rollback plus matching released owner permits retirement");
  var closedUnknown=event(owned,Outcome.DELIVERY_UNCERTAIN,Set.of(mutation));var old=closedUnknown.header();
  closedUnknown=new EventInstance(new AggregateHeader(old.schemaVersion(),old.id(),new Revision(2),old.lastSequence(),old.updatedTick()),closedUnknown.instanceId(),closedUnknown.definition(),closedUnknown.catalogGeneration(),closedUnknown.state(),closedUnknown.phase(),closedUnknown.roleAssignments(),closedUnknown.regionIds(),closedUnknown.contextRef(),closedUnknown.stepStates(),closedUnknown.reservationRefs(),closedUnknown.effectRefs(),closedUnknown.mutationRefs(),closedUnknown.chainId(),closedUnknown.requirementIds(),closedUnknown.startedTick(),closedUnknown.elapsedActiveTicks(),closedUnknown.maxDurationTicks(),closedUnknown.recoveryPolicy(),closedUnknown.outcome());
  append(store,List.of(new DurableAggregate(new EventAggregate(closedUnknown))));check(read(store,owned).released(),"proven physical cleanup can retire pin while original delivery remains uncertain in history");
  check(read(store,owned).event().orElseThrow().outcome().orElseThrow()==Outcome.DELIVERY_UNCERTAIN&&read(store,owned).mutations().containsKey(mutation),"uncertain outcome and mutation reference retained unchanged");
  var absent=new EventInstanceId(UUID.randomUUID());check(!read(store,absent).released(),"absence alone never proves no invocation");
  var fact=new DirectorFact(new FactId(UUID.randomUUID()),new FactType("proutlost_director:catalogue_event_aborted"),List.of(new ScopeRef(ScopeKind.EVENT,absent.value())),Optional.of(absent),EvidenceStatus.CONFIRMED,
   Map.of("no_driver_start",new JsonValue.Bool(true),"no_physical_effect",new JsonValue.Bool(true),"proof_source",new JsonValue.Text("EXECUTOR_PRESTART_CANCEL")),true,Optional.empty());
  append(store,List.of(new DurableFact(fact)));check(read(store,absent).released(),"durable executor prestart cancel permits retirement without event");
  await(store.closeAsync());store=await(JournalStore.open(directory,world,OPTIONS));
  check(read(store,owned).released()&&read(store,absent).released()&&!read(store,missing).released(),"cold reopen preserves exact cleanup proof and unknown evidence");
  await(store.closeAsync());System.out.println("PHYSICAL_EVENT_RETENTION_PASS assertions="+assertions+" minecraft=false multiplayer=false");
 }
}
