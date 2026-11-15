package fr.proutlost.director.testkit;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.permission.CommitPermit;
import fr.proutlost.director.api.permission.PermitResult;
import fr.proutlost.director.api.time.DeterministicRandom;
import fr.proutlost.director.bootstrap.DirectorBootstrap;
import fr.proutlost.director.config.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** TEST_ONLY executable contract fixtures. No Minecraft boot or production effects. */
public final class ContractTests {
 private ContractTests() {}
 private static int assertions;
 private static final UUID ID=UUID.fromString("00000000-0000-0000-0000-000000000001");
 private static final DimensionId DIM=new DimensionId("minecraft:overworld");
 private static final Fingerprint HASH=new Fingerprint("a".repeat(64));
 private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
 private static void rejects(Runnable action){assertions++;try{action.run();}catch(IllegalArgumentException|IllegalStateException|java.util.concurrent.CompletionException expected){return;}throw new AssertionError("expected rejection");}
 private static byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
 private static <T> void round(Class<T> type,T value){ModelCodec<T> codec=new ModelCodec<>(type);check(value.equals(codec.decode(codec.encode(value))),"roundtrip "+type.getSimpleName());}
 public static void main(String[] args)throws Exception {
  Path spec=Path.of(args[0]);assertions+=Amendment101Tests.run(spec);assertions+=ColdRead102Tests.run();json();structuralBounds();primitiveBounds();codecBoundaries();messages(spec);configuration(spec);context();ai(spec);admin();network();permissions();durability(spec);decisionAndCapture();memoryReduction();permitLifecycle();bootstrap();states();clocks();architecture();
  System.out.println("PASS: "+assertions+" assertions; JSON/golden/config231/context/AI/admin/network/permit/fake/state/clock/architecture");
 }
 // Mutate exactly one field of a valid record through both public construction and persisted JSON.
 private static <T> void invalidField(Class<T> type,T valid,String field,String jsonField,Object replacement,String json)throws Exception {
  var components=type.getRecordComponents();var types=new Class<?>[components.length];var values=new Object[components.length];boolean found=false;
  for(int i=0;i<components.length;i++){types[i]=components[i].getType();values[i]=components[i].getAccessor().invoke(valid);if(components[i].getName().equals(field)){values[i]=replacement;found=true;}}
  check(found,"fixture field exists");var constructor=type.getConstructor(types);
  rejects(()->{try{constructor.newInstance(values);}catch(InvocationTargetException e){if(e.getCause() instanceof IllegalArgumentException invalid)throw invalid;throw new AssertionError(e);}catch(ReflectiveOperationException e){throw new AssertionError(e);}});
  var codec=new ModelCodec<>(type);var fields=new HashMap<>(((JsonValue.ObjectValue)CanonicalJsonV1.decode(codec.encode(valid))).values());
  check(fields.containsKey(jsonField),"fixture JSON field exists");fields.put(jsonField,CanonicalJsonV1.decode(bytes(json)));
  rejects(()->codec.decode(CanonicalJsonV1.encode(new JsonValue.ObjectValue(fields))));
 }
 private static void structuralBounds()throws Exception {
  var canonicalZero=new ScoreContribution("signed_zero",-0.0,-0.0,-0.0);
  check(Double.doubleToRawLongBits(canonicalZero.value())==0L && Double.doubleToRawLongBits(canonicalZero.weight())==0L
    && Double.doubleToRawLongBits(canonicalZero.contribution())==0L,"score DTO signed zero canonicalization preserves audit roundtrip");
  round(ScoreContribution.class,canonicalZero);
  var negativeContribution=new ScoreContribution("negative_penalty",.5,-1,-.25);
  check(negativeContribution.value()==.5 && negativeContribution.weight()==-1 && negativeContribution.contribution()==-.25,
    "nonzero scoring inputs and negative contribution remain exact");
  round(ScoreContribution.class,negativeContribution);
  var a=new ResourceId("fixture:a");var b=new ResourceId("fixture:b");
  var choice=new SharedChoice(List.of("witness"),new TagId("fixture:anchor"),List.of(a,b),TimeoutPolicy.KEEP_UNCHANGED);
  round(SharedChoice.class,choice);round(PrimitiveArgs.class,choice);
  invalidField(SharedChoice.class,choice,"roles","roles",List.of(),"[]");
  invalidField(SharedChoice.class,choice,"roles","roles",List.of("witness","witness"),"[\"witness\",\"witness\"]");
  invalidField(SharedChoice.class,choice,"choiceIds","choice_ids",List.of(),"[]");
  invalidField(SharedChoice.class,choice,"choiceIds","choice_ids",List.of(a),"[\"fixture:a\"]");
  invalidField(SharedChoice.class,choice,"choiceIds","choice_ids",List.of(a,a),"[\"fixture:a\",\"fixture:a\"]");
  var step=new EventStep("choice",List.of("warn"),new DurationTicks(0),new PrimitiveId("proutlost:shared_choice"),choice,OnFailure.ABORT_SAFE);
  round(EventStep.class,step);
  invalidField(EventStep.class,step,"after","after",List.of("warn","warn"),"[\"warn\",\"warn\"]");
  for(String id:List.of("","Bad","bad-id"))invalidField(EventStep.class,step,"id","id",id,"\""+id+"\"");
  var transition=new ChainTransition(new ResourceId("fixture:observe"),"WAIT","DONE",new FactType("fixture:observed"),Optional.of(new DefinitionId("fixture:event")),new PlayersPresent(1),true,Optional.empty());
  var chain=new ChainDefinition(1,new DefinitionId("fixture:chain"),new Revision(1),Calibration.DEFAULT_FOR_TESTING,ChainMode.SHORT_ARC,ScopeKind.REGION,"WAIT",Set.of("WAIT","DONE"),2,new DurationTicks(20),new UnitValue(.5),List.of(transition),Set.of("DONE"),RecoveryMode.RECOVER);
  round(ChainDefinition.class,chain);
  invalidField(ChainDefinition.class,chain,"contentRevision","content_revision",new Revision(0),"\"0\"");
  invalidField(ChainDefinition.class,chain,"transitions","transitions",List.of(),"[]");
  invalidField(ChainDefinition.class,chain,"terminalStates","terminal_states",Set.of(),"[]");
  for(var scope:List.of(ScopeKind.PLAYER,ScopeKind.LOCATION,ScopeKind.EVENT))invalidField(ChainDefinition.class,chain,"scope","scope",scope,"\""+scope+"\"");
  for(var recovery:List.of(RecoveryMode.CLEANUP,RecoveryMode.COMPLETE_FROM_STATE))invalidField(ChainDefinition.class,chain,"recovery","recovery",recovery,"\""+recovery+"\"");
  var approvedFields=new HashMap<>(((JsonValue.ObjectValue)CanonicalJsonV1.decode(new ModelCodec<>(ChainDefinition.class).encode(chain))).values());
  approvedFields.put("calibration",new JsonValue.Text("APPROVED"));
  var approvedChain=new ModelCodec<>(ChainDefinition.class).decode(CanonicalJsonV1.encode(new JsonValue.ObjectValue(approvedFields)));
  check(approvedChain.calibration()==Calibration.APPROVED,"approved chain codec");
  round(ChainDefinition.class,approvedChain);
  invalidField(ChainDefinition.class,chain,"states","states",Set.of(),"[]");
  invalidField(ChainDefinition.class,chain,"schemaVersion","schema_version",0,"0");
  invalidField(ChainDefinition.class,chain,"planningHorizon","planning_horizon",-1,"-1");
  var location=new ImportedLocation(new LocationId(ID),"external",Set.of(),DIM,new ImportBounds(0,0,0,1,1,1),Set.of());
  round(ImportedLocation.class,location);invalidField(ImportedLocation.class,location,"externalId","external_id","","\"\"");
 }
 private static void primitiveBounds(){
  var asset=new ResourceId("fixture:asset");
  for(double negative:List.of(-1.0,-Double.MIN_VALUE)){
   rejects(()->new TargetSound("target",asset,negative,0,0));
   rejects(()->new TargetSound("target",asset,0,negative,0));
   rejects(()->new TargetParticles("target",asset,0,negative));
   rejects(()->new Apparition("target",asset,new DurationTicks(0),negative));
  }
  var sound=new TargetSound("target",asset,0,0,0);var particles=new TargetParticles("target",asset,0,0);
  round(TargetSound.class,sound);round(TargetParticles.class,particles);round(PrimitiveArgs.class,sound);round(PrimitiveArgs.class,particles);
  var apparition=new Apparition("target",asset,new DurationTicks(0),0);
  round(Apparition.class,apparition);round(PrimitiveArgs.class,apparition);
  for(String invalid:List.of("-1","-0.000001")){
   String soundJson="{\"role\":\"target\",\"sound\":\"fixture:asset\",\"volume\":0,\"radius_blocks\":0,\"repeats\":0}";
   String particlesJson="{\"role\":\"target\",\"particle\":\"fixture:asset\",\"count\":0,\"radius_blocks\":0}";
   for(String field:List.of("volume","radius_blocks")){
    String bad=soundJson.replace("\""+field+"\":0","\""+field+"\":"+invalid);
    rejects(()->new ModelCodec<>(TargetSound.class).decode(bytes(bad)));
   }
   String apparitionJson=new String(new ModelCodec<>(Apparition.class).encode(apparition),StandardCharsets.UTF_8);
   String invalidApparition=apparitionJson.replace("\"max_distance_blocks\":0","\"max_distance_blocks\":"+invalid);
   check(!invalidApparition.equals(apparitionJson),"apparition distance fixture modified");
   rejects(()->new ModelCodec<>(Apparition.class).decode(bytes(invalidApparition)));
   String bad=particlesJson.replace("\"radius_blocks\":0","\"radius_blocks\":"+invalid);
   rejects(()->new ModelCodec<>(TargetParticles.class).decode(bytes(bad)));
  }
 }
 private static void json(){
  String golden="{\"a\":\"é\\n\",\"n\":1.25,\"z\":[true,null]}";
  check(Arrays.equals(CanonicalJsonV1.encode(CanonicalJsonV1.decode(bytes("{\"z\":[true,null],\"n\":1.2500,\"a\":\"é\\n\"}"))),bytes(golden)),"canonical JSON golden");
  for(String bad:List.of("{\"x\":1,\"x\":2}","{\"x\":1,\"\\u0078\":2}","[] []","01","-01","1.","1e","NaN","1e99999999","[1,]","{\"x\":true,}","\"\\ud800\"","\"\n\"","[".repeat(34)+"0"+"]".repeat(34)))rejects(()->CanonicalJsonV1.decode(bytes(bad)));
  rejects(()->CanonicalJsonV1.decode(new byte[]{(byte)0xc0,(byte)0xaf}));rejects(()->CanonicalJsonV1.decode(new byte[CanonicalJsonV1.MAX_BYTES+1]));
  rejects(()->CanonicalJsonV1.encode(new JsonValue.Array(Collections.nCopies(65536,new JsonValue.Bool(true)))));
  check(new String(new ModelCodec<>(Tick.class).encode(new Tick(Long.MAX_VALUE)),StandardCharsets.UTF_8).equals("\"9223372036854775807\""),"tick golden");
  rejects(()->new ModelCodec<>(Tick.class).decode(bytes("\"9223372036854775808\"")));
  rejects(()->new ModelCodec<>(Tick.class).decode(bytes("2")));
  rejects(()->new ModelCodec<>(WorldId.class).decode(bytes("\"1-1-1-1-1\"")));
  rejects(()->new ModelCodec<>(BudgetVector.class).decode(bytes("{\"unknown\":0}")));
  rejects(()->new ModelCodec<>(PrimitiveArgs.class).decode(bytes("{\"type\":\"java.lang.Runtime\",\"value\":{}}")));
  rejects(()->new ResourceId("a:../escape"));rejects(()->new UnitValue(Double.NaN));rejects(()->new Tick(-1));rejects(()->new ModelCodec<>(CommitPermit.class));
  round(BudgetVector.class,BudgetMath.zero());round(Condition.class,new AllConditions(List.of(new PlayersPresent(1),new SoloReady(new DurationTicks(100)))));
  round(PrimitiveArgs.class,new TargetSound("target",new ResourceId("minecraft:entity.goat.ambient"),1,16,1));
  var mutable=new ArrayList<PlayerId>();mutable.add(new PlayerId(ID));var role=new RoleAssignments(Map.of("target",mutable));mutable.clear();check(role.roles().get("target").size()==1,"deep role snapshot");
 }
 private static void messages(Path spec)throws Exception {
  byte[] fixture=Files.readAllBytes(spec.resolve("examples/messages/require.json"));
  var envelope=DirectorMessageCodec.decode(fixture);
  check(envelope.payload() instanceof RequestMessage r && r.value() instanceof DirectorRequirement,"normative REQUIRE fixture");
  check(Arrays.equals(DirectorMessageCodec.encode(envelope),CanonicalJsonV1.encode(CanonicalJsonV1.decode(fixture))),"normative REQUIRE golden canonical bytes");
  rejects(()->DirectorMessageCodec.decode(bytes(new String(fixture,StandardCharsets.UTF_8).replace("NEW_EXECUTION","BYPASS"))));
  byte[] location=Files.readAllBytes(spec.resolve("examples/locations/import.json"));
  var imported=new ModelCodec<>(LocationImport.class).decode(location);
  check(Arrays.equals(new ModelCodec<>(LocationImport.class).encode(imported),CanonicalJsonV1.encode(CanonicalJsonV1.decode(location))),"normative location import golden");
  byte[] ai=Files.readAllBytes(spec.resolve("examples/ai/proposal.json"));
  var proposal=new ModelCodec<>(AiProposal.class).decode(ai);
  check(Arrays.equals(new ModelCodec<>(AiProposal.class).encode(proposal),CanonicalJsonV1.encode(CanonicalJsonV1.decode(ai))),"normative AI proposal golden");
 }
 private static void configuration(Path spec)throws Exception {
  byte[] profile=Files.readAllBytes(spec.resolve("DEFAULT_FOR_TESTING.json"));var config=TestingConfig.load(profile);check(ConfigRegistry.KEYS.size()==231,"231 keys");
  for(var key:ConfigRegistry.KEYS.values())check(config.get(key).doubleValue()==key.testingDefault().doubleValue(),"typed calibration "+key.name());
  check(config.view().calibration()==Calibration.DEFAULT_FOR_TESTING,"test provenance");round(ConfigView.class,config.view());
  rejects(()->TestingConfig.load(bytes(new String(profile,StandardCharsets.UTF_8).replace("\"production_approved\": false","\"production_approved\": true"))));
  rejects(()->config.get(new ConfigKey<>("invented",Long.class,java.math.BigDecimal.ZERO)));
 }
 static SnapshotBatch context(){
  RegionId regionId=new RegionId(ID);PlayerId playerId=new PlayerId(ID);AnchorId anchorId=new AnchorId(ID);LocationId locationId=new LocationId(ID);
  ScopeRef scope=new ScopeRef(ScopeKind.REGION,ID);var scopes=new ScopeSet(new WorldId(ID),Set.of(scope));var rev=new Revision(1);var tick=new Tick(100);
  var header=new AggregateHeader(1,ID,rev,new Sequence(1),tick);var position=new BlockPosition(DIM,100,64,-50);
  var box=new Aabb(DIM,96,60,-64,112,80,-48);var tag=new TagId("director:camp");
  var region=new RegionRecord(header,regionId,DIM,RegionStatus.values()[0],new SupportShape(List.of(box),List.of(),List.of()),Set.of(anchorId),new UnitValue(.8),Set.of(),Set.of(),rev,new RegionPersonality(Map.of(RegionTrait.values()[0],new UnitValue(.5))),Set.of());
  var anchor=new AnchorRecord(header,anchorId,Optional.of(locationId),Set.of(regionId),DIM,position,"camp",Set.of(tag),AnchorOrigin.values()[0],EvidenceStatus.CONFIRMED,true);
  var location=new LocationRecord(header,locationId,new ResourceId("fixture:source"),"camp",DIM,box,Set.of(tag),Set.of(anchorId),rev,true);
  var client=new ClientCapabilitySnapshot(1,Set.of(new ResourceId("director:apparition")),Map.of(new ResourceId("director:goat"),HASH),true);
  var player=new PlayerSnapshot(playerId,rev,tick,PlayerEligibility.ELIGIBLE,new PositionSnapshot(position,tick),Set.of(regionId),Set.of(ID),new DurationTicks(400),Set.of(ObservableReaction.EXAMINED_TRACE),client,Set.of(tag));
  var camp=new CampSnapshot(anchorId,4,new DurationTicks(600),Map.of("north",3L),Map.of("night",4L),2,new UnitValue(.9));
  var footprint=new Footprint(DIM,Set.of(new ChunkRef(DIM,6,-4)),Set.of(position),List.of(box));
  var terrain=new TerrainSnapshot(footprint,Map.of(position,new TerrainCellSnapshot(position,HASH,new ResourceId("minecraft:stone"),Set.of(),Set.of(TerrainHazard.UNSTABLE_SLOPE),false,true,new Epoch(1),new Epoch(0))),List.of(),Map.of(new ResourceId("director:landslide"),new UnitValue(.7)),rev);
  var rich=new ObservationContext(Map.of(scope,new ScopeSnapshot(scope,rev,Set.of(SensorKind.PLAYER_PRESENCE,SensorKind.CAMP_ACTIVITY),Set.of(),Map.of(Feature.contextCompatibility,new UnitValue(.9)))),Map.of(playerId,player),Map.of(ID,new GroupSnapshot(ID,Set.of(playerId),Set.of(regionId),tick,rev)),Map.of(regionId,new RegionSnapshot(region,Set.of(tag),Set.of(playerId),List.of(camp),terrain.risks())),Map.of(anchorId,anchor),Map.of(locationId,location),List.of(terrain),Map.of());
  var snapshot=new SnapshotBatch(new RequestId(ID),scopes,tick,Map.of(scope,rev),rich,Set.of(),footprint.loadedChunks(),HealthMode.NORMAL);
  var memory=new MemoryView(scopes,Map.of(scope,rev),List.of(new RegionMemory(header,regionId,Map.of(),tick,tick,Set.of(),Set.of(),new RegionActivityState(Map.of(),Map.of()))),List.of(),List.of(),Map.of(regionId,region),Map.of(anchorId,anchor),Map.of(locationId,location));
  var input=new DecisionInput(snapshot,memory,new CatalogSnapshot(new Epoch(1),HASH,Map.of(),Map.of(),Map.of()),new ConfigView(rev,HASH,Calibration.DEFAULT_FOR_TESTING,Map.of()),List.of(),List.of(),-42,decisionState(),Optional.empty());
  round(DecisionInput.class,input);check(input.context().context().players().get(playerId).isolatedTicks().value()==400,"solo observation");check(input.memory().anchors().containsKey(anchorId)&&rich.regions().get(regionId).camps().getFirst().independentVisits()==4,"camp memory");check(rich.groups().get(ID).members().contains(playerId)&&rich.terrain().getFirst().risks().size()==1&&player.clientCapabilities().connected(),"group terrain capabilities");var observation=new TestObservationPort(snapshot);check(observation.sample(new SampleRequest(snapshot.requestId(),scopes,Set.of(SensorKind.CAMP_ACTIVITY),BudgetMath.zero())).equals(snapshot),"observation port fixture");return snapshot;
 }
 private static void ai(Path spec)throws Exception {
  CandidateId candidate=new CandidateId(UUID.fromString("10000000-0000-0000-0000-000000000001"));RequestId request=new RequestId(UUID.fromString("20000000-0000-0000-0000-000000000001"));
  var c=new AiCandidate(candidate,new DefinitionId("director:goat"),Category.AMBIENT,Set.of(Family.HUMOUR),Map.of(Feature.novelty,new UnitValue(.8)),.7,Map.of("client",new UnitValue(.1)),Set.of(new Pseudonym("p_1234567890abcdef")),Set.of(new TagId("director:camp")));
  var chain=new ChainId(UUID.fromString("30000000-0000-0000-0000-000000000001"));var transition=new ResourceId("director:camp_to_trace");
  var input=new AiInput(1,request,new Pseudonym("p_abcdef1234567890"),new DecisionId(UUID.fromString("40000000-0000-0000-0000-000000000001")),HASH,HASH,HASH,500,List.of(c),List.of(new AiAllowedTransition(chain,transition,"camp","trace",BudgetMath.zero(),Set.of())),2,new AiOutputLimits(8192,8,2,2,.2,BudgetMath.zero()));
  round(AiInput.class,input);String serialized=new String(new ModelCodec<>(AiInput.class).encode(input),StandardCharsets.UTF_8);
  for(String forbidden:List.of(ID.toString(),"player_id","world_id","position","inventory","role_assignments","100,64"))check(!serialized.contains(forbidden),"AI privacy "+forbidden);
  var proposal=new AiProposal(1,request,HASH,List.of(new AiAdjustment(candidate,.1)),List.of(new AiArcProposal(chain,List.of(transition))),Set.of(AiRationale.CHAIN_CONTINUITY));byte[] response=new ModelCodec<>(AiProposal.class).encode(proposal);
  check(AiProposalContract.decode(response,input,499,true,HASH).equals(proposal),"full AI response");
  rejects(()->AiProposalContract.decode(response,input,500,true,HASH));rejects(()->AiProposalContract.decode(response,input,499,false,HASH));rejects(()->AiProposalContract.decode(response,input,499,true,new Fingerprint("b".repeat(64))));
  rejects(()->AiProposalContract.decode(bytes(new String(response,StandardCharsets.UTF_8).replace(request.value().toString(),ID.toString())),input,499,true,HASH));
  rejects(()->AiProposalContract.decode(bytes(new String(response,StandardCharsets.UTF_8).replace("0.1","0.3")),input,499,true,HASH));
  rejects(()->AiProposalContract.decode(bytes(new String(response,StandardCharsets.UTF_8).replace(candidate.value().toString(),ID.toString())),input,499,true,HASH));
  var accepted=new AiAcceptedProposal(proposal,HASH,HASH,HASH,input.decisionId(),new ResourceId("fixture:advisor"),"test",499);round(AiProposalResult.class,new AiProposalResult(AiStatus.VALID_PROPOSAL,Optional.of(accepted),Optional.empty()));
  for(Path file:Files.list(spec.resolve("examples")).toList())if(file.getFileName().toString().contains("ai")&&file.toString().endsWith(".json")){var parsed=CanonicalJsonV1.decode(Files.readAllBytes(file));check(parsed!=null,"normative AI JSON");}
 }
 private static void admin(){
  var session=new SessionId(ID);var request=new RequestId(ID);var rev=new Revision(1);
  var trigger=new AdminCommand(session,request,rev,AdminOperation.TRIGGER,new TriggerPayload(new DefinitionId("director:goat"),new RegionId(ID),Set.of(new PlayerId(ID)),Set.of(new PlayerId(ID))));round(AdminCommand.class,trigger);
  rejects(()->new AdminCommand(session,request,rev,AdminOperation.PAUSE,trigger.payload()));
  rejects(()->new AdminCommand(session,request,rev,AdminOperation.RESUME,new ControlPayload(RunControl.PAUSED)));
  check(!AdminContracts.authorized(trigger,new ActorIdentity(session,ActorKind.PLAYER,Optional.of(new PlayerId(ID)),Optional.empty(),0,true)),"client cannot grant admin");
  check(!AdminContracts.authorized(trigger,new ActorIdentity(session,ActorKind.SERVER,Optional.empty(),Optional.empty(),4,false)),"untrusted identity");
  check(AdminContracts.authorized(trigger,new ActorIdentity(session,ActorKind.SERVER,Optional.empty(),Optional.empty(),4,true)),"server operator");
  round(AdminPayload.class,new TensionPayload(GlobalTension.TENSE,new DurationTicks(200),new Tick(300)));
  round(AdminPayload.class,new ReplayPayload(new DecisionId(ID),true));round(AdminPayload.class,new AuditExportPayload(new DecisionId(ID)));round(AdminPayload.class,new StabilizePayload(new DisasterId(ID)));
  round(AdminPayload.class,new SimulationPayload(new ResourceId("fixture:camp"),context(),true));
  round(AdminPayload.class,new RecordObservationPayload(Optional.of(new EffectId(ID)),Optional.empty(),Optional.of("vu près du feu")));
  rejects(()->new RecordObservationPayload(Optional.empty(),Optional.empty(),Optional.empty()));rejects(()->new ReportPayload("x".repeat(1001),Set.of()));
 }
 private static void network(){
  var h=new PerceptionHeader(1,new WorldId(ID),new Epoch(2),new Epoch(3),DIM,new EventInstanceId(ID),new EffectId(ID),new Sequence(127),new DurationTicks(128));
  byte[] encoded=PerceptionHeaderCodec.encode(h);check(PerceptionHeaderCodec.decode(encoded).equals(h),"binary header roundtrip");
  String golden="01"+"00000000000000000000000000000001"+"020313"+"6d696e6563726166743a6f766572776f726c64"+"00000000000000000000000000000001".repeat(2)+"7f8001";
  check(HexFormat.of().formatHex(encoded).equals(golden),"binary UUID/VarLong golden");
  rejects(()->PerceptionHeaderCodec.decode(Arrays.copyOf(encoded,encoded.length-1)));rejects(()->PerceptionHeaderCodec.decode(Arrays.copyOf(encoded,encoded.length+1)));
  byte[] overlong=new byte[encoded.length+1];overlong[0]=(byte)0x81;System.arraycopy(encoded,0,overlong,1,encoded.length);overlong[1]=0;rejects(()->PerceptionHeaderCodec.decode(overlong));
  var reset=new SessionReset(1,new WorldId(ID),new Epoch(2),new Epoch(3),Set.of(PerceptionKind.SOUND),Map.of());check(NetworkContracts.current(h,reset,DIM),"session match");check(!NetworkContracts.current(h,new SessionReset(1,new WorldId(ID),new Epoch(3),new Epoch(3),Set.of(),Map.of()),DIM),"old boot rejected");
  round(PerceptionUpsert.class,new PerceptionUpsert(h,PerceptionKind.SOUND,new PerceptionTransform(0,64,0,0,0,1),new ResourceId("director:goat"),HASH,new SoundParameters(1,1,16)));
  round(PerceptionStop.class,new PerceptionStop(h,true));round(PerceptionReset.class,new PerceptionReset(h,PerceptionResetScope.INSTANCE));round(PerceptionAck.class,new PerceptionAck(h,PerceptionAckStatus.RECEIVED));round(AnchorInteraction.class,new AnchorInteraction(h,new AnchorId(ID),new ResourceId("director:inspect")));
 }
 private record PermitFixture(TestDurableStore store,CommitPermit.Authority authority,DurableBatch batch,PreparedSlice slice,LiveValidationSnapshot live) {}
 private static BlobContent fixtureBlob(){return BlobContent.capture(new ResourceId("fixture:canonical_plan"),1,bytes("{}"));}
 private static EffectBlobs fixtureBlobs(){BlobRef ref=fixtureBlob().reference();return new EffectBlobs(Set.of(ref),ref,ref,Map.of(),Map.of());}
 private static PermitFixture permit(){
  var store=new TestDurableStore();var authority=new CommitPermit.Authority(store,4);var read=new ReadSet(Map.of(),Map.of(),Map.of());var footprint=new Footprint(DIM,Set.of(new ChunkRef(DIM,0,0)),Set.of(),List.of());var op=new OperationToken(ID);var slice=new PreparedSlice(new SliceId(ID),op,footprint,read,List.of(),BudgetMath.zero());var prepare=new PrepareRecord(new TransactionId(ID),op,new Epoch(1),read,new Tick(200),slice,new ReservationToken(new ReservationId(ID),new WorldId(ID),new EventInstanceId(ID),new Epoch(1),new Tick(200),List.of()),new ValidationResult(true,List.of(),read),List.of(),Set.of(),List.of(),fixtureBlobs());var batch=new DurableBatch(prepare.transactionId(),new Epoch(7),Map.of(),List.of(new DurablePrepare(prepare)),List.of(fixtureBlob()),Optional.empty(),CommitPermit.Authority.prepareHash(prepare));var live=new LiveValidationSnapshot(read,footprint.loadedChunks(),HealthMode.NORMAL,new Tick(100),new Epoch(1));return new PermitFixture(store,authority,batch,slice,live);
 }
 private static CommitAck ack(PermitFixture f,AckStatus status,Fingerprint hash){return new CommitAck(f.batch().transactionId(),f.batch().expectedStoreGeneration(),new Sequence(5),hash,status,f.store().submitted(f.batch().transactionId()).permitAttempt(),CommitPermit.Authority.references(fixtureBlobs()));}
 private static void permissions()throws Exception {
  check(Arrays.stream(CommitPermit.class.getDeclaredConstructors()).allMatch(c->Modifier.isPrivate(c.getModifiers())),"permit private constructors");
  var f=permit();f.authority().submit(f.batch(),f.live());check(f.authority().poll(f.batch().transactionId(),f.live()).status()==PermitResult.Status.PENDING,"no permit before ACK");
  AtomicReference<Throwable> foreign=new AtomicReference<>();Thread thread=new Thread(()->{try{f.authority().poll(f.batch().transactionId(),f.live());}catch(IllegalStateException e){foreign.set(e);}f.store().force(f.batch().transactionId());});thread.start();thread.join();check(foreign.get()!=null,"callback thread cannot issue");
  var permit=f.authority().poll(f.batch().transactionId(),f.live()).permit().orElseThrow();check(permit.prepareSequence().value()==1,"ack sequence");permit.consume(f.authority(),f.slice(),f.live());rejects(()->permit.consume(f.authority(),f.slice(),f.live()));f.authority().submit(f.batch(),f.live());check(f.authority().poll(f.batch().transactionId(),f.live()).status()==PermitResult.Status.REJECTED,"reused original ACK cannot reissue");
  for(AckStatus status:List.of(AckStatus.REJECTED,AckStatus.UNAVAILABLE)){var x=permit();x.authority().submit(x.batch(),x.live());x.store().acknowledge(ack(x,status,x.batch().transactionHash()));check(x.authority().poll(x.batch().transactionId(),x.live()).status()==PermitResult.Status.REJECTED,"non durable ACK rejected");}
  var wrong=permit();wrong.authority().submit(wrong.batch(),wrong.live());wrong.store().acknowledge(ack(wrong,AckStatus.DURABLE,HASH));check(wrong.authority().poll(wrong.batch().transactionId(),wrong.live()).status()==PermitResult.Status.REJECTED,"wrong ACK rejected");
  var failed=permit();failed.authority().submit(failed.batch(),failed.live());failed.store().fail(failed.batch().transactionId());check(failed.authority().poll(failed.batch().transactionId(),failed.live()).status()==PermitResult.Status.REJECTED,"failed ACK rejected");
  var stale=permit();stale.authority().submit(stale.batch(),stale.live());stale.store().acknowledge(ack(stale,AckStatus.DURABLE,stale.batch().transactionHash()));check(stale.authority().poll(stale.batch().transactionId(),new LiveValidationSnapshot(stale.live().readSet(),Set.of(),HealthMode.NORMAL,new Tick(100),new Epoch(1))).status()==PermitResult.Status.REJECTED,"stale prestate");
  var expired=permit();expired.authority().submit(expired.batch(),expired.live());expired.store().acknowledge(ack(expired,AckStatus.DURABLE,expired.batch().transactionHash()));check(expired.authority().poll(expired.batch().transactionId(),new LiveValidationSnapshot(expired.live().readSet(),expired.live().loadedChunks(),HealthMode.NORMAL,new Tick(200),new Epoch(1))).status()==PermitResult.Status.REJECTED,"expired prepare");
  var fault=permit();fault.authority().submit(fault.batch(),fault.live());fault.store().acknowledge(ack(fault,AckStatus.DURABLE,fault.batch().transactionHash()));
  var authorized=fault.authority().poll(fault.batch().transactionId(),fault.live()).permit().orElseThrow();
  var effects=new TestWorldEffectPort(fault.authority(),fault.live(),new SliceResult(Outcome.values()[0],Set.of(),Map.of(),0,Set.of(),Set.of(),List.of()),point->{throw new IllegalStateException("TEST_ONLY first slice crash");});
  rejects(()->effects.apply(fault.slice(),authorized));rejects(()->effects.apply(fault.slice(),authorized));
  round(DurableBatch.class,f.batch());
 }

