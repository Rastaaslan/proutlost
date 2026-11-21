package fr.proutlost.quest.qa;

import com.google.gson.*;
import fr.proutlost.director.adapter.neoforge.server.effects.*;
import fr.proutlost.director.adapter.neoforge.server.lifecycle.DirectorSavedIdentity;
import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.permission.CommitPermit;
import fr.proutlost.director.api.port.PlanningPort;
import fr.proutlost.director.bootstrap.*;
import fr.proutlost.director.engine.*;
import fr.proutlost.director.integration.quest.DirectorCommittedFactEvent;
import fr.proutlost.director.persistence.*;
import fr.proutlost.director.runtime.*;
import fr.proutlost.quest.core.QuestCore;
import fr.proutlost.quest.runtime.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Closed console-driven native QA: real J03/admission/J04/J05/lifecycle/Quest.
 * Synthetic GROUP actors are not clients. No canon, terrain generation, fixture
 * reset, journal injection or hand-posted completion event is performed here. */
public final class PublicGroupQaRuntime {
 private PublicGroupQaRuntime(){}
 private static final String FILE="proutlost_quest_public_group_qa_v1";
 private static final Map<MinecraftServer,Probe> ACTIVE=new IdentityHashMap<>();
 private static final StoreOptions OPTIONS=new StoreOptions(1_048_620,2_097_152,4_194_304,16_777_216,32,0,128);
 private static final BudgetVector CAP=new BudgetVector(100,100,100,100,100,100,100,100,100,100);
 private static final SavedData.Factory<QuestSavedData> FACTORY=new SavedData.Factory<>(
  ()->QuestSavedData.publicGroupQualification(PublicGroupQaFixture.CALIBRATION,PublicGroupQaFixture.quests()),
  (nbt,registries)->QuestSavedData.readPublicGroupQualification(nbt,PublicGroupQaFixture.CALIBRATION,PublicGroupQaFixture.quests()));
 public static void install(){
  NeoForge.EVENT_BUS.addListener(PublicGroupQaRuntime::register);
  NeoForge.EVENT_BUS.addListener(PublicGroupQaRuntime::started);
  NeoForge.EVENT_BUS.addListener(PublicGroupQaRuntime::tick);
  NeoForge.EVENT_BUS.addListener(PublicGroupQaRuntime::stopped);
  NeoForge.EVENT_BUS.addListener(PublicGroupQaRuntime::confirmed);
 }
 private static Path guard(MinecraftServer server){
  var root=DirectorPublicGroupQaFence.require(server);
  if(QuestConfig.ENABLED.get()||QuestConfig.TEST_COMMANDS.get())throw new IllegalStateException("canonical campaign must be disabled");
  return root;
 }
 private static void started(ServerStartedEvent event){guard(event.getServer());}
 private static void register(RegisterCommandsEvent event){
  var root=Commands.literal("public_group_qa").requires(s->Boolean.getBoolean("proutlost.quest.publicGroupQa")&&s.hasPermission(4));
  root.then(Commands.literal("produce").then(Commands.argument("cell",BlockPosArgument.blockPos()).executes(c->{
   var server=c.getSource().getServer();guard(server);
   if(ACTIVE.containsKey(server))throw new IllegalStateException("public GROUP QA already active");
   var cell=BlockPosArgument.getLoadedBlockPos(c,"cell");
   try{ACTIVE.put(server,new Probe(server,cell,false));}catch(java.io.IOException e){throw new IllegalStateException(e);}
   c.getSource().sendSuccess(()->Component.literal("PUBLIC_GROUP_QA_STARTED TEST_ONLY; inspect status and retained receipt"),false);return 1;
  })));
  root.then(Commands.literal("reopen").executes(c->{
   var server=c.getSource().getServer();guard(server);
   if(ACTIVE.containsKey(server))throw new IllegalStateException("new JVM required; QA already active");
   try{ACTIVE.put(server,new Probe(server,null,true));}catch(java.io.IOException e){throw new IllegalStateException(e);}
   return 1;
  }));
  root.then(Commands.literal("status").executes(c->{
   guard(c.getSource().getServer());var p=ACTIVE.get(c.getSource().getServer());
   c.getSource().sendSuccess(()->Component.literal(p==null?"PUBLIC_GROUP_QA_NOT_STARTED":p.status()),false);return 1;
  }));
  event.getDispatcher().register(Commands.literal("proutlost").then(root));
 }
 private static void tick(ServerTickEvent.Post event){
  var p=ACTIVE.get(event.getServer());if(p==null||p.done||p.failure!=null)return;
  try{guard(event.getServer());p.advance();}catch(RuntimeException|java.io.IOException failed){p.failure=failed.toString();System.out.println("PUBLIC_GROUP_QA_FAILED "+p.failure);}
 }
 private static void confirmed(DirectorCommittedFactEvent event){
  var p=ACTIVE.get(event.server());if(p==null||p.failure!=null||!event.fact().factType().equals(PublicGroupQaFixture.FACT))return;
  p.consume(event.fact());
 }
 private static void stopped(ServerStoppedEvent event){
  var p=ACTIVE.remove(event.getServer());if(p==null)return;
  if(p.store!=null)p.store.closeAsync();
  else p.opening.thenCompose(JournalStore::closeAsync);
 }
 private static void require(boolean yes,String why){if(!yes)throw new IllegalStateException(why);}
 private static Fingerprint hash(String text){return BlobContent.hash(text.getBytes(StandardCharsets.UTF_8));}
 private static String process(){return ProcessHandle.current().pid()+"@"+ProcessHandle.current().info().startInstant().orElseThrow();}
 private static String jar(Class<?> type,String property){
  // NeoForge may expose a union: CodeSource. Check the operator-named installed
  // JAR against the actual loaded class resource instead of guessing its URL.
  try{
   var path=Path.of(System.getProperty(property,""));require(path.isAbsolute()&&Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(path)&&Files.size(path)<=64*1024*1024L,"bounded installed QA JAR path required: "+property);
   String entry=type.getName().replace('.','/')+".class";
   try(var archive=new java.util.jar.JarFile(path.toFile());var runtime=type.getResourceAsStream("/"+entry)){
    require(runtime!=null&&archive.getJarEntry(entry)!=null,"QA class missing from installed JAR");
    try(var packed=archive.getInputStream(archive.getJarEntry(entry))){require(Arrays.equals(runtime.readNBytes(8*1024*1024+1),packed.readNBytes(8*1024*1024+1)),"loaded QA class and installed JAR differ");}
   }
   var digest=java.security.MessageDigest.getInstance("SHA-256");try(var bytes=Files.newInputStream(path)){byte[] chunk=new byte[65536];for(int n;(n=bytes.read(chunk))>=0;)digest.update(chunk,0,n);}return HexFormat.of().formatHex(digest.digest());
  }catch(java.io.IOException|java.security.NoSuchAlgorithmException invalid){throw new IllegalStateException("QA JAR identity unavailable",invalid);}
 }
 private static String source(){String v=System.getProperty("proutlost.quest.publicGroupQaSourcesSha256","");require(v.matches("[0-9a-f]{64}"),"exact qualified-source SHA-256 property required");return v;}
 private static JsonObject read(Path path)throws java.io.IOException {require(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(path)&&Files.size(path)<=65536,"bounded regular QA receipt required");return JsonParser.parseString(Files.readString(path)).getAsJsonObject();}
 private static void writeNew(Path target,JsonObject body)throws java.io.IOException{
  require(!Files.exists(target),"retain the previous QA receipt");
  Path temporary=target.resolveSibling(target.getFileName()+".tmp");
  try(var channel=java.nio.channels.FileChannel.open(temporary,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
   var data=StandardCharsets.UTF_8.encode(new GsonBuilder().setPrettyPrinting().create().toJson(body)+"\n");while(data.hasRemaining())channel.write(data);channel.force(true);
  }
  Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);
 }
 private static final class Probe {
  final MinecraftServer server;final Path worldRoot,root,wal;final boolean reopening;final long startTick;
  final WorldId world;final RegionId region;final ScopeRef worldScope,regionScope;final ScopeSet scopes;
  final ConfigView config;final CatalogSnapshot catalog;final String fixtureSha,mainSha,directorSha,sourceSha,processIdentity;
  final BlockPos cell;final DimensionId dimension;final BlockPosition position;final ResourceKey resource;final Footprint footprint;
  final UUID first,second,other,ungrouped;final QuestSavedData data;final JsonObject prior;
  final AtomicReference<OwnershipView> owners=new AtomicReference<>();
  final CompletableFuture<JournalStore> opening;JournalStore store;
  DirectorNativeEventLifecycle lifecycle;DirectorDecisionJournal journal;DecisionInput input,currentInput;DecisionProposal proposal;
  SelectedEventStart selected;EffectIntent intent;BlockTransformBinding binding;EffectPreparation capture;PreparedEffect prepared;
  AdmissionService admission;AdmissionRequest request;ResourceArbiter arbiter;ReservationToken token;PrepareRecord prepare;LiveValidationSnapshot live;
  EventRunner runner;CompletableFuture<CommitAck> pending;CompletableFuture<Void> closing;DirectorFact observed;OwnershipRecord retainedOwner;
  int phase;boolean done;String failure;long events;
  Probe(MinecraftServer server,BlockPos supplied,boolean reopen)throws java.io.IOException{
   this.server=server;worldRoot=guard(server);root=worldRoot.resolve("public-group-qa");wal=root.resolve("wal");reopening=reopen;startTick=server.getTickCount();
   sourceSha=source();mainSha=jar(PublicGroupQaRuntime.class,"proutlost.quest.publicGroupQaMainJar");directorSha=jar(DirectorNativeEventLifecycle.class,"proutlost.quest.publicGroupQaDirectorJar");processIdentity=process();
   config=DirectorLabObservationProfile.load();catalog=PublicGroupQaFixture.catalog(config);fixtureSha=catalog.hash().value();
   world=server.overworld().getDataStorage().computeIfAbsent(DirectorSavedIdentity.FACTORY,DirectorSavedIdentity.KEY).worldId();
   region=new RegionId(PublicGroupQaFixture.actor(world,"region"));worldScope=new ScopeRef(ScopeKind.WORLD,world.value());regionScope=new ScopeRef(ScopeKind.REGION,region.value());scopes=new ScopeSet(world,Set.of(worldScope,regionScope));
   first=PublicGroupQaFixture.actor(world,"first");second=PublicGroupQaFixture.actor(world,"second");other=PublicGroupQaFixture.actor(world,"other-group");ungrouped=PublicGroupQaFixture.actor(world,"ungrouped");
   Path saved=worldRoot.resolve("data").resolve(FILE+".dat");
   if(reopen){
    prior=read(root.resolve("produce.json"));
    require(!prior.get("process").getAsString().equals(processIdentity),"new JVM PID/start identity required");
    for(var e:Map.of("world_path",worldRoot.toString(),"wal_path",wal.toString(),"world_id",world.value().toString(),"source_sha256",sourceSha,"main_jar_sha256",mainSha,"director_jar_sha256",directorSha,"fixture_sha256",fixtureSha).entrySet())require(prior.get(e.getKey()).getAsString().equals(e.getValue()),"paired cold identity mismatch: "+e.getKey());
    require(Files.isRegularFile(saved,LinkOption.NOFOLLOW_LINKS),"cold proof requires existing actual Quest SavedData");
    cell=new BlockPos(prior.get("x").getAsInt(),prior.get("y").getAsInt(),prior.get("z").getAsInt());
   }else{
    require(!Files.exists(root)&&!Files.exists(saved),"retain previous QA; use a new distinct world");Files.createDirectory(root);prior=null;cell=Objects.requireNonNull(supplied).immutable();
   }
   var level=server.overworld();require(level.getChunkSource().getChunkNow(cell.getX()>>4,cell.getZ()>>4)!=null,"existing loaded QA cell required; no loading or generation");
   require(level.getWorldBorder().isWithinBounds(cell)&&cell.getY()>=level.getMinBuildHeight()&&cell.getY()<level.getMaxBuildHeight(),"bounded in-world QA cell required");
   require(level.getBlockEntity(cell)==null&&level.getBlockState(cell).is(reopen?Blocks.MOSS_BLOCK:Blocks.STONE),"QA physical PRE/POST cell mismatch");
   dimension=new DimensionId(level.dimension().location().toString());position=new BlockPosition(dimension,cell.getX(),cell.getY(),cell.getZ());resource=new ResourceKey(OwnershipDomain.BLOCK,dimension,cell.getX()+","+cell.getY()+","+cell.getZ());
   footprint=new Footprint(dimension,Set.of(new ChunkRef(dimension,cell.getX()>>4,cell.getZ()>>4)),Set.of(position),List.of());
   data=level.getDataStorage().computeIfAbsent(FACTORY,FILE);
   if(reopen){require(hash(data.core().save()).value().equals(prior.get("quest_core_sha256").getAsString()),"SavedData cold core differs from retained receipt");verifyGroups();}
   else{data.core().assignGroup(first,"QA_GROUP_A");data.core().assignGroup(second,"QA_GROUP_A");data.core().assignGroup(other,"QA_GROUP_B");data.setDirty();}
   opening=JournalStore.open(wal,world,OPTIONS).toCompletableFuture();
  }
  // Actual world gameTime is persisted by the same cold checkpoint. The
  // constant offset is explicitly QA; restarting a JVM cannot reset the clock.
  Tick now(){return new Tick(Math.addExact(20000L,server.overworld().getGameTime()));}
  String status(){return "PUBLIC_GROUP_QA TEST_ONLY stage="+phase+" done="+done+" reopening="+reopening+" events="+events+" failure="+Objects.toString(failure,"NONE")+" clients=0 actors=SYNTHETIC";}
  void verifyGroups(){
   for(var id:List.of(first,second,other))require(data.core().state(PublicGroupQaFixture.QUEST,id)==QuestCore.State.RESOLVED,"public result missing for assigned QA group");
   require(data.core().state(PublicGroupQaFixture.QUEST,ungrouped)==QuestCore.State.LOCKED,"ungrouped actor gained GROUP quest");
  }
  void consume(DirectorFact fact){
   require(fact.durable()&&fact.evidenceStatus()==EvidenceStatus.CONFIRMED&&fact.eventId().isPresent(),"actual confirmed native completion required");
   require(new HashSet<>(fact.subjectRefs()).equals(scopes.scopes())&&fact.subjectRefs().stream().noneMatch(s->s.kind()==ScopeKind.PLAYER),"original public WORLD/REGION scopes required");
   var expectedEvent=reopening?new EventInstanceId(UUID.fromString(prior.get("event_id").getAsString())):intent.instanceId();
   require(fact.eventId().equals(Optional.of(expectedEvent))&&fact.factId().value().equals(UUID.nameUUIDFromBytes(("catalogue-completion-v1\0"+expectedEvent.value()).getBytes(StandardCharsets.UTF_8))),"actual native completion identity");
   String before=data.core().save();QuestDirectorFacts.acceptConfirmed(server,data,fact,System.currentTimeMillis());
   if(observed!=null)require(before.equals(data.core().save()),"actual J04 completion replay changed Quest state");
   observed=fact;events++;verifyGroups();
  }
  void advance()throws java.io.IOException{
   require(server.getTickCount()-startTick<2400,"bounded public GROUP QA timeout");
   if(store!=null&&lifecycle!=null&&phase!=6){lifecycle.poll();require(!lifecycle.failed(),"actual EVENT lifecycle failed: "+lifecycle.status());}
   switch(phase){
    case 0->{if(!opening.isDone())return;store=opening.join();require(!store.readOnlySafe(),"actual J04 writer required");
     lifecycle=DirectorNativeEventLifecycle.publicGroupQualification(server,world,store,catalog,this::now);
     if(reopening){require(store.session().bootEpoch().value()>prior.get("wal_boot_epoch").getAsLong(),"new J04 boot required");require(now().value()>=prior.get("qa_tick").getAsLong(),"persisted native world clock moved backwards");phase=20;return;}
     setup();phase=1;}
    case 1->{if(!durable(pending))return;journal=new DirectorDecisionJournal(store,4);require(!journal.submit(proposal).mayProceedToAdmission(),"no admission before actual audit ACK");phase=2;}
    case 2->{var audit=journal.poll(proposal.proposalId());require(audit.state()!=DirectorDecisionJournal.State.REJECTED&&audit.state()!=DirectorDecisionJournal.State.UNAVAILABLE,"native J03 audit rejected");if(!audit.mayProceedToAdmission()||lifecycle.recovering())return;
     var gate=lifecycle.beginAudited(selected,journal);require(gate.status()!=DirectorNativeCatalogueExecution.GateStatus.REJECTED,"native EVENT rejected");if(gate.status()!=DirectorNativeCatalogueExecution.GateStatus.READY)return;prepareAdmission();phase=3;}
    case 3->{var result=admission.admit(request);require(result.status()!=AdmissionStatus.REJECTED,"actual admission rejected: "+result.reasons());
     var epochs=new HashMap<ResourceKey,Epoch>();prepared.claims().forEach(c->epochs.put(c.resource(),c.expectedEpoch()));
     admission.resolveWindow(epochs,Map.of(worldScope,CAP,regionScope,CAP));result=admission.admit(request);if(result.status()==AdmissionStatus.DEFERRED)return;
     require(result.status()==AdmissionStatus.ACCEPTED,"actual admission unavailable");token=result.token().orElseThrow();submitPhysical();phase=4;}
    case 4->{live=currentLive();var result=runner.poll(prepare.transactionId(),live);require(result.stage()!=EventRunner.Stage.REJECTED&&result.stage()!=EventRunner.Stage.RECOVERY_REQUIRED,"native EventRunner failed: "+result.failure());
     if(result.stage()!=EventRunner.Stage.COMMITTED)return;require(result.result().orElseThrow().outcome()==Outcome.SUCCEEDED&&server.overworld().getBlockState(cell).is(Blocks.MOSS_BLOCK),"actual Minecraft POST not observed");phase=5;}
    case 5->{if(observed==null||!lifecycle.publicGroupQualificationSettled(intent.instanceId()))return;verifyJournalAndSave();phase=6;}
    case 6->{if(closing==null)closing=store.closeAsync().toCompletableFuture();if(!closing.isDone())return;closing.join();done=true;System.out.println((reopening?"PUBLIC_GROUP_QA_REOPEN_PASS ":"PUBLIC_GROUP_QA_PRODUCE_PASS ")+root);}
    case 20->{if(observed==null||!lifecycle.publicGroupQualificationSettled(observed.eventId().orElseThrow()))return;require(data.core().factById(observed.factId().value().toString()).isPresent(),"actual cold Quest fact missing");verifyGroups();verifyNativeOwnership();
     require(hash(data.core().save()).value().equals(prior.get("quest_core_sha256").getAsString()),"cold replay mutated durable Quest core");
     var receipt=receipt("NEW_JVM_NATIVE_GROUP_READBACK");receipt.addProperty("producing_process",prior.get("process").getAsString());writeNew(root.resolve("reopen-"+ProcessHandle.current().pid()+"-"+ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli()+".json"),receipt);phase=6;}
    default->throw new IllegalStateException("invalid public GROUP QA phase");
   }
  }
  boolean durable(CompletableFuture<CommitAck> future){if(!future.isDone())return false;var ack=future.join();require(ack.ackStatus()==AckStatus.DURABLE&&ack.durableSequence().value()>0,"actual durable ACK required");return true;}
  void setup(){
   var original=server.overworld().getBlockState(cell);var pre=BlockTransformBinding.stateBlob(original);
   var owner=new OwnershipRecord(new AggregateHeader(1,UUID.randomUUID(),new Revision(1),new Sequence(0),now()),resource,Set.of(new OwnerToken(UUID.randomUUID())),new Epoch(1),OwnershipDisposition.DIRECTOR_ACTIVE,new MutationId(UUID.randomUUID()),pre.reference().hash(),new Epoch(0),Optional.empty());owners.set(new OwnershipView(Map.of(resource,owner)));
   binding=new BlockTransformBinding(dim->server.overworld(),owners::get,(p,next)->{require(p.equals(position),"foreign QA cell");owners.set(new OwnershipView(Map.of(resource,next)));},this::now,Map.of(PublicGroupQaFixture.RECIPE,new BlockTransformBinding.Recipe(Map.of(new ResourceId("minecraft:stone"),new ResourceId("minecraft:moss_block")),true)),1);
   var regionRecord=new RegionRecord(new AggregateHeader(1,region.value(),new Revision(1),new Sequence(0),now()),region,dimension,RegionStatus.ACTIVE,new SupportShape(List.of(),List.of(),List.of()),Set.of(),new UnitValue(.5),Set.of(),Set.of(),new Revision(1),new RegionPersonality(Map.of()),Set.of());
   var control=new EngineControlState(1,new Revision(0),now(),now(),new UnitValue(0),now(),Optional.empty(),new UnitValue(.5));
   var initialWorld=new DirectorWorldState(new AggregateHeader(1,world.value(),new Revision(1),new Sequence(0),now()),world,store.session().storeGeneration(),now(),RunControl.RUNNING,HealthMode.NORMAL,PacingState.ACTIVE,GlobalTension.CALM,new ResourceId("proutlost_director:qa_public_group"),catalog.hash(),config.provenanceHash(),store.session().bootEpoch(),new Sequence(0),control);
   input=input(initialWorld,regionRecord);currentInput=input;
   var generated=new CandidateGenerator().generate(input,new DecisionRandom(input.seed())).candidates();require(generated.size()==1,"one actual generated public QA candidate required");var candidate=generated.getFirst();
   require(candidate.scopes().scopes().equals(scopes.scopes())&&candidate.roles().roles().values().stream().allMatch(List::isEmpty),"public scope must arise from zero-role generation");
   var step=candidate.definition().steps().getFirst();var bindings=new PlanningBindings(candidate.id(),candidate.reference(),new EventInstanceId(UUID.randomUUID()),new OperationToken(UUID.randomUUID()),step.id(),candidate.roles(),candidate.scopes(),footprint,Set.of(),true);
   var planning=new PlanningSnapshot(input.context(),owners.get(),input.state().budgets(),new BlobBundle(List.of()),Optional.of(bindings));
   PlanningPort port=r->{var b=r.snapshot().bindings().orElseThrow();var i=new EffectIntent(b.operationToken(),r.step().primitive(),1,r.step().args(),b.footprint(),b.instanceId());return new PlannedPrimitive(b,binding.capture(i,r.snapshot()).prepared().orElseThrow());};
   var engine=new WorldDirector(ProviderMode.TEST_ONLY,new CandidatePlanner(port,Map.of(candidate.id(),List.of(planning))));proposal=engine.evaluate(input);
   require(proposal.selectedPlan().isPresent()&&engine.replay(input,proposal.audit()).equals(proposal),"real deterministic J03 selection required");
   selected=new SelectedEventStart(input,proposal,proposal.selectedPlan().orElseThrow(),Optional.empty());intent=selected.plan().effects().getFirst();
   pending=store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),List.of(new DurableOwnership(owner),new DurableAggregate(new WorldAggregate(initialWorld)),new DurableAggregate(new RegionAggregate(regionRecord))),List.of())).toCompletableFuture();
  }
  DecisionInput input(DirectorWorldState actualWorld,RegionRecord rr){
   var revisions=Map.of(worldScope,actualWorld.header().revision(),regionScope,rr.header().revision());var rows=new HashMap<ScopeRef,ScopeSnapshot>();revisions.forEach((k,v)->rows.put(k,new ScopeSnapshot(k,v,EnumSet.allOf(SensorKind.class),Set.of(),Map.of())));
   var terrain=new TerrainCellSnapshot(position,BlockTransformBinding.stateBlob(server.overworld().getBlockState(cell)).reference().hash(),BlockTransformBinding.STATE_CODEC,Set.of(),Set.of(),false,true,owners.get().records().get(resource).epoch(),new Epoch(0));
   var capId=new CapabilityId("mutation.plants_v1");var capability=new CapabilityDescriptor(capId,1,ProviderMode.TEST_ONLY,CapabilityStatus.AVAILABLE,new ResourceId("proutlost_director:qa_public_group"),Set.of("TEST_ONLY_SYNTHETIC_REGION_ZERO_PLAYERS"));
   var context=new ObservationContext(rows,Map.of(),Map.of(),Map.of(region,new RegionSnapshot(rr,Set.of(new TagId("proutlost:qa_public_group_site")),Set.of(),List.of(),Map.of())),Map.of(),Map.of(),List.of(new TerrainSnapshot(footprint,Map.of(position,terrain),List.of(),Map.of(),new Revision(1))),Map.of(capId,capability));
   var snapshot=new SnapshotBatch(new RequestId(UUID.randomUUID()),scopes,now(),revisions,context,Set.of(),footprint.loadedChunks(),HealthMode.NORMAL);
   var memory=new MemoryView(scopes,revisions,List.of(new RegionMemory(rr.header(),region,Map.of(),now(),now(),Set.of(),Set.of(),new RegionActivityState(Map.of(),Map.of()))),List.of(),List.of(),Map.of(region,rr),Map.of(),Map.of());
   return new DecisionInput(snapshot,memory,catalog,config,List.of(),List.of(),42,new DecisionState(actualWorld,new BudgetView(CAP,BudgetMath.zero(),Map.of(regionScope,BudgetMath.zero())),owners.get(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of()),Optional.empty());
  }
  void prepareAdmission(){
   var q=new StoreQuery(world,Set.of(new AggregateKey(AggregateKind.WORLD,world.value()),new AggregateKey(AggregateKind.REGION,region.value())),new Sequence(0),Optional.empty(),8);var view=store.view(q);ReadContracts.validate(view,q,store.session());require(view.status()==ReadStatus.AVAILABLE,"actual admission scopes resident");
   DirectorWorldState w=null;RegionRecord r=null;for(var row:view.page().orElseThrow().aggregates()){if(row.state() instanceof WorldAggregate a)w=a.value();if(row.state() instanceof RegionAggregate a)r=a.value();}
   require(w!=null&&r!=null,"actual J04 post-audit WORLD/REGION required");currentInput=input(w,r);
   capture=binding.capture(intent,new PlanningSnapshot(currentInput.context(),owners.get(),currentInput.state().budgets(),new BlobBundle(List.of())));require(capture.validation().valid(),"native post-audit capture failed");prepared=capture.prepared().orElseThrow();
   var plan=new ExecutionPlan(selected.plan().candidateId(),selected.plan().definition(),selected.plan().effects(),selected.plan().roles(),selected.plan().scopes(),prepared.preconditions());request=new AdmissionRequest(proposal.proposalId(),plan.readSet(),prepared.claims(),plan.roles(),plan);
   arbiter=new ResourceArbiter(world,new Epoch(0),1000,4,new ResourceArbiter.Compatibility(){public boolean share(ResourceClaim a,ResourceClaim b){return false;}public boolean merge(ResourceId id,ResourceClaim a,ResourceClaim b){return false;}});
   admission=new AdmissionService(()->catalog,new AdmissionService.HardValidator(){
    public ProviderMode mode(){return ProviderMode.TEST_ONLY;}
    public AdmissionService.Assessment assess(AdmissionRequest actual,Tick tick){
     boolean valid=actual.equals(request)&&binding.revalidate(prepared,capture.blobs())&&server.overworld().getBlockState(cell).is(Blocks.STONE);
     var validation=new ValidationResult(valid,valid?List.of():List.of(ReasonCode.STALE_CONTEXT),prepared.preconditions());
     return new AdmissionService.Assessment(validation,prepared.claims(),new ResourceArbiter.Rank(false,false,false,Priority.AMBIENT,0,1,42),Map.of(worldScope,prepared.budgetEstimate(),regionScope,prepared.budgetEstimate()));
    }
   },arbiter,this::now,new DurationTicks(600),4,ProviderMode.TEST_ONLY);
  }
  void submitPhysical(){
   var slice=new PreparedSlice(new SliceId(UUID.randomUUID()),intent.operationToken(),footprint,prepared.preconditions(),List.of(intent),prepared.budgetEstimate());
   prepare=new PrepareRecord(new TransactionId(UUID.randomUUID()),intent.operationToken(),token.generation(),prepared.preconditions(),now().plus(600),slice,token,capture.validation(),List.of(),Set.of(),List.of(),prepared.durableBlobs());
   live=new LiveValidationSnapshot(prepared.preconditions(),footprint.loadedChunks(),HealthMode.NORMAL,now(),token.generation());
   var authority=new CommitPermit.Authority(store,4);var port=new ServerEffectPort(authority,i->{throw new IllegalStateException("native capture already sealed");},s->currentLive(),Map.of(intent.primitive(),binding),Set.of(new CapabilityId("mutation.plants_v1")),4,s->new StabilizationLiveSnapshot(store.session(),currentLive(),owners.get()),m->{},(m,result)->{});port.hydrate(prepared,capture.blobs());runner=new EventRunner(authority,store,port,arbiter,4);
   var batch=new DurableBatch(prepare.transactionId(),store.session().storeGeneration(),prepare.readSet().revisions(),List.of(new DurablePrepare(prepare)),capture.blobs().contents(),Optional.empty(),CommitPermit.Authority.prepareHash(prepare));
   runner.submit(batch,prepared.expectedPost(),live,actual->{
    require(actual.outcome()==Outcome.SUCCEEDED&&actual.appliedOperationIds().contains(intent.operationToken())&&actual.observedHashes().equals(prepared.expectedPost()),"only actual successful exact POST may complete native event");
    var rows=new ArrayList<DurableRecord>();rows.add(new DurableResult(new ResultRecord(prepare.transactionId(),prepare.operationToken(),actual,Set.of(),List.of(),List.of())));rows.add(new DurableOwnership(owners.get().records().get(resource)));
    rows.addAll(selected.completionRecords(Map.of("test_only",new JsonValue.Bool(true),"actual_cells",new JsonValue.Text("1"),"observed_post",new JsonValue.Text(hash(actual.observedHashes().toString()).value()))));
    return ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),rows,List.of());
   });
   require(server.overworld().getBlockState(cell).is(Blocks.STONE),"native physical effect before PREPARE ACK");
  }
  LiveValidationSnapshot currentLive(){return new LiveValidationSnapshot(prepared.preconditions(),footprint.loadedChunks(),HealthMode.NORMAL,now(),token.generation());}
  void verifyJournalAndSave()throws java.io.IOException{
   verifyGroups();verifyNativeOwnership();require(data.core().factById(observed.factId().value().toString()).isPresent(),"actual completion missing from Quest core");
   server.saveEverything(true,true,true);server.overworld().getDataStorage().save();net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
   Path saved=worldRoot.resolve("data").resolve(FILE+".dat");require(Files.isRegularFile(saved,LinkOption.NOFOLLOW_LINKS),"native SavedData file missing");
   var nbt=net.minecraft.nbt.NbtIo.readCompressed(saved,net.minecraft.nbt.NbtAccounter.create(16*1024*1024L));
   var reread=QuestSavedData.readPublicGroupQualification(nbt.getCompound("data"),PublicGroupQaFixture.CALIBRATION,PublicGroupQaFixture.quests());require(reread.core().save().equals(data.core().save()),"native disk Quest SavedData readback differs");
   writeNew(root.resolve("produce.json"),receipt("NATIVE_PRODUCE_SAVED_COLD_PENDING"));
  }
  void verifyNativeOwnership(){
   UUID id=reopening?UUID.fromString(prior.get("ownership_id").getAsString()):owners.get().records().get(resource).header().id();
   var q=new StoreQuery(world,Set.of(new AggregateKey(AggregateKind.OWNERSHIP,id)),new Sequence(0),Optional.empty(),1);var view=store.view(q);ReadContracts.validate(view,q,store.session());
   require(view.status()==ReadStatus.AVAILABLE&&view.page().orElseThrow().aggregates().size()==1,"retained native J04 ownership head unavailable");
   var row=view.page().orElseThrow().aggregates().getFirst();require(row.committedSequence().value()>0&&row.state() instanceof OwnershipAggregate,"actual committed native ownership required");
   retainedOwner=((OwnershipAggregate)row.state()).value();require(retainedOwner.resourceKey().equals(resource)&&retainedOwner.expectedPostHash().equals(BlockTransformBinding.stateBlob(server.overworld().getBlockState(cell)).reference().hash()),"native cell and retained J04 ownership POST differ");
   if(reopening)require(BlobContent.hash(new ModelCodec<>(OwnershipRecord.class).encode(retainedOwner)).value().equals(prior.get("ownership_record_sha256").getAsString()),"cold native ownership record changed");
  }
  JsonObject receipt(String phaseName){
   require(retainedOwner!=null,"native J04 ownership must be read before receipt");
   var v=new JsonObject();v.addProperty("schema",1);v.addProperty("phase",phaseName);v.addProperty("scope","TEST_ONLY_SYNTHETIC_GROUP_ACTORS_NATIVE_SINGLE_CELL_NO_CLIENT_RECEPTION");v.addProperty("synthetic_selection_context","region,regional-memory,capability-descriptor,budgets,ACTIVE-pacing,zero-role-definition,group-actors");v.addProperty("native_observed_chain","loaded-Minecraft-cell,J03-engine,AdmissionService,J04-PREPARE,one-use-permit,BlockTransformBinding,J04-RESULT,EVENT-lifecycle,shared-Quest-consumer,SavedData-disk");v.addProperty("not_claimed","natural-observation,production-canon,clients,human-reception,private-and-prerequisite-native-negative-tests,cleanup,release,inverse,physical-restoration");v.addProperty("process",processIdentity);v.addProperty("world_path",worldRoot.toString());v.addProperty("wal_path",wal.toString());v.addProperty("world_id",world.value().toString());v.addProperty("source_sha256",sourceSha);v.addProperty("main_jar_sha256",mainSha);v.addProperty("director_jar_sha256",directorSha);v.addProperty("fixture_sha256",fixtureSha);v.addProperty("quest_core_sha256",hash(data.core().save()).value());v.addProperty("world_game_time",server.overworld().getGameTime());v.addProperty("qa_tick",now().value());v.addProperty("wal_boot_epoch",store.session().bootEpoch().value());v.addProperty("wal_authority",store.session().authoritySession().toString());v.addProperty("fact_id",observed.factId().value().toString());v.addProperty("event_id",observed.eventId().orElseThrow().value().toString());v.addProperty("original_subjects",observed.subjectRefs().toString());v.addProperty("ownership_id",retainedOwner.header().id().toString());v.addProperty("ownership_disposition",retainedOwner.disposition().name());v.addProperty("ownership_record_sha256",BlobContent.hash(new ModelCodec<>(OwnershipRecord.class).encode(retainedOwner)).value());v.addProperty("x",cell.getX());v.addProperty("y",cell.getY());v.addProperty("z",cell.getZ());v.addProperty("physical_post","minecraft:moss_block");v.addProperty("native_clients",0);v.addProperty("synthetic_group_actors",3);return v;
  }
 }
}
