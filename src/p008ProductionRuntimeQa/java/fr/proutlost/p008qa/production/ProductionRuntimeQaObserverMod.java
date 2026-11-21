package fr.proutlost.p008qa.production;

import fr.proutlost.director.bootstrap.*;
import fr.proutlost.director.api.codec.CanonicalJsonV1;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.neoforge.DirectorStandaloneMod;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.jar.JarFile;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Separate optional server-only TEST_ONLY observer. No ports, author approvals,
 * admission, ACKs, resource ownership, world effects or client payloads are created. */
@Mod(ProductionRuntimeQaObserverMod.MOD_ID)
public final class ProductionRuntimeQaObserverMod {
 public static final String MOD_ID="proutlost_p008_production_qa";
 private static final String PREFIX="proutlost.qa.productionRuntime.";
 private static MinecraftServer owner;private static DirectorProductionRuntime runtime;
 private static CompletedTickCapture ticks;private static ActorFrameCapture actors;
 private static Map<String,String> identity;private static Path output;private static long lastFrame=-1,lastProgress=-1;
 public ProductionRuntimeQaObserverMod(){
  require(DirectorProductionRuntimeQa.requested()&&Boolean.getBoolean("proutlost.qa.productionRuntime")&&!FMLEnvironment.dist.isClient(),"explicit dedicated DECLARED_SYNTHETIC observer only");
  NeoForge.EVENT_BUS.addListener(ProductionRuntimeQaObserverMod::started);NeoForge.EVENT_BUS.addListener(ProductionRuntimeQaObserverMod::post);
  NeoForge.EVENT_BUS.addListener(ProductionRuntimeQaObserverMod::stopping);NeoForge.EVENT_BUS.addListener(ProductionRuntimeQaObserverMod::stopped);NeoForge.EVENT_BUS.addListener(ProductionRuntimeQaObserverMod::commands);
 }
 private static void started(ServerStartedEvent event){
  var server=event.getServer();require(owner==null&&server.isSameThread(),"one QA observer owner per JVM");guard(server);
  try{
   var values=new LinkedHashMap<String,String>();Path actual=server.getWorldPath(LevelResource.ROOT).toRealPath();require(actual.equals(Path.of(System.getProperty(DirectorProductionRuntimeQa.ROOT,"")).toRealPath()),"QA world differs from factory root");
   values.put("world_root",actual.toString());values.put("main_jar_sha256",boundJar("fr.proutlost.Proutlost","mainJar","mainSha256"));
   values.put("director_jar_sha256",boundJar(DirectorProductionRuntime.class.getName(),"directorJar","directorSha256"));
   require(values.get("director_jar_sha256").equals(System.getProperty("proutlost.director.productionRuntimeQaDirectorSha256","")),"factory and observer Director pins differ");
   values.put("observer_jar_sha256",boundJar(ProductionRuntimeQaObserverMod.class.getName(),"observerJar","observerSha256"));
   values.put("qualified_sources_sha256",shaProperty("sourcesSha256"));require(values.get("qualified_sources_sha256").equals(System.getProperty("proutlost.director.productionRuntimeQaSourcesSha256","")),"factory and observer source pins differ");
   values.put("fixture_sha256",System.getProperty(DirectorProductionRuntimeQa.FIXTURE_PIN,""));values.put("client_manifest_sha256",shaProperty("clientManifestSha256"));
   values.put("java_pid",Long.toString(ProcessHandle.current().pid()));values.put("process_started_utc",ProcessHandle.current().info().startInstant().orElseThrow().toString());
   values.put("runtime_scope","ACTUAL_PRODUCTION_RUNTIME_CLASS_DECLARED_SYNTHETIC_INPUTS_DEFAULT_FOR_TESTING_NO_OPERATOR_APPROVAL");
   values.put("player_scope","AT_MOST_TWO_ACTUAL_SERVER_PLAYERS_COUNT_AT_OBSERVATION_NO_CLIENT_ACTIVITY_OR_HUMAN_ATTESTATION");
   values.put("join_scope","CPU_FRAME_NATIVE_TICK_JOINS_VANILLA_COMPLETED_TICK_EXACTLY_NO_WALL_TIME_APPROXIMATION");
   output=absolute("outputParent").toRealPath();require(!output.startsWith(actual)&&!actual.startsWith(output),"observer output separate from QA world");identity=Map.copyOf(values);owner=server;
   System.out.println("P008_PRODUCTION_QA_OBSERVER_READY capture=NOT_STARTED; use explicit start after runtime and warmup readback");
  }catch(IOException|ReflectiveOperationException invalid){throw new IllegalStateException("production QA observer identity guard",invalid);}
 }
 private static void begin(MinecraftServer server)throws IOException{
  guard(server);require(server==owner&&ticks==null&&actors==null,"one new capture per JVM; preserve previous evidence");
  var life=DirectorStandaloneMod.directorLifecycle();runtime=life.productionRuntime(server).orElseThrow(()->new IllegalStateException("actual ProductionRuntime not available"));
  require(runtime.declaredSyntheticQualification()&&runtime.declaredSyntheticComposition().isPresent()&&runtime.missingPorts()==0,"actual closed QA runtime and its nine real ports required");
  require(runtime.phase()==DirectorProductionRuntime.Phase.PAUSED||runtime.phase()==DirectorProductionRuntime.Phase.RUNNING,"native ready phase required");
  var ports=runtime.declaredSyntheticComposition().orElseThrow();require(ports.fixture().value().equals(identity.get("fixture_sha256"))&&!ports.operatorApprovalObtained(),"closed QA graph cannot be production approved");
  var store=life.runtimePerceptionStore(server).orElseThrow();var session=store.session();var world=runtime.worldState();
  require(!store.readOnlySafe()&&world.worldId().equals(session.worldId())&&world.bootEpoch().equals(session.bootEpoch())&&world.storeGeneration().equals(session.storeGeneration()),"current WORLD/J04 identity required");
  var anchor=store.verifiedManifestAnchor().orElseThrow();var values=new LinkedHashMap<>(identity);
  String run=property("runId");require(run.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,55}"),"bounded new capture run ID");
  byte[] ninePorts=CanonicalJsonV1.encode(verifyNinePorts(server,runtime));
  Path ninePortsFile=output.resolve(run+"_nine-ports.json");
  try(var channel=FileChannel.open(ninePortsFile,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
   var buffer=ByteBuffer.wrap(ninePorts);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
  }
  try(var directory=FileChannel.open(output,StandardOpenOption.READ)){directory.force(true);}
  try{values.put("nine_ports_proof_sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ninePorts)));}
  catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
  values.put("nine_ports_proof_file",ninePortsFile.getFileName().toString());
  values.put("geometry_source",ports.geometrySource());values.put("geometry_sha256",ports.geometryHash().value());values.put("run_id",run);values.put("world_id",session.worldId().value().toString());values.put("store_id",anchor.storeId().toString());values.put("store_generation",Long.toString(session.storeGeneration().value()));values.put("boot_epoch",Long.toString(session.bootEpoch().value()));values.put("authority_session",session.authoritySession().toString());
  var tickLimits=new CompletedTickCapture.Limits(value("durationSeconds",900,1,32400),value("maxSamples",650000,1,650000),value("maxBytes",128L*1024*1024,131072,128L*1024*1024),Math.toIntExact(value("queueCapacity",4096,1,4096)));
  var actorLimits=new ActorFrameCapture.Limits(tickLimits.seconds(),tickLimits.samples(),tickLimits.bytes(),tickLimits.queueCapacity());
  ticks=CompletedTickCapture.open(output,run+"_vanilla",tickLimits,Map.copyOf(values));
  try{actors=ActorFrameCapture.open(output,run+"_actor",actorLimits,Map.copyOf(values));}catch(IOException|RuntimeException failure){ticks.sourceFailure("ACTOR_SINK_START_FAILED");throw failure;}
  CompletableFuture.allOf(ticks.completion(),actors.completion()).whenComplete((ignored,error)->System.out.println(error==null?"P008_PRODUCTION_QA_CAPTURE_ENDS_WRITTEN inspect_both_manifests=true acceptance=NOT_CLAIMED":"P008_PRODUCTION_QA_CAPTURE_END_INCOMPLETE preserve_both_runs=true"));
  System.out.println("P008_PRODUCTION_QA_CAPTURE_STARTED source=ACTUAL_RUNTIME_ACTOR_FRAMES_AND_PREVIOUS_COMPLETED_VANILLA cpu_worker=NOT_MEASURED operator_approval=false");
 }
 /** Inspect the already-bound real ports and actual same-cut WORLD/J04.
  * This method grants no port, admission, calibration, permit or approval. */
 private static JsonValue.ObjectValue verifyNinePorts(MinecraftServer server,DirectorProductionRuntime selected){
  guard(server);require(server==owner&&selected.declaredSyntheticQualification()&&selected.composition().isEmpty()&&selected.missingPorts()==0,"actual separate declared QA runtime graph required");
  var ports=selected.declaredSyntheticComposition().orElseThrow();
  var expected=new TreeMap<String,Set<ProviderMode>>();
  for(String port:List.of("ObservationPort","MemoryPort","CatalogPort","DecisionPort","AdmissionPort","AdminPort"))expected.put(port,Set.of(ProviderMode.TEST_ONLY));
  expected.put("WorldEffectPort",Set.of(ProviderMode.PRODUCTION));
  expected.put("DurableStore",Set.of(ProviderMode.TEST_ONLY,ProviderMode.PRODUCTION));expected.put("FactBusPort",expected.get("DurableStore"));
  require(ports.implementations().keySet().equals(expected.keySet())&&ports.modes().equals(expected),"exact nine native QA port names and provenance required");
  var expectedClasses=Map.of(
   "ObservationPort","fr.proutlost.director.adapter.neoforge.server.observation.NeoForgeObservation",
   "MemoryPort","fr.proutlost.director.memory.DurableMemoryPort",
   "CatalogPort","fr.proutlost.director.bootstrap.DirectorRuntimeContent",
   "DecisionPort","fr.proutlost.director.bootstrap.DirectorNativeCatalogueExecution",
   "AdmissionPort","fr.proutlost.director.bootstrap.DirectorRuntimeContent$NativeAdmissionRouter",
   "WorldEffectPort","fr.proutlost.director.bootstrap.DirectorNativeEffectRouter",
   "DurableStore","fr.proutlost.director.persistence.JournalStore",
   "FactBusPort","fr.proutlost.director.persistence.JournalStore",
   "AdminPort","fr.proutlost.director.admin.server.AdminAccessGuard");
  require(ports.implementations().equals(expectedClasses),"exact nine native QA implementation classes required");
  require(!ports.operatorApprovalObtained()&&ports.fixture().value().equals(identity.get("fixture_sha256")),"synthetic ports cannot imply operator approval");
  var release=selected.releaseDiagnostic().orElseThrow();
  require(!release.mayProceedToQualification()&&release.blockers().containsAll(List.of("DECLARED_SYNTHETIC_DEFAULT_FOR_TESTING","OPERATOR_APPROVAL_NOT_OBTAINED")),"production approval remains blocked");
  var store=DirectorStandaloneMod.directorLifecycle().runtimePerceptionStore(server).orElseThrow();var session=store.session();
  require(!store.readOnlySafe(),"live native J04 writer required");
  var snapshot=store.metadataSnapshot().orElseThrow();require(snapshot.session().equals(session),"same native snapshot session required");
  var world=selected.worldState();var query=new StoreQuery(session.worldId(),Set.of(new AggregateKey(AggregateKind.WORLD,session.worldId().value())),new Sequence(0),Optional.empty(),1);
  var read=store.view(query);ReadContracts.validate(read,query,session);
  require(read.status()==ReadStatus.AVAILABLE&&read.page().orElseThrow().lastDurableSequence().equals(snapshot.cut())&&read.page().orElseThrow().aggregates().size()==1,"actual complete same-cut WORLD readback required; retry if cut advanced");
  var row=read.page().orElseThrow().aggregates().getFirst();
  require(row.state() instanceof WorldAggregate&&((WorldAggregate)row.state()).value().equals(world)&&world.worldId().equals(session.worldId())&&world.bootEpoch().equals(session.bootEpoch())&&world.storeGeneration().equals(session.storeGeneration()),"actual runtime WORLD/J04 bodies and identities must agree");
  require(store.session().equals(session),"native J04 session changed during nine-port observation");
  var anchor=store.verifiedManifestAnchor().orElseThrow();var result=new LinkedHashMap<String,JsonValue>();
  result.put("schema",text("P008_ACTUAL_DECLARED_SYNTHETIC_NINE_PORTS_V1"));result.put("provider_scope",text("MIXED_NATIVE_IMPLEMENTATIONS_TEST_ONLY_INPUTS_NO_PRODUCTION_COMPOSITION"));
  var implementations=new TreeMap<String,JsonValue>();ports.implementations().forEach((k,v)->implementations.put(k,text(v)));result.put("implementations",new JsonValue.ObjectValue(implementations));
  var modes=new TreeMap<String,JsonValue>();ports.modes().forEach((k,v)->modes.put(k,new JsonValue.Array(v.stream().map(Enum::name).sorted().map(ProductionRuntimeQaObserverMod::text).map(JsonValue.class::cast).toList())));result.put("modes",new JsonValue.ObjectValue(modes));
  result.put("world_id",text(session.worldId().value().toString()));result.put("store_id",text(anchor.storeId().toString()));result.put("authority_session",text(session.authoritySession().toString()));
  result.put("boot_epoch",number(session.bootEpoch().value()));result.put("store_generation",number(session.storeGeneration().value()));result.put("durable_cut",number(snapshot.cut().value()));
  result.put("world_revision",number(world.header().revision().value()));result.put("native_tick_at_observation",number(Integer.toUnsignedLong(server.getTickCount())));result.put("observed_monotonic_ns",number(System.nanoTime()));
  result.put("runtime_phase",text(selected.phase().name()));result.put("world_control",text(world.runControl().name()));result.put("measured_health",text(world.healthMode().name()));
  result.put("fixture_sha256",text(ports.fixture().value()));result.put("geometry_source",text(ports.geometrySource()));result.put("geometry_sha256",text(ports.geometryHash().value()));
  result.put("operator_approval_obtained",new JsonValue.Bool(false));result.put("normal_production_composition_present",new JsonValue.Bool(false));result.put("qualification_candidate",new JsonValue.Bool(false));
  var pins=new TreeMap<String,JsonValue>();identity.forEach((k,v)->pins.put(k,text(v)));result.put("loaded_candidate_identity",new JsonValue.ObjectValue(pins));
  return new JsonValue.ObjectValue(result);
 }
 private static JsonValue.Text text(String value){return new JsonValue.Text(value);}
 private static JsonValue.Number number(long value){return new JsonValue.Number(java.math.BigDecimal.valueOf(value));}
 private static void post(ServerTickEvent.Post event){
  var server=event.getServer();if(server!=owner||ticks==null||actors==null)return;
  if(!ticks.snapshot().accepting()&&!actors.snapshot().accepting())return;
  try{
   guard(server);long observed=Integer.toUnsignedLong(server.getTickCount());int players=server.getPlayerList().getPlayers().size();long wall=System.nanoTime();
   if(ticks.snapshot().accepting())PreviousCompletedTickReader.observe(server.getTickCount(),server.getTickTimesNanos(),wall,players,ticks);
   if(actors.snapshot().accepting())runtime.cpuStatus().ifPresent(frame->{
    require(frame.tick()<=observed,"CPU frame cannot belong to a future native tick");
    if(frame.tick()==lastFrame)return;require(frame.tick()>lastFrame,"CPU frame tick regressed");lastFrame=frame.tick();
    String health="UNAVAILABLE";long revision=-1;try{var state=runtime.worldState();health=state.healthMode().name();revision=state.header().revision().value();}catch(RuntimeException unavailable){/* Missing actual readback remains explicit. */}
    actors.offer(new ActorFrameCapture.Sample(frame.tick(),wall,observed,players,frame.frames(),frame.globalNanos(),frame.criticalNanos(),frame.operationLimit(),frame.operations(),frame.mandatoryWallNanos(),frame.criticalWallNanos(),frame.ordinaryWallNanos(),frame.actorWallNanos(),frame.actorCpuNanos(),frame.criticalUnits(),frame.ordinaryUnits(),frame.unitOverruns(),frame.criticalOverruns(),frame.emergencyCriticalUnits(),frame.windowSamples(),frame.actorWallP95Nanos(),frame.actorCpuP95Nanos(),frame.p95GoalNanos(),frame.p95GoalExceeded(),runtime.phase().name(),health,revision));
   });
   if(ticks.snapshot().sourceSamples()-lastProgress>=1200){lastProgress=ticks.snapshot().sourceSamples();System.out.println("P008_PRODUCTION_QA_CAPTURE_PROGRESS vanilla="+ticks.snapshot()+" actor="+actors.snapshot()+" runtime_phase_at_observation="+runtime.phase());}
  }catch(RuntimeException invalid){ticks.sourceFailure("NATIVE_PRODUCTION_OBSERVER_FAILED:"+invalid.getClass().getSimpleName());actors.sourceFailure("NATIVE_PRODUCTION_OBSERVER_FAILED:"+invalid.getClass().getSimpleName());System.err.println("P008_PRODUCTION_QA_CAPTURE_SOURCE_FAILED preserve_partial_evidence=true "+invalid);}
 }
 private static void guard(MinecraftServer server){
  require(server.isSameThread()&&DirectorProductionRuntimeQa.requested()&&!Boolean.getBoolean("proutlost.director.nativeLab")&&server.getPort()==25579&&server.getPlayerList().isUsingWhitelist(),"private production-runtime QA actor and port required");
  require(server.getPlayerList().getPlayers().size()<=2&&server.getPlayerList().getPlayers().stream().noneMatch(p->p instanceof FakePlayer),"frozen at most two real clients");
  // Paths were resolved once at startup. No filesystem IO in the Post producer.
  if(identity!=null)require(server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().equals(Path.of(identity.get("world_root"))),"QA world path changed");
 }
 private static void stop(){if(ticks!=null)ticks.stop(CompletedTickCapture.End.EXPLICIT_STOP);if(actors!=null)actors.stop(ActorFrameCapture.End.EXPLICIT_STOP);}
 private static void stopping(ServerStoppingEvent event){if(event.getServer()==owner)stop();}
 private static void stopped(ServerStoppedEvent event){if(event.getServer()==owner)stop();}
 private static void commands(RegisterCommandsEvent event){event.getDispatcher().register(Commands.literal("p008_production_capture").requires(s->s.hasPermission(4)&&DirectorProductionRuntimeQa.requested())
  .then(Commands.literal("nineports").executes(c->{var selected=DirectorStandaloneMod.directorLifecycle().productionRuntime(c.getSource().getServer()).orElseThrow();var bytes=CanonicalJsonV1.encode(verifyNinePorts(c.getSource().getServer(),selected));c.getSource().sendSuccess(()->Component.literal(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)),false);return 1;}))
  .then(Commands.literal("start").executes(c->{try{begin(c.getSource().getServer());}catch(IOException failure){throw new IllegalStateException(failure);}return 1;}))
  .then(Commands.literal("status").executes(c->{guard(c.getSource().getServer());c.getSource().sendSuccess(()->Component.literal(ticks==null?"P008_PRODUCTION_CAPTURE_NOT_STARTED":"vanilla="+ticks.snapshot()+" actor="+(actors==null?"MISSING":actors.snapshot())),false);return 1;}))
  .then(Commands.literal("stop").executes(c->{guard(c.getSource().getServer());require(ticks!=null&&actors!=null,"owned active capture required");stop();c.getSource().sendSuccess(()->Component.literal("Capture draining; verify BOTH durable run-end.json files before stopping JVM"),false);return 1;})));}
 private static String property(String key){String s=System.getProperty(PREFIX+key,"");require(!s.isBlank()&&s.length()<=1024,"explicit bounded observer property: "+key);return s;}
 private static String shaProperty(String key){String s=property(key);require(s.matches("[0-9a-f]{64}"),"exact observer SHA256 required: "+key);return s;}
 private static Path absolute(String key){Path p=Path.of(property(key));require(p.isAbsolute()&&!Files.isSymbolicLink(p),"absolute unlinked observer path: "+key);return p;}
 private static long value(String key,long fallback,long low,long high){String raw=System.getProperty(PREFIX+key);long n=raw==null?fallback:Long.parseLong(raw);require(n>=low&&n<=high,"bounded observer value: "+key);return n;}
 private static String boundJar(String className,String pathKey,String shaKey)throws IOException,ReflectiveOperationException{
  var type=Class.forName(className,false,ProductionRuntimeQaObserverMod.class.getClassLoader());var jar=absolute(pathKey);require(Files.isRegularFile(jar,LinkOption.NOFOLLOW_LINKS)&&Files.size(jar)<=64L*1024*1024,"bounded candidate JAR");
  String entry=className.replace('.','/')+".class";try(var archive=new JarFile(jar.toFile());var loaded=type.getResourceAsStream("/"+entry)){
   require(loaded!=null&&archive.getJarEntry(entry)!=null,"loaded candidate identity missing");try(var packed=archive.getInputStream(archive.getJarEntry(entry))){byte[] a=loaded.readNBytes(8*1024*1024+1),b=packed.readNBytes(8*1024*1024+1);require(a.length<=8*1024*1024&&b.length<=8*1024*1024&&Arrays.equals(a,b),"loaded class differs from candidate");}
  }
  MessageDigest digest;try{digest=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}try(var in=Files.newInputStream(jar)){byte[] chunk=new byte[65536];for(int n;(n=in.read(chunk))!=-1;)digest.update(chunk,0,n);}String actual=HexFormat.of().formatHex(digest.digest());require(actual.equals(shaProperty(shaKey)),"candidate hash differs: "+pathKey);return actual;
 }
 private static void require(boolean yes,String why){if(!yes)throw new IllegalStateException(why);}
}
