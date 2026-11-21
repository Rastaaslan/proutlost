package fr.proutlost.p008qa.cohort;

import fr.proutlost.director.bootstrap.DirectorProductionCohortQa;
import fr.proutlost.director.bootstrap.DirectorProductionRuntime;
import fr.proutlost.director.neoforge.DirectorStandaloneMod;
import java.io.IOException;
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

/** Separate TEST_ONLY cohort observer. No launcher, admission, ACK, effect or world write.
 * BASELINE observes vanilla only and requires the production runtime to be absent.
 * DIRECTOR observes a real closed QA runtime's completed actor frames as a second stream. */
@Mod(CohortQaObserverMod.MOD_ID)
public final class CohortQaObserverMod {
    public static final String MOD_ID = "proutlost_p008_cohort_qa";
    private static final String PREFIX = "proutlost.qa.cohort.";
    private static MinecraftServer owner;
    private static DirectorProductionCohortQa policy;
    private static DirectorProductionRuntime runtime;
    private static CompletedTickCapture ticks;
    private static ActorFrameCapture actors;
    private static Map<String,String> identity;
    private static Path output;
    private static Set<UUID> allowedUuids;
    private static long lastFrame = -1, lastProgress = -1;

    public CohortQaObserverMod() {
        require(DirectorProductionCohortQa.requested() && Boolean.getBoolean("proutlost.qa.cohort") && !FMLEnvironment.dist.isClient(), "explicit dedicated cohort observer only");
        NeoForge.EVENT_BUS.addListener(CohortQaObserverMod::started);
        NeoForge.EVENT_BUS.addListener(CohortQaObserverMod::post);
        NeoForge.EVENT_BUS.addListener(CohortQaObserverMod::stopping);
        NeoForge.EVENT_BUS.addListener(CohortQaObserverMod::stopped);
        NeoForge.EVENT_BUS.addListener(CohortQaObserverMod::commands);
    }
    private static void started(ServerStartedEvent event) {
        var server = event.getServer();
        require(owner == null && server.isSameThread(), "one cohort observer owner per JVM");
        policy = DirectorProductionCohortQa.open(server);
        allowedUuids = policy.plan().actualUuids();
        guard(server);
        try {
            var values = new LinkedHashMap<String,String>();
            Path world = server.getWorldPath(LevelResource.ROOT).toRealPath();
            values.put("world_root", world.toString());
            values.put("main_jar_sha256", boundJar("fr.proutlost.Proutlost", "mainJar", "mainSha256"));
            values.put("director_jar_sha256", boundJar(DirectorProductionRuntime.class.getName(), "directorJar", "directorSha256"));
            values.put("observer_jar_sha256", boundJar(CohortQaObserverMod.class.getName(), "observerJar", "observerSha256"));
            var pins = policy.plan().pins();
            require(values.get("director_jar_sha256").equals(pins.get("director_jar_sha256")), "loaded Director differs from cohort plan");
            values.put("qualified_sources_sha256", shaProperty("sourcesSha256"));
            require(values.get("qualified_sources_sha256").equals(pins.get("sources_sha256")), "observer and policy source pins differ");
            values.put("candidate_sha256", pins.get("candidate_sha256"));
            values.put("profile_sha256", pins.get("profile_sha256"));
            values.put("action_plan_sha256", pins.get("action_plan_sha256"));
            values.put("plan_sha256", policy.planSha());
            values.put("allowed_uuid_sha256", CohortPlayerCensus.digest(allowedUuids));
            values.put("cohort_mode", policy.plan().mode());
            values.put("branch", policy.branch().name());
            values.put("actual_players_target", Integer.toString(policy.plan().actualPlayersTarget()));
            values.put("human_operators", Integer.toString(policy.plan().humanOperators()));
            values.put("controller_id", policy.plan().controllerId());
            values.put("java_pid", Long.toString(ProcessHandle.current().pid()));
            values.put("process_started_utc", ProcessHandle.current().info().startInstant().orElseThrow().toString());
            values.put("runtime_scope", director() ? "REAL_CLOSED_QA_RUNTIME_ACTOR_FRAMES" : "PRODUCTION_RUNTIME_ABSENT_DIRECTOR_JAR_LOADED");
            values.put("player_scope", "ACTUAL_WHITELISTED_NON_FAKE_PLAYER_LIST_AT_CURRENT_POST_NOT_HUMAN_ATTESTATION");
            values.put("join_scope", "EXACT_NATIVE_TICK_ONLY_NOT_WALL_TIME_APPROXIMATION");
            values.put("capture_format", "P008_COHORT_V2");
            output = absolute("outputParent").toRealPath();
            require(!output.startsWith(world) && !world.startsWith(output), "observer output separate from world");
            identity = Map.copyOf(values); owner = server;
            System.out.println("P008_COHORT_QA_OBSERVER_READY branch=" + policy.branch() + " capture=NOT_STARTED actual_players_target=" + policy.plan().actualPlayersTarget());
        } catch (IOException | ReflectiveOperationException invalid) { throw new IllegalStateException("cohort observer identity guard", invalid); }
    }
    private static boolean director() { return policy.branch().name().equals("DIRECTOR"); }
    private static void begin(MinecraftServer server) throws IOException {
        guard(server); require(server == owner && ticks == null && actors == null, "one new capture per JVM");
        var life = DirectorStandaloneMod.directorLifecycle();
        var values = new LinkedHashMap<>(identity);
        if (director()) {
            runtime = life.productionRuntime(server).orElseThrow(() -> new IllegalStateException("actual ProductionRuntime absent"));
            require(runtime.declaredSyntheticQualification() && runtime.declaredSyntheticComposition().isPresent() && runtime.missingPorts() == 0, "closed runtime and its nine real ports required");
            require(runtime.phase() == DirectorProductionRuntime.Phase.PAUSED || runtime.phase() == DirectorProductionRuntime.Phase.RUNNING, "native ready phase required");
            var ports = runtime.declaredSyntheticComposition().orElseThrow();
            require(!ports.operatorApprovalObtained(), "QA graph cannot be production approved");
            var store = life.runtimePerceptionStore(server).orElseThrow(); var session = store.session(); var world = runtime.worldState();
            require(!store.readOnlySafe() && world.worldId().equals(session.worldId()) && world.bootEpoch().equals(session.bootEpoch()) && world.storeGeneration().equals(session.storeGeneration()), "current WORLD/J04 identity required");
            var anchor = store.verifiedManifestAnchor().orElseThrow();
            values.put("fixture_sha256", ports.fixture().value()); values.put("geometry_sha256", ports.geometryHash().value());
            values.put("world_id", session.worldId().value().toString()); values.put("store_id", anchor.storeId().toString());
            values.put("store_generation", Long.toString(session.storeGeneration().value())); values.put("boot_epoch", Long.toString(session.bootEpoch().value())); values.put("authority_session", session.authoritySession().toString());
        } else {
            require(life.productionRuntime(server).isEmpty(), "BASELINE requires absent production runtime");
        }
        String run = property("runId"); require(run.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,55}"), "bounded new run ID"); values.put("run_id", run); identity = Map.copyOf(values);
        var limits = new CompletedTickCapture.Limits(value("durationSeconds",900,1,32400),value("maxSamples",650000,1,650000),value("maxBytes",128L*1024*1024,131072,128L*1024*1024),Math.toIntExact(value("queueCapacity",4096,1,4096)));
        ticks = CompletedTickCapture.open(output,run+"_vanilla",limits,Map.copyOf(values));
        if (director()) {
            try { actors = ActorFrameCapture.open(output,run+"_actor",new ActorFrameCapture.Limits(limits.seconds(),limits.samples(),limits.bytes(),limits.queueCapacity()),Map.copyOf(values)); }
            catch (IOException | RuntimeException failed) { ticks.sourceFailure("ACTOR_SINK_START_FAILED"); throw failed; }
        }
        CompletableFuture<?> done = actors == null ? ticks.completion() : CompletableFuture.allOf(ticks.completion(),actors.completion());
        done.whenComplete((ignored,error) -> System.out.println(error == null ? "P008_COHORT_QA_CAPTURE_ENDS_WRITTEN inspect_manifests=true acceptance=NOT_CLAIMED" : "P008_COHORT_QA_CAPTURE_END_INCOMPLETE preserve_partial=true"));
        System.out.println("P008_COHORT_QA_CAPTURE_STARTED branch="+policy.branch()+" actors_created=false runtime_created=false");
    }
    private static void post(ServerTickEvent.Post event) {
        var server=event.getServer(); if(server != owner || ticks == null) return;
        if (!ticks.snapshot().accepting() && (actors == null || !actors.snapshot().accepting())) return;
        try {
            int players=guard(server); long observed=Integer.toUnsignedLong(server.getTickCount()), wall=System.nanoTime();
            if (ticks.snapshot().accepting()) PreviousCompletedTickReader.observe(server.getTickCount(),server.getTickTimesNanos(),wall,players,ticks);
            if (actors != null && actors.snapshot().accepting()) runtime.cpuStatus().ifPresent(frame -> {
                require(frame.tick() <= observed,"future CPU frame"); if(frame.tick()==lastFrame)return; require(frame.tick()>lastFrame,"CPU frame regression"); lastFrame=frame.tick();
                String health="UNAVAILABLE";long revision=-1;
                try { var state=runtime.worldState(); health=state.healthMode().name();revision=state.header().revision().value(); } catch(RuntimeException unavailable) { /* Unavailable readback remains explicit. */ }
                actors.offer(new ActorFrameCapture.Sample(frame.tick(),wall,observed,players,frame.frames(),frame.globalNanos(),frame.criticalNanos(),frame.operationLimit(),frame.operations(),frame.mandatoryWallNanos(),frame.criticalWallNanos(),frame.ordinaryWallNanos(),frame.actorWallNanos(),frame.actorCpuNanos(),frame.criticalUnits(),frame.ordinaryUnits(),frame.unitOverruns(),frame.criticalOverruns(),frame.emergencyCriticalUnits(),frame.windowSamples(),frame.actorWallP95Nanos(),frame.actorCpuP95Nanos(),frame.p95GoalNanos(),frame.p95GoalExceeded(),runtime.phase().name(),health,revision));
            });
            if(ticks.snapshot().sourceSamples()-lastProgress>=1200){lastProgress=ticks.snapshot().sourceSamples();System.out.println("P008_COHORT_QA_CAPTURE_PROGRESS vanilla="+ticks.snapshot()+" actor="+(actors==null?"NOT_APPLICABLE_BASELINE":actors.snapshot()));}
        } catch(RuntimeException invalid) {
            ticks.sourceFailure("COHORT_SOURCE_FAILED:"+invalid.getClass().getSimpleName()); if(actors!=null)actors.sourceFailure("COHORT_SOURCE_FAILED:"+invalid.getClass().getSimpleName());
            System.err.println("P008_COHORT_QA_CAPTURE_SOURCE_FAILED preserve_partial=true "+invalid);
        }
    }
    private static int guard(MinecraftServer server) {
        require(server.isSameThread() && DirectorProductionCohortQa.requested() && !Boolean.getBoolean("proutlost.director.nativeLab"),"private actor context required");
        require(policy != null && DirectorProductionCohortQa.current(server).filter(p -> p == policy).isPresent(),"same current cohort policy required"); policy.requireWorld();
        int census=CohortPlayerCensus.count(allowedUuids,server.getPlayerList().getPlayers().stream().map(p -> new CohortPlayerCensus.Observed(p.getUUID(),p instanceof FakePlayer)).toList());
        if(!director())require(DirectorStandaloneMod.directorLifecycle().productionRuntime(server).isEmpty() && DirectorStandaloneMod.directorLifecycle().runtimePerceptionStore(server).isEmpty(),"BASELINE runtime or store appeared");
        if(director() && runtime!=null)require(DirectorStandaloneMod.directorLifecycle().productionRuntime(server).filter(r -> r==runtime).isPresent(),"observed runtime changed");
        if(identity!=null)require(server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().equals(Path.of(identity.get("world_root"))),"world path changed");
        return census;
    }
    private static void stop(){if(ticks!=null)ticks.stop(CompletedTickCapture.End.EXPLICIT_STOP);if(actors!=null)actors.stop(ActorFrameCapture.End.EXPLICIT_STOP);}
    private static void stopping(ServerStoppingEvent event){if(event.getServer()==owner)stop();}
    private static void stopped(ServerStoppedEvent event){if(event.getServer()==owner)stop();}
    private static void commands(RegisterCommandsEvent event){event.getDispatcher().register(Commands.literal("p008_cohort_capture").requires(s -> s.hasPermission(4)&&DirectorProductionCohortQa.requested())
        .then(Commands.literal("start").executes(c -> {try{begin(c.getSource().getServer());}catch(IOException failed){throw new IllegalStateException(failed);}return 1;}))
        .then(Commands.literal("status").executes(c -> {guard(c.getSource().getServer());c.getSource().sendSuccess(() -> Component.literal(ticks==null?"P008_COHORT_CAPTURE_NOT_STARTED":"vanilla="+ticks.snapshot()+" actor="+(actors==null?"NOT_APPLICABLE_BASELINE":actors.snapshot())),false);return 1;}))
        .then(Commands.literal("stop").executes(c -> {guard(c.getSource().getServer());require(ticks!=null,"owned capture required");stop();c.getSource().sendSuccess(() -> Component.literal("Capture draining; inspect durable run-end.json before JVM shutdown"),false);return 1;})));}
    private static String property(String key){String s=System.getProperty(PREFIX+key,"");require(!s.isBlank()&&s.length()<=1024,"bounded observer property: "+key);return s;}
    private static String shaProperty(String key){String s=property(key);require(s.matches("[0-9a-f]{64}"),"exact SHA256: "+key);return s;}
    private static Path absolute(String key){Path p=Path.of(property(key));require(p.isAbsolute()&&!Files.isSymbolicLink(p),"absolute unlinked path: "+key);return p;}
    private static long value(String key,long fallback,long low,long high){String raw=System.getProperty(PREFIX+key);long n=raw==null?fallback:Long.parseLong(raw);require(n>=low&&n<=high,"bounded observer value: "+key);return n;}
    private static String boundJar(String className,String pathKey,String shaKey)throws IOException,ReflectiveOperationException{
        var type=Class.forName(className,false,CohortQaObserverMod.class.getClassLoader());var jar=absolute(pathKey);require(Files.isRegularFile(jar,LinkOption.NOFOLLOW_LINKS)&&Files.size(jar)<=64L*1024*1024,"bounded candidate JAR");String entry=className.replace('.','/')+".class";
        try(var archive=new JarFile(jar.toFile());var loaded=type.getResourceAsStream("/"+entry)){require(loaded!=null&&archive.getJarEntry(entry)!=null,"loaded class identity missing");try(var packed=archive.getInputStream(archive.getJarEntry(entry))){byte[] a=loaded.readNBytes(8*1024*1024+1),b=packed.readNBytes(8*1024*1024+1);require(a.length<=8*1024*1024&&b.length<=8*1024*1024&&Arrays.equals(a,b),"loaded class differs from candidate");}}
        MessageDigest digest;try{digest=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}try(var in=Files.newInputStream(jar)){byte[] chunk=new byte[65536];for(int n;(n=in.read(chunk))!=-1;)digest.update(chunk,0,n);}String actual=HexFormat.of().formatHex(digest.digest());require(actual.equals(shaProperty(shaKey)),"candidate hash differs: "+pathKey);return actual;
    }
    private static void require(boolean yes,String why){if(!yes)throw new IllegalStateException(why);}
}
