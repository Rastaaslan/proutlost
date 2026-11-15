package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.integration.locations.StandaloneLocationImporter;
import fr.proutlost.director.memory.MemoryProjection;
import fr.proutlost.director.memory.AuthoredRegionTagProjection;
import fr.proutlost.director.persistence.JournalStore;
import fr.proutlost.director.runtime.ResultTransaction;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/** Explicit operator-authored J08 metadata -> existing J04 writer.
 * File and registry fsync run on one private IO thread; Minecraft/metadata
 * decisions, WAL submission and ACK readback remain on the server actor.
 * Tags are authored data, never physical survey or event outcome evidence.
 */
public final class DirectorNativeLocationImports implements AutoCloseable {
    public static final FactType APPLIED=new FactType("proutlost_director:location_import_applied");
    private static final ModelCodec<LocationImport> CODEC=new ModelCodec<>(LocationImport.class);
    private static final int MAX_BYTES=StandaloneLocationImporter.NATIVE_MAX_BYTES,MAX_ENTRIES=StandaloneLocationImporter.NATIVE_MAX_ENTRIES,MAX_METADATA=512,MAX_SOURCES=32;
    private static final Set<AggregateKind> METADATA=Set.of(AggregateKind.REGION,
        AggregateKind.REGION_MEMORY,AggregateKind.LOCATION,AggregateKind.ANCHOR);
    private static final Set<String> LEGACY_TAGS=Set.of("proutlost:clearing","proutlost:fauna_corridor",
        "proutlost:camp","proutlost:abandoned_camp","proutlost:camp_abandoned",
        "proutlost:regrown_camp","proutlost:signal_anchor","proutlost:landslide_scar",
        "proutlost:fauna_settled","proutlost:remembered_camp","proutlost:recovering_landscape",
        "proutlost:unfinished_recovery","proutlost:structure","proutlost:dungeon","proutlost:anchor_center");

    // Exact metadata classifications and anchor roles emitted by the retained
    // Contra revision3 producer. These labels grant no physical capability.
    private static final Set<String> LOCATION_CLASSIFICATIONS=Set.of(
        "proutlost:bridge","proutlost:explorable","proutlost:gathering_candidate",
        "proutlost:mineshaft","proutlost:minor_poi","proutlost:perception_candidate",
        "proutlost:pillar","proutlost:route_candidate","proutlost:shelter_candidate",
        "proutlost:signal_candidate","proutlost:witch_circle","proutlost:yungs");
    private static final Set<String> ANCHOR_ROLES=Set.of(
        "proutlost:anchor_apparition","proutlost:anchor_entrance","proutlost:anchor_gathering",
        "proutlost:anchor_interaction","proutlost:anchor_observation",
        "proutlost:anchor_route_end","proutlost:anchor_route_start");
    private static final Set<String> TAGS;
    static {
        var known=new HashSet<>(LEGACY_TAGS);known.addAll(LOCATION_CLASSIFICATIONS);known.addAll(ANCHOR_ROLES);
        TAGS=Set.copyOf(known);
    }
    private final DirectorRuntimeContent runtimeContent;
    private final MinecraftServer server;
    private final WorldId world;
    private final JournalStore store;
    private final StoreSession session;
    private final Supplier<Optional<MemoryView>> memory;
    private final BooleanSupplier quiescent;
    private final Predicate<Object> acquireLane;
    private final Consumer<Object> releaseLane;
    private final Object laneOwner=new Object();
    private final Path inputDirectory;
    private final ExecutorService io;
    private final CompletableFuture<StandaloneLocationImporter> opening;
    private StandaloneLocationImporter registry;
    private final Map<ResourceId,LocationImport> applied=new TreeMap<>();
    private final Map<AggregateKey,ResourceId> owners=new HashMap<>();
    private final ArrayDeque<LocationImport> reconcile=new ArrayDeque<>();
    private CompletableFuture<LocationImport> inputRead;
    private CompletableFuture<LocationImportResult> registryWrite;
    private CompletableFuture<CommitAck> wal;
    private LocationImport pending;
    private DurableBatch batch;
    private Plan expected;
    private Context preparedContext;
    private boolean recovered,queuedRegistry,closed,failed,laneHeld,pendingRegistered;
    private Sequence recoveryCut=new Sequence(0),committedSequence=new Sequence(0);
    private String phase="OPENING_REGISTRY",last="NONE";
    private Map<RegionId,Set<TagId>> publishedRegionTags=Map.of();
    private Sequence publishedTagsCut=new Sequence(0);
    private boolean tagsInitialized;

