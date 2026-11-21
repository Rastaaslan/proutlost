package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.PlanningPort;
import fr.proutlost.director.engine.*;
import java.nio.file.*;
import java.util.*;

/** Pure fixture/zero-role engine contract only. No Minecraft, J04 or clients. */
public final class RealGroupClientsQaCatalogTests {
 private RealGroupClientsQaCatalogTests(){}
 private static int checks;
 private static void check(boolean yes,String why){checks++;if(!yes)throw new AssertionError(why);}
 public static void main(String[] args)throws Exception{
  byte[] raw=Files.readAllBytes(Path.of(args[0]));var json=((JsonValue.ObjectValue)CanonicalJsonV1.decode(raw)).values();
  var values=new HashMap<String,JsonValue.Number>();((JsonValue.ObjectValue)json.get("values")).values().forEach((k,v)->values.put(k,(JsonValue.Number)v));
  var config=new ConfigView(new Revision(1),BlobContent.hash(raw),Calibration.DEFAULT_FOR_TESTING,values);
  var catalog=DirectorRealGroupClientsQaCatalog.snapshot();var def=catalog.events().get(DirectorRealGroupClientsQaCatalog.EVENT);
  check(catalog.equals(DirectorRealGroupClientsQaCatalog.snapshot()),"exact closed fixture stable");
  check(catalog.events().size()==1&&catalog.chains().isEmpty()&&catalog.primitives().size()==1,"one new QA definition/primitive");
  check(def.category()==Category.HYBRID&&def.calibration()==Calibration.DEFAULT_FOR_TESTING,"unapproved HYBRID fixture");
  check(def.target().roles().equals(Map.of("observer",new Role(0,0,false))),"declared zero-role contract");
  check(((CampGrowth)def.steps().getFirst().args()).maxBlocks()==1,"one native QA cell only");
  check(new ModelCodec<>(CatalogSnapshot.class).decode(new ModelCodec<>(CatalogSnapshot.class).encode(catalog)).equals(catalog),"closed model codec round trip");
  WorldId world=new WorldId(UUID.randomUUID());RegionId region=new RegionId(UUID.randomUUID());DimensionId dim=new DimensionId("minecraft:overworld");Tick now=new Tick(20000);
  ScopeRef ws=new ScopeRef(ScopeKind.WORLD,world.value()),rs=new ScopeRef(ScopeKind.REGION,region.value());ScopeSet scopes=new ScopeSet(world,Set.of(ws,rs));
  var versions=Map.of(ws,new Revision(1),rs,new Revision(1));var scopeRows=new HashMap<ScopeRef,ScopeSnapshot>();versions.forEach((k,v)->scopeRows.put(k,new ScopeSnapshot(k,v,EnumSet.allOf(SensorKind.class),Set.of(),Map.of())));
  var rr=new RegionRecord(new AggregateHeader(1,region.value(),new Revision(1),new Sequence(0),now),region,dim,RegionStatus.ACTIVE,new SupportShape(List.of(),List.of(),List.of()),Set.of(),new UnitValue(.5),Set.of(),Set.of(),new Revision(1),new RegionPersonality(Map.of()),Set.of());
  var cap=new CapabilityId("mutation.plants_v1");var caps=Map.of(cap,new CapabilityDescriptor(cap,1,ProviderMode.TEST_ONLY,CapabilityStatus.AVAILABLE,new ResourceId("proutlost_director:qa_real_group_clients"),Set.of("TEST_ONLY_SYNTHETIC")));
  var context=new ObservationContext(scopeRows,Map.of(),Map.of(),Map.of(region,new RegionSnapshot(rr,Set.of(new TagId("proutlost:qa_real_group_clients_site")),Set.of(),List.of(),Map.of())),Map.of(),Map.of(),List.of(),caps);
  var footprint=new Footprint(dim,Set.of(),Set.of(),List.of());var sample=new SnapshotBatch(new RequestId(UUID.randomUUID()),scopes,now,versions,context,Set.of(),Set.of(),HealthMode.NORMAL);
  var memory=new MemoryView(scopes,versions,List.of(new RegionMemory(rr.header(),region,Map.of(),now,now,Set.of(),Set.of(),new RegionActivityState(Map.of(),Map.of()))),List.of(),List.of(),Map.of(region,rr),Map.of(),Map.of());
  var control=new EngineControlState(1,new Revision(0),now,now,new UnitValue(0),now,Optional.empty(),new UnitValue(.5));
  var state=new DirectorWorldState(new AggregateHeader(1,world.value(),new Revision(1),new Sequence(0),now),world,new Epoch(1),now,RunControl.RUNNING,HealthMode.NORMAL,PacingState.ACTIVE,GlobalTension.CALM,new ResourceId("proutlost_director:qa_real_group_clients"),catalog.hash(),config.provenanceHash(),new Epoch(1),new Sequence(0),control);
  var budget=new BudgetView(new BudgetVector(100,100,100,100,100,100,100,100,100,100),BudgetMath.zero(),Map.of(rs,BudgetMath.zero()));
  var input=new DecisionInput(sample,memory,catalog,config,List.of(),List.of(),42,new DecisionState(state,budget,new OwnershipView(Map.of()),List.of(),List.of(),List.of(),List.of(),List.of(),List.of()),Optional.empty());
  var generated=new CandidateGenerator().generate(input,new DecisionRandom(input.seed())).candidates();check(generated.size()==1,"actual generation from one region without players");var candidate=generated.getFirst();
  check(candidate.completeRoles()&&candidate.roles().roles().values().stream().allMatch(List::isEmpty),"actual zero-role binding complete");
  check(candidate.scopes().scopes().equals(scopes.scopes()),"generated WORLD and REGION public; no evidence scopes removed");
  var eligible=new EligibilityEngine(ProviderMode.TEST_ONLY).validateKnown(candidate,input,1);check(eligible.valid(),"zero-role public event genuinely eligible: "+eligible.reasons());
  var step=def.steps().getFirst();var pb=new PlanningBindings(candidate.id(),candidate.reference(),new EventInstanceId(UUID.randomUUID()),new OperationToken(UUID.randomUUID()),step.id(),candidate.roles(),candidate.scopes(),footprint,Set.of(),true);
  var planning=new PlanningSnapshot(sample,input.state().ownership(),budget,new BlobBundle(List.of()),Optional.of(pb));var blob=BlobContent.capture(new ResourceId("proutlost_director:qa_contract_only"),1,new byte[]{1});
  PlanningPort port=req->{var b=req.snapshot().bindings().orElseThrow();return new PlannedPrimitive(b,new PreparedEffect(new EffectIntent(b.operationToken(),req.step().primitive(),1,req.step().args(),b.footprint(),b.instanceId()),sample,List.of(),new ReadSet(versions,Map.of(),Map.of()),Map.of(),BudgetMath.zero(),List.of(),Set.of(cap),new EffectBlobs(Set.of(),blob.reference(),blob.reference(),Map.of(),Map.of())));};
  var engine=new WorldDirector(ProviderMode.TEST_ONLY,new CandidatePlanner(port,Map.of(candidate.id(),List.of(planning))));var proposal=engine.evaluate(input);
  check(proposal.selectedPlan().isPresent(),"actual J03 selects the zero-role public QA event: "+proposal.candidates().stream().map(c->c.eligible()+":"+c.reasons()).toList());
  check(engine.replay(input,proposal.audit()).equals(proposal),"frozen exact decision input replays");
  var plan=proposal.selectedPlan().orElseThrow();check(plan.scopes().scopes().equals(scopes.scopes())&&plan.roles().roles().values().stream().allMatch(List::isEmpty),"selected public scopes unchanged");
  // Safety regression: public selection never exempts an incidental player.
  check(!new EligibilityEngine(ProviderMode.TEST_ONLY).validateAudience(candidate,Set.of(new PlayerId(UUID.randomUUID())),input).valid(),"unobserved incidental audience remains refused");
  var missingRows=new HashMap<>(scopeRows);var sensors=EnumSet.allOf(SensorKind.class);sensors.remove(SensorKind.REGION_TAGS);
  missingRows.put(rs,new ScopeSnapshot(rs,new Revision(1),sensors,Set.of(SensorKind.REGION_TAGS),Map.of()));
  var missingContext=new ObservationContext(missingRows,context.players(),context.groups(),context.regions(),context.anchors(),context.locations(),context.terrain(),context.capabilities());
  var missingInput=new DecisionInput(new SnapshotBatch(sample.requestId(),scopes,now,versions,missingContext,Set.of(),Set.of(),HealthMode.NORMAL),memory,catalog,config,List.of(),List.of(),42,input.state(),Optional.empty());
  check(!new EligibilityEngine(ProviderMode.TEST_ONLY).validateKnown(candidate,missingInput,1).valid(),"missing regional tag knowledge remains refused");
  check(new ConditionEvaluator().evaluate(new PlayersPresent(0),candidate,input)==ConditionEvaluator.Truth.UNKNOWN,"PlayersPresent retains three-valued unknown without player scopes");
  // A genuinely bound participant still requires PLAYER_ELIGIBILITY even if
  // its position, memory and other sensors are present and current.
  var privateDef=withRoles(def,Map.of("observer",new Role(1,1,false)));var privateCatalog=new CatalogSnapshot(catalog.generation(),BlobContent.hash(new ModelCodec<>(EventDefinition.class).encode(privateDef)),Map.of(privateDef.id(),privateDef),Map.of(),catalog.primitives());
  PlayerId playerId=new PlayerId(UUID.randomUUID());ScopeRef ps=new ScopeRef(ScopeKind.PLAYER,playerId.value());var privateScopes=new ScopeSet(world,Set.of(ws,rs,ps));var privateVersions=new HashMap<>(versions);privateVersions.put(ps,new Revision(1));
  var privateRows=new HashMap<>(scopeRows);var playerSensors=EnumSet.allOf(SensorKind.class);playerSensors.remove(SensorKind.PLAYER_ELIGIBILITY);privateRows.put(ps,new ScopeSnapshot(ps,new Revision(1),playerSensors,Set.of(SensorKind.PLAYER_ELIGIBILITY),Map.of()));
  var player=new PlayerSnapshot(playerId,new Revision(1),now,PlayerEligibility.ELIGIBLE,new PositionSnapshot(new BlockPosition(dim,0,65,0),now),Set.of(region),Set.of(),new DurationTicks(20000),Set.of(),new ClientCapabilitySnapshot(1,Set.of(),Map.of(),true),Set.of());
  var privateContext=new ObservationContext(privateRows,Map.of(playerId,player),Map.of(),Map.of(region,new RegionSnapshot(rr,Set.of(new TagId("proutlost:qa_real_group_clients_site")),Set.of(playerId),List.of(),Map.of())),Map.of(),Map.of(),List.of(),caps);
  var pm=new PlayerOperationalMemory(new AggregateHeader(1,playerId.value(),new Revision(1),new Sequence(0),now),playerId,new DurationTicks(20000),Optional.of(region),Optional.empty(),List.of(),List.of(),Set.of(),new UnitValue(.5),Map.of(),Optional.empty(),new Epoch(0));
  var privateMemory=new MemoryView(privateScopes,privateVersions,memory.regions(),List.of(pm),List.of(),memory.regionRecords(),Map.of(),Map.of());
  var privateWorld=new DirectorWorldState(state.header(),state.worldId(),state.storeGeneration(),state.simTick(),state.runControl(),state.healthMode(),state.pacingState(),state.tension(),state.intensityProfileId(),privateCatalog.hash(),state.configHash(),state.bootEpoch(),state.lastCheckpointSeq(),state.engineControl());
  var privateInput=new DecisionInput(new SnapshotBatch(new RequestId(UUID.randomUUID()),privateScopes,now,privateVersions,privateContext,Set.of(),Set.of(new ChunkRef(dim,0,0)),HealthMode.NORMAL),privateMemory,privateCatalog,config,List.of(),List.of(),42,new DecisionState(privateWorld,budget,input.state().ownership(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of()),Optional.empty());
  var privateReference=new DefinitionRef(privateDef.id(),privateDef.contentRevision(),BlobContent.hash(new ModelCodec<>(EventDefinition.class).encode(privateDef)));
  var bound=new CandidateGenerator.Generated(new CandidateId(UUID.randomUUID()),privateDef,privateReference,Optional.of(region),Optional.empty(),new RoleAssignments(Map.of("observer",List.of(playerId))),privateScopes,true);
  var missingEligibility=new EligibilityEngine(ProviderMode.TEST_ONLY).validateKnown(bound,privateInput,1);
  check(!missingEligibility.valid()&&missingEligibility.reasons().contains(fr.proutlost.director.api.error.ReasonCode.STALE_CONTEXT),"bound participant missing eligibility sensor remains refused");
  var incomplete=new CandidateGenerator.Generated(bound.id(),privateDef,privateReference,Optional.of(region),Optional.empty(),new RoleAssignments(Map.of("observer",List.of())),scopes,false);
  check(new EligibilityEngine(ProviderMode.TEST_ONLY).validateKnown(incomplete,privateInput,1).reasons().contains(fr.proutlost.director.api.error.ReasonCode.NO_TARGET),"mandatory min1 role remains refused when incomplete");
  System.out.println("RealGroupClientsQaCatalogTests checks="+checks+" PASS scope=PURE_CLOSED_FIXTURE_ENGINE_NO_NATIVE_J04_CLIENTS catalog_sha256="+catalog.hash().value());
 }
 private static EventDefinition withRoles(EventDefinition d,Map<String,Role> roles){return new EventDefinition(d.schemaVersion(),d.contentRevision(),d.id(),d.calibration(),d.category(),d.families(),d.enabled(),d.selection(),new Target(d.target().scope(),d.target().regionTags(),d.target().playerPolicy(),roles),d.eligibility(),d.timing(),d.uniqueness(),d.budget(),d.conflicts(),d.safety(),d.steps(),d.recovery(),d.completion());}
}
