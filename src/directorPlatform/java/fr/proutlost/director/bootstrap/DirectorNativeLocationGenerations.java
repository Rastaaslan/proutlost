package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.codec.MetadataAttemptContract;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.integration.locations.*;
import fr.proutlost.director.persistence.JournalStore;
import fr.proutlost.director.runtime.ResultTransaction;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/** Authenticated operator admission is owned by DirectorNativeLocationImports.
 * Transport bytes and proposed heads are prepared in J04; only its activation
 * transaction publishes heads. The private IO executor never mutates Minecraft.
 */
final class DirectorNativeLocationGenerations implements AutoCloseable {
    private static final ModelCodec<LocationImport> IMPORT=new ModelCodec<>(LocationImport.class);
    private static final ModelCodec<MetadataGenerationPage> PAGE=new ModelCodec<>(MetadataGenerationPage.class);
    private static final ResourceId SOURCE_CODEC=new ResourceId("proutlost_director:location_transfer_fragment");
    private static final ResourceId PAGE_CODEC=new ResourceId("proutlost_director:metadata_generation_page");
    private record Transfer(LocationTransferManifest manifest,LocationImport value,List<byte[]> fragments) {}
    private record Tags(Map<ResourceId,Fingerprint> generations,MemoryView memory,Map<RegionId,Set<TagId>> values) {}
    private final MinecraftServer server;
    private final WorldId world;
    private final JournalStore store;
    private final StoreSession session;
    private final Supplier<Optional<MemoryView>> memory;
    private final BooleanSupplier quiescent;
    private final Predicate<Object> acquire;
    private final Consumer<Object> release;
    private final Supplier<Map<ResourceId,LocationImport>> legacy;
    private final Function<AggregateKey,Optional<ResourceId>> legacyOwner;
    private final Map<DimensionId,ImportBounds> dimensions;
    private final Object lane=new Object();
    private final ExecutorService io=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"director-native-generation-io");t.setDaemon(true);return t;});
    private final Path inputs;
    private CompletableFuture<Transfer> input;
    private CompletableFuture<LocationTransferManifest> cancelInput;
    private LocationTransferManifest cancelling;
    private CompletableFuture<Optional<LocationImport>> previousRead;
    private CompletableFuture<Optional<MetadataGenerationPage>> stagedRead;
    private CompletableFuture<Optional<Tick>> preparedTickRead;
    private CompletableFuture<StoreReadResult> headsRead;
    private CompletableFuture<List<MetadataPlanSpool.FileRef>> packing;
    private CompletableFuture<MetadataGenerationPage> packedRead;
    private final List<MetadataPlanSpool.FileRef> packedPages=new ArrayList<>();
    private int planningGroup;
    private long packedBytes;
    private CompletableFuture<CommitAck> writing;
    private CompletableFuture<Tags> tagsRead;
    private JournalStore.MetadataSnapshot snapshot;
    private Transfer transfer;
    private LocationImport previous;
    private DirectorNativeLocationGenerationPlan planner;
    private DurableBatch batch;
    private StoreQuery headQuery;
    private List<AggregateKey> pageKeys;
    private int sourceIndex,stageIndex;
    private Sequence committed=new Sequence(0),tagCut=new Sequence(0);
    private boolean held,failed,closed,done,activated,previousLoaded,preparationDurable;
    private Tick preparedClock,repreparedClock;
    private Epoch attempt=new Epoch(0),batchAttempt;
    private boolean preflightDone,needsReprepare;
    private int preflightGroup,preflightStage;
    private List<Integer> existingStages=List.of();
    private String phase="IDLE",last="NONE";
    private Tags publishedTags;

    DirectorNativeLocationGenerations(MinecraftServer server,WorldId world,JournalStore store,
            Supplier<Optional<MemoryView>> memory,BooleanSupplier quiescent,Predicate<Object> acquire,
            Consumer<Object> release,Map<DimensionId,ImportBounds> dimensions,
            Supplier<Map<ResourceId,LocationImport>> legacy,Function<AggregateKey,Optional<ResourceId>> legacyOwner){
        this.server=server;this.world=world;this.store=store;this.memory=memory;this.quiescent=quiescent;
        this.acquire=acquire;this.release=release;this.dimensions=Map.copyOf(dimensions);this.legacy=legacy;this.legacyOwner=legacyOwner;
        session=store.session();require(world.equals(session.worldId()),"generation world/session");
        inputs=server.getWorldPath(LevelResource.ROOT).resolve("director-location-imports").toAbsolutePath().normalize();
    }
    private void actor(){if(closed||!server.isSameThread())throw new IllegalStateException("native generation actor");}
    private static void require(boolean b,String why){if(!b)throw new IllegalArgumentException(why);}
    boolean busy(){return input!=null||cancelInput!=null||cancelling!=null||transfer!=null||writing!=null||held;}
    boolean complete(){return done&&!busy()&&!failed;}
    boolean failed(){return failed||last.startsWith("REFUSED");}
    boolean laneHeld(){return held;}
    String status(){return "LOCATION_IMPORT "+phase+" lane="+held+" source_pages="+sourceIndex+" metadata_pages="+stageIndex+" attempt="+attempt.value()+" sequence="+committed.value()+" "+last;}
    String submit(String name){
        actor();if(failed)return "Import indisponible: "+last;
        if(busy())return "Un import est deja en cours.";
        if(name==null||!name.matches("[a-z0-9][a-z0-9_-]{0,63}"))return "Nom de fichier invalide.";
        done=false;last="PENDING "+name;phase="READING_INPUT";
        input=CompletableFuture.supplyAsync(()->readTransfer(name),io);
        return "Lecture de director-location-imports/"+name+(name.endsWith("_transfer")?"/":".json")+" en cours.";
    }
    String cancel(String name){
        actor();if(failed)return "Reprise J04 requise avant annulation.";
        if(busy())return "Un import est deja en cours.";
        if(name==null||!name.matches("[a-z0-9][a-z0-9_-]{0,63}_transfer"))return "Nom de transfert invalide.";
        done=false;last="PENDING_CANCELLATION";phase="READING_CANCELLATION";
        cancelInput=CompletableFuture.supplyAsync(()->{
            try{byte[] bytes=read(inputs.resolve(name).resolve("manifest.json"),LocationTransfer.WIRE_BYTES);
                var value=new ModelCodec<>(LocationTransferManifest.class).decode(bytes);
                require(Arrays.equals(bytes,new ModelCodec<>(LocationTransferManifest.class).encode(value))&&value.worldId().equals(world),"cancellation manifest identity");return value;
            }catch(IOException error){throw new CompletionException(error);}
        },io);return "Annulation du transfert en attente; la generation active est conservee.";
    }
    /** Files are transport messages, never separate source revisions. */
    private Transfer readTransfer(String name){
        try{
            if(!name.endsWith("_transfer")){
                byte[] bytes=read(inputs.resolve(name+".json"),LocationTransfer.WIRE_BYTES);
                var value=IMPORT.decode(bytes);StandaloneLocationImporter.requireNativeEnvelope(value);
                validate(value);var prepared=LocationTransfer.prepare(value);
                return new Transfer(prepared.manifest(),value,prepared.pages().stream().map(p->LocationTransfer.fragment(prepared.manifest(),p)).toList());
            }
            Path directory=inputs.resolve(name);noLinks(directory);
            require(Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS),"explicit transfer directory required");
            byte[] manifestBytes=read(directory.resolve("manifest.json"),LocationTransfer.WIRE_BYTES);
            var manifest=new ModelCodec<>(LocationTransferManifest.class).decode(manifestBytes);
            require(Arrays.equals(manifestBytes,new ModelCodec<>(LocationTransferManifest.class).encode(manifest)),"noncanonical transfer manifest");
            require(manifest.worldId().equals(world),"foreign transfer world");
            var fragments=new byte[manifest.pageHashes().size()][];
            List<Path> messages;
            try(var files=Files.list(directory)){messages=files.filter(p->!p.getFileName().toString().equals("manifest.json")).limit(2L*fragments.length+1).toList();}
            require(messages.size()<=2*fragments.length,"transfer message count bound");
            for(var file:messages){
                byte[] wire=read(file,LocationTransfer.WIRE_BYTES);
                var page=new ModelCodec<>(LocationTransferPage.class).decode(wire);
                require(Arrays.equals(wire,new ModelCodec<>(LocationTransferPage.class).encode(page)),"noncanonical transfer page");
                byte[] fragment=LocationTransfer.fragment(manifest,page);
                var old=fragments[page.pageIndex()];require(old==null||Arrays.equals(old,fragment),"contradictory duplicate page");
                fragments[page.pageIndex()]=fragment;
            }
            var bytes=new ByteArrayOutputStream(manifest.sourceBytes());
            for(var fragment:fragments){require(fragment!=null,"missing page; prior generation retained");bytes.writeBytes(fragment);}
            byte[] assembled=bytes.toByteArray();var value=IMPORT.decode(assembled);
            LocationTransfer.matches(manifest,value,assembled);validate(value);
            return new Transfer(manifest,value,List.of(fragments));
        }catch(IOException error){throw new CompletionException(error);}
    }
    private static byte[] read(Path path,int max)throws IOException{
        noLinks(path);require(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)&&Files.size(path)>0&&Files.size(path)<=max,"import file type/size");
        try(var in=Files.newInputStream(path)){byte[] b=in.readNBytes(max+1);require(b.length<=max,"import grew above bound");return b;}
    }
    private static void noLinks(Path path)throws IOException{for(var p=path;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("symlinked import path");}
    private void validate(LocationImport value){
        require(value.worldId().equals(world)&&value.exportDigest().equals(StandaloneLocationImporter.digest(value)),"import world/digest");
        require(IMPORT.encode(value).length<=LocationTransfer.SOURCE_BYTES,"complete source byte bound");
        DirectorNativeLocationImports.requireAuthoredScopes(value);
        var ids=new HashSet<UUID>();var regions=new HashMap<RegionId,ImportedRegion>();
        for(var r:value.regions()){require(ids.add(r.regionId().value()),"duplicate import identity");bounds(r.dimension(),r.bounds());regions.put(r.regionId(),r);}
        var locations=new HashMap<LocationId,ImportedLocation>();var external=new HashSet<String>();
        for(var l:value.locations()){
            require(ids.add(l.locationId().value())&&external.add(l.externalId()),"duplicate location identity");bounds(l.dimension(),l.bounds());
            for(var id:l.regionIds())require(regions.containsKey(id)&&regions.get(id).dimension().equals(l.dimension()),"region reference");
            locations.put(l.locationId(),l);
        }
        for(var a:value.anchors()){
            require(ids.add(a.anchorId().value()),"duplicate anchor identity");var l=locations.get(a.locationId());
            require(l!=null&&l.dimension().equals(a.dimension())&&contains(l.bounds(),a.position()),"anchor location/bounds");
            for(var id:l.regionIds())require(contains(regions.get(id).bounds(),a.position()),"anchor region bounds");
        }
    }
    private static boolean contains(ImportBounds b,ImportPosition p){return p.x()>=b.minX()&&p.x()<b.maxXExclusive()&&p.y()>=b.minY()&&p.y()<b.maxYExclusive()&&p.z()>=b.minZ()&&p.z()<b.maxZExclusive();}
    private void bounds(DimensionId d,ImportBounds b){var x=dimensions.get(d);require(x!=null&&b.minX()>=x.minX()&&b.minY()>=x.minY()&&b.minZ()>=x.minZ()&&b.maxXExclusive()<=x.maxXExclusive()&&b.maxYExclusive()<=x.maxYExclusive()&&b.maxZExclusive()<=x.maxZExclusive(),"dimension/border/height");}

    private String receiptBinding(){return " source="+transfer.manifest().sourceNamespace().value()
        +" generation="+LocationTransfer.identity(transfer.manifest()).value();}
    private void append(List<DurableRecord> records,List<BlobContent> blobs,String next){
        batchAttempt=attempt;batch=ResultTransaction.batch(new TransactionId(UUID.randomUUID()),session.storeGeneration(),Map.of(),records,blobs);
        writing=store.append(batch).toCompletableFuture();phase=next;
    }
    private void checkAck(CommitAck ack){
        require(attempt.equals(batchAttempt)&&ack!=null&&ack.ackStatus()==AckStatus.DURABLE&&ack.transactionId().equals(batch.transactionId())
            &&ack.transactionHash().equals(batch.transactionHash())&&ack.storeGeneration().equals(session.storeGeneration())
            &&ack.permitAttempt().isEmpty()&&ack.forcedBlobs().equals(DurableBlobReferences.collect(batch.records()))
            &&ack.durableSequence().value()>0,"J04 generation ACK uncertain/rejected");
        committed=ack.durableSequence();
    }
    void poll(){
        actor();if(failed)return;
        try{
            require(session.equals(store.session())&&!store.readOnlySafe(),"J04 generation authority unavailable");
            refreshTags();
            if(input!=null){
                if(!input.isDone())return;transfer=input.join();input=null;phase="WAIT_QUIESCENT";
            }
            if(cancelInput!=null){if(!cancelInput.isDone())return;cancelling=cancelInput.join();cancelInput=null;}
            if(cancelling!=null){
                if(!held){if(!quiescent.getAsBoolean()||!acquire.test(lane))return;held=true;}
                if(writing==null){append(List.of(new DurableMetadataCancellation(cancelling)),List.of(),"CANCELLATION_PENDING");return;}
                if(!writing.isDone())return;var ack=writing.join();
                if(ack!=null&&ack.ackStatus()==AckStatus.REJECTED){writing=null;batch=null;last="REFUSED cancellation";clear();return;}
                checkAck(ack);writing=null;batch=null;last="CANCELLED_STAGING_ONLY";clear();return;
            }
            if(transfer==null)return;
            if(!held){if(!quiescent.getAsBoolean()||!acquire.test(lane))return;held=true;}
            if(snapshot==null){
                var available=store.metadataSnapshot();if(available.isEmpty())return;snapshot=available.get();
                var sources=new HashSet<>(snapshot.sources().keySet());sources.addAll(legacy.get().keySet());
                require(sources.contains(transfer.value().sourceNamespace())||sources.size()<32,"import source capacity");
                previousRead=store.readMetadataSource(snapshot,transfer.value().sourceNamespace()).toCompletableFuture();
                phase="READING_PREVIOUS_SOURCE";return;
            }
            if(!previousLoaded){
                if(!previousRead.isDone())return;
                previous=previousRead.join().orElse(legacy.get().get(transfer.value().sourceNamespace()));previousRead=null;previousLoaded=true;
                if(previous!=null){
                    int order=transfer.value().exportRevision().compareTo(previous.exportRevision());
                    require(order>0||order==0&&transfer.value().exportDigest().equals(previous.exportDigest()),"source revision conflict/obsolete");
                    if(order==0){done=true;last="ALREADY_DURABLE";clear();return;}
                }
                
                var manifest=transfer.manifest();preparedTickRead=CompletableFuture.supplyAsync(()->{
                    try{return MetadataPlanSpool.preparedTick(inputs,manifest);}
                    catch(IOException error){throw new CompletionException(error);}
                },io);return;
            }
            if(planner==null){
                if(!preparedTickRead.isDone())return;
                long now=fr.proutlost.director.neoforge.DirectorStandaloneMod.directorLifecycle().runtimeClock(server);
                var durableClock=store.metadataPreparationTick(transfer.manifest());var localClock=preparedTickRead.join();
                // The original declaration remains historical. A later attempt's
                // clock comes only from its separately forced J04 reprepare fact.
                Tick original=MetadataPreparationClock.proposalTick(durableClock,localClock,new Tick(now));preparedTickRead=null;
                var current=store.metadataAttempt(transfer.manifest());
                if(current.isPresent())require(current.get().session().equals(session),"metadata attempt session changed");
                attempt=current.map(JournalStore.MetadataAttempt::attempt).orElse(new Epoch(0));
                preparedClock=current.flatMap(JournalStore.MetadataAttempt::plannedTick).orElse(original);
                existingStages=current.map(x->x.pages().stream().sorted().toList()).orElse(List.of());
                preparationDurable=durableClock.isPresent();
                require(preparedClock.value()<=now,"prepared generation clock ahead of native authority");
                planner=new DirectorNativeLocationGenerationPlan(world,transfer.value(),previous,preparedClock,
                    k->store.metadataOwner(snapshot,k).or(()->legacyOwner.apply(k)));
            }
            if(writing!=null){
                if(!writing.isDone())return;var ack=writing.join();
                if(ack!=null&&ack.ackStatus()==AckStatus.REJECTED){writing=null;batch=null;last="REFUSED J04 generation conflict";clear();return;}
                checkAck(ack);writing=null;batch=null;
                if(phase.equals("REPREPARATION_PENDING")){
                    var current=store.metadataAttempt(transfer.manifest()).orElseThrow();
                    require(current.session().equals(session)&&current.attempt().equals(attempt.plus(1))
                        &&current.plannedTick().equals(Optional.of(repreparedClock))&&current.pages().isEmpty(),"reprepare ACK lacks current attempt authority");
                    attempt=current.attempt();preparedClock=repreparedClock;repreparedClock=null;existingStages=List.of();
                    snapshot=store.metadataSnapshot().orElseThrow();
                    planner=new DirectorNativeLocationGenerationPlan(world,transfer.value(),previous,preparedClock,
                        k->store.metadataOwner(snapshot,k).or(()->legacyOwner.apply(k)));
                    preflightDone=true;needsReprepare=false;
                    System.out.println("DIRECTOR_LOCATION_REPREPARATION_DURABLE attempt="+attempt.value()+" tick="+preparedClock.value()+" cut="+committed.value()+receiptBinding());
                }
                else if(phase.equals("CLOCK_PREPARATION_PENDING")){preparationDurable=true;System.out.println("DIRECTOR_LOCATION_PREPARATION_CLOCK_DURABLE tick="+preparedClock.value()+" attempt="+attempt.value()+" cut="+committed.value()+receiptBinding());}
                else if(phase.equals("SOURCE_PAGE_PENDING")){System.out.println("DIRECTOR_LOCATION_SOURCE_PAGE_DURABLE index="+sourceIndex+" attempt="+attempt.value()+" cut="+committed.value()+receiptBinding());sourceIndex++;}
                else if(phase.equals("METADATA_PAGE_PENDING")){System.out.println("DIRECTOR_LOCATION_METADATA_PAGE_DURABLE attempt="+attempt.value()+" index="+stageIndex+" cut="+committed.value()+receiptBinding());stageIndex++;}
                else if(phase.equals("ACTIVATION_PENDING")){activated=true;System.out.println("DIRECTOR_LOCATION_GENERATION_ACTIVATED attempt="+attempt.value()+" cut="+committed.value()+receiptBinding());}
                phase="PREPARING";return;
            }
            if(!preparationDurable){
                // The native clock declaration is forced by J04 before private
                // packing starts. A spool is never used to advance a cold clock.
                append(List.of(new DurableFact(MetadataPreparationClock.fact(transfer.manifest(),preparedClock))),List.of(),"CLOCK_PREPARATION_PENDING");return;
            }
            if(!preflightDone){
                // At most 96 lightweight keys per poll. No body hydration on the
                // actor, and no private spool can establish a durable old read set.
                if(preflightGroup<planner.pageCount()){
                    long now=fr.proutlost.director.neoforge.DirectorStandaloneMod.directorLifecycle().runtimeClock(server);
                    for(var key:planner.pageKeys(preflightGroup++)){
                        var tick=store.metadataUpdatedTick(snapshot,key);require(tick.value()<=now,"metadata head ahead of native clock");
                        if(tick.compareTo(preparedClock)>0)needsReprepare=true;
                    }
                    phase="CHECKING_CURRENT_HEADS";return;
                }
                if(preflightStage<existingStages.size()){
                    if(stagedRead==null){stagedRead=store.readStagedMetadataPage(transfer.manifest(),attempt,existingStages.get(preflightStage)).toCompletableFuture();return;}
                    if(!stagedRead.isDone())return;var page=stagedRead.join().orElseThrow();stagedRead=null;
                    for(var guard:page.beforeRevisions().entrySet())if(!store.metadataRevision(snapshot,guard.getKey()).equals(guard.getValue()))needsReprepare=true;
                    preflightStage++;phase="CHECKING_STAGED_GUARDS";return;
                }
                if(needsReprepare){
                    repreparedClock=new Tick(fr.proutlost.director.neoforge.DirectorStandaloneMod.directorLifecycle().runtimeClock(server));
                    require(repreparedClock.compareTo(preparedClock)>=0,"reprepare native clock regression");
                    var active=Optional.ofNullable(snapshot.generations().get(transfer.manifest().sourceNamespace()));
                    append(List.of(new DurableFact(MetadataAttemptContract.reprepare(transfer.manifest(),attempt,active,repreparedClock))),List.of(),"REPREPARATION_PENDING");return;
                }
                preflightDone=true;phase="PREPARING";return;
            }
            if(sourceIndex<transfer.fragments().size()){
                var blob=BlobContent.capture(SOURCE_CODEC,1,transfer.fragments().get(sourceIndex));
                append(List.of(new DurableMetadataSourcePage(transfer.manifest(),sourceIndex,blob.reference())),List.of(blob),"SOURCE_PAGE_PENDING");return;
            }
            // First finish bounded packing on private IO. The final immutable
            // J04 page count is frozen only after the complete plan fits the
            // unchanged codec/transaction limits. The spool has no authority.
            if(planningGroup<planner.pageCount()){
                if(packing!=null){
                    if(!packing.isDone())return;var files=packing.join();packing=null;
                    for(var file:files){packedBytes=Math.addExact(packedBytes,file.bytes());packedPages.add(file);}
                    require(packedPages.size()<=1024&&packedBytes<=64L*1024*1024,"native prepared generation capacity");
                    planningGroup++;phase="PLANNING_METADATA";return;
                }
                if(headsRead==null){
                    pageKeys=planner.pageKeys(planningGroup);
                    headQuery=new StoreQuery(world,Set.copyOf(pageKeys),new Sequence(0),Optional.empty(),96);
                    headsRead=store.readMetadata(snapshot,headQuery).toCompletableFuture();return;
                }
                if(!headsRead.isDone())return;var read=headsRead.join();headsRead=null;ReadContracts.validate(read,headQuery,session);
                require(read.status()==ReadStatus.AVAILABLE&&!read.page().orElseThrow().hasMore(),"generation head page unavailable");
                var actual=new HashMap<AggregateKey,AggregateState>();for(var h:read.page().orElseThrow().aggregates())actual.put(h.state().key(),h.state());
                var planned=planner.planPage(pageKeys,actual);var frozenManifest=transfer.manifest();int group=planningGroup;
                var frozenAttempt=attempt;long bytesLeft=64L*1024*1024-packedBytes;int pagesLeft=1024-packedPages.size();long reserve=store.metadataPreparationReserveBytes();
                packing=CompletableFuture.supplyAsync(()->{
                    try{return MetadataPlanSpool.prepare(inputs,frozenManifest,frozenAttempt,group,planned.states(),planned.beforeRevisions(),bytesLeft,pagesLeft,reserve);}
                    catch(IOException error){throw new CompletionException(error);}
                },io);return;
            }
            if(stageIndex<packedPages.size()){
                if(packedRead==null&&stagedRead==null){
                    var reference=packedPages.get(stageIndex);var manifest=transfer.manifest();int index=stageIndex,count=packedPages.size();
                    packedRead=CompletableFuture.supplyAsync(()->{
                        try{return MetadataPlanSpool.read(reference,manifest,index,count);}
                        catch(IOException error){throw new CompletionException(error);}
                    },io);return;
                }
                if(packedRead!=null&&!packedRead.isDone())return;
                if(stagedRead==null){stagedRead=store.readStagedMetadataPage(transfer.manifest(),attempt,stageIndex).toCompletableFuture();return;}
                if(!stagedRead.isDone())return;var existing=stagedRead.join();stagedRead=null;var page=packedRead.join();packedRead=null;
                if(existing.isPresent()){
                    require(existing.get().equals(page),"immutable staged plan changed; preserve and reconcile");stageIndex++;return;
                }
                var blob=BlobContent.capture(PAGE_CODEC,1,PAGE.encode(page));
                var staged=new DurableMetadataStage(transfer.manifest(),stageIndex,packedPages.size(),blob.reference());
                append(List.of(attempt.value()==0?staged:new DurableFact(MetadataAttemptContract.stage(attempt,staged))),List.of(blob),"METADATA_PAGE_PENDING");return;
            }
            if(!activated){
                var records=new ArrayList<DurableRecord>();
                var activation=new DurableMetadataActivation(transfer.manifest(),Optional.ofNullable(snapshot.generations().get(transfer.value().sourceNamespace())),packedPages.size());
                records.add(attempt.value()==0?activation:new DurableFact(MetadataAttemptContract.activate(attempt,activation)));
                // Retain the original small-import fact contract for existing
                // chronology consumers. Large imports use J04 source fragments.
                if(IMPORT.encode(transfer.value()).length<=LocationTransfer.WIRE_BYTES
                    &&(long)transfer.value().regions().size()+transfer.value().locations().size()+transfer.value().anchors().size()<=96)
                    records.add(new DurableFact(legacyFact(transfer.value())));
                append(records,List.of(),"ACTIVATION_PENDING");return;
            }
            var active=store.metadataSnapshot();if(active.isEmpty())return;
            require(LocationTransfer.identity(transfer.manifest()).equals(active.get().generations().get(transfer.value().sourceNamespace())),"generation activation not observable");
            // The actual reader publishes its own bounded selected context; an ACK
            // here claims J04 activation, never fullpack or human reception.
            System.out.println("DIRECTOR_LOCATION_IMPORT_DURABLE source="+transfer.value().sourceNamespace().value()
                +" revision="+transfer.value().exportRevision().value()+" attempt="+attempt.value()+" sequence="+committed.value()+" digest="+transfer.value().exportDigest().value()+" scope=J04_GENERATION");
            done=true;last="DURABLE";clear();
        }catch(RuntimeException failure){
            if(writing!=null||activated){failed=true;phase="FAILED_SAFE";last="UNCERTAIN "+failure.getClass().getSimpleName();}
            else{last="REFUSED "+rootCause(failure);clear();}
            System.out.println("DIRECTOR_LOCATION_IMPORT_REFUSED "+last);
        }
    }
    private DirectorFact legacyFact(LocationImport value){
        var id=new FactId(UUID.nameUUIDFromBytes(("director-location-import-v1/"+world.value()+"/"
            +value.sourceNamespace().value()+"/"+value.exportRevision().value()+"/"+value.exportDigest().value()).getBytes(StandardCharsets.UTF_8)));
        return new DirectorFact(id,DirectorNativeLocationImports.APPLIED,List.of(new ScopeRef(ScopeKind.WORLD,world.value())),
            Optional.empty(),EvidenceStatus.CONFIRMED,Map.of(
                "source_namespace",new JsonValue.Text(value.sourceNamespace().value()),
                "export_revision",new JsonValue.Text(Long.toString(value.exportRevision().value())),
                "export_digest",new JsonValue.Text(value.exportDigest().value()),
                "location_import_base64",new JsonValue.Text(Base64.getEncoder().encodeToString(IMPORT.encode(value))),
                "tick",new JsonValue.Text(Long.toString(fr.proutlost.director.neoforge.DirectorStandaloneMod.directorLifecycle().runtimeClock(server))),
                "operator_authored",new JsonValue.Bool(true)),true,Optional.empty());
    }
    private static String rootCause(Throwable error){while(error.getCause()!=null)error=error.getCause();return error.getClass().getSimpleName()+":"+error.getMessage();}
    private void clear(){
        input=null;cancelInput=null;cancelling=null;transfer=null;previous=null;previousRead=null;previousLoaded=false;stagedRead=null;preparedTickRead=null;headsRead=null;
        snapshot=null;planner=null;batch=null;writing=null;packing=null;packedRead=null;packedPages.clear();planningGroup=0;packedBytes=0;sourceIndex=0;stageIndex=0;activated=false;preparationDurable=false;preparedClock=null;repreparedClock=null;attempt=new Epoch(0);batchAttempt=null;preflightDone=false;needsReprepare=false;preflightGroup=0;preflightStage=0;existingStages=List.of();
        if(held){release.accept(lane);held=false;}phase="IDLE";
    }
    private static boolean sameMemory(MemoryView a,MemoryView b){return a.regionRecords().equals(b.regionRecords())&&a.locations().equals(b.locations());}
    private void refreshTags(){
        var current=store.metadataSnapshot();var remembered=memory.get();if(current.isEmpty()||remembered.isEmpty())return;
        var snap=current.get();var view=remembered.get();
        if(tagsRead!=null){
            if(!tagsRead.isDone())return;var next=tagsRead.join();tagsRead=null;
            if(next.generations().equals(snap.generations())&&sameMemory(next.memory(),view)){publishedTags=next;tagCut=snap.cut();}
        }
        if(publishedTags!=null&&publishedTags.generations().equals(snap.generations())&&sameMemory(publishedTags.memory(),view)){tagCut=snap.cut();return;}
        var historical=legacy.get();
        tagsRead=CompletableFuture.supplyAsync(()->{
            var result=new HashMap<RegionId,Set<TagId>>();var sources=new HashSet<>(historical.keySet());sources.addAll(snap.sources().keySet());
            for(var source:sources){
                var imported=store.readMetadataSource(snap,source).toCompletableFuture().join().orElse(historical.get(source));
                if(imported==null)continue;
                for(var r:imported.regions()){
                    var actual=view.regionRecords().get(r.regionId());if(actual==null)continue;
                    require(actual.dimension().equals(r.dimension()),"authored region dimension changed");
                    if(actual.status()==RegionStatus.ACTIVE||actual.status()==RegionStatus.DORMANT)result.put(r.regionId(),new HashSet<>(r.tags()));
                }
                for(var location:imported.locations())for(var id:location.regionIds())if(result.containsKey(id))result.get(id).addAll(location.tags());
            }
            var frozen=new HashMap<RegionId,Set<TagId>>();result.forEach((id,tags)->frozen.put(id,Set.copyOf(tags)));
            return new Tags(snap.generations(),view,Map.copyOf(frozen));
        },io);
    }
    boolean tagsReady(){
        if(held||failed||publishedTags==null)return false;var m=memory.get();var s=store.metadataSnapshot();
        return m.isPresent()&&s.isPresent()&&sameMemory(publishedTags.memory(),m.get())&&publishedTags.generations().equals(s.get().generations());
    }
    Map<RegionId,Set<TagId>> tags(){actor();require(tagsReady(),"authored tags not at selected generation");return publishedTags.values();}
    Sequence tagsCut(){return tagCut;}
    @Override public void close(){actor();closed=true;io.shutdown();}
}