    public DirectorNativeLocationImports(MinecraftServer server,WorldId world,JournalStore store,
            Supplier<Optional<MemoryView>> memory,BooleanSupplier quiescent,
            Predicate<Object> acquireLane,Consumer<Object> releaseLane){
        this(server,world,store,memory,quiescent,acquireLane,releaseLane,null);
    }
    public DirectorNativeLocationImports(MinecraftServer server,WorldId world,JournalStore store,
            Supplier<Optional<MemoryView>> memory,BooleanSupplier quiescent,
            Predicate<Object> acquireLane,Consumer<Object> releaseLane,DirectorRuntimeContent runtimeContent){
        this.runtimeContent=runtimeContent;
        if(runtimeContent!=null&&!runtimeContent.production())throw new IllegalArgumentException("explicit approved native content required");
        this.server=Objects.requireNonNull(server);this.world=Objects.requireNonNull(world);
        this.store=Objects.requireNonNull(store);this.memory=Objects.requireNonNull(memory);
        this.quiescent=Objects.requireNonNull(quiescent);
        this.acquireLane=Objects.requireNonNull(acquireLane);this.releaseLane=Objects.requireNonNull(releaseLane);
        actor();session=Objects.requireNonNull(store.session());
        if(!world.equals(session.worldId()))throw new IllegalArgumentException("import world/session");
        inputDirectory=server.getWorldPath(LevelResource.ROOT).resolve("director-location-imports")
            .toAbsolutePath().normalize();
        Map<DimensionId,ImportBounds> dimensions=new HashMap<>();
        for(var level:server.getAllLevels()){
            var border=level.getWorldBorder();
            int minX=(int)Math.ceil(Math.max(-29999984,border.getMinX()));
            int minZ=(int)Math.ceil(Math.max(-29999984,border.getMinZ()));
            int maxX=(int)Math.floor(Math.min(29999984,border.getMaxX()));
            int maxZ=(int)Math.floor(Math.min(29999984,border.getMaxZ()));
            dimensions.put(new DimensionId(level.dimension().location().toString()),
                new ImportBounds(minX,level.getMinBuildHeight(),minZ,maxX,level.getMaxBuildHeight(),maxZ));
        }
        io=Executors.newSingleThreadExecutor(r->{
            var thread=new Thread(r,"director-location-imports-io");thread.setDaemon(true);return thread;
        });
        opening=StandaloneLocationImporter.open(inputDirectory.resolve("registry"),world,dimensions,
            DirectorNativeLocationImports::acceptsAuthoredTag,io,MAX_BYTES,MAX_ENTRIES).toCompletableFuture();
    }
    private void actor(){
        if(closed||!server.isSameThread())throw new IllegalStateException("location imports server actor");
    }
    static boolean acceptsAuthoredTag(TagId tag){return TAGS.contains(tag.value());}
    private static void requireAuthoredScopes(LocationImport input){
        for(var row:input.regions())for(var tag:row.tags())
            require(LEGACY_TAGS.contains(tag.value()),"unsupported import region tag scope");
        for(var row:input.locations())for(var tag:row.tags())
            require(LEGACY_TAGS.contains(tag.value())||LOCATION_CLASSIFICATIONS.contains(tag.value()),
                "unsupported import location tag scope");
        for(var row:input.anchors())for(var tag:row.tags())
            require(LEGACY_TAGS.contains(tag.value())||ANCHOR_ROLES.contains(tag.value()),
                "unsupported import anchor tag scope");
    }

