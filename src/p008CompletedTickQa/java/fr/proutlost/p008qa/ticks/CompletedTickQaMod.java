package fr.proutlost.p008qa.ticks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
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
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Standalone observational QA addon. Does not import a Director port, grant a
 * capability, open J04, submit admission, change player state or mutate a world.
 * It refuses to load without an explicit TEST_ONLY flag and an exact world marker.
 */
@Mod(CompletedTickQaMod.MOD_ID)
public final class CompletedTickQaMod {
    public static final String MOD_ID="proutlost_p008_ticks_qa";
    private static final String PREFIX="proutlost.qa.completedTicks.";
    private static final String MARKER="P008_COMPLETED_TICKS_TEST_ONLY";
    private static final String MARKER_TEXT="PROUTLOST_P008_COMPLETED_TICKS_QA_V1";
    private static CompletedTickCapture capture;
    private static MinecraftServer owner;
    private static int maxPlayers;
    private static long lastProgress=-1;

    public CompletedTickQaMod(){
        require(Boolean.getBoolean("proutlost.qa.completedTicks")&&!FMLEnvironment.dist.isClient(),"explicit dedicated TEST_ONLY completed-tick observer required");
        NeoForge.EVENT_BUS.addListener(CompletedTickQaMod::started);
        NeoForge.EVENT_BUS.addListener(CompletedTickQaMod::post);
        NeoForge.EVENT_BUS.addListener(CompletedTickQaMod::stopping);
        NeoForge.EVENT_BUS.addListener(CompletedTickQaMod::stopped);
        NeoForge.EVENT_BUS.addListener(CompletedTickQaMod::commands);
    }
    private static void started(ServerStartedEvent event){
        var server=event.getServer();require(server.isSameThread()&&capture==null&&owner==null,"one new QA run per JVM");
        try{
            Path requested=absolute("worldRoot"),actual=server.getWorldPath(LevelResource.ROOT).toRealPath();
            require(requested.toRealPath().equals(actual)&&!Files.isSymbolicLink(requested),"exact isolated QA world root required");
            Path marker=actual.resolve(MARKER);
            require(Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(marker)&&Files.size(marker)<=96&&Files.readString(marker).trim().equals(MARKER_TEXT),"explicit isolated TEST_ONLY marker required");
            require(Boolean.getBoolean("proutlost.director.nativeLab")&&server.getPort()==25579&&server.getPlayerList().isUsingWhitelist(),"existing private native laboratory required");
            maxPlayers=integer("maxPlayers",2,2,2);checkPlayers(server);
            Path parent=absolute("outputParent");require(!parent.toRealPath().startsWith(actual)&&!actual.startsWith(parent.toRealPath()),"output parent must be separate from the QA world");
            var identity=new LinkedHashMap<String,String>();
            identity.put("world_root",actual.toString());identity.put("run_id",property("runId"));
            identity.put("main_jar_sha",boundJar("fr.proutlost.Proutlost","mainJar","mainSha256"));
            identity.put("director_jar_sha",boundJar("fr.proutlost.director.neoforge.DirectorStandaloneMod","directorJar","directorSha256"));
            identity.put("collector_jar_sha",boundJar(CompletedTickQaMod.class.getName(),"collectorJar","collectorSha256"));
            identity.put("qualified_sources_sha",shaProperty("sourcesSha256"));
            identity.put("client_manifest_sha",shaProperty("clientManifestSha256"));
            identity.put("java_pid",Long.toString(ProcessHandle.current().pid()));
            identity.put("process_started_utc",ProcessHandle.current().info().startInstant().orElseThrow().toString());
            identity.put("declared_scope","AT_MOST_TWO_SERVER_PLAYERS_NO_J06_OR_CLIENT_ACTIVITY_ATTESTATION");
            require(maxPlayers==2,"this frozen binary scenario is exactly the existing two-client laboratory");
            var limits=new CompletedTickCapture.Limits(integer("durationSeconds",900,1,32_400),
                longValue("maxSamples",650_000,1,650_000),longValue("maxBytes",128L*1024*1024,131_072,128L*1024*1024),integer("queueCapacity",4096,1,4096));
            capture=CompletedTickCapture.open(parent,property("runId"),limits,Map.copyOf(identity));owner=server;
            capture.completion().whenComplete((path,error)->System.out.println(error==null?
                "P008_COMPLETED_TICK_CAPTURE_END_WRITTEN inspect_manifest=true path="+path:
                "P008_COMPLETED_TICK_CAPTURE_END_INCOMPLETE inspect_start_and_csv=true"));
            System.out.println("P008_COMPLETED_TICK_CAPTURE_STARTED TEST_ONLY=true source=PREVIOUS_COMPLETED_VANILLA_TICK cpu=NOT_MEASURED root="+capture.root());
        }catch(IOException|ReflectiveOperationException failed){throw new IllegalStateException("QA completed-tick identity/world guard failed",failed);}
    }
    private static void post(ServerTickEvent.Post event){
        var c=capture;var server=event.getServer();if(c==null||server!=owner||!c.snapshot().accepting())return;
        require(server.isSameThread(),"completed-tick server actor");
        try{
            checkPlayers(server);
            PreviousCompletedTickReader.observe(server.getTickCount(),server.getTickTimesNanos(),System.nanoTime(),server.getPlayerList().getPlayers().size(),c);
            var state=c.snapshot();
            if(state.sourceSamples()-lastProgress>=1200){lastProgress=state.sourceSamples();System.out.println("P008_COMPLETED_TICK_CAPTURE_PROGRESS written="+state.written()+" queue="+state.queueSize()+" dropped="+state.queueDropped()+" gaps="+state.sourceGapTicks()+" end_not_yet_verified=true");}
        }catch(RuntimeException failed){c.sourceFailure("NATIVE_OBSERVER_FAILED:"+failed.getClass().getSimpleName());System.err.println("P008_COMPLETED_TICK_CAPTURE_SOURCE_FAILED capture_incomplete=true");}
    }
    private static void checkPlayers(MinecraftServer server){
        var players=server.getPlayerList().getPlayers();require(players.size()<=maxPlayers&&players.stream().noneMatch(p->p instanceof FakePlayer),"frozen two-real-client QA observer player bound");
    }
    private static void stopping(ServerStoppingEvent event){if(event.getServer()==owner&&capture!=null)capture.stop(CompletedTickCapture.End.SERVER_STOP);}
    private static void stopped(ServerStoppedEvent event){if(event.getServer()==owner&&capture!=null)capture.stop(CompletedTickCapture.End.SERVER_STOP);}
    private static void commands(RegisterCommandsEvent event){
        event.getDispatcher().register(Commands.literal("p008_tick_capture").requires(s->s.hasPermission(4))
            .then(Commands.literal("status").executes(context->{
                String state=capture==null?"P008_COMPLETED_TICK_CAPTURE_NOT_STARTED":capture.snapshot().toString();
                context.getSource().sendSuccess(()->Component.literal(state),false);return 1;
            })).then(Commands.literal("stop").executes(context->{
                require(capture!=null&&context.getSource().getServer()==owner,"owned QA capture required");
                capture.stop(CompletedTickCapture.End.EXPLICIT_STOP);
                context.getSource().sendSuccess(()->Component.literal("P008 capture stopping asynchronously; inspect run-end.json before accepting any result"),false);return 1;
            })));
    }
    private static Path absolute(String suffix){Path p=Path.of(property(suffix));require(p.isAbsolute()&&!Files.isSymbolicLink(p),"absolute unlinked QA path: "+suffix);return p;}
    private static String property(String suffix){String value=System.getProperty(PREFIX+suffix,"");require(!value.isBlank()&&value.length()<=1024,"explicit bounded property: "+suffix);return value;}
    private static String shaProperty(String suffix){String value=property(suffix);require(value.matches("[a-f0-9]{64}"),"SHA256 property: "+suffix);return value;}
    private static long longValue(String suffix,long fallback,long low,long high){String value=System.getProperty(PREFIX+suffix);long result=value==null?fallback:Long.parseLong(value);require(result>=low&&result<=high,"bounded QA property: "+suffix);return result;}
    private static int integer(String suffix,int fallback,int low,int high){return Math.toIntExact(longValue(suffix,fallback,low,high));}
    private static String boundJar(String className,String pathKey,String shaKey)throws IOException,ReflectiveOperationException{
        var type=Class.forName(className,false,CompletedTickQaMod.class.getClassLoader());var jar=absolute(pathKey);
        require(Files.isRegularFile(jar,LinkOption.NOFOLLOW_LINKS)&&Files.size(jar)<=64L*1024*1024,"bounded candidate JAR");
        String entry=className.replace('.','/')+".class";
        try(var archive=new JarFile(jar.toFile());var loaded=type.getResourceAsStream("/"+entry)){
            require(loaded!=null&&archive.getJarEntry(entry)!=null,"loaded identity class missing");
            try(var bytes=archive.getInputStream(archive.getJarEntry(entry))){
                byte[] actual=loaded.readNBytes(8*1024*1024+1),packed=bytes.readNBytes(8*1024*1024+1);
                require(actual.length<=8*1024*1024&&packed.length<=8*1024*1024&&Arrays.equals(actual,packed),"loaded class differs from named candidate JAR");
            }
        }
        var digest=sha();try(var stream=Files.newInputStream(jar)){byte[] chunk=new byte[65536];for(int n;(n=stream.read(chunk))>=0;)digest.update(chunk,0,n);}
        String result=HexFormat.of().formatHex(digest.digest());require(result.equals(shaProperty(shaKey)),"candidate SHA256 mismatch: "+pathKey);return result;
    }
    private static MessageDigest sha(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static void require(boolean ok,String why){if(!ok)throw new IllegalStateException(why);}
}