 // Reflective fixture construction is test-only; every aggregate family is enumerated by the sealed API.
 private static Object sample(Type type)throws Exception {
  if(type instanceof ParameterizedType p){Class<?> raw=(Class<?>)p.getRawType();if(raw==Optional.class)return Optional.empty();if(raw==List.class)return List.of();if(raw==Set.class)return Set.of();if(raw==Map.class)return Map.of();}
  Class<?> c=(Class<?>)type;
  if(c==String.class)return "fixture:value";if(c==UUID.class)return ID;if(c==long.class)return 1L;if(c==int.class)return 1;if(c==double.class)return .5;if(c==boolean.class)return true;
  if(c==RequestSchedulingPolicy.class)return RequestSchedulingPolicy.legacyWire();
  if(c==AggregateHeader.class)return new AggregateHeader(1,ID,new Revision(37),new Sequence(41),new Tick(9001));
  if(c==Fingerprint.class)return HASH;if(c==BlobRef.class)return fixtureBlob().reference();
  if(c==AnyOfEvents.class)return new AnyOfEvents(Set.of(new DefinitionId("fixture:event")));
  if(c==Aabb.class)return new Aabb(DIM,0,0,0,1,1,1);
  if(c.isEnum())return c.getEnumConstants()[0];
  if(c.isSealed())return sample(c.getPermittedSubclasses()[0]);
  if(c.isRecord()){var fields=c.getRecordComponents();Object[] args=new Object[fields.length];Class<?>[] types=new Class<?>[fields.length];for(int i=0;i<fields.length;i++){args[i]=sample(fields[i].getGenericType());types[i]=fields[i].getType();}try{return c.getConstructor(types).newInstance(args);}catch(InvocationTargetException e){throw new IllegalStateException("fixture "+c.getSimpleName(),e.getCause());}}
  throw new IllegalStateException("fixture unsupported "+type);
 }
 private static void durability(Path spec)throws Exception {
  var restored=new ArrayList<RestoredAggregate>();
  for(Class<?> family:AggregateState.class.getPermittedSubclasses()){
   var state=(AggregateState)sample(family);round(DurableRecord.class,new DurableAggregate(state));
   restored.add(new RestoredAggregate(state,new Sequence(42),HASH));check(state.header().revision().value()==37&&state.header().lastSequence().value()==41&&state.header().updatedTick().value()==9001,"aggregate metadata "+family);
  }
  round(StoreView.class,new StoreView(List.of(),restored,new Sequence(42),false,Optional.empty()));
  byte[] original=bytes("abc");var blob=BlobContent.capture(new ResourceId("fixture:binary"),1,original);original[0]=0;
  check(blob.reference().hash().value().equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),"blob SHA golden");
  byte[] copy=blob.bytes();copy[0]=0;check(Arrays.equals(blob.bytes(),bytes("abc")),"blob defensive copies");
  byte[] wire=BlobCodec.encode(blob);check(BlobCodec.decode(wire).equals(blob),"binary blob roundtrip");round(BlobContent.class,blob);
  String ref=new String(new ModelCodec<>(BlobRef.class).encode(blob.reference()),StandardCharsets.UTF_8);
  byte[] golden=java.nio.ByteBuffer.allocate(8+bytes(ref).length+3).putInt(0x57444231).putInt(bytes(ref).length).put(bytes(ref)).put(bytes("abc")).array();check(Arrays.equals(wire,golden),"WDB1 golden frame");
  wire[wire.length-1]^=1;rejects(()->BlobCodec.decode(wire));rejects(()->new BlobContent(blob.reference(),bytes("abd")));rejects(()->BlobCodec.decode(Arrays.copyOf(golden,golden.length-1)));
  rejects(()->BlobContent.capture(new ResourceId("fixture:binary"),1,new byte[BlobContent.MAX_BYTES+1]));
  var large=BlobContent.capture(new ResourceId("fixture:binary"),1,new byte[70000]);check(large.equals(BlobCodec.decode(BlobCodec.encode(large))),"binary blob beyond JSON string bound");
  var f=permit();f.authority().submit(f.batch(),f.live());f.store().force(f.batch().transactionId());f.store().reboot();
  var read=f.store().readBlobs(new BlobReadRequest(new WorldId(ID),new Epoch(7),Set.of(fixtureBlob().reference()))).toCompletableFuture().getNow(null);
  check(read!=null&&read.contents().contains(fixtureBlob()),"asynchronous reboot blob restore");
  var required=DirectorMessageCodec.decode(Files.readAllBytes(spec.resolve("examples/messages/require.json")));
  for(RequestPayload payload:List.of(((RequestMessage)required.payload()).value(),new DirectorPreference(new RequestId(ID),((DirectorRequirement)((RequestMessage)required.payload()).value()).selector(),.35,new Tick(500)))){
   var envelope=new DirectorEnvelope(1,required.messageId(),required.worldId(),required.producerId(),required.producerKey(),required.scope(),required.correlationId(),required.causationId(),new Sequence(27),Optional.of(new Sequence(42)),new Tick(100),PayloadType.DirectorRequest,1,new RequestMessage(payload));
   var request=MessageContracts.request(envelope);var requirement=(DirectorRequirement)((RequestMessage)required.payload()).value();
   var state=new DirectorRequestState(new AggregateHeader(1,ID,new Revision(3),new Sequence(42),new Tick(100)),RequestContracts.id(payload),request.producerId(),request.producerKey(),payload instanceof DirectorPreference?RequestKind.PREFER:RequestKind.REQUIRE,requirement.selector(),request.scope(),requirement.targetConstraints(),requirement.fulfillmentMode(),RequestStatus.WAITING_SAFETY,new DurationTicks(30),payload instanceof DirectorPreference?Optional.of(new Tick(500)):Optional.empty(),Set.of(),List.of());
   var record=new DurableRequest(envelope,new RequestView(request,state,new Revision(3)),RequestContracts.idempotenceHash(request));round(DurableRecord.class,record);check(record.original().producerSequence().value()==27,"request producer sequence");rejects(()->new DurableRequest(envelope,record.request(),HASH));
  }
 }
 private static void memoryReduction()throws Exception {
  var snapshot=context();var anchor=snapshot.context().anchors().values().iterator().next();var region=snapshot.context().regions().values().iterator().next().region();
  var window=new EvidenceWindow(new EvidenceWindowId(ID),new Tick(10),new Tick(20),Set.of(new EpisodeId(ID)));
  var histogram=new ActivityHistogram(Map.of(Sector.N,4L),Map.of(DistanceBand.INNER,3L),Map.of(DayPhase.NIGHT,4L),Map.of(GroupSizeBucket.SOLO,2L),Optional.of(new ActivityTimeCounts(12,java.util.stream.LongStream.rangeClosed(1,12).boxed().toList())));
  var camp=new CampRoutineMemory(anchor.anchorId(),List.of(new CampWindowContribution(window,4,new DurationTicks(600),histogram)),4,new DurationTicks(600),histogram,new UnitValue(.8));
  var route=new RouteKey(region.regionId(),new RegionId(new UUID(0,2)));
  var activity=new RegionActivityState(Map.of(anchor.anchorId(),camp),Map.of(route,new RouteTransitionMemory(route,3,Map.of(Sector.N,3L),List.of(new RouteWindowContribution(window,3,Map.of(Sector.N,3L))))));
  var memory=new RegionMemory(region.header(),region.regionId(),Map.of(),new Tick(100),new Tick(100),Set.of(),Set.of(),activity);
  var exposure=new Exposure(new DefinitionId("director:goat"),Family.HUMOUR,"target",new Tick(100),new UnitValue(.5));
  var player=new PlayerOperationalMemory(region.header(),new PlayerId(ID),new DurationTicks(500),Optional.of(region.regionId()),Optional.of(new Tick(100)),List.of(exposure),List.of(exposure),Set.of(),new UnitValue(.7),Map.of(),Optional.empty(),new Epoch(1));
  var significant=new PlayerSignificantMemory(region.header(),new MemoryId(ID),new PlayerId(ID),new EventInstanceId(ID),exposure.definitionId(),"target",PerceptionStatus.values()[0],fixtureBlob().reference(),Optional.of(region.regionId()),anchor.locationId(),Set.of(new FactId(ID)),Set.of(ObservableReaction.EXAMINED_TRACE),new Tick(100));
  List<AggregateState> states=List.of(new RegionAggregate(region),new AnchorAggregate(anchor),new LocationAggregate(snapshot.context().locations().values().iterator().next()),new RegionMemoryAggregate(memory),new PlayerOperationalAggregate(player),new PlayerSignificantAggregate(significant));
  var reduction=new Reduction(states.stream().map(x->new AggregateChange(x.key(),Optional.empty(),x)).toList(),List.of(),List.of(),List.of(),List.of(new ObservationId(ID)));
  round(Reduction.class,reduction);check(reduction.aggregateChanges().size()==6,"single pure reduction transports camp region fairness significant memory");
  var observation=new ObservationBatch(List.of(new ObservationId(ID)),List.of(),snapshot.scopeRevisions(),snapshot,List.of(new CampEpisodeObservation(new ObservationId(ID),region.regionId(),anchor.anchorId(),window.id(),new EpisodeId(ID),window.startTick(),window.endTick(),new DurationTicks(10),histogram)),List.of(new RouteTransitionObservation(new ObservationId(ID),route,window.id(),new EpisodeId(ID),window.startTick(),window.endTick(),Sector.N,3)),Map.of(new PlayerId(ID),List.of(exposure)));
  round(ObservationBatch.class,observation);round(RegionActivityState.class,activity);
  var w2=new EvidenceWindow(new EvidenceWindowId(new UUID(0,2)),new Tick(20),new Tick(30),Set.of(new EpisodeId(new UUID(0,2))));
  var w3=new EvidenceWindow(new EvidenceWindowId(new UUID(0,3)),new Tick(30),new Tick(40),Set.of(new EpisodeId(new UUID(0,3))));
  var c1=new CampWindowContribution(window,1,new DurationTicks(10),histogram);var c2=new CampWindowContribution(w2,2,new DurationTicks(20),histogram);var c3=new CampWindowContribution(w3,3,new DurationTicks(30),histogram);
  // Test-only merge demonstration, not RegionService: overlap W2 is retained exactly once.
  Map<EvidenceWindowId,CampWindowContribution> union=new HashMap<>();
  for(var part:List.of(List.of(c1,c2),List.of(c2,c3)))for(var c:part){var previous=union.putIfAbsent(c.window().id(),c);check(previous==null||previous.equals(c),"identical overlapping contribution");}
  check(union.size()==3&&union.values().stream().mapToLong(c->c.occupiedTicks().value()).sum()==60&&union.values().stream().mapToLong(CampWindowContribution::independentVisits).sum()==6,"camp exact W1 W2 W3 union");
  check(union.values().stream().mapToLong(c->c.histogram().sectors().get(Sector.N)).sum()==12,"histogram overlap counted once");
  Map<EvidenceWindowId,RouteWindowContribution> routes=new HashMap<>();
  for(var part:List.of(List.of(window,w2),List.of(w2,w3)))for(var w:part)routes.putIfAbsent(w.id(),new RouteWindowContribution(w,2,Map.of(Sector.N,2L)));
  check(routes.values().stream().mapToLong(RouteWindowContribution::transitions).sum()==6&&routes.values().stream().mapToLong(c->c.directionHistogram().get(Sector.N)).sum()==6,"route exact window union");
  round(CampWindowContribution.class,c2);round(RouteWindowContribution.class,routes.get(w2.id()));
  String windowGolden="{\"direction_histogram\":[{\"key\":\"N\",\"value\":\"2\"}],\"transitions\":\"2\",\"window\":{\"end_tick\":\"30\",\"id\":\"00000000-0000-0000-0000-000000000002\",\"independent_episodes\":[\"00000000-0000-0000-0000-000000000002\"],\"start_tick\":\"20\"}}";
  check(new String(new ModelCodec<>(RouteWindowContribution.class).encode(routes.get(w2.id())),StandardCharsets.UTF_8).equals(windowGolden),"window contribution golden");
  rejects(()->new CampRoutineMemory(anchor.anchorId(),List.of(new CampWindowContribution(window,4,new DurationTicks(10),histogram),new CampWindowContribution(window,4,new DurationTicks(10),histogram)),4,new DurationTicks(10),histogram,new UnitValue(.8)));
  rejects(()->new ActivityHistogram(Map.of(Sector.N,-1L),Map.of(),Map.of(),Map.of()));
  rejects(()->new AggregateChange(states.getFirst().key(),Optional.of(new Revision(1)),states.getFirst()));
 }
 private static DurableBatch operation(PermitFixture f,long number,OperationToken op){
  var old=((DurablePrepare)f.batch().records().getFirst()).value();var slice=new PreparedSlice(new SliceId(new UUID(1,number)),op,f.slice().footprint(),f.slice().preconditions(),List.of(),BudgetMath.zero());
  var p=new PrepareRecord(new TransactionId(new UUID(1,number)),op,old.reservationGeneration(),old.readSet(),old.expiryTick(),slice,old.reservation(),old.hardValidation(),old.uniqueness(),old.noninvertibleEffects(),old.authorizedStabilization(),old.durableBlobs());
  return new DurableBatch(p.transactionId(),new Epoch(7),Map.of(),List.of(new DurablePrepare(p)),List.of(fixtureBlob()),Optional.empty(),CommitPermit.Authority.prepareHash(p));
 }
 private static void permitLifecycle(){
  var f=permit();f.authority().submit(f.batch(),f.live());f.store().force(f.batch().transactionId());var permit=f.authority().poll(f.batch().transactionId(),f.live()).permit().orElseThrow();
  rejects(()->permit.consume(permit().authority(),f.slice(),f.live()));permit.consume(f.authority(),f.slice(),f.live());
  var duplicate=operation(f,2,new OperationToken(ID));f.authority().submit(duplicate,f.live());f.store().force(duplicate.transactionId());check(f.authority().poll(duplicate.transactionId(),f.live()).status()==PermitResult.Status.REJECTED,"durable reservation operation claim prevents new transaction replay");
  for(long i=3;i<65543;i++){var batch=operation(f,i,new OperationToken(new UUID(1,i)));f.authority().submit(batch,f.live());f.store().force(batch.transactionId());var granted=f.authority().poll(batch.transactionId(),f.live());check(granted.status()==PermitResult.Status.GRANTED,"long lived permit "+i);granted.permit().orElseThrow().consume(f.authority(),((DurablePrepare)batch.records().getFirst()).value().slice(),f.live());}
  check(f.authority().inFlight()==0,"completed permits retain no inflight cache");
  var reboot=permit();reboot.authority().submit(reboot.batch(),reboot.live());reboot.store().force(reboot.batch().transactionId());var old=reboot.authority().poll(reboot.batch().transactionId(),reboot.live()).permit().orElseThrow();reboot.store().reboot();rejects(()->old.consume(reboot.authority(),reboot.slice(),reboot.live()));
  var fresh=new CommitPermit.Authority(reboot.store(),4);fresh.submit(reboot.batch(),reboot.live());check(fresh.poll(reboot.batch().transactionId(),reboot.live()).status()==PermitResult.Status.REJECTED,"old boot ACK not usable by new authority");
  var delayed=permit();delayed.authority().submit(delayed.batch(),delayed.live());var late=ack(delayed,AckStatus.DURABLE,delayed.batch().transactionHash());
  delayed.authority().expire(new Tick(200));check(delayed.authority().inFlight()==0,"expired mailbox removed");delayed.store().acknowledge(late);
  check(delayed.authority().poll(delayed.batch().transactionId(),delayed.live()).status()==PermitResult.Status.REJECTED,"late ACK cannot revive expired attempt");
  delayed.authority().submit(delayed.batch(),delayed.live());delayed.store().acknowledge(late);check(delayed.authority().poll(delayed.batch().transactionId(),delayed.live()).status()==PermitResult.Status.REJECTED,"old ACK cannot bind fresh attempt");
  var missing=permit();missing.authority().submit(missing.batch(),missing.live());var a=ack(missing,AckStatus.DURABLE,missing.batch().transactionHash());missing.store().acknowledge(new CommitAck(a.transactionId(),a.storeGeneration(),a.durableSequence(),a.transactionHash(),a.ackStatus(),a.permitAttempt(),Set.of()));check(missing.authority().poll(missing.batch().transactionId(),missing.live()).status()==PermitResult.Status.REJECTED,"all referenced blobs forced before permit");
 }

 private static DecisionState decisionState(){try{return (DecisionState)sample(DecisionState.class);}catch(Exception e){throw new AssertionError(e);}}
 private static void codecBoundaries(){
  for(String token:List.of("1".repeat(70)+"e340","-"+"1".repeat(384)+"e340","1e340","1e-340","1".repeat(384),"0e340","10e339")){
   var value=CanonicalJsonV1.decode(bytes(token));byte[] encoded=CanonicalJsonV1.encode(value);
   check(value.equals(CanonicalJsonV1.decode(encoded)),"numeric precision/exponent roundtrip");check(Arrays.equals(encoded,CanonicalJsonV1.encode(CanonicalJsonV1.decode(encoded))),"numeric canonical stability");
  }
  for(String token:List.of("1e341","1e-341","1".repeat(385),"10e340"))rejects(()->CanonicalJsonV1.decode(bytes(token)));
  rejects(()->new JsonValue.Number(new java.math.BigDecimal("1e341")));
  var longId=BlobContent.capture(new ResourceId("fixture:"+"a".repeat(1100)),1,bytes("abc"));rejects(()->BlobCodec.encode(longId));
  byte[] maximum=new byte[BlobContent.MAX_BYTES];maximum[0]=1;maximum[maximum.length-1]=2;
  var shortBlob=BlobContent.capture(new ResourceId("fixture:a"),1,maximum);
  int baseLength=new ModelCodec<>(BlobRef.class).encode(shortBlob.reference()).length;
  var fullRef=new BlobRef(shortBlob.reference().hash(),maximum.length,1,new ResourceId("fixture:a"+"a".repeat(BlobCodec.MAX_REFERENCE_BYTES-baseLength)));
  var full=new BlobContent(fullRef,maximum);byte[] frame=BlobCodec.encode(full);
  check(frame.length==BlobCodec.MAX_FRAME_BYTES,"maximum frame includes eight header bytes");check(full.equals(BlobCodec.decode(frame)),"maximum reference and payload roundtrip");
  rejects(()->BlobCodec.decode(Arrays.copyOf(frame,frame.length+1)));
  var tooLong=new BlobContent(new BlobRef(fullRef.hash(),fullRef.bytes(),1,new ResourceId(fullRef.codecId().value()+"a")),maximum);rejects(()->BlobCodec.encode(tooLong));
  byte[] oversizedRef=java.nio.ByteBuffer.allocate(8+1025).putInt(0x57444231).putInt(1025).array();rejects(()->BlobCodec.decode(oversizedRef));
 }
 private static void decisionAndCapture()throws Exception {
  SnapshotBatch snapshot=context();DecisionState initial=decisionState();
  var fact=new DirectorFact(new FactId(ID),new FactType("fixture:observed"),List.of(new ScopeRef(ScopeKind.REGION,ID)),Optional.empty(),EvidenceStatus.CONFIRMED,Map.of("observed",new JsonValue.Bool(true)),true,Optional.empty());
  DecisionState state=new DecisionState(initial.world(),initial.budgets(),initial.ownership(),List.of((CooldownRecord)sample(CooldownRecord.class)),List.of((UniqueUseRecord)sample(UniqueUseRecord.class)),List.of(fact),List.of((EventInstance)sample(EventInstance.class)),List.of((ChainInstance)sample(ChainInstance.class)),List.of((DisasterInstance)sample(DisasterInstance.class)));round(DecisionState.class,state);
  var engine=new EngineControlState(1,new Revision(2),new Tick(10),new Tick(300),new UnitValue(.6),new Tick(100),Optional.of(new Tick(80)),new UnitValue(.75));round(EngineControlState.class,engine);
  var world=state.world();var next=new DirectorWorldState(new AggregateHeader(1,world.header().id(),world.header().revision().plus(1),new Sequence(42),new Tick(100)),world.worldId(),world.storeGeneration(),new Tick(100),world.runControl(),world.healthMode(),world.pacingState(),world.tension(),world.intensityProfileId(),world.catalogHash(),world.configHash(),world.bootEpoch(),world.lastCheckpointSeq(),engine);
  var change=new AggregateChange(new WorldAggregate(next).key(),Optional.of(world.header().revision()),new WorldAggregate(next));round(AggregateChange.class,change);round(DecisionProposal.class,new DecisionProposal(new DecisionId(ID),List.of(),Optional.empty(),(AuditDecision)sample(AuditDecision.class),List.of(change)));
  var memory=new MemoryView(snapshot.scopes(),Map.of(),List.of(),List.of(),List.of(),Map.of(),Map.of(),Map.of());
  var catalog=new CatalogSnapshot(new Epoch(1),HASH,Map.of(),Map.of(),Map.of());var config=new ConfigView(new Revision(1),HASH,Calibration.DEFAULT_FOR_TESTING,Map.of());
  var without=new DecisionInput(snapshot,memory,catalog,config,List.of(),List.of(),42,state,Optional.empty());
  var proposal=new AiProposal(1,new RequestId(ID),HASH,List.of(new AiAdjustment(new CandidateId(ID),.1)),List.of(),Set.of(AiRationale.CHAIN_CONTINUITY));
  var accepted=new AiAcceptedProposal(proposal,HASH,HASH,HASH,new DecisionId(ID),new ResourceId("fixture:advisor"),"fixture",99);
  var with=new DecisionInput(snapshot,memory,catalog,config,List.of(),List.of(),42,state,Optional.of(new AiProposalResult(AiStatus.VALID_PROPOSAL,Optional.of(accepted),Optional.empty())));
  round(DecisionInput.class,with);round(DecisionInput.class,without);check(with.state().equals(without.state())&&!with.equals(without),"pure rescore/replay advisory is explicit input");
  var decoded=new ModelCodec<>(DecisionInput.class).decode(new ModelCodec<>(DecisionInput.class).encode(with));check(decoded.advisorySnapshot().orElseThrow().equals(with.advisorySnapshot().orElseThrow()),"replay retains exact accepted adjustments without async advisor call");
  var f=permit();var pre=BlobContent.capture(new ResourceId("fixture:pre"),1,bytes("before"));var post=BlobContent.capture(new ResourceId("fixture:post"),1,bytes("after"));var plan=BlobContent.capture(new ResourceId("fixture:plan"),1,bytes("plan"));var stop=BlobContent.capture(new ResourceId("fixture:stop"),1,bytes("stop"));
  var bundle=new BlobBundle(List.of(pre,post,plan,stop));var refs=new EffectBlobs(Set.of(pre.reference(),post.reference()),plan.reference(),stop.reference(),Map.of(),Map.of());
  var intent=new EffectIntent(new OperationToken(ID),new PrimitiveId("fixture:sound"),1,new TargetSound("target",new ResourceId("minecraft:entity.goat.ambient"),1,16,1),f.slice().footprint(),new EventInstanceId(ID));
  var prepared=new PreparedEffect(intent,snapshot,List.of(),f.live().readSet(),Map.of(),BudgetMath.zero(),List.of(),Set.of(),refs);
  var output=new EffectPreparation(intent,new ValidationResult(true,List.of(),f.live().readSet()),Optional.of(prepared),bundle);round(EffectPreparation.class,output);
  var p=((DurablePrepare)f.batch().records().getFirst()).value();var capture=new PrepareRecord(p.transactionId(),p.operationToken(),p.reservationGeneration(),p.readSet(),p.expiryTick(),p.slice(),p.reservation(),p.hardValidation(),p.uniqueness(),p.noninvertibleEffects(),p.authorizedStabilization(),refs);
  var batch=new DurableBatch(p.transactionId(),new Epoch(7),Map.of(),List.of(new DurablePrepare(capture)),output.blobs().contents(),Optional.empty(),CommitPermit.Authority.prepareHash(capture));
  f.authority().submit(batch,f.live());f.store().force(batch.transactionId());check(f.authority().poll(batch.transactionId(),f.live()).status()==PermitResult.Status.GRANTED,"capture bytes forced before permit");f.store().reboot();
  var read=f.store().readBlobs(new BlobReadRequest(new WorldId(ID),new Epoch(7),CommitPermit.Authority.references(refs))).toCompletableFuture().getNow(null);check(read!=null&&read.status()==AckStatus.DURABLE,"hydration after reboot");
  var recovery=new RecoveryProbe(prepared,List.of(),new BlobBundle(read.contents()));round(RecoveryProbe.class,recovery);
  for(var content:bundle.contents())check(recovery.blobs().contents().contains(content),"exact distinct bytes/hash restored");
  round(PlanningSnapshot.class,new PlanningSnapshot(snapshot,state.ownership(),state.budgets(),bundle));
  round(CleanupIntent.class,new CleanupIntent(new EventInstanceId(ID),new OperationToken(ID),CleanupReason.values()[0],f.slice().footprint(),state.ownership(),bundle));
 }
 private static void bootstrap(){
  var missing=DirectorBootstrap.compose(ProviderMode.PRODUCTION,List.of());check(missing.missingPorts().size()==11,"explicit missing ports");check(missing.ports().quest().isEmpty()&&missing.ports().advisor().isEmpty(),"optional integrations");
  var fake=new DirectorBootstrap.Provider(new TestDurableStore(),ProviderMode.TEST_ONLY,List.of(),List.of());rejects(()->DirectorBootstrap.compose(ProviderMode.PRODUCTION,List.of(fake)));
  var lying=new DirectorBootstrap.Provider(new TestDurableStore(),ProviderMode.PRODUCTION,List.of(),List.of());rejects(()->DirectorBootstrap.compose(ProviderMode.PRODUCTION,List.of(lying)));
  var wrapped=new DirectorBootstrap.Provider(new Object(),ProviderMode.PRODUCTION,List.of(fake),List.of());rejects(()->DirectorBootstrap.compose(ProviderMode.PRODUCTION,List.of(wrapped)));
  check(DirectorBootstrap.compose(ProviderMode.TEST_ONLY,List.of(fake)).ports().durableStore().isPresent(),"explicit test composition");
  // A testing composition must not ingest a production provider, even when
  // its implementation does not expose a physical port today.
  var real=new DirectorBootstrap.Provider(new Object(),ProviderMode.PRODUCTION,List.of(),List.of());
  rejects(()->DirectorBootstrap.compose(ProviderMode.TEST_ONLY,List.of(real)));
  rejects(()->DirectorBootstrap.compose(ProviderMode.TEST_ONLY,List.of(new DirectorBootstrap.Provider(new Object(),ProviderMode.TEST_ONLY,List.of(real),List.of()))));
  // A caller's Provider label must not promote an actual LAB algorithm.
  var labDirector=new fr.proutlost.director.engine.WorldDirector(ProviderMode.TEST_ONLY);
  rejects(()->DirectorBootstrap.compose(ProviderMode.PRODUCTION,List.of(new DirectorBootstrap.Provider(labDirector,ProviderMode.PRODUCTION,List.of(),List.of()))));
  check(DirectorBootstrap.compose(ProviderMode.TEST_ONLY,List.of(new DirectorBootstrap.Provider(labDirector,ProviderMode.TEST_ONLY,List.of(),List.of()))).ports().decision().orElseThrow()==labDirector,"real LAB mode retained");
  var productionDirector=new fr.proutlost.director.engine.WorldDirector(ProviderMode.PRODUCTION);
  check(DirectorBootstrap.compose(ProviderMode.PRODUCTION,List.of(new DirectorBootstrap.Provider(productionDirector,ProviderMode.PRODUCTION,List.of(),List.of()))).ports().decision().orElseThrow()==productionDirector,"actual production decision backend");
  rejects(()->DirectorBootstrap.compose(ProviderMode.TEST_ONLY,List.of(new DirectorBootstrap.Provider(productionDirector,ProviderMode.TEST_ONLY,List.of(),List.of()))));
  fr.proutlost.director.api.port.DecisionPort unprovenanced=input->{throw new AssertionError("composition only");};
  rejects(()->DirectorBootstrap.compose(ProviderMode.PRODUCTION,List.of(new DirectorBootstrap.Provider(unprovenanced,ProviderMode.PRODUCTION,List.of(),List.of()))));
  for(var type:java.util.List.of(
      fr.proutlost.director.api.port.ObservationPort.class,fr.proutlost.director.api.port.MemoryPort.class,
      fr.proutlost.director.api.port.CatalogPort.class,fr.proutlost.director.api.port.DecisionPort.class,
      fr.proutlost.director.api.port.AdmissionPort.class,fr.proutlost.director.api.port.WorldEffectPort.class,
      fr.proutlost.director.api.port.DurableStore.class,fr.proutlost.director.api.port.FactBusPort.class,
      fr.proutlost.director.api.port.AdminPort.class)){
   Object unknown=java.lang.reflect.Proxy.newProxyInstance(ContractTests.class.getClassLoader(),new Class<?>[]{type},
       (proxy,method,args)->{throw new AssertionError("unprovenanced backend must never be invoked");});
   rejects(()->DirectorBootstrap.compose(ProviderMode.PRODUCTION,List.of(new DirectorBootstrap.Provider(unknown,ProviderMode.PRODUCTION,List.of(),List.of()))));
  }
  var realClaim=new CapabilityDescriptor(new CapabilityId("world.blocks"),1,ProviderMode.PRODUCTION,CapabilityStatus.AVAILABLE,new ResourceId("fixture:fake"),Set.of());
  var fakeClaim=new DirectorBootstrap.Provider(new TestDurableStore(),ProviderMode.TEST_ONLY,List.of(),List.of(realClaim));rejects(()->DirectorBootstrap.compose(ProviderMode.TEST_ONLY,List.of(fakeClaim)));
  rejects(()->DirectorBootstrap.compose(ProviderMode.PRODUCTION,List.of(new DirectorBootstrap.Provider(new Object(),ProviderMode.PRODUCTION,List.of(fake),List.of(realClaim)))));
  // Distinct provider objects with deceptive value equality must not masquerade
  // as a single durable store port. Port identity, not equals(), is authoritative.
  var equalHandler=(java.lang.reflect.InvocationHandler)(proxy,method,args)->switch(method.getName()){
   case "equals" -> true;
   case "hashCode" -> 1;
   case "toString" -> "same-value-port";
   default -> throw new UnsupportedOperationException("test-only port");
  };
  Object equalPortA=java.lang.reflect.Proxy.newProxyInstance(ContractTests.class.getClassLoader(),
   new Class<?>[]{fr.proutlost.director.api.port.DurableStore.class,DirectorBootstrap.TestOnlyBackend.class},equalHandler);
  Object equalPortB=java.lang.reflect.Proxy.newProxyInstance(ContractTests.class.getClassLoader(),
   new Class<?>[]{fr.proutlost.director.api.port.DurableStore.class,DirectorBootstrap.TestOnlyBackend.class},equalHandler);
  check(equalPortA!=equalPortB&&equalPortA.equals(equalPortB),"port equality attack fixture");
  var a=new DirectorBootstrap.Provider(equalPortA,ProviderMode.TEST_ONLY,List.of(),List.of());
  var b=new DirectorBootstrap.Provider(equalPortB,ProviderMode.TEST_ONLY,List.of(),List.of());
  rejects(()->DirectorBootstrap.compose(ProviderMode.TEST_ONLY,List.of(a,b)));
  // Reusing the same concrete port through two provider roots remains legal.
  check(DirectorBootstrap.compose(ProviderMode.TEST_ONLY,List.of(a,a)).ports().durableStore().orElseThrow()==equalPortA,
   "shared port instance is not a duplicate");
 }
 private static void states(){
  check(!StateTransitions.allows(EventState.PROPOSED,EventState.SUCCEEDED),"cannot skip execution");check(StateTransitions.allows(EventState.RESERVED,EventState.PREPARING),"prepare phase");
  check(!StateTransitions.allows(DisasterStage.WARN,DisasterStage.IMPACT,false),"WARN proof");check(StateTransitions.allows(DisasterStage.WARN,DisasterStage.IMPACT,true),"WARN sufficient");
  check(!StateTransitions.allows(UniqueStatus.RESERVED,UniqueStatus.RELEASED_BEFORE_EFFECT,true,false),"no reuse ambiguous prepare");
  check(!StateTransitions.allows(RequestStatus.EXECUTING,RequestStatus.FULFILLED,RequestKind.REQUIRE,false),"no fulfillment without confirmed fact");
  check(!StateTransitions.allows(RequestStatus.WAITING_SAFETY,RequestStatus.EXPIRED,RequestKind.REQUIRE,false),"REQUIRE not preference TTL");
  check(!StateTransitions.allows(OwnershipDisposition.YIELDED_TO_PLAYER,OwnershipDisposition.DIRECTOR_ACTIVE),"no reclaim player object");
 }
 private static void clocks(){
  TestClock c=new TestClock();c.elapsed(1000000);check(c.snapshot().simTick().value()==0,"elapsed time never replays server ticks");c.tick();check(c.snapshot().simTick().value()==1,"explicit tick");
  DeterministicRandom a=new DeterministicRandom(42),b=new DeterministicRandom(42);for(int i=0;i<100;i++)check(a.nextUnit()==b.nextUnit(),"seed determinism");
  check(Double.doubleToLongBits(a.draws().getFirst())==Double.doubleToLongBits(0.7415648787718233),"Java21 SplittableRandom golden");
 }
 private static void architecture()throws Exception {
  try(var files=Files.walk(Path.of("src/director/java"))){for(Path file:files.filter(p->p.toString().endsWith(".java")).toList()){
   String source=Files.readString(file);String relative=Path.of("src/director/java/fr/proutlost/director").relativize(file).toString().replace(java.io.File.separatorChar, '.').replace('/', '.');
   boolean pure=List.of("api.","catalog.","memory.","engine.","runtime.","persistence.","admin.server.","integration.","config.").stream().anyMatch(relative::startsWith);
   check(!source.contains("fr.proutlost.worldprep"),"no WorldPrep "+file);
   if(pure)check(!source.contains("net.minecraft")&&!source.contains("net.neoforged"),"pure domain "+file);
   var imports=java.util.regex.Pattern.compile("import\\s+(?:static\\s+)?fr\\.proutlost\\.director\\.([\\w.]+)").matcher(source);
   String module=relative.split("\\.")[0];
   while(imports.find()){
    String target=imports.group(1);boolean allowed=relative.startsWith("bootstrap.")||target.startsWith("api.")||target.startsWith(module+".");
    if(relative.startsWith("admin.server."))allowed=target.startsWith("api.")||target.startsWith("admin.server.");
    if(relative.startsWith("integration.")){String own="integration."+relative.split("\\.")[1]+".";allowed=target.startsWith("api.")||target.startsWith(own);}
    check(allowed,"no neighbor implementation import "+file+" -> "+target);
   }
  }}
  for(Class<?> type:List.of(AiInput.class,AiCandidate.class,AiAllowedTransition.class)){Set<Class<?>> types=new HashSet<>();walk(type,types);check(!types.contains(PlayerId.class)&&!types.contains(WorldId.class)&&!types.contains(BlockPosition.class)&&!types.contains(RoleAssignments.class),"transitive AI privacy "+type);}
  try(var files=Files.list(Path.of("src/director/java/fr/proutlost/director/api/model"))){for(Path file:files.filter(p->p.toString().endsWith(".java")).toList()){Class<?> c=Class.forName("fr.proutlost.director.api.model."+file.getFileName().toString().replace(".java",""));if(c.isRecord())for(RecordComponent r:c.getRecordComponents())check(r.getType()!=Object.class&&!Runnable.class.isAssignableFrom(r.getType())&&!java.util.function.Function.class.isAssignableFrom(r.getType()),"data-only DTO "+c);}}
 }
 private static void walk(Type type,Set<Class<?>> found){if(type instanceof ParameterizedType p){walk(p.getRawType(),found);for(Type t:p.getActualTypeArguments())walk(t,found);}else if(type instanceof Class<?> c&&found.add(c)&&c.isRecord())for(RecordComponent r:c.getRecordComponents())walk(r.getGenericType(),found);}
}