    private static void require(boolean value,String why){if(!value)throw new IllegalArgumentException(why);}
    private static JsonValue.Text text(Object value){return new JsonValue.Text(value.toString());}
    private static String text(DirectorFact fact,String key){
        return ((JsonValue.Text)Objects.requireNonNull(fact.payload().get(key),key)).value();
    }
    private static UUID identity(String value){
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }
    private void release(){
        if(laneHeld){releaseLane.accept(laneOwner);laneHeld=false;}
    }
    private void fail(String reason){
        failed=true;phase="FAILED_SAFE";last=reason;
        // An uncertain J04 append retains the metadata lane. Only process
        // recovery may decide its outcome; there is no new-ID blind retry.
        if(wal==null)release();
        System.out.println("DIRECTOR_LOCATION_IMPORT_FAILED_SAFE "+reason);
    }
    /** Bootstrap feeds the already verified chronological J04 recovery pages. */
    public void resetRecoveryScan(){
        actor();require(wal==null&&!laneHeld,"cannot reset live import");
        applied.clear();owners.clear();reconcile.clear();recovered=false;queuedRegistry=false;
        recoveryCut=new Sequence(0);
        publishedRegionTags=Map.of();publishedTagsCut=new Sequence(0);tagsInitialized=false;
    }
    public void acceptRows(List<DurableRecord> records){
        actor();require(!recovered,"import recovery already finished");
        for(var row:records)if(row instanceof DurableFact fact&&fact.value().factType().equals(APPLIED)){
            var f=fact.value();
            require(f.durable()&&f.evidenceStatus()==EvidenceStatus.CONFIRMED
                &&f.eventId().isEmpty()&&f.subjectRefs().contains(new ScopeRef(ScopeKind.WORLD,world.value())),
                "invalid import manifest fact");
            byte[] bytes=Base64.getDecoder().decode(text(f,"location_import_base64"));
            require(bytes.length<=MAX_BYTES,"import manifest byte bound");
            var value=CODEC.decode(bytes);requireAuthoredScopes(value);
            require(value.worldId().equals(world)&&value.exportDigest().equals(StandaloneLocationImporter.digest(value))
                &&value.sourceNamespace().value().equals(text(f,"source_namespace"))
                &&Long.toString(value.exportRevision().value()).equals(text(f,"export_revision"))
                &&value.exportDigest().value().equals(text(f,"export_digest"))
                &&f.factId().equals(factId(world,value)),"import manifest identity");
            remember(value);
        }
    }
    public void finishRecovery(Sequence cut){
        actor();require(!recovered,"import recovery already finished");
        require(cut.compareTo(recoveryCut)>=0,"import recovery cut regression");
        recoveryCut=cut;recovered=true;phase="WAIT_REGISTRY";
    }
    public void acceptRecoveryRows(List<DurableRecord> rows,boolean last,Sequence cut){
        acceptRows(rows);if(last)finishRecovery(cut);
    }
    private void remember(LocationImport value){
        var prior=applied.get(value.sourceNamespace());
        if(prior!=null){
            require(value.exportRevision().compareTo(prior.exportRevision())>=0,"import manifest revision regression");
            if(value.exportRevision().equals(prior.exportRevision())){
                require(value.exportDigest().equals(prior.exportDigest()),"same import revision changed");
                return;
            }
        }
        require(applied.containsKey(value.sourceNamespace())||applied.size()<MAX_SOURCES,"import source capacity");
        for(var r:value.regions()){
            own(new AggregateKey(AggregateKind.REGION,r.regionId().value()),value.sourceNamespace());
            own(new AggregateKey(AggregateKind.REGION_MEMORY,r.regionId().value()),value.sourceNamespace());
        }
        for(var l:value.locations())own(new AggregateKey(AggregateKind.LOCATION,l.locationId().value()),value.sourceNamespace());
        for(var a:value.anchors())own(new AggregateKey(AggregateKind.ANCHOR,a.anchorId().value()),value.sourceNamespace());
        applied.put(value.sourceNamespace(),value);
    }
    private void own(AggregateKey key,ResourceId source){
        require(owners.containsKey(key)||owners.size()<4096,"import historical identity capacity");
        var previous=owners.putIfAbsent(key,source);
        require(previous==null||previous.equals(source),"cross-source import identity");
    }
    /** Root command wiring must use this authenticated overload. */
    public String submit(ServerPlayer player,String name){
        actor();
        if(player==null||player.getServer()!=server||!player.hasPermissions(4))
            return "OP4 requis pour importer les lieux.";
        return submit(name);
    }
    /** Trusted server integration entry, after the command dispatcher checked OP4. */
    public String submit(String name){
        actor();
        if(runtimeContent==null&&!Boolean.getBoolean("proutlost.director.nativeLab"))return "Import LAB desactive.";
        if(failed)return "Import indisponible: "+last;
        if(!recovered||registry==null||!queuedRegistry)return "Import en recuperation.";
        if(busy())return "Un import est deja en cours.";
        if(name==null||!name.matches("[a-z0-9][a-z0-9_-]{0,63}"))return "Nom de fichier invalide.";
        Path file=inputDirectory.resolve(name+".json");
        phase="READING_INPUT";last="PENDING "+name;
        inputRead=CompletableFuture.supplyAsync(()->{
            try{
                if(Files.isSymbolicLink(inputDirectory)||Files.isSymbolicLink(file)
                    ||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))
                    throw new IllegalArgumentException("explicit regular import file required");
                long length=Files.size(file);
                require(length>0&&length<=MAX_BYTES,"import input byte bound");
                byte[] bytes=Files.readAllBytes(file);
                require(bytes.length>0&&bytes.length<=MAX_BYTES,"import input changed/oversized");
                var value=CODEC.decode(bytes);requireAuthoredScopes(value);
                require(value.worldId().equals(world),"import input belongs to another world");
                require(CODEC.encode(value).length<=MAX_BYTES,"canonical import byte bound");
                return value;
            }catch(IOException error){throw new CompletionException(error);}
        },io);
        return "Lecture de director-location-imports/"+name+".json en cours.";
    }
    public boolean submitApproved(String name,DirectorRuntimeContent expected){
        actor();if(runtimeContent==null||runtimeContent!=expected||!expected.production())throw new IllegalArgumentException("production import owner mismatch");
        submit(name);return inputRead!=null;
    }
    public boolean approvedSubmissionComplete(DirectorRuntimeContent expected){
        actor();if(runtimeContent!=expected)throw new IllegalArgumentException("production import owner mismatch");
        return !busy()&&!failed&&(last.equals("DURABLE")||last.equals("ALREADY_DURABLE"));
    }
    public boolean approvedSubmissionFailed(DirectorRuntimeContent expected){
        actor();if(runtimeContent!=expected)throw new IllegalArgumentException("production import owner mismatch");
        return failed||last.startsWith("INPUT_REFUSED")||last.startsWith("REGISTRY_REFUSED")||last.startsWith("REFUSED");
    }
    public boolean busy(){
        actor();
        return laneHeld||inputRead!=null||registryWrite!=null||pending!=null||wal!=null
            ||!reconcile.isEmpty();
    }
    public boolean metadataLaneHeld(){actor();return laneHeld;}
    public boolean regionTagsReady(){actor();return tagsInitialized&&!laneHeld&&!failed;}
    /** Never expose an old semantic view while a newer import is publishing. */
    public Map<RegionId,Set<TagId>> regionTags(){
        actor();require(regionTagsReady(),"authored region tags not durably projected");
        return publishedRegionTags;
    }
    public Sequence regionTagsCut(){actor();return publishedTagsCut;}
    private void publishRegionTags(Context context){
        publishedRegionTags=AuthoredRegionTagProjection.fromCommittedImports(applied.values(),context.memory);
        publishedTagsCut=context.cut;tagsInitialized=true;
    }
    public String status(){
        actor();return "LOCATION_IMPORT "+phase+" sources="+applied.size()
            +" lane="+laneHeld+" sequence="+committedSequence.value()+" "+last;
    }
    private void queueRegistry(){
        var current=registry.snapshot().current();
        require(current.size()<=MAX_SOURCES,"registry source capacity");
        for(var entry:applied.entrySet()){
            var saved=current.get(entry.getKey());
            require(saved!=null&&saved.exportRevision().compareTo(entry.getValue().exportRevision())>=0,
                "J04 import manifest is ahead of registry");
        }
        for(var entry:new TreeMap<>(current).entrySet()){
            var previous=applied.get(entry.getKey());var value=entry.getValue();
            require(CODEC.encode(value).length<=MAX_BYTES,"registry canonical byte bound");
            if(previous!=null&&value.exportRevision().equals(previous.exportRevision()))
                require(value.exportDigest().equals(previous.exportDigest()),"registry/J04 digest disagreement");
            else reconcile.addLast(value);
        }
        queuedRegistry=true;phase=reconcile.isEmpty()?"IDLE":"RECONCILING";
    }
    public void poll(){
        actor();if(failed)return;
        try{
            require(session.equals(store.session())&&!store.readOnlySafe(),"J04 import session unavailable");
            if(registry==null){
                if(!opening.isDone())return;
                registry=opening.getNow(null);require(registry!=null,"registry open unavailable");
            }
            if(!recovered)return;
            if(!queuedRegistry)queueRegistry();
            if(!tagsInitialized){
                var initial=context();
                if(initial.isEmpty()){phase="WAIT_RESTORED_PROJECTION";return;}
                // Startup rows came from verified committed J04 frames. Exact
                // resident metadata/memory readback qualifies their projection.
                publishRegionTags(initial.get());
            }
            if(inputRead!=null){
                if(!inputRead.isDone())return;
                pending=inputRead.getNow(null);inputRead=null;require(pending!=null,"empty import input");
                // No registry commit is allowed before actor ownership/capacity/batch preflight.
                expected=null;batch=null;preparedContext=null;pendingRegistered=false;phase="WAIT_QUIESCENT";return;
            }
            if(registryWrite!=null){
                if(!registryWrite.isDone())return;
                var ack=registryWrite.getNow(null);
                require(ack!=null,"missing registry ACK");
                if(ack.status()!=AckStatus.DURABLE){
                    if(ack.status()==AckStatus.UNAVAILABLE){fail("REGISTRY_UNAVAILABLE");return;}
                    registryWrite=null;pending=null;expected=null;batch=null;preparedContext=null;pendingRegistered=false;release();
                    phase="IDLE";last="REGISTRY_REFUSED "+ack.status();return;
                }
                pendingRegistered=true;registryWrite=null;
                Map<RegionId,RegionId> canonical=new HashMap<>();
                pending.regions().forEach(r->canonical.put(r.regionId(),r.regionId()));
                require(ack.revision().equals(pending.exportRevision())
                    &&ack.canonicalRegions().equals(canonical),"registry ACK identity mismatch");
                require(expected!=null&&batch!=null,"registry ACK without actor preflight");
                phase="WAIT_QUIESCENT";
            }
            if(wal!=null){
                if(!wal.isDone())return;
                var ack=wal.getNow(null);
                require(ack!=null&&ack.ackStatus()==AckStatus.DURABLE
                    &&session.equals(store.session())
                    &&ack.transactionId().equals(batch.transactionId())
                    &&ack.transactionHash().equals(batch.transactionHash())
                    &&ack.storeGeneration().equals(session.storeGeneration())
                    &&ack.durableSequence().value()>0&&ack.permitAttempt().isEmpty()
                    &&ack.forcedBlobs().isEmpty(),"J04 import ACK uncertain/rejected");
                if(phase.equals("WAL_PENDING")){
                    committedSequence=ack.durableSequence();remember(pending);phase="PROJECTION_PENDING";
                }
                var context=context();
                if(context.isEmpty()||context.get().cut.compareTo(committedSequence)<0)return;
                for(var entry:expected.states.entrySet())
                    require(entry.getValue().equals(context.get().states.get(entry.getKey())),
                        "import exact projected aggregate mismatch");
                publishRegionTags(context.get());
                System.out.println("DIRECTOR_LOCATION_IMPORT_DURABLE source="+pending.sourceNamespace().value()
                    +" revision="+pending.exportRevision().value()+" sequence="+committedSequence.value()
                    +" digest="+pending.exportDigest().value()+" changed="+expected.states.size());
                wal=null;pending=null;batch=null;expected=null;preparedContext=null;pendingRegistered=false;release();phase="IDLE";last="DURABLE";return;
            }
            if(pending==null&&!reconcile.isEmpty()){
                pending=reconcile.removeFirst();expected=null;batch=null;preparedContext=null;pendingRegistered=true;phase="WAIT_QUIESCENT";
            }
            if(pending==null)return;
            var prior=applied.get(pending.sourceNamespace());
            if(prior!=null&&prior.exportRevision().equals(pending.exportRevision())){
                require(prior.exportDigest().equals(pending.exportDigest()),"import idempotency conflict");
                pending=null;expected=null;batch=null;preparedContext=null;pendingRegistered=false;release();phase="IDLE";last="ALREADY_DURABLE";return;
            }
            if(!laneHeld&&!quiescent.getAsBoolean())return;
            var context=context();if(context.isEmpty()){phase="WAIT_COMPLETE_CONTEXT";return;}
            if(!laneHeld){if(!acquireLane.test(laneOwner))return;laneHeld=true;}
            // The same actor holds the lane from this point through exact
            // J04 ACK + memory readback. Existing ingress ACKs may still drain.
            context=context();if(context.isEmpty())return;
            if(expected==null){
                expected=preflight(world,pending,prior,context.get(),owners,registry.snapshot().current().keySet());
                preparedContext=context.get();
                var records=new ArrayList<DurableRecord>();
                expected.states.values().forEach(s->records.add(new DurableAggregate(s)));
                records.add(new DurableFact(manifest(pending,context.get().tick)));
                // Encoding/hash/CAS construction must also succeed before CURRENT can change.
                batch=ResultTransaction.batch(new TransactionId(UUID.randomUUID()),session.storeGeneration(),
                    expected.revisions,records,List.of());
            }
            if(!pendingRegistered){
                registryWrite=registry.submit(pending).toCompletableFuture();phase="REGISTRY_PENDING";return;
            }
            // Keep the same metadata lane and immutable preflight through registry IO.
            // Reconciliation already has a durable registry entry, but uses this same plan.
            // Ingress ACKs may drain while registry IO runs: a later cut/tick alone
            // is harmless, but metadata/CAS/ownership must still yield the exact plan.
            verifyPreparedPlan(expected,world,pending,prior,preparedContext,context.get(),owners,
                registry.snapshot().current().keySet());
            wal=store.append(batch).toCompletableFuture();phase="WAL_PENDING";
            System.out.println("DIRECTOR_LOCATION_IMPORT_PREPARED source="+pending.sourceNamespace().value()
                +" revision="+pending.exportRevision().value()+" cut="+context.get().cut.value());
        }catch(CompletionException error){
            if(wal==null&&inputRead!=null){
                inputRead=null;pending=null;expected=null;batch=null;preparedContext=null;pendingRegistered=false;release();
                phase="IDLE";last="INPUT_REFUSED "+error.getClass().getSimpleName();
                return;
            }
            fail("ASYNC_"+error.getClass().getSimpleName());
        }catch(IllegalArgumentException refused){
            if(wal==null&&registryWrite==null&&!pendingRegistered){
                last="REFUSED "+refused.getMessage();pending=null;expected=null;batch=null;preparedContext=null;pendingRegistered=false;release();phase="IDLE";
                System.out.println("DIRECTOR_LOCATION_IMPORT_REFUSED "+refused.getMessage());
            }else fail(refused.getMessage());
        }catch(RuntimeException error){fail(error.getClass().getSimpleName()+":"+error.getMessage());}
    }
    record Context(Sequence cut,Tick tick,MemoryView memory,Map<AggregateKey,AggregateState> states,
                   Set<AggregateKey> allKeys){}
    private Optional<Context> context(){
        var view=memory.get();
        if(view.isEmpty()||!view.get().scopes().worldId().equals(world)
            ||!view.get().scopes().scopes().contains(new ScopeRef(ScopeKind.WORLD,world.value())))return Optional.empty();
        var index=store.residentAggregateKeys(EnumSet.allOf(AggregateKind.class),4096);
        if(index.isEmpty()||!index.get().session().equals(session))return Optional.empty();
        Set<AggregateKey> keys=new HashSet<>();
        for(var key:index.get().keys())if(METADATA.contains(key.kind()))keys.add(key);
        requireCompleteContextCapacity(keys.size());
        keys.add(new AggregateKey(AggregateKind.WORLD,world.value()));
        var query=new StoreQuery(world,keys,new Sequence(0),Optional.empty(),MAX_METADATA+1);
        var read=store.view(query);ReadContracts.validate(read,query,session);
        if(read.status()!=ReadStatus.AVAILABLE||read.page().orElseThrow().hasMore()
            ||!read.page().orElseThrow().lastDurableSequence().equals(index.get().cut()))return Optional.empty();
        var states=new HashMap<AggregateKey,AggregateState>();DirectorWorldState worldState=null;
        for(var row:read.page().orElseThrow().aggregates()){
            states.put(row.state().key(),row.state());
            if(row.state() instanceof WorldAggregate w)worldState=w.value();
        }
        if(worldState==null||!worldState.worldId().equals(world)
            ||!worldState.bootEpoch().equals(session.bootEpoch())
            ||!worldState.storeGeneration().equals(session.storeGeneration())
            ||!states.keySet().equals(keys))return Optional.empty();
        var actual=MemoryProjection.of(new ScopeSet(world,Set.of(new ScopeRef(ScopeKind.WORLD,world.value()))),
            states.values());
        var supplied=view.get();
        if(!actual.regionRecords().equals(supplied.regionRecords())
            ||!actual.anchors().equals(supplied.anchors())||!actual.locations().equals(supplied.locations())
            ||!new HashSet<>(actual.regions()).equals(new HashSet<>(supplied.regions())))return Optional.empty();
        // Metadata and subsequent J01 reductions share the recovered Director
        // clock. Minecraft gameTime can legitimately be ahead after a paused
        // Director/restart; importing that time poisons every regional reduction.
        var tick=importTick(worldState,states.values(),
            fr.proutlost.director.neoforge.DirectorStandaloneMod.directorLifecycle().runtimeClock(server));
        return Optional.of(new Context(index.get().cut(),tick,actual,Map.copyOf(states),index.get().keys()));
    }
    static Tick importTick(DirectorWorldState worldState,Collection<AggregateState> states,long logicalNow){
        long floor=worldState.simTick().value();
        for(var state:states)floor=Math.max(floor,state.header().updatedTick().value());
        // A future durable aggregate requires recovery; never forge a new clock
        // or mutate the input timestamps to hide an unverified projection.
        require(logicalNow>=floor,"import context ahead of Director logical clock");
        return new Tick(logicalNow);
    }
    /** A fixed resident capacity is not a transient projection wait. */
    static void requireCompleteContextCapacity(int count){
        if(count>MAX_METADATA)throw new IllegalStateException("location import metadata context capacity "+count+">"+MAX_METADATA);
    }
    /** Pure bounded preflight: no registry, filesystem or J04 mutation. */
    static Plan preflight(WorldId world,LocationImport input,LocationImport previous,Context context,
            Map<AggregateKey,ResourceId> ownership,Set<ResourceId> currentSources){
        require(currentSources.contains(input.sourceNamespace())||currentSources.size()<MAX_SOURCES,
            "import source capacity");
        var result=plan(world,input,previous,context,ownership);
        Set<AggregateKey> projected=new HashSet<>();
        for(var key:context.states.keySet())if(METADATA.contains(key.kind()))projected.add(key);
        projected.addAll(result.states.keySet());
        require(projected.size()<=MAX_METADATA,"import projected metadata capacity");
        Set<AggregateKey> historical=new HashSet<>(ownership.keySet());
        historical.addAll(result.states.keySet());
        require(historical.size()<=4096,"import historical identity capacity");
        return result;
    }
    /** Recheck after registry IO without inventing a fresh plan for an accepted input. */
    static void verifyPreparedPlan(Plan preparedPlan,WorldId world,LocationImport input,LocationImport previous,
            Context preparedContext,Context current,Map<AggregateKey,ResourceId> ownership,
            Set<ResourceId> currentSources){
        require(preparedContext!=null&&preparedPlan!=null,"missing import actor preflight");
        require(current.cut.compareTo(preparedContext.cut)>=0,"import context cut regressed");
        var atPreparedTick=new Context(current.cut,preparedContext.tick,current.memory,current.states,current.allKeys);
        var checked=preflight(world,input,previous,atPreparedTick,ownership,currentSources);
        require(preparedPlan.equals(checked),"import actor preflight changed after registry commit");
    }
    private static FactId factId(WorldId world,LocationImport value){
        return new FactId(identity("director-location-import-v1/"+world.value()+"/"
            +value.sourceNamespace().value()+"/"+value.exportRevision().value()+"/"+value.exportDigest().value()));
    }
    private DirectorFact manifest(LocationImport value,Tick tick){
        return new DirectorFact(factId(world,value),APPLIED,List.of(new ScopeRef(ScopeKind.WORLD,world.value())),
            Optional.empty(),EvidenceStatus.CONFIRMED,Map.of(
                "source_namespace",text(value.sourceNamespace().value()),
                "export_revision",text(value.exportRevision().value()),
                "export_digest",text(value.exportDigest().value()),
                "location_import_base64",text(Base64.getEncoder().encodeToString(CODEC.encode(value))),
                "tick",text(tick.value()),"operator_authored",new JsonValue.Bool(true)),
            true,Optional.empty());
    }
    record Plan(Map<AggregateKey,AggregateState> states,Map<ScopeRef,Revision> revisions){}
    private static Aabb bounds(DimensionId d,ImportBounds b){
        return new Aabb(d,b.minX(),b.minY(),b.minZ(),b.maxXExclusive(),b.maxYExclusive(),b.maxZExclusive());
    }
    private static boolean contains(ImportBounds b,ImportPosition p){
        return p.x()>=b.minX()&&p.x()<b.maxXExclusive()&&p.y()>=b.minY()&&p.y()<b.maxYExclusive()
            &&p.z()>=b.minZ()&&p.z()<b.maxZExclusive();
    }
    private static Map<AnchorId,Set<TagId>> authored(LocationImport input){
        Map<AnchorId,Set<TagId>> result=new HashMap<>();if(input==null)return result;
        Map<LocationId,ImportedLocation> locations=new HashMap<>();
        input.locations().forEach(l->locations.put(l.locationId(),l));
        Map<RegionId,ImportedRegion> regions=new HashMap<>();input.regions().forEach(r->regions.put(r.regionId(),r));
        for(var a:input.anchors()){
            var location=locations.get(a.locationId());
            require(location!=null,"import anchor location");
            // Anchor granularity is exact. Region/location classifications
            // are projected separately into RegionSnapshot after J04 ACK.
            Set<TagId> tags=new HashSet<>(a.tags());
            for(var id:location.regionIds()){
                var region=regions.get(id);
                require(region!=null,"import anchor region");
                require(region.dimension().equals(a.dimension())&&contains(region.bounds(),a.position()),
                    "import anchor outside declared region");
            }
            result.put(a.anchorId(),Set.copyOf(tags));
        }
        return result;
    }
    private static AggregateHeader next(AggregateState before,UUID id,Tick tick){
        return before==null?new AggregateHeader(1,id,new Revision(1),new Sequence(0),tick)
            :new AggregateHeader(1,id,before.header().revision().plus(1),before.header().lastSequence(),tick);
    }
    /** Pure plan, reusable by native qualification without filesystem or Minecraft writes. */
    static Plan plan(WorldId world,LocationImport input,LocationImport previous,Context context,
            Map<AggregateKey,ResourceId> ownership){
        require(input.worldId().equals(world)&&input.exportDigest().equals(StandaloneLocationImporter.digest(input)),
            "input world/digest changed");
        requireAuthoredScopes(input);
        require((long)input.regions().size()+input.locations().size()+input.anchors().size()<=MAX_ENTRIES,
            "import entry bound");
        require(previous==null||previous.sourceNamespace().equals(input.sourceNamespace())
            &&previous.worldId().equals(world)
            &&input.exportRevision().compareTo(previous.exportRevision())>0,"import revision/source not newer");
        Map<AggregateKey,AggregateState> changes=new LinkedHashMap<>();
        Map<ScopeRef,Revision> cas=new HashMap<>();
        var source=input.sourceNamespace();var old=context.states;var tick=context.tick;
        Set<AggregateKey> desired=new HashSet<>();
        for(var r:input.regions()){
            desired.add(new AggregateKey(AggregateKind.REGION,r.regionId().value()));
            desired.add(new AggregateKey(AggregateKind.REGION_MEMORY,r.regionId().value()));
        }
        input.locations().forEach(l->desired.add(new AggregateKey(AggregateKind.LOCATION,l.locationId().value())));
        input.anchors().forEach(a->desired.add(new AggregateKey(AggregateKind.ANCHOR,a.anchorId().value())));
        for(var key:desired){
            for(var existing:context.allKeys)if(existing.id().equals(key.id())){
                boolean expectedMemoryPair=existing.kind()==AggregateKind.REGION&&key.kind()==AggregateKind.REGION_MEMORY || existing.kind()==AggregateKind.REGION_MEMORY&&key.kind()==AggregateKind.REGION;
                require((existing.kind()==key.kind()||expectedMemoryPair)&&source.equals(ownership.get(existing)),
                    "identity collision with another origin/source: "+existing);
            }
        }
        var beforeTags=authored(previous);var afterTags=authored(input);
        Map<LocationId,ImportedLocation> locations=new HashMap<>();input.locations().forEach(l->locations.put(l.locationId(),l));
        Map<RegionId,ImportedRegion> priorRegions=new HashMap<>();
        if(previous!=null)previous.regions().forEach(r->priorRegions.put(r.regionId(),r));
        for(var r:input.regions()){
            var key=new AggregateKey(AggregateKind.REGION,r.regionId().value());var existing=old.get(key);
            RegionRecord before=existing==null?null:((RegionAggregate)existing).value();
            require(before==null||before.status()!=RegionStatus.ALIAS,"cannot overwrite region topology alias");
            Set<AnchorId> anchors=new HashSet<>();
            if(before!=null)for(var a:before.anchorIds())
                if(!source.equals(ownership.get(new AggregateKey(AggregateKind.ANCHOR,a.value()))))anchors.add(a);
            for(var a:input.anchors())if(locations.get(a.locationId()).regionIds().contains(r.regionId()))anchors.add(a.anchorId());
            List<Aabb> boxes=new ArrayList<>();
            if(before!=null)boxes.addAll(before.supportShape().boxes());
            var prior=priorRegions.get(r.regionId());if(prior!=null)boxes.remove(bounds(prior.dimension(),prior.bounds()));
            boxes.add(bounds(r.dimension(),r.bounds()));
            var shape=new SupportShape(boxes.stream().distinct().toList(),
                before==null?List.of():before.supportShape().discs(),before==null?List.of():before.supportShape().routes());
            var next=new RegionRecord(next(existing,r.regionId().value(),tick),r.regionId(),r.dimension(),
                RegionStatus.ACTIVE,shape,anchors,before==null?new UnitValue(0):before.importance(),
                before==null?Set.of():before.parentIds(),before==null?Set.of():before.aliases(),
                before==null?new Revision(1):before.membershipRevision().plus(1),
                before==null?new RegionPersonality(Map.of()):before.personality(),
                before==null?Set.of(new MemoryId(r.regionId().value())):before.memoryRefs());
            changes.put(key,new RegionAggregate(next));
            cas.put(new ScopeRef(ScopeKind.REGION,r.regionId().value()),existing==null?new Revision(0):existing.header().revision());
            if(before==null){
                var memoryKey=new AggregateKey(AggregateKind.REGION_MEMORY,r.regionId().value());
                require(!old.containsKey(memoryKey),"orphan region memory collision");
                changes.put(memoryKey,new RegionMemoryAggregate(new RegionMemory(
                    next(null,r.regionId().value(),tick),r.regionId(),Map.of(),new Tick(0),new Tick(0),
                    Set.of(),Set.of(),new RegionActivityState(Map.of(),Map.of()))));
            }else require(context.memory.regions().stream().filter(m->m.regionId().equals(r.regionId())).count()==1,
                "existing region memory absent/ambiguous");
        }
        for(var l:input.locations()){
            var key=new AggregateKey(AggregateKind.LOCATION,l.locationId().value());var existing=old.get(key);
            if(existing!=null)require(((LocationAggregate)existing).value().sourceNamespace().equals(source),"location source collision");
            Set<AnchorId> anchors=new HashSet<>();
            input.anchors().stream().filter(a->a.locationId().equals(l.locationId())).forEach(a->anchors.add(a.anchorId()));
            var value=new LocationRecord(next(existing,l.locationId().value(),tick),l.locationId(),source,l.externalId(),
                l.dimension(),bounds(l.dimension(),l.bounds()),l.tags(),anchors,input.exportRevision(),true);
            changes.put(key,new LocationAggregate(value));
            cas.put(new ScopeRef(ScopeKind.LOCATION,l.locationId().value()),existing==null?new Revision(0):existing.header().revision());
        }
        for(var a:input.anchors()){
            var key=new AggregateKey(AggregateKind.ANCHOR,a.anchorId().value());var existing=old.get(key);
            AnchorRecord before=existing==null?null:((AnchorAggregate)existing).value();
            require(before==null||before.origin()==AnchorOrigin.IMPORTED,"anchor origin collision");
            Set<TagId> tags=new HashSet<>(before==null?Set.of():before.tags());
            tags.removeAll(beforeTags.getOrDefault(a.anchorId(),Set.of()));tags.addAll(afterTags.get(a.anchorId()));
            var value=new AnchorRecord(next(existing,a.anchorId().value(),tick),a.anchorId(),Optional.of(a.locationId()),
                locations.get(a.locationId()).regionIds(),a.dimension(),
                new BlockPosition(a.dimension(),a.position().x(),a.position().y(),a.position().z()),
                before==null?"region.camp_radius_blocks":before.radiusProfile(),tags,AnchorOrigin.IMPORTED,
                EvidenceStatus.CONFIRMED,true);
            changes.put(key,new AnchorAggregate(value));
        }
        // Missing entries retire only identities from the immediately previous
        // authored source. Runtime memory/personality and extra anchor tags stay.
        if(previous!=null){
            for(var a:previous.anchors()){
                var key=new AggregateKey(AggregateKind.ANCHOR,a.anchorId().value());
                if(desired.contains(key))continue;
                var existing=Objects.requireNonNull(old.get(key),"removed anchor missing");var before=((AnchorAggregate)existing).value();
                require(source.equals(ownership.get(key))&&before.origin()==AnchorOrigin.IMPORTED,"removed anchor ownership");
                changes.put(key,new AnchorAggregate(new AnchorRecord(next(existing,key.id(),tick),before.anchorId(),
                    before.locationId(),before.regionIds(),before.dimension(),before.position(),before.radiusProfile(),
                    before.tags(),before.origin(),before.evidence(),false)));
            }
            for(var l:previous.locations()){
                var key=new AggregateKey(AggregateKind.LOCATION,l.locationId().value());if(desired.contains(key))continue;
                var existing=Objects.requireNonNull(old.get(key),"removed location missing");var before=((LocationAggregate)existing).value();
                require(source.equals(ownership.get(key))&&before.sourceNamespace().equals(source),"removed location ownership");
                changes.put(key,new LocationAggregate(new LocationRecord(next(existing,key.id(),tick),before.locationId(),
                    source,before.externalId(),before.dimension(),before.bounds(),before.tags(),before.anchorIds(),
                    input.exportRevision(),false)));
                cas.put(new ScopeRef(ScopeKind.LOCATION,key.id()),existing.header().revision());
            }
            for(var r:previous.regions()){
                var key=new AggregateKey(AggregateKind.REGION,r.regionId().value());if(desired.contains(key))continue;
                var existing=Objects.requireNonNull(old.get(key),"removed region missing");var before=((RegionAggregate)existing).value();
                require(source.equals(ownership.get(key))&&before.status()!=RegionStatus.ALIAS,"removed region ownership/topology");
                changes.put(key,new RegionAggregate(new RegionRecord(next(existing,key.id(),tick),before.regionId(),
                    before.dimension(),RegionStatus.ARCHIVED,before.supportShape(),before.anchorIds(),before.importance(),
                    before.parentIds(),before.aliases(),before.membershipRevision().plus(1),before.personality(),before.memoryRefs())));
                cas.put(new ScopeRef(ScopeKind.REGION,key.id()),existing.header().revision());
            }
        }
        require(changes.size()<=MAX_ENTRIES*2,"import batch aggregate bound");
        return new Plan(Map.copyOf(changes),Map.copyOf(cas));
    }
    @Override public void close(){
        actor();closed=true;
        // No actor wait, filesystem close or blind WAL cancellation.
        io.execute(()->{
            try{
                var importer=opening.getNow(null);
                if(importer!=null)importer.closeAsync().whenComplete((v,e)->io.shutdown());
                else io.shutdown();
            }catch(RuntimeException error){io.shutdown();}
        });
    }
}

