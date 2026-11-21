package fr.proutlost.director.adapter.neoforge.server;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.bootstrap.*;
import fr.proutlost.director.integration.locations.*;
import fr.proutlost.director.neoforge.DirectorStandaloneMod;
import fr.proutlost.director.persistence.JournalStore;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Read-only observer of the existing native admission and actual memory readers.
 * It never submits an import, opens a writer, appends a record, creates a player,
 * changes a player's position/permission or grants production/canonical authority.
 * The operator supplies declared synthetic input, runs the actual OP4 command
 * (or the approved production bootstrap path), and moves a real connected QA
 * client between the two logged positions. The sidecar is observer evidence,
 * never an import ACK. This class is absent from the shipped Director JAR.
 */
@GameTestHolder("proutlost_director")
@PrefixGameTestTemplate(false)
public final class DirectorNativePagedLocationImportGameTests {
    private static final String PREFIX="proutlost.director.nativePagedLocationImport";
    private static final long PID=ProcessHandle.current().pid();
    private static final long START=ManagementFactory.getRuntimeMXBean().getStartTime();
    private static final ModelCodec<LocationImport> IMPORT=new ModelCodec<>(LocationImport.class);
    private static final Map<MinecraftServer,InstalledRun> INSTALLED_RUNS=new IdentityHashMap<>();
    private static boolean installed;
    private DirectorNativePagedLocationImportGameTests(){}
    /** Fixed reflection entry point called ONLY by the separate TEST_ONLY addon.
     * Production GameTestHooks is deliberately left unchanged. Both execution
     * lanes below use this class's same read-only Observer implementation. */
    public static synchronized void install(){
        require(Boolean.getBoolean("proutlost.director.p008InstalledQaObserver"),"explicit installed TEST_ONLY observer flag required");
        flags();require(!installed,"duplicate installed QA observer bootstrap");installed=true;
        NeoForge.EVENT_BUS.addListener(DirectorNativePagedLocationImportGameTests::registerInstalled);
        NeoForge.EVENT_BUS.addListener(DirectorNativePagedLocationImportGameTests::tickInstalled);
        NeoForge.EVENT_BUS.addListener(DirectorNativePagedLocationImportGameTests::stoppedInstalled);
        System.out.println("P008_INSTALLED_OBSERVER_REGISTERED TEST_ONLY=true writer=false gametest_hooks_modified=false");
    }
    private static void flags(){
        require(Boolean.getBoolean("proutlost.director.nativeLab")&&Boolean.getBoolean("proutlost.director.nativeGameTests")
            &&Boolean.getBoolean("proutlost.quest.neutralLab")&&Boolean.getBoolean(PREFIX+"GameTest"),"explicit native/neutral TEST_ONLY observer flags required");
    }
    private static void guardInstalled(MinecraftServer server){
        flags();require(Boolean.getBoolean("proutlost.director.p008InstalledQaObserver")&&server.isSameThread(),"installed QA actor/flag required");
        require(server.getPort()==25579&&server.getPlayerList().isUsingWhitelist(),"isolated native QA port25579 and whitelist required");
        var id=UUID.fromString(property("Operator"));var player=server.getPlayerList().getPlayer(id);
        require(player!=null&&player.hasPermissions(4),"actual connected configured OP4 QA client required: "+id);
        try{
            Path requested=Path.of(System.getProperty("proutlost.quest.neutralLabRoot",""));
            Path actual=server.getWorldPath(LevelResource.ROOT).toRealPath();
            require(requested.isAbsolute()&&requested.toRealPath().equals(actual),"exact configured neutral QA world required");
            require(new String(read(actual.resolve("TEST_ONLY"),128),StandardCharsets.UTF_8).trim().equals("PROUTLOST_NEUTRAL_QA_V1")
                &&!Files.exists(actual.resolve("data/proutlost_quest_v1.dat"),LinkOption.NOFOLLOW_LINKS),"neutral marker required and canonical campaign must be absent");
        }catch(IOException failure){throw new UncheckedIOException(failure);}
    }
    private static void registerInstalled(RegisterCommandsEvent event){
        var observe=Commands.literal("observe");
        for(boolean cold:List.of(false,true))observe.then(Commands.literal(cold?"cold":"first").executes(context->{
            var server=context.getSource().getServer();guardInstalled(server);
            require(cold==Boolean.getBoolean(PREFIX+"RecoveryOnly"),"command mode differs from explicit JVM recovery mode");
            require(!INSTALLED_RUNS.containsKey(server),"preserve existing observer run; inspect status or use a new JVM");
            var run=new InstalledRun(new Observer(server,cold));INSTALLED_RUNS.put(server,run);
            String status=run.status();System.out.println(status);context.getSource().sendSuccess(()->Component.literal(status),false);return 1;
        }));
        observe.then(Commands.literal("status").executes(context->{
            var server=context.getSource().getServer();guardInstalled(server);var run=INSTALLED_RUNS.get(server);
            String status=run==null?"P008_INSTALLED_OBSERVER_NOT_STARTED source_sha256="+property("Sha256"):run.status();
            context.getSource().sendSuccess(()->Component.literal(status),false);return 1;
        }));
        event.getDispatcher().register(Commands.literal("proutlost_p008_qa").requires(source->
            Boolean.getBoolean("proutlost.director.p008InstalledQaObserver")&&source.hasPermission(4)).then(observe));
    }
    private static void tickInstalled(ServerTickEvent.Post event){
        var run=INSTALLED_RUNS.get(event.getServer());if(run==null||run.finished())return;
        try{
            guardInstalled(event.getServer());
            require(event.getServer().getTickCount()-run.startTick<12000&&System.nanoTime()-run.startedNanos<TimeUnit.MINUTES.toNanos(20),"installed observer timeout");
            run.observer.poll();
            if(!run.lastPhase.equals(run.observer.phase)||run.observer.done){run.lastPhase=run.observer.phase;System.out.println(run.status());}
        }catch(RuntimeException failure){run.fail(failure);}
    }
    private static void stoppedInstalled(ServerStoppedEvent event){
        var run=INSTALLED_RUNS.remove(event.getServer());if(run==null)return;
        if(!run.finished())run.fail(new IllegalStateException("server stopped before observer completion"));
        run.observer.io.shutdown();
    }
    private static final class InstalledRun {
        final Observer observer;final int startTick;final long startedNanos=System.nanoTime();String lastPhase="",failure="";
        InstalledRun(Observer observer){this.observer=observer;startTick=observer.server.getTickCount();}
        boolean finished(){return observer.done||!failure.isEmpty();}
        void fail(RuntimeException cause){failure=cause.toString().replace('\n',' ').replace('\r',' ');System.out.println(status());observer.io.shutdown();}
        String status(){
            var expected=observer.expected;var session=observer.session;
            return "P008_INSTALLED_OBSERVER_"+(!failure.isEmpty()?"FAILED":observer.done?"PASS":"PENDING")
                +" cold="+observer.cold+" phase="+observer.phase+" source_sha256="+property("Sha256")
                +" source="+property("Source")+" export_digest="+(expected==null?"PENDING":expected.input().exportDigest().value())
                +" generation="+(expected==null?"PENDING":expected.generation().value())
                +" world="+(session==null?"PENDING":session.worldId().value())+" boot_epoch="+(session==null?"PENDING":session.bootEpoch().value())
                +" authority_session="+(session==null?"PENDING":session.authoritySession().value())
                +" cut="+(observer.snapshot==null?"PENDING":observer.snapshot.cut().value())+" heads="+observer.verifiedHeads
                +" native_admission=["+observer.nativeStatus+"]"
                +" first_memory_cut="+observer.firstMemoryCut+" far_memory_cut="+observer.farMemoryCut
                +" readback_run_id="+readbackRunId()+" pending_boundary="+(expected==null?"PENDING":expected.pending().map(Pending::boundary).orElse("NONE"))
                +" pid="+PID+" process_start="+START+" scope=TECHNICAL_SYNTHETIC_QA_ONLY"
                +(failure.isEmpty()?"":" failure="+failure);
        }
    }
    @GameTestGenerator public static Collection<TestFunction> pagedImportObserver(){
        if(!Boolean.getBoolean(PREFIX+"GameTest"))return List.of();
        require(Boolean.getBoolean("proutlost.director.nativeLab")&&Boolean.getBoolean("proutlost.director.nativeGameTests")
            &&Boolean.getBoolean("proutlost.quest.neutralLab"),"explicit native/neutral TEST_ONLY flags required");
        boolean cold=Boolean.getBoolean(PREFIX+"RecoveryOnly");
        return List.of(new TestFunction("p008_paged_native_import",
            cold?"p008_paged_import_same_directory_cold_and_selected_readers":"p008_paged_import_actual_j04_and_selected_readers",
            "proutlost_director:smoke_empty",Rotation.NONE,12000,0,true,h->{
                var observer=new Observer(h.getLevel().getServer(),cold);
                h.succeedWhen(()->{observer.poll();h.assertTrue(observer.done,"P008 native paged observer pending: "+observer.phase);});
            }));
    }
    private static void require(boolean value,String reason){if(!value)throw new IllegalStateException("P008 paged observer: "+reason);}
    private static String property(String suffix){String value=System.getProperty(PREFIX+suffix,"").strip();require(!value.isEmpty(),"required property "+PREFIX+suffix);return value;}
    private static String fileKey(Path path)throws IOException{return Objects.toString(Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey(),"");}
    private static byte[] read(Path path,int maximum)throws IOException{
        for(Path p=path;p!=null;p=p.getParent())require(!Files.isSymbolicLink(p),"symlink fixture/evidence path");
        require(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)&&Files.size(path)>0&&Files.size(path)<=maximum,"bounded fixture/evidence file required");
        try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){byte[] bytes=input.readNBytes(maximum+1);require(bytes.length<=maximum,"fixture grew above bound");return bytes;}
    }
    private static Aabb box(DimensionId d,ImportBounds b){return new Aabb(d,b.minX(),b.minY(),b.minZ(),b.maxXExclusive(),b.maxYExclusive(),b.maxZExclusive());}
    private static boolean contains(ImportBounds b,int x,int y,int z){return x>=b.minX()&&x<b.maxXExclusive()&&y>=b.minY()&&y<b.maxYExclusive()&&z>=b.minZ()&&z<b.maxZExclusive();}
    private record Pending(String name,LocationTransferManifest manifest,String boundary){}
    private record PendingProof(Tick clock,Set<Integer> sourcePages,Set<Integer> metadataPages,int activations,int cancellations,int journalPages,long cut,int staleRestarts){}
    private record Expected(Path root,String rootKey,String name,String sourceSha,LocationImport input,
            LocationTransferManifest manifest,Fingerprint generation,LocationImport other,Properties prior,Optional<Pending> pending){}
    private static Expected expected(Path configuredWorld,String name,String expectedSha,String source,boolean cold)throws IOException{
        Path root=configuredWorld.toRealPath(),configured=Path.of(System.getProperty("proutlost.quest.neutralLabRoot","")).toRealPath();
        require(root.equals(configured),"world must equal explicitly assigned neutral QA root");
        require(new String(read(root.resolve("TEST_ONLY"),128),StandardCharsets.UTF_8).trim().equals("PROUTLOST_NEUTRAL_QA_V1"),"exact neutral TEST_ONLY marker required");
        String key=fileKey(root);require(!key.isEmpty(),"observable QA directory file identity required");
        byte[] bytes=read(root.resolve("director-location-imports").resolve(name+"-expected.json"),LocationTransfer.SOURCE_BYTES);
        require(BlobContent.hash(bytes).value().equals(expectedSha),"exact operator-specified source SHA differs");
        var input=IMPORT.decode(bytes);
        require(Arrays.equals(bytes,IMPORT.encode(input))&&input.sourceNamespace().value().equals(source)
            &&source.startsWith("qa:")&&input.exportDigest().equals(StandaloneLocationImporter.digest(input)),"declared synthetic canonical G0 source identity");
        require(input.regions().size()==80&&input.locations().size()==600&&input.anchors().size()==600,"declared 80/600/600 QA scenario required");
        byte[] raw=read(root.resolve("director-location-imports").resolve(name).resolve("manifest.json"),LocationTransfer.WIRE_BYTES);
        var manifest=new ModelCodec<>(LocationTransferManifest.class).decode(raw);
        require(Arrays.equals(raw,new ModelCodec<>(LocationTransferManifest.class).encode(manifest))&&manifest.pageHashes().size()>1,"canonical multi-page transfer manifest required");
        LocationTransfer.matches(manifest,input,bytes);
        Properties prior=new Properties();Path first=sidecar(root,expectedSha,false);
        if(cold)try(var in=new ByteArrayInputStream(read(first,16384))){prior.load(in);}
        else require(!Files.exists(first,LinkOption.NOFOLLOW_LINKS),"preserve first observer sidecar; cold run requires RecoveryOnly=true");
        byte[] otherBytes=read(root.resolve("director-location-imports/p008_other_transfer-expected.json"),LocationTransfer.WIRE_BYTES);
        var other=IMPORT.decode(otherBytes);
        require(Arrays.equals(otherBytes,IMPORT.encode(other))&&other.worldId().equals(input.worldId())
            &&other.sourceNamespace().value().equals("qa:p008_l2_other")&&!other.sourceNamespace().equals(input.sourceNamespace())
            &&other.exportDigest().equals(StandaloneLocationImporter.digest(other))
            &&other.regions().size()==1&&other.locations().size()==1&&other.anchors().size()==1,"explicit disjoint other-source fixture required");
        String pendingName=System.getProperty(PREFIX+"PendingTransferName","").strip();
        String boundary=System.getProperty(PREFIX+"ExpectedPendingBoundary","").strip();
        require(pendingName.isEmpty()==boundary.isEmpty(),"pending transfer and boundary properties must be supplied together");
        Optional<Pending> pending=Optional.empty();
        if(!pendingName.isEmpty()){
            require(cold&&pendingName.matches("[a-z0-9][a-z0-9_-]{0,54}_transfer")&&Set.of("clock","source","stage").contains(boundary),"explicit cold pending-generation observation boundary required");
            byte[] pendingBytes=read(root.resolve("director-location-imports").resolve(pendingName).resolve("manifest.json"),LocationTransfer.WIRE_BYTES);
            var proposed=new ModelCodec<>(LocationTransferManifest.class).decode(pendingBytes);
            require(Arrays.equals(pendingBytes,new ModelCodec<>(LocationTransferManifest.class).encode(proposed))
                &&proposed.worldId().equals(input.worldId())&&proposed.sourceNamespace().equals(input.sourceNamespace())
                &&proposed.exportRevision().compareTo(input.exportRevision())>0,"canonical pending manifest must be newer than same-world/source active input");
            pending=Optional.of(new Pending(pendingName,proposed,boundary));
        }
        return new Expected(root,key,name,expectedSha,input,manifest,LocationTransfer.identity(manifest),other,prior,pending);
    }
    private static String readbackRunId(){String id=System.getProperty(PREFIX+"ReadbackRunId","").strip();require(id.isEmpty()||id.matches("[a-z0-9_-]{1,48}"),"bounded optional readback run id required");return id;}
    private static Path sidecar(Path root,String sha,boolean cold){String id=readbackRunId();return root.resolve("P008-paged-native-"+sha.substring(0,16)+(cold?"-cold"+(id.isEmpty()?"":"-"+id):"-first")+".properties");}
    /** Chronology is read through the existing writer, one128-row page at a
     * time off the actor. STALE restarts the entire census; no missing/cache
     * result can establish absence, and duplicate durable pages count once. */
    private static PendingProof scanPending(JournalStore store,StoreSession session,LocationTransferManifest transfer){
        for(int attempt=0;attempt<16;attempt++){
            Map<Integer,DurableMetadataSourcePage> sources=new HashMap<>();Map<Integer,DurableMetadataStage> stages=new HashMap<>();
            Set<DurableMetadataActivation> activations=new HashSet<>();Set<DurableMetadataCancellation> cancellations=new HashSet<>();
            Tick clock=null;Optional<String> cursor=Optional.empty();long cut=-1;boolean stale=false;
            for(int pages=1;pages<=2048;pages++){
                require(session.equals(store.session()),"pending chronology J04 session changed");
                var query=new StoreQuery(session.worldId(),Set.of(),new Sequence(0),cursor,128);
                var result=store.read(query).toCompletableFuture().join();ReadContracts.validate(result,query,session);
                if(result.status()==ReadStatus.STALE){stale=true;break;}
                require(result.status()==ReadStatus.AVAILABLE,"complete pending chronology unavailable: "+result.status());
                var page=result.page().orElseThrow();require(page.aggregates().isEmpty()&&page.records().size()<=128,"bounded chronological record page required");
                if(cut<0)cut=page.lastDurableSequence().value();require(cut==page.lastDurableSequence().value(),"pending chronology mixed cuts");
                for(var record:page.records()){
                    if(record instanceof DurableMetadataSourcePage source&&source.transfer().equals(transfer)){
                        var old=sources.putIfAbsent(source.pageIndex(),source);require(old==null||old.equals(source),"conflicting durable pending source page");
                    }else if(record instanceof DurableMetadataStage stage&&stage.transfer().equals(transfer)){
                        var old=stages.putIfAbsent(stage.pageIndex(),stage);require(old==null||old.equals(stage),"conflicting durable pending metadata page");
                    }else if(record instanceof DurableMetadataActivation activation&&activation.transfer().equals(transfer))activations.add(activation);
                    else if(record instanceof DurableMetadataCancellation cancellation&&cancellation.transfer().equals(transfer))cancellations.add(cancellation);
                    else if(record instanceof DurableFact fact&&fact.value().factType().equals(MetadataPreparationClock.TYPE)){
                        var prepared=MetadataPreparationClock.decode(fact.value(),session.worldId()).orElseThrow();
                        if(prepared.transfer().equals(transfer)){require(clock==null||clock.equals(prepared.tick()),"conflicting durable pending preparation clocks");clock=prepared.tick();}
                    }
                }
                if(!page.hasMore()){
                    require(clock!=null,"complete chronology has no pending preparation clock fact");
                    return new PendingProof(clock,Set.copyOf(sources.keySet()),Set.copyOf(stages.keySet()),activations.size(),cancellations.size(),pages,cut,attempt);
                }
                cursor=page.nextCursor();
            }
            require(stale,"pending chronology exceeded bounded2048-page census");
        }
        throw new IllegalStateException("P008 pending chronology remained STALE across16 full restarts; absence not proved");
    }
    private static final class Observer {
        final MinecraftServer server;final DirectorServerLifecycle life;final boolean cold;final UUID operator;
        final ExecutorService io=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"p008-paged-observer-io");t.setDaemon(true);return t;});
        final CompletableFuture<Expected> loading;
        Expected expected;JournalStore store;StoreSession session;JournalStore.MetadataSnapshot snapshot;
        CompletableFuture<Optional<LocationImport>> sourceRead,otherRead;CompletableFuture<StoreReadResult> headRead,otherHeads;
        CompletableFuture<Void> checkpoint;StoreQuery query;List<AggregateKey> keys;int offset,headPages,verifiedHeads;
        CompletableFuture<PendingProof> pendingRead;CompletableFuture<Optional<MetadataGenerationPage>> pendingPage;PendingProof pendingProof;boolean pendingVerified;
        Map<RegionId,ImportedRegion> regions;Map<LocationId,ImportedLocation> locations;Map<AnchorId,ImportedAnchor> anchors;
        Map<RegionId,Set<AnchorId>> regionAnchors;Map<LocationId,Set<AnchorId>> locationAnchors;
        Set<AggregateKey> requested;ImportedAnchor first,far;RegionId firstRegion,farRegion;
        boolean sourceVerified,otherVerified,firstSelected,farRequested,done;long firstMemoryCut,farMemoryCut;String phase="LOADING_DECLARED_QA_FIXTURE",nativeStatus="PENDING";
        Observer(MinecraftServer actualServer,boolean cold){
            server=Objects.requireNonNull(actualServer);life=DirectorStandaloneMod.directorLifecycle();this.cold=cold;
            require(server.isSameThread(),"actual Minecraft actor required");operator=UUID.fromString(property("Operator"));
            require(cold||readbackRunId().isEmpty(),"readback run id changes only the cold evidence target");
            String name=property("Name"),sha=property("Sha256"),source=property("Source");
            require(name.matches("[a-z0-9][a-z0-9_-]{0,54}_transfer")&&sha.matches("[a-f0-9]{64}"),"explicit fixture name and SHA required");
            Path root=server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            loading=CompletableFuture.supplyAsync(()->{try{return expected(root,name,sha,source,cold);}catch(IOException failure){throw new UncheckedIOException(failure);}},io);
        }
        void initialize(){
            expected=loading.join();var input=expected.input();
            regions=new HashMap<>();locations=new HashMap<>();anchors=new HashMap<>();regionAnchors=new HashMap<>();locationAnchors=new HashMap<>();
            Set<AggregateKey> all=new HashSet<>();
            for(var r:input.regions()){
                regions.put(r.regionId(),r);all.add(new AggregateKey(AggregateKind.REGION,r.regionId().value()));all.add(new AggregateKey(AggregateKind.REGION_MEMORY,r.regionId().value()));
            }
            input.locations().forEach(l->{locations.put(l.locationId(),l);all.add(new AggregateKey(AggregateKind.LOCATION,l.locationId().value()));});
            for(var a:input.anchors()){
                anchors.put(a.anchorId(),a);all.add(new AggregateKey(AggregateKind.ANCHOR,a.anchorId().value()));
                locationAnchors.computeIfAbsent(a.locationId(),k->new HashSet<>()).add(a.anchorId());
                for(var r:locations.get(a.locationId()).regionIds())regionAnchors.computeIfAbsent(r,k->new HashSet<>()).add(a.anchorId());
            }
            keys=all.stream().sorted(Comparator.comparing(k->k.kind()+":"+k.id())).toList();require(keys.size()==1360,"complete QA head denominator");
            first=input.anchors().getFirst();far=input.anchors().get(79);
            firstRegion=locations.get(first.locationId()).regionIds().iterator().next();farRegion=locations.get(far.locationId()).regionIds().iterator().next();
            require(!firstRegion.equals(farRegion),"independent first/far regions required");phase="WAIT_ACTUAL_NATIVE_ADMISSION_TERMINAL";
        }
        void poll(){
            if(done)return;require(server.isSameThread(),"observer must remain on Minecraft actor");
            if(expected==null){if(!loading.isDone())return;initialize();}
            var gameplay=life.runtimeGameplay(server);var actualStore=life.runtimePerceptionStore(server);
            if(gameplay.isEmpty()||actualStore.isEmpty())return;
            String status=gameplay.orElseThrow().locationImportStatus();nativeStatus=status;require(!status.contains("FAILED_SAFE"),"actual native import failed safe: "+status);
            boolean terminal=cold?status.endsWith(" ALREADY_DURABLE"):
                status.endsWith(" DURABLE")||status.endsWith(" ALREADY_DURABLE");
            if(!terminal||gameplay.orElseThrow().metadataLaneBusy())return;
            if(store==null){
                var current=actualStore.orElseThrow();var available=current.metadataSnapshot();if(available.isEmpty())return;
                store=current;session=store.session();require(session.worldId().equals(expected.input().worldId()),"QA source world differs from actual J04");snapshot=available.orElseThrow();
                require(expected.generation().equals(snapshot.generations().get(expected.input().sourceNamespace()))
                    &&expected.manifest().equals(snapshot.sources().get(expected.input().sourceNamespace())),"actual J04 active generation/manifest differs");
                sourceRead=store.readMetadataSource(snapshot,expected.input().sourceNamespace()).toCompletableFuture();
                var otherManifest=LocationTransfer.prepare(expected.other()).manifest();
                require(otherManifest.equals(snapshot.sources().get(expected.other().sourceNamespace())),"previous other-source generation absent or replaced");
                otherRead=store.readMetadataSource(snapshot,expected.other().sourceNamespace()).toCompletableFuture();
                var other=expected.other();var otherKeys=Set.of(
                    new AggregateKey(AggregateKind.REGION,other.regions().getFirst().regionId().value()),
                    new AggregateKey(AggregateKind.REGION_MEMORY,other.regions().getFirst().regionId().value()),
                    new AggregateKey(AggregateKind.LOCATION,other.locations().getFirst().locationId().value()),
                    new AggregateKey(AggregateKind.ANCHOR,other.anchors().getFirst().anchorId().value()));
                otherHeads=store.readMetadata(snapshot,new StoreQuery(session.worldId(),otherKeys,new Sequence(0),Optional.empty(),96)).toCompletableFuture();
                if(expected.pending().isPresent()){
                    var pending=expected.pending().orElseThrow();
                    pendingRead=CompletableFuture.supplyAsync(()->scanPending(store,session,pending.manifest()),io);
                    pendingPage=store.readStagedMetadataPage(pending.manifest(),0).toCompletableFuture();
                }
            }
            require(store==actualStore.orElseThrow()&&session.equals(store.session()),"existing J04 owner/session changed during observer");
            var active=store.metadataSnapshot();if(active.isEmpty())return;
            require(active.orElseThrow().generations().equals(snapshot.generations())&&active.orElseThrow().sources().equals(snapshot.sources()),"source generations/manifests changed during pinned QA read");
            require(store.metadataCachedEntries()<=512,"actual J04 metadata cache above512");
            if(!sourceVerified){
                if(!sourceRead.isDone())return;require(sourceRead.join().filter(expected.input()::equals).isPresent(),"J04 complete original source read differs");
                sourceRead=null;sourceVerified=true;phase="VERIFYING_ALL_1360_NATIVE_HEADS";
            }
            if(!otherVerified){
                if(!otherRead.isDone()||!otherHeads.isDone())return;
                require(otherRead.join().filter(expected.other()::equals).isPresent(),"previous other-source original bytes differ");
                var other=expected.other();var result=otherHeads.join();
                require(result.status()==ReadStatus.AVAILABLE&&result.page().isPresent(),"other-source heads unavailable");
                var page=result.page().orElseThrow();
                require(!page.hasMore()&&page.aggregates().size()==4&&page.lastDurableSequence().equals(snapshot.cut()),"four prior source heads retained at pinned cut");
                for(var row:page.aggregates()){
                    require(store.metadataOwner(snapshot,row.state().key()).filter(other.sourceNamespace()::equals).isPresent(),"prior source head ownership changed");
                    boolean valid=switch(row.state()){
                        case RegionAggregate value->value.value().regionId().equals(other.regions().getFirst().regionId())&&value.value().status()==RegionStatus.ACTIVE;
                        case RegionMemoryAggregate value->value.value().regionId().equals(other.regions().getFirst().regionId());
                        case LocationAggregate value->value.value().locationId().equals(other.locations().getFirst().locationId())&&value.value().active();
                        case AnchorAggregate value->value.value().anchorId().equals(other.anchors().getFirst().anchorId())&&value.value().active();
                        default->false;
                    };require(valid,"prior source head disappeared or was retired");
                }
                otherRead=null;otherHeads=null;otherVerified=true;
                System.out.println("P008_NATIVE_OTHER_SOURCE_RETAINED_PASS cold="+cold+" heads=4 source="+other.sourceNamespace().value()+" cut="+snapshot.cut().value());
            }
            if(expected.pending().isPresent()){
                if(!pendingVerified){
                    phase="VERIFYING_PENDING_GENERATION_BOUNDARY";if(!pendingRead.isDone()||!pendingPage.isDone())return;
                    pendingProof=pendingRead.join();checkPending();
                    var pending=expected.pending().orElseThrow();var page=pendingPage.join();
                    require(page.isPresent()==pending.boundary().equals("stage"),"pending metadata page0 visibility differs from exact boundary");
                    page.ifPresent(value->require(value.generation().equals(LocationTransfer.identity(pending.manifest()))
                        &&value.sourceNamespace().equals(pending.manifest().sourceNamespace())&&value.exportRevision().equals(pending.manifest().exportRevision())
                        &&value.exportDigest().equals(pending.manifest().exportDigest())&&value.pageIndex()==0&&!value.states().isEmpty()
                        &&value.states().size()<=96&&value.beforeRevisions().size()<=96,"actual hidden metadata page0 binding/capacity differs"));
                    pendingVerified=true;pendingRead=null;pendingPage=null;
                    System.out.println("P008_NATIVE_PENDING_GENERATION_INVISIBLE_PASS boundary="+pending.boundary()+" active_generation="+expected.generation().value()
                        +" pending_generation="+LocationTransfer.identity(pending.manifest()).value()+" preparation_tick="+pendingProof.clock().value()
                        +" distinct_source_pages="+new TreeSet<>(pendingProof.sourcePages())+" distinct_metadata_pages="+new TreeSet<>(pendingProof.metadataPages())
                        +" activations="+pendingProof.activations()+" cancellations="+pendingProof.cancellations()+" journal_pages="+pendingProof.journalPages()
                        +" journal_cut="+pendingProof.cut()+" stale_restarts="+pendingProof.staleRestarts()+" readback_run_id="+readbackRunId()+" synthetic=true canonical=false");
                }
                checkPending();
            }
            if(offset<keys.size()){
                if(headRead==null){
                    var pageKeys=keys.subList(offset,Math.min(offset+96,keys.size()));requested=Set.copyOf(pageKeys);
                    query=new StoreQuery(session.worldId(),requested,new Sequence(0),Optional.empty(),96);
                    headRead=store.readMetadata(snapshot,query).toCompletableFuture();return;
                }
                if(!headRead.isDone())return;var result=headRead.join();headRead=null;ReadContracts.validate(result,query,session);
                require(result.status()==ReadStatus.AVAILABLE,"native metadata head page not available: "+result.status());
                var page=result.page().orElseThrow();require(page.records().isEmpty()&&!page.hasMore()&&page.lastDurableSequence().equals(snapshot.cut()),"native head page wrong kind/cut/truncation");
                Set<AggregateKey> missing=new HashSet<>(requested);require(page.aggregates().size()==requested.size()&&page.aggregates().size()<=96,"exact bounded native head page required");
                for(var row:page.aggregates()){require(missing.remove(row.state().key()),"duplicate/foreign native head");verify(row.state());verifiedHeads++;}
                require(missing.isEmpty(),"native source head missing");offset+=requested.size();headPages++;
                if(offset==keys.size())System.out.println("P008_NATIVE_PAGED_ALL_HEADS_PASS cold="+cold+" heads="+verifiedHeads+" pages="+headPages+" cut="+snapshot.cut().value()+" cache="+store.metadataCachedEntries()+" scope=TECHNICAL_SYNTHETIC_QA_ONLY");
                return;
            }
            if(checkpoint!=null){
                if(!checkpoint.isDone())return;checkpoint.join();
                System.out.println("P008_NATIVE_PAGED_IMPORT_AND_SELECTED_READERS_PASS cold="+cold+" world="+session.worldId().value()
                    +" boot_epoch="+session.bootEpoch().value()+" generation="+expected.generation().value()+" source_sha256="+expected.sourceSha()
                    +" heads="+verifiedHeads+" pages="+headPages+" first_memory_cut="+firstMemoryCut+" far_memory_cut="+farMemoryCut
                    +" metadata_cache="+store.metadataCachedEntries()+" synthetic=true canonical=false human_reception=false");
                done=true;phase="PASS_SCOPED_NATIVE_QA";io.shutdown();return;
            }
            var player=server.getPlayerList().getPlayer(operator);
            if(player==null||!player.hasPermissions(4)){phase="WAIT_REAL_CONNECTED_OP4_QA_CLIENT "+operator;return;}
            if(!firstSelected){
                phase="WAIT_FIRST_REAL_CLIENT_DEMAND position="+position(first);
                if(!inside(player,firstRegion)||!selected(first,firstRegion))return;
                var memory=life.runtimeMemoryView(server).orElseThrow();
                if(memory.regionRecords().containsKey(farRegion)){phase="WAIT_OLD_FAR_DEMAND_TO_DRAIN";return;}
                firstMemoryCut=life.memoryProjectionView(server).orElseThrow().lastDurableSequence();firstSelected=true;
                System.out.println("P008_NATIVE_PAGED_FIRST_SELECTED_PASS cold="+cold+" player="+operator+" region="+firstRegion.value()+" memory_cut="+firstMemoryCut+" next_real_client_position="+position(far));
            }
            phase="WAIT_FAR_REAL_CLIENT_DEMAND position="+position(far);
            if(!inside(player,farRegion)||!selected(far,farRegion))return;
            farMemoryCut=life.memoryProjectionView(server).orElseThrow().lastDurableSequence();require(farMemoryCut>=firstMemoryCut,"selected memory cut regressed on demand change");
            var values=receipt();checkpoint=CompletableFuture.runAsync(()->{try{checkpoint(values);}catch(IOException failure){throw new UncheckedIOException(failure);}},io);
            phase="WRITING_NATIVE_OBSERVER_RECEIPT";
        }
        void checkPending(){
            var pending=expected.pending().orElseThrow();var manifest=pending.manifest();
            require(pendingProof.cut()>=snapshot.cut().value(),"pending chronology cut predates pinned active read");
            require(store.metadataPreparationTick(manifest).filter(pendingProof.clock()::equals).isPresent(),"actual J04 durable pending clock differs from scanned fact");
            require(pendingProof.activations()==0&&pendingProof.cancellations()==0,"pending generation was activated or cancelled in complete chronology");
            Set<Integer> sourcePages=pending.boundary().equals("clock")?Set.of():pending.boundary().equals("source")?Set.of(0):
                java.util.stream.IntStream.range(0,manifest.pageHashes().size()).boxed().collect(java.util.stream.Collectors.toUnmodifiableSet());
            Set<Integer> metadataPages=pending.boundary().equals("stage")?Set.of(0):Set.of();
            require(pendingProof.sourcePages().equals(sourcePages)&&pendingProof.metadataPages().equals(metadataPages),"durable pending pages differ from exact interruption boundary; possible monitor overshoot");
            var stage=store.stagedMetadata(manifest.sourceNamespace());
            require(stage.filter(value->value.transfer().equals(manifest)&&value.sourcePages().equals(sourcePages)&&value.pages().equals(metadataPages)).isPresent(),"actual pending staging indexes differ from durable chronology");
        }
        String position(ImportedAnchor a){return a.position().x()+","+a.position().y()+","+a.position().z();}
        boolean inside(ServerPlayer player,RegionId region){
            var r=regions.get(region);var p=player.blockPosition();
            return player.serverLevel().dimension().location().toString().equals(r.dimension().value())&&contains(r.bounds(),p.getX(),p.getY(),p.getZ());
        }
        boolean selected(ImportedAnchor anchor,RegionId region){
            var state=life.memoryProjectionView(server);var memory=life.runtimeMemoryView(server);
            if(state.isEmpty()||!state.orElseThrow().complete()||state.orElseThrow().failClosed()||memory.isEmpty())return false;
            var view=memory.orElseThrow();long count=(long)view.regionRecords().size()+view.regions().size()+view.locations().size()+view.anchors().size();
            require(count<=512,"actual selected metadata working set above512");require(!view.scopes().scopes().contains(new ScopeRef(ScopeKind.WORLD,session.worldId().value())),"selected context must not claim complete world metadata");
            var r=view.regionRecords().get(region);var a=view.anchors().get(anchor.anchorId());var l=view.locations().get(anchor.locationId());
            if(r==null||a==null||l==null)return false;
            verify(new RegionAggregate(r));verify(new AnchorAggregate(a));verify(new LocationAggregate(l));
            if(view.regions().stream().noneMatch(m->m.regionId().equals(region)))return false;
            var point=new BlockPosition(anchor.dimension(),anchor.position().x(),anchor.position().y(),anchor.position().z());
            if(!life.runtimePrimaryRegion(server,point).equals(Optional.of(region)))return false;
            var tags=life.runtimeImportedRegionTags(server);if(tags.isEmpty()||!tags.orElseThrow().containsKey(region))return false;
            Set<TagId> expectedTags=new HashSet<>(regions.get(region).tags());
            for(var sourceLocation:expected.input().locations())if(sourceLocation.regionIds().contains(region))expectedTags.addAll(sourceLocation.tags());
            require(tags.orElseThrow().get(region).equals(expectedTags),"actual selected authored region tags differ");return true;
        }
        void verify(AggregateState state){
            require(state.header().revision().value()>0,"native head revision zero");
            switch(state){
                case RegionAggregate a->{var value=a.value();var input=regions.get(value.regionId());require(input!=null&&value.status()==RegionStatus.ACTIVE&&value.dimension().equals(input.dimension())
                    &&value.supportShape().boxes().contains(box(input.dimension(),input.bounds()))&&value.anchorIds().containsAll(regionAnchors.getOrDefault(value.regionId(),Set.of())),"actual region metadata differs");}
                case RegionMemoryAggregate a->require(regions.containsKey(a.value().regionId()),"foreign region memory");
                case LocationAggregate a->{var value=a.value();var input=locations.get(value.locationId());require(input!=null&&value.active()&&value.sourceNamespace().equals(expected.input().sourceNamespace())
                    &&value.externalId().equals(input.externalId())&&value.importRevision().equals(expected.input().exportRevision())&&value.dimension().equals(input.dimension())
                    &&value.bounds().equals(box(input.dimension(),input.bounds()))&&value.tags().containsAll(input.tags())&&value.anchorIds().containsAll(locationAnchors.getOrDefault(value.locationId(),Set.of())),"actual location metadata differs");}
                case AnchorAggregate a->{var value=a.value();var input=anchors.get(value.anchorId());require(input!=null&&value.active()&&value.origin()==AnchorOrigin.IMPORTED&&value.evidence()==EvidenceStatus.CONFIRMED
                    &&value.locationId().equals(Optional.of(input.locationId()))&&value.regionIds().equals(locations.get(input.locationId()).regionIds())&&value.dimension().equals(input.dimension())
                    &&value.position().equals(new BlockPosition(input.dimension(),input.position().x(),input.position().y(),input.position().z()))&&value.tags().containsAll(input.tags()),"actual anchor metadata differs");}
                default->throw new IllegalStateException("unexpected source aggregate kind");
            }
        }
        Properties receipt(){
            Properties p=new Properties();p.setProperty("world",session.worldId().value().toString());p.setProperty("store_generation",Long.toString(session.storeGeneration().value()));
            p.setProperty("directory",expected.root().toString());p.setProperty("directory_file_key",expected.rootKey());p.setProperty("source_sha256",expected.sourceSha());p.setProperty("export_digest",expected.input().exportDigest().value());
            p.setProperty("generation",expected.generation().value());p.setProperty("source_namespace",expected.input().sourceNamespace().value());p.setProperty("source_revision",Long.toString(expected.input().exportRevision().value()));
            var allSources=new StringBuilder();snapshot.sources().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry->allSources.append(entry.getKey().value()).append('=').append(LocationTransfer.identity(entry.getValue()).value()).append('\n'));
            p.setProperty("all_source_manifests_sha256",BlobContent.hash(allSources.toString().getBytes(StandardCharsets.UTF_8)).value());
            p.setProperty("other_source_sha256",BlobContent.hash(IMPORT.encode(expected.other())).value());
            p.setProperty("other_source_heads","4");
            p.setProperty("native_admission_status",nativeStatus);p.setProperty("observer_lane",installed?"INSTALLED_TEST_ONLY_ADDON":"DEVELOPMENT_GAMETEST");
            p.setProperty("readback_run_id",readbackRunId());
            if(expected.pending().isPresent()){
                var pending=expected.pending().orElseThrow();p.setProperty("pending_transfer_name",pending.name());p.setProperty("pending_boundary",pending.boundary());
                p.setProperty("pending_generation",LocationTransfer.identity(pending.manifest()).value());p.setProperty("pending_revision",Long.toString(pending.manifest().exportRevision().value()));
                p.setProperty("pending_preparation_tick",Long.toString(pendingProof.clock().value()));p.setProperty("pending_source_pages",new TreeSet<>(pendingProof.sourcePages()).toString());
                p.setProperty("pending_metadata_pages",new TreeSet<>(pendingProof.metadataPages()).toString());p.setProperty("pending_activations",Integer.toString(pendingProof.activations()));
                p.setProperty("pending_cancellations",Integer.toString(pendingProof.cancellations()));p.setProperty("pending_journal_pages",Integer.toString(pendingProof.journalPages()));
                p.setProperty("pending_journal_cut",Long.toString(pendingProof.cut()));p.setProperty("pending_stale_restarts",Integer.toString(pendingProof.staleRestarts()));
            }
            p.setProperty("operator",operator.toString());p.setProperty("first_region",firstRegion.value().toString());p.setProperty("far_region",farRegion.value().toString());
            p.setProperty("boot_epoch",Long.toString(session.bootEpoch().value()));p.setProperty("authority_session",session.authoritySession().value().toString());p.setProperty("cut",Long.toString(snapshot.cut().value()));
            p.setProperty("first_memory_cut",Long.toString(firstMemoryCut));p.setProperty("far_memory_cut",Long.toString(farMemoryCut));p.setProperty("heads",Integer.toString(verifiedHeads));p.setProperty("head_pages",Integer.toString(headPages));
            p.setProperty("pid",Long.toString(PID));p.setProperty("process_start",Long.toString(START));p.setProperty("scope","DECLARED_SYNTHETIC_NATIVE_QA_ONLY_NOT_CANONICAL_OR_HUMAN_ACCEPTANCE");
            return p;
        }
        void checkpoint(Properties values)throws IOException{
            require(expected.root().equals(expected.root().toRealPath())&&expected.rootKey().equals(fileKey(expected.root())),"QA world directory identity changed");
            if(cold){
                var prior=expected.prior();
                for(var key:List.of("world","store_generation","directory","directory_file_key","source_sha256","export_digest","generation","source_namespace","source_revision","all_source_manifests_sha256","other_source_sha256","other_source_heads","operator","first_region","far_region","heads"))
                    require(values.getProperty(key).equals(prior.getProperty(key)),"cold original identity changed: "+key);
                require(Long.parseLong(prior.getProperty("boot_epoch"))<session.bootEpoch().value()&&Long.parseLong(prior.getProperty("process_start"))!=START
                    &&!prior.getProperty("authority_session").equals(session.authoritySession().value().toString())&&Long.parseLong(prior.getProperty("pid"))>0
                    &&Long.parseLong(prior.getProperty("cut"))<=snapshot.cut().value(),"genuine new JVM, new J04 boot/session and nonregressing durable cut required");
            }
            Path target=sidecar(expected.root(),expected.sourceSha(),cold);require(!Files.exists(target,LinkOption.NOFOLLOW_LINKS),"observer receipt already exists; preserve original evidence");
            Path temp=Files.createTempFile(expected.root(),".p008-paged-observer-",".tmp");
            try{
                var bytes=new ByteArrayOutputStream();values.store(bytes,"TEST_ONLY observer evidence; NOT a Director ACK or canonical reception");
                try(var channel=FileChannel.open(temp,StandardOpenOption.WRITE)){var buffer=ByteBuffer.wrap(bytes.toByteArray());while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}
                Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE);try(var directory=FileChannel.open(expected.root(),StandardOpenOption.READ)){directory.force(true);}
            }finally{Files.deleteIfExists(temp);}
        }
    }
}
