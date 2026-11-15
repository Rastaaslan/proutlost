package fr.proutlost.director.persistence;

import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.permission.CommitPermit;
import fr.proutlost.director.api.port.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static java.nio.file.StandardOpenOption.*;
import static fr.proutlost.director.persistence.StoreJson.*;
import static fr.proutlost.director.persistence.StoreProjection.require;

/** Single IO writer. Startup/close/checkpoint are asynchronous; views are published memory only.
 * Completion callbacks carry DTOs, never Minecraft permission. Bootstrap owns this store path.
 */
public final class JournalStore implements DurableStore, FactBusPort, ProviderProvenance {
    @Override public Set<ProviderMode> supportedModes(){return Set.of(ProviderMode.TEST_ONLY,ProviderMode.PRODUCTION);}
    @FunctionalInterface interface IoProbe { void at(String point) throws IOException; }
    private final Path root;
    private final WorldId world;
    private final StoreOptions options;
    private final IoProbe probe;
    private final ThreadPoolExecutor writer;
    private final AtomicLong queuedBytes = new AtomicLong();
    private final AtomicBoolean closing = new AtomicBoolean();
    private volatile StoreProjection published = new StoreProjection();
    private volatile StoreSession session;
    private volatile boolean readOnly;
    private volatile String diagnostic = "RECOVERING";
    private FileChannel lockChannel, wal;
    private FileLock lock;
    private CheckpointStore checkpoints;
    private volatile CheckpointStore.Manifest manifest;
    // Writer-only, independently reconstructed once per immutable checkpoint.
    // Never share an unchecked sidecar as a proof of absent data.
    private ArchivedWalIndex verifiedColdTransactionIndex;
    private ArchivedAggregatePages verifiedColdAggregateIndex;
    // LAB-ONLY: disabled by default, configured only through explicit test API.
    private AutomaticReadEvictionPolicy labPolicy;
    private long lastAutomaticEvictionGeneration;
    private volatile AutomaticReadEvictionPolicy.Outcome lastAutoEviction =
        new AutomaticReadEvictionPolicy.Outcome(
            AutomaticReadEvictionPolicy.Reason.DISABLED,
            ReadStatus.NOT_RESIDENT,0,0,0,0,0);
    private final List<Path> segments = new ArrayList<>();
    private long sequence;
    private final Map<ConsumerId, ConsumerSpec> consumers = new ConcurrentHashMap<>();
    private final Map<ConsumerId, Subscription> deliveries = new ConcurrentHashMap<>();
    private static final ModelCodec<DurableRecord> RECORD = new ModelCodec<>(DurableRecord.class);

    private JournalStore(Path root, WorldId world, StoreOptions options, IoProbe probe) {
        this.root = root.toAbsolutePath().normalize(); this.world = Objects.requireNonNull(world);
        this.options = Objects.requireNonNull(options); this.probe = probe;
        writer = new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(options.queueTasks()),r -> {
            Thread t = new Thread(r,"proutlost-director-store"); t.setDaemon(true); return t;
        },new ThreadPoolExecutor.AbortPolicy());
    }
    public static CompletionStage<JournalStore> open(Path directory, WorldId world, StoreOptions options) {
        return open(directory,world,options,point -> {});
    }
    static CompletionStage<JournalStore> open(Path directory, WorldId world, StoreOptions options, IoProbe probe) {
        JournalStore store = new JournalStore(directory,world,options,probe);
        CompletableFuture<JournalStore> result = new CompletableFuture<>();
        store.writer.execute(() -> {
            try { store.initialize(); result.complete(store); }
            catch (Exception e) { var closeError=store.release();if(closeError!=null)e.addSuppressed(closeError);store.writer.shutdown(); result.completeExceptionally(e); }
        }); return result.minimalCompletionStage();
    }
    /**
     * Immutable identity of an already verified durable manifest. This is a
     * diagnostic/identity anchor, NOT a commit permit or a world effect gate.
     */
    public record ManifestAnchor(UUID storeId, WorldId worldId, Epoch storeEpoch,
                                 long checkpointGeneration, long checkpointSequence) {
        public ManifestAnchor {
            Objects.requireNonNull(storeId);
            Objects.requireNonNull(worldId);
            Objects.requireNonNull(storeEpoch);
            if(checkpointGeneration<1 || checkpointSequence<0)
                throw new IllegalArgumentException("invalid persistent anchor");
        }
    }
    /** Never manufactures proof while recovering, read-only or closing. */
    public Optional<ManifestAnchor> verifiedManifestAnchor() {
        var manifestSnapshot=manifest;
        var sessionSnapshot=session;
        if(readOnly || closing.get() || !"RUNNING".equals(diagnostic) ||
            manifestSnapshot==null || sessionSnapshot==null ||
            !manifestSnapshot.world().equals(world) ||
            !sessionSnapshot.worldId().equals(world))
            return Optional.empty();
        return Optional.of(new ManifestAnchor(manifestSnapshot.store(),
            manifestSnapshot.world(),sessionSnapshot.storeGeneration(),
            manifestSnapshot.generation(),manifestSnapshot.sequence()));
    }
    public boolean readOnlySafe() { return readOnly; }
    public String diagnostic() { return diagnostic; }
    @Override public StoreSession session() { return session; }
    /** Read-only discovery of existing resident aggregate keys, never a WAL scan or authority.
     * The operational map is already immutable/published and is never evicted.
     * A capacity refusal is unknown, never a truncated successful empty index. */
    public record ResidentAggregateKeys(StoreSession session,Sequence cut,Set<AggregateKey> keys) {
        public ResidentAggregateKeys {Objects.requireNonNull(session);Objects.requireNonNull(cut);keys=Set.copyOf(keys);}
    }
    public Optional<ResidentAggregateKeys> residentAggregateKeys(Set<AggregateKind> kinds,int maximumEntries) {
        return discoverAggregateKeys(kinds,maximumEntries,state->true);
    }
    /** Planning excludes finished execution heads, never their durable history.
     * Settled regional world-state chains remain relevant planning context. */
    public Optional<ResidentAggregateKeys> residentPlanningAggregateKeys(Set<AggregateKind> kinds,int maximumEntries) {
        return discoverAggregateKeys(kinds,maximumEntries,JournalStore::planningHead);
    }
    /** Exactly the committed heads charged by CatalogueBudgetProjection. Old
     * released/removed rows remain queryable without exhausting an active budget. */
    public Optional<ResidentAggregateKeys> residentBudgetAggregateKeys(int maximumEntries) {
        return discoverAggregateKeys(Set.of(AggregateKind.REGION,AggregateKind.ENTITY_OWNERSHIP,
            AggregateKind.OWNERSHIP),maximumEntries,JournalStore::budgetHead);
    }
    private Optional<ResidentAggregateKeys> discoverAggregateKeys(Set<AggregateKind> kinds,int maximumEntries,
            java.util.function.Predicate<AggregateState> include) {
        Objects.requireNonNull(kinds);
        if(kinds.isEmpty()||maximumEntries<1||maximumEntries>4096)throw new IllegalArgumentException("aggregate key discovery bound");
        var bound=session;var snapshot=published;
        if(readOnly||bound==null)return Optional.empty();
        Set<AggregateKey> keys=new HashSet<>();
        for(var entry:snapshot.aggregates.entrySet()) {
            var key=entry.getKey();
            if(!kinds.contains(key.kind()) || !include.test(entry.getValue().state()))continue;
            keys.add(key);
            if(keys.size()>maximumEntries)return Optional.empty();
        }
        if(readOnly||!bound.equals(session))return Optional.empty();
        return Optional.of(new ResidentAggregateKeys(bound,new Sequence(snapshot.sequence),keys));
    }
    private static boolean planningHead(AggregateState state) {
        if(state instanceof EventAggregate event)return switch(event.value().state()) {
            case SUCCEEDED,PARTIAL,ABORTED_SAFE,FAILED_SAFE -> false;
            default -> true;
        };
        if(state instanceof ChainAggregate chain)return chain.value().state()!=ChainState.COMPLETED
            && chain.value().state()!=ChainState.ABORTED_SAFE
            && (chain.value().state()!=ChainState.SETTLED_AS_WORLD_STATE || chain.value().regionalStateRef().isPresent());
        if(state instanceof DisasterAggregate disaster)return disaster.value().stage()!=DisasterStage.RESOLVED
            && disaster.value().stage()!=DisasterStage.ABORTED_SAFE;
        if(state instanceof EntityOwnershipAggregate)return budgetHead(state);
        return true;
    }
    private static boolean budgetHead(AggregateState state) {
        if(state instanceof RegionAggregate region)return region.value().status()==RegionStatus.ACTIVE;
        if(state instanceof OwnershipAggregate owner)return owner.value().disposition()==OwnershipDisposition.DIRECTOR_ACTIVE;
        if(state instanceof EntityOwnershipAggregate entity)return entity.value().disposition()!=OwnershipDisposition.RELEASED
            && entity.value().phase()!=EntityPhase.REMOVED;
        return false;
    }

    /** Read-only lookup over the already published resident map; no index/WAL IO.
     * Random ownership UUIDs come from real OwnershipRecords, never a derived UUID. */
    public Optional<ResidentAggregateKeys> residentOwnershipKeys(Set<ResourceKey> resources,int maximumEntries) {
        Objects.requireNonNull(resources);
        if(resources.size()>16||maximumEntries<1||maximumEntries>4096)
            throw new IllegalArgumentException("ownership discovery bound");
        var bound=session;var snapshot=published;
        if(readOnly||bound==null)return Optional.empty();
        Set<AggregateKey> keys=new HashSet<>();
        for(var resource:resources) {
            var owner=snapshot.owners.get(resource);
            if(owner==null)continue;
            var key=new AggregateKey(AggregateKind.OWNERSHIP,owner.header().id());
            var head=snapshot.aggregates.get(key);
            if(head==null || !(head.state() instanceof OwnershipAggregate aggregate)
                || !aggregate.value().equals(owner) || !owner.resourceKey().equals(resource))return Optional.empty();
            keys.add(key);
            if(keys.size()>maximumEntries)return Optional.empty();
        }
        if(readOnly||!bound.equals(session))return Optional.empty();
        return Optional.of(new ResidentAggregateKeys(bound,new Sequence(snapshot.sequence),keys));
    }

    /** Exact bounded event/ownership join over the existing non-evicting operational maps.
     * No sidecar, IO, new writer, inferred UUID or history truncation is involved.
     * Missing/changed/capacity-limited evidence remains unknown. */
    public Optional<PhysicalEventReadback> physicalEventReadback(EventInstanceId event,int maximumRows) {
        Objects.requireNonNull(event);
        if(maximumRows<1||maximumRows>4096)throw new IllegalArgumentException("physical event readback bound");
        var bound=session;var snapshot=published;
        if(readOnly||bound==null||closing.get())return Optional.empty();
        var head=snapshot.aggregates.get(new AggregateKey(AggregateKind.EVENT,event.value()));
        Optional<EventInstance> actual=head!=null&&head.state() instanceof EventAggregate e
            ?Optional.of(e.value()):Optional.empty();
        var mutations=new HashMap<MutationId,MutationRecord>();
        var entities=new ArrayList<EntityOwnershipRecord>();
        var unresolved=new HashSet<OperationToken>();
        for(var entry:snapshot.aggregates.values()) {
            if(entry.state() instanceof MutationAggregate m&&m.value().instanceId().equals(event))
                mutations.put(m.value().mutationId(),m.value());
            if(entry.state() instanceof EntityOwnershipAggregate e&&e.value().instanceId().equals(event))
                entities.add(e.value());
            if(mutations.size()+entities.size()>maximumRows)return Optional.empty();
        }
        var resources=new HashSet<ResourceKey>();var cleanupWithoutMutation=new HashSet<MutationId>();
        for(var p:snapshot.prepares.values())if(p.slice().operations().stream().anyMatch(i->i.instanceId().equals(event))){
            p.reservation().claims().forEach(c->resources.add(c.resource()));resources.addAll(p.durableBlobs().expectedPostStates().keySet());
            if(resources.size()>maximumRows)return Optional.empty();
        }
        RegionalWeatherJournal.State latestRegional=null;int regionalRows=0;
        for(var fact:snapshot.facts.values())if(fact.factType().equals(RegionalWeatherJournal.TYPE)&&fact.eventId().filter(event::equals).isPresent()){
            if(++regionalRows>maximumRows||!fact.subjectRefs().equals(List.of(new ScopeRef(ScopeKind.WORLD,bound.worldId().value()))))return Optional.empty();
            var value=RegionalWeatherJournal.state(fact);if(latestRegional==null||value.ordinal()>latestRegional.ordinal())latestRegional=value;
        }
        if(latestRegional!=null){
            var sealed=latestRegional.owner();resources.add(sealed.resourceKey());var currentOwner=snapshot.owners.get(sealed.resourceKey());
            if(latestRegional.completed()&&currentOwner!=null&&currentOwner.header().id().equals(sealed.header().id())&&currentOwner.epoch().equals(sealed.epoch())
                    &&currentOwner.ownerTokens().equals(sealed.ownerTokens())&&currentOwner.creationMutationId().equals(sealed.creationMutationId())
                    &&currentOwner.playerOverrideEpoch().equals(sealed.playerOverrideEpoch())&&currentOwner.disposition()==OwnershipDisposition.RELEASED)
                cleanupWithoutMutation.add(sealed.creationMutationId());
        }
        var owners=new ArrayList<OwnershipRecord>();
        for(var owner:snapshot.owners.values())if(mutations.containsKey(owner.creationMutationId())||resources.contains(owner.resourceKey())) {
            var origin=snapshot.aggregates.get(new AggregateKey(AggregateKind.MUTATION,owner.creationMutationId().value()));
            if(origin!=null&&origin.state() instanceof MutationAggregate known&&!known.value().instanceId().equals(event))continue;
            owners.add(owner);
            if(mutations.size()+entities.size()+owners.size()>maximumRows)return Optional.empty();
        }
        for(var p:snapshot.prepares.values()) {
            if(p.slice().operations().stream().noneMatch(i->i.instanceId().equals(event)))continue;
            if(p.slice().operations().stream().anyMatch(i->!i.instanceId().equals(event)))return Optional.empty();
            if(!snapshot.results.contains(p.operationToken()))unresolved.add(p.operationToken());
            if(mutations.size()+entities.size()+owners.size()+unresolved.size()>maximumRows)return Optional.empty();
        }
        boolean preStartAbort=false;
        if(actual.isEmpty()&&mutations.isEmpty()&&entities.isEmpty()&&unresolved.isEmpty()) {
            for(var fact:snapshot.facts.values()) {
                if(fact.eventId().filter(event::equals).isEmpty()||!fact.durable()
                        ||fact.evidenceStatus()!=EvidenceStatus.CONFIRMED)continue;
                if(!Set.of(new FactType("proutlost_director:catalogue_event_aborted"),
                        new FactType("proutlost_director:catalogue_request_aborted")).contains(fact.factType()))continue;
                if(new JsonValue.Bool(true).equals(fact.payload().get("no_driver_start"))
                        &&new JsonValue.Bool(true).equals(fact.payload().get("no_physical_effect"))
                        &&new JsonValue.Text("EXECUTOR_PRESTART_CANCEL").equals(fact.payload().get("proof_source")))
                    preStartAbort=true;
            }
        }
        if(readOnly||!bound.equals(session)||snapshot!=published)return Optional.empty();
        return Optional.of(new PhysicalEventReadback(bound,new Sequence(snapshot.sequence),actual,
            mutations,owners,entities,unresolved,preStartAbort,cleanupWithoutMutation));
    }

    private void initialize() throws IOException {
        boolean existing = Files.exists(root);
        if (existing && Files.isSymbolicLink(root)) throw new IOException("symlink store root");
        Files.createDirectories(root);
        lockChannel = FileChannel.open(root.resolve("writer.lock"),CREATE,WRITE);
        try { lock = lockChannel.tryLock(); } catch (OverlappingFileLockException e) { throw new IOException("store already locked",e); }
        if (lock == null) throw new IOException("store already locked");
        boolean hasManifest = Files.exists(root.resolve("manifest-A.json")) || Files.exists(root.resolve("manifest-B.json"));
        boolean hasData;
        try (var children = Files.list(root)) { hasData = children.anyMatch(p -> !p.getFileName().toString().equals("writer.lock")); }
        boolean unsupportedSchema=false;
        for (String child : List.of("wal","blobs","checkpoints","recovery","audit")) {
            Path p = root.resolve(child); if (Files.isSymbolicLink(p)) throw new IOException("symlink store directory"); Files.createDirectories(p);
        }
        checkpoints = new CheckpointStore(root,probe);
        DurableFiles.directory(root); if (!existing) DurableFiles.directory(root.getParent());
        session = new StoreSession(world,new Epoch(1),new Epoch(0),new SessionId(UUID.randomUUID()));
        if (!hasManifest && hasData) { failSafe("STORE_CORRUPT: missing manifests"); return; }
        if (!hasManifest) manifest = checkpoints.initialize(world);
        else {
            List<CheckpointStore.Manifest> candidates = checkpoints.candidates();
            if(candidates.stream().map(CheckpointStore.Manifest::store).distinct().count()>1) {
                failSafe("STORE_CORRUPT: manifest store identity disagreement");return;
            }
            for (var candidate : candidates) {
                if (!candidate.world().equals(world)) { failSafe("STORE_CORRUPT: world identity mismatch"); return; }
                if (candidate.schema() != 1) { unsupportedSchema=true; continue; }
                try { checkpoints.verify(candidate); manifest = candidate; break; } catch (IOException invalid) { diagnostic = invalid.getMessage(); }
            }
            if (manifest == null) { failSafe(unsupportedSchema?"unsupported future schema; no readable source generation":"STORE_CORRUPT: no valid generation"); return; }
        }
        try {
            try (var files = Files.list(root.resolve("wal"))) {
                for (Path p : files.toList()) {
                    if (!p.getFileName().toString().matches("[1-9][0-9]*\\.wdwal") || Files.isSymbolicLink(p)) throw new IOException("unexpected WAL file");
                    segments.add(p);
                }
            }
            segments.sort(Comparator.comparingLong(JournalStore::firstSequence));
            if (!manifest.checkpoint().isEmpty()) replay(checkpoints.path(manifest),false,manifest.walBytes(),true);
            require(sequence == manifest.sequence(),"checkpoint sequence mismatch");
            for (int i=0;i<segments.size();i++) {
                Path segment=segments.get(i);
                if(firstSequence(segment)>manifest.sequence()) replay(segment,i==segments.size()-1,Files.size(segment),false);
            }
            require(sequence >= manifest.sequence(),"WAL is behind checkpoint: restore coherent backup");
            for (BlobRef ref : published.references) readBlob(ref);
            if(unsupportedSchema){failSafe("unsupported future schema; source projection is historical read-only evidence");return;}
            if (readOnly) return;
            StoreSession next = new StoreSession(world,session.storeGeneration(),session.bootEpoch().plus(1),new SessionId(UUID.randomUUID()));
            writeTransaction(UUID.randomUUID(),Map.of("kind",text("SESSION"),"count",text(0),"session",model(StoreSession.class,next)),List.of());
            session = next; rebuildAudit(); var state=new StoreProjection(published);state.sequence=sequence;published=state; diagnostic = "RUNNING";
        } catch (IOException | RuntimeException corrupt) { failSafe("STORE_CORRUPT: "+corrupt.getMessage()); }
    }
    private static long firstSequence(Path p) { return Long.parseLong(p.getFileName().toString().replace(".wdwal","")); }
    private void replay(Path path, boolean last, long payloadBytes, boolean snapshot) throws IOException {
        long transactionStart = -1, committedOffset = 0;
        Map<String,JsonValue> begin = null; UUID tx = null; List<DurableRecord> records = new ArrayList<>();
        var digest = DurableFiles.digest(); long bytes = 0; long frameSequence = sequence;
        try (SeekableByteChannel in = snapshot?checkpoints.open(manifest):FileChannel.open(path,READ,WRITE)) {
            if(!snapshot)require(firstSequence(path) == Math.incrementExact(sequence),"WAL segment sequence gap");
            while (true) {
                long offset = in.position(); WalFrame.Frame f;
                try { f = in.position()==payloadBytes?null:WalFrame.read(in,options.frameBytes());
                    require(in.position()<=payloadBytes,"frame crosses checkpoint footer"); }
                catch (WalFrame.IncompleteTail tail) {
                    if (!last) throw new IOException("incomplete middle WAL segment",tail);
                    isolateTail(path, transactionStart >= 0 ? transactionStart : committedOffset);
                    in.truncate(transactionStart >= 0 ? transactionStart : committedOffset); ((FileChannel)in).force(true); break;
                }
                if (f == null) {
                    if (begin != null) {
                        if (!last) throw new IOException("incomplete middle transaction");
                        isolateTail(path,transactionStart); in.truncate(transactionStart); ((FileChannel)in).force(true);
                    } break;
                }
                require(f.sequence() == Math.incrementExact(frameSequence),"WAL frame sequence gap"); frameSequence = f.sequence();
                bytes = Math.addExact(bytes,f.encoded().length); require(bytes <= options.transactionBytes(),"transaction limit");
                if (f.type() == WalFrame.Type.BEGIN) {
                    require(begin == null,"interleaved transaction"); begin = fields(f.payload()); tx=f.transaction();
                    transactionStart=offset; records.clear(); digest=DurableFiles.digest(); digest.update(f.encoded());
                } else {
                    require(begin != null && tx.equals(f.transaction()),"transaction UUID/order");
                    if (f.type() == WalFrame.Type.RECORD) {
                        records.add(RECORD.decode(f.payload())); digest.update(f.encoded());
                        require(records.size() <= number(begin,"count"),"record count overflow");
                    } else {
                        var commit = fields(f.payload()); keys(commit,"count","ordered_hash");
                        require(number(commit,"count") == records.size() && number(begin,"count") == records.size(),"commit count");
                        require(string(commit,"ordered_hash").equals(HexFormat.of().formatHex(digest.digest())),"ordered frame hash");
                        applyReplay(tx,begin,records,f.sequence()); sequence=f.sequence();
                        committedOffset=in.position(); begin=null; transactionStart=-1; bytes=0;
                    }
                }
            }
        }
        if (!snapshot && Files.size(path)==0) {
            // Only an uncommitted final segment may be empty. Remove our WAL segment, never user files.
            require(last,"empty middle segment"); Files.delete(path); DurableFiles.directory(path.getParent());
        }
    }
    private void isolateTail(Path file,long offset) throws IOException {
        // Preserve incomplete bytes for diagnosis before truncation. No valid committed frame is discarded.
        Path target=root.resolve("recovery").resolve("tail-"+UUID.randomUUID()+".bin");
        try(FileChannel in=FileChannel.open(file,READ); FileChannel out=FileChannel.open(target,CREATE_NEW,WRITE)) {
            in.position(offset); java.nio.ByteBuffer b=java.nio.ByteBuffer.allocate(65536);
            while(in.read(b)>0){b.flip();while(b.hasRemaining())out.write(b);b.clear();} out.force(true);
        } DurableFiles.directory(target.getParent());
    }
    /** A WAL header cannot claim extra, missing or duplicated forced blobs.
     * The live writer emits the exact unique reference set collected from the
     * records; recovery must enforce the same invariant before publishing.
     * This is an attestation check, never a replacement for strict fsync.
     */
    static Set<BlobRef> verifiedForcedBlobs(JsonValue encoded,Set<BlobRef> expected) {
        require(encoded instanceof JsonValue.Array,"forced blob attestation must be an array");
        Set<BlobRef> refs=new HashSet<>();
        for(var v:((JsonValue.Array)encoded).values())
            require(refs.add(model(BlobRef.class,v)),"duplicate WAL forced blob");
        require(refs.equals(expected),"unexpected or missing durable blob attestation");
        return Set.copyOf(refs);
    }

    private void applyReplay(UUID tx,Map<String,JsonValue> begin,List<DurableRecord> records,long seq) {
        String kind=string(begin,"kind");
        switch(kind) {
            case "SESSION" -> {
                keys(begin,"kind","count","session"); require(records.isEmpty(),"session records");
                var next=model(StoreSession.class,begin.get("session"));
                require(next.worldId().equals(world) && next.storeGeneration().equals(session.storeGeneration())
                    && next.bootEpoch().value()==Math.incrementExact(session.bootEpoch().value()),"session fence"); session=next;
                var state=new StoreProjection(published);state.sequence=seq;published=state;
            }
            case "APPEND" -> {
                keys(begin,"kind","count","batch","request_digest","forced_blobs");
                var header=model(DurableBatch.class,begin.get("batch")); require(header.transactionId().value().equals(tx),"batch UUID");
                var batch=new DurableBatch(header.transactionId(),header.expectedStoreGeneration(),header.expectedAggregateRevisions(),records,List.of(),header.permitAttempt(),header.transactionHash());
                var refs=verifiedForcedBlobs(begin.get("forced_blobs"),
                    BlobReferences.collect(records));
                StoreProjection next=candidate(batch,seq);
                var ack=new CommitAck(batch.transactionId(),session.storeGeneration(),new Sequence(seq),batch.transactionHash(),AckStatus.DURABLE,batch.permitAttempt(),refs);
                require(!next.transactions.containsKey(batch.transactionId()),"duplicate transaction in WAL");
                next.transactions.put(batch.transactionId(),new StoreProjection.Receipt(string(begin,"request_digest"),ack));
                next.references.addAll(refs); next.sequence=seq; published=next;
            }
            case "ACK" -> {
                keys(begin,"kind","count","ack"); require(records.isEmpty(),"ACK records");
                ConsumerAck ack=model(ConsumerAck.class,begin.get("ack"));
                require(validAckCursor(published,ack),"consumer cursor CAS/range");
                var next=new StoreProjection(published); next.cursors.put(ack.consumerId(),ack.contiguousAckSequence().value()); next.sequence=seq; published=next;
            }
            default -> throw new IllegalArgumentException("unknown transaction kind");
        }
    }
    private StoreProjection candidate(DurableBatch batch,long seq) {
        require(batch.expectedStoreGeneration().equals(session.storeGeneration()),"store generation");
        for(var e:batch.expectedAggregateRevisions().entrySet()) require(published.revisions.getOrDefault(e.getKey(),new Revision(0)).equals(e.getValue()),"scope revision CAS");
        StoreProjection next=new StoreProjection(published);
        if(batch.permitAttempt().isPresent()) {
            var a=batch.permitAttempt().orElseThrow(); require(a.session().equals(session),"obsolete authority session");
            require(batch.records().size()==1 && batch.records().getFirst() instanceof DurablePrepare,"permit PREPARE");
            var p=((DurablePrepare)batch.records().getFirst()).value();
            require(p.transactionId().equals(batch.transactionId()) && p.operationToken().equals(a.operationToken())
                && p.reservation().id().equals(a.reservationId()) && p.reservationGeneration().equals(a.reservationGeneration())
                && p.reservation().worldId().equals(world) && batch.transactionHash().equals(CommitPermit.Authority.prepareHash(p))
                && batch.expectedAggregateRevisions().equals(p.readSet().revisions()) && p.readSet().equals(p.slice().preconditions()),"permit binding");
            require(next.claims.add(StoreProjection.claim(a)),"operation already claimed");
        }
        Set<AggregateKey> changed=new HashSet<>();
        for(var record:batch.records()) {
            var aggregate=StoreProjection.aggregate(record);
            if(aggregate!=null) require(changed.add(aggregate.key()),"aggregate changed twice");
            if(record instanceof DurablePrepare) require(batch.permitAttempt().isPresent(),"PREPARE needs authority attempt");
            next.add(record,seq,batch.transactionHash());
        }

        next.sequence=seq; return next;
    }
    private DirectorEnvelope sequenceMessage(DirectorEnvelope m,long sequence) {
        require(m.worldId().equals(world) && m.worldSequence().isEmpty(),"outbox world/assigned sequence");
        return new DirectorEnvelope(m.schemaVersion(),m.messageId(),m.worldId(),m.producerId(),m.producerKey(),m.scope(),m.correlationId(),m.causationId(),m.producerSequence(),Optional.of(new Sequence(sequence)),m.createdTick(),m.payloadType(),m.payloadVersion(),m.payload());
    }
    private DurableBatch assignMessages(DurableBatch batch) {
        long[] seq={published.messageSequence}; List<DurableRecord> records=new ArrayList<>();
        for(var record:batch.records()) {
            if(record instanceof DurableOutbox o) record=new DurableOutbox(sequenceMessage(o.value(),++seq[0]));
            else if(record instanceof DurableResult r) {
                var v=r.value(); List<DirectorEnvelope> out=new ArrayList<>();
                for(var m:v.outbox()) out.add(sequenceMessage(m,++seq[0]));
                record=new DurableResult(new ResultRecord(v.prepareTransactionId(),v.operationToken(),v.result(),v.mutations(),v.facts(),out));
            } records.add(record);
        }
        return new DurableBatch(batch.transactionId(),batch.expectedStoreGeneration(),batch.expectedAggregateRevisions(),records,List.of(),batch.permitAttempt(),batch.transactionHash());
    }
    private Map<String,JsonValue> appendHeader(DurableBatch b,String digest,Set<BlobRef> refs) {
        var header=new DurableBatch(b.transactionId(),b.expectedStoreGeneration(),b.expectedAggregateRevisions(),List.of(),List.of(),b.permitAttempt(),b.transactionHash());
        var array=refs.stream().sorted(Comparator.comparing(r->new String(new ModelCodec<>(BlobRef.class).encode(r),java.nio.charset.StandardCharsets.UTF_8))).map(r->model(BlobRef.class,r)).toList();
        return Map.of("kind",text("APPEND"),"count",text(b.records().size()),"batch",model(DurableBatch.class,header),"request_digest",text(digest),"forced_blobs",new JsonValue.Array(array));
    }
    private String requestDigest(DurableBatch b) {
        var d=DurableFiles.digest();
        var header=new DurableBatch(b.transactionId(),b.expectedStoreGeneration(),b.expectedAggregateRevisions(),List.of(),List.of(),b.permitAttempt(),b.transactionHash());
        d.update(new ModelCodec<>(DurableBatch.class).encode(header));
        for(var r:b.records()) d.update(RECORD.encode(r));
        return HexFormat.of().formatHex(d.digest());
    }
    @Override public CompletionStage<CommitAck> append(DurableBatch batch) {
        Objects.requireNonNull(batch);
        long weight=options.transactionBytes()+batch.blobs().stream().mapToLong(b->b.reference().bytes()).sum();
        return submit(weight,() -> appendNow(batch),() -> rejected(batch,AckStatus.UNAVAILABLE));
    }
    private CommitAck appendNow(DurableBatch input) throws IOException {
        if(readOnly) return rejected(input,AckStatus.UNAVAILABLE);
        boolean ioStarted=false;
        try {
            if(input.permitAttempt().isPresent() && !input.permitAttempt().orElseThrow().session().equals(session)) return rejected(input,AckStatus.REJECTED);
            String digest=requestDigest(input); var receipt=published.transactions.get(input.transactionId());
            if(receipt!=null) return receipt.requestDigest().equals(digest)?receipt.ack():rejected(input,AckStatus.REJECTED);
            DurableBatch batch=assignMessages(input); long end=Math.addExact(sequence,Math.addExact(batch.records().size(),2));
            var next=candidate(batch,end); Set<BlobRef> refs=BlobReferences.collect(batch.records());
            var header=appendHeader(batch,digest,refs);
            // Validate full transaction capacity before touching files.
            transactionFrames(batch.transactionId().value(),header,batch.records());
            checkSpace();ioStarted=true;
            for(var content:input.blobs()) writeBlob(content);
            for(var ref:refs) forceBlob(ref);
            probe.at("BLOBS_FORCED");
            long committed=writeTransaction(batch.transactionId().value(),header,batch.records());
            var ack=new CommitAck(batch.transactionId(),session.storeGeneration(),new Sequence(committed),batch.transactionHash(),AckStatus.DURABLE,batch.permitAttempt(),refs);
            next.transactions.put(batch.transactionId(),new StoreProjection.Receipt(digest,ack)); next.references.addAll(refs);
            published=next; projectAudit(batch.records(),committed); probe.at("BEFORE_ACK"); return ack;
        } catch(IllegalArgumentException e) {
            if(ioStarted)throw new IOException("failure after transaction IO started",e);
            // The store remains writable after a pre-IO refusal. Keep the
            // actual transaction and structural/CAS reason visible; a generic
            // WAL_UNAVAILABLE caller result must not hide the reproducible cause.
            var mismatch=input.expectedAggregateRevisions().entrySet().stream()
                .filter(entry->!published.revisions.getOrDefault(entry.getKey(),new Revision(0)).equals(entry.getValue()))
                .limit(8).map(entry->entry.getKey()+" expected="+entry.getValue()+" actual="+published.revisions.getOrDefault(entry.getKey(),new Revision(0))).toList();
            System.err.println("DIRECTOR_J04_APPEND_REFUSED tx="+input.transactionId().value()+" reason="+e.getMessage()+" mismatched="+mismatch);
            return rejected(input,AckStatus.REJECTED);
        }
    }
    private CommitAck rejected(DurableBatch b,AckStatus status) {
        return new CommitAck(b.transactionId(),session.storeGeneration(),new Sequence(0),b.transactionHash(),status,b.permitAttempt(),Set.of());
    }
    private List<byte[]> transactionFrames(UUID tx,Map<String,JsonValue> begin,List<DurableRecord> records) {
        List<byte[]> frames=new ArrayList<>(); long seq=sequence;
        frames.add(WalFrame.encode(WalFrame.Type.BEGIN,Math.incrementExact(seq),tx,encode(begin),options.frameBytes())); seq++;
        for(var r:records) { frames.add(WalFrame.encode(WalFrame.Type.RECORD,Math.incrementExact(seq),tx,RECORD.encode(r),options.frameBytes())); seq++; }
        var hash=DurableFiles.digest(); for(byte[] frame:frames)hash.update(frame);
        frames.add(WalFrame.encode(WalFrame.Type.COMMIT,Math.incrementExact(seq),tx,encode(Map.of("count",text(records.size()),"ordered_hash",text(HexFormat.of().formatHex(hash.digest())))),options.frameBytes()));
        require(frames.stream().mapToLong(b->b.length).sum()<=options.transactionBytes(),"transaction byte bound");return frames;
    }
    private long writeTransaction(UUID tx,Map<String,JsonValue> begin,List<DurableRecord> records) throws IOException {
        List<byte[]> frames=transactionFrames(tx,begin,records); long size=frames.stream().mapToLong(b->b.length).sum();
        checkSpace();
        segments.removeIf(p->!Files.exists(p));
        if(wal==null || wal.size()+size>options.segmentBytes()) {
            if(wal!=null)wal.close();
            Path p=root.resolve("wal").resolve(Math.incrementExact(sequence)+".wdwal");
            wal=FileChannel.open(p,CREATE_NEW,WRITE); segments.add(p); DurableFiles.directory(p.getParent());
        }
        for(int i=0;i<frames.size();i++) { probe.at("BEFORE_WAL_WRITE"); writeWalFrame(frames.get(i)); probe.at(i==0?"TX_BEGIN":i==frames.size()-1?"TX_COMMIT":"TX_RECORD"); }
        probe.at("BEFORE_WAL_FORCE"); wal.force(true); probe.at("WAL_FORCED"); sequence=Math.addExact(sequence,frames.size()); return sequence;
    }
    private void writeWalFrame(byte[] frame) throws IOException {
        java.nio.ByteBuffer bytes=java.nio.ByteBuffer.wrap(frame);
        while(bytes.hasRemaining()) {
            int limit=bytes.limit();bytes.limit(bytes.position()+Math.min(bytes.remaining(),options.writeBatchBytes()));
            while(bytes.hasRemaining())wal.write(bytes);
            bytes.limit(limit);
        }
    }
    private void rebuildAudit() throws IOException {
        Path target=root.resolve("audit/critical.jsonl");
        Path temp=target.resolveSibling("critical.tmp-"+UUID.randomUUID());
        try {
            try(FileChannel out=FileChannel.open(temp,CREATE_NEW,WRITE)) {
                for(var entry:published.entries)writeAudit(out,entry.record(),entry.sequence());
                out.force(true);
            }
            Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            DurableFiles.directory(target.getParent());
        }finally{Files.deleteIfExists(temp);}
    }
    private void projectAudit(List<DurableRecord> records,long seq) throws IOException {
        try(FileChannel out=FileChannel.open(root.resolve("audit/critical.jsonl"),CREATE,WRITE,APPEND)) {
            for(var record:records)writeAudit(out,record,seq);
        }
    }
    private void writeAudit(FileChannel out,DurableRecord record,long seq) throws IOException {
        if(record instanceof DurableAudit || record instanceof DurablePrepare || record instanceof DurableResult
            || record instanceof DurableOwnership || record instanceof DurableMutation) {
            WalFrame.writeFully(out,"{\"record\":".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            WalFrame.writeFully(out,RECORD.encode(record));
            WalFrame.writeFully(out,(",\"sequence\":\""+seq+"\"}\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
    private void checkSpace() throws IOException {
        if(Files.getFileStore(root).getUsableSpace()<options.minimumFreeBytes())throw new IOException("disk low/full");
    }
    private Path blobPath(BlobRef ref) { return root.resolve("blobs").resolve(ref.hash().value()+".blob"); }
    private void writeBlob(BlobContent content) throws IOException {
        BlobRef ref=content.reference(); Path path=blobPath(ref);
        if(Files.exists(path)) { forceBlob(ref); return; }
        probe.at("BEFORE_BLOB_WRITE"); DurableFiles.replace(path,BlobCodec.encode(content),probe,"BLOB"); probe.at("BLOB_FORCED");
    }
    private void forceBlob(BlobRef ref) throws IOException {
        readBlob(ref);
        // A valid content hash does not prove that a previous rename reached durable directory state.
        // This covers both supplied orphan blobs and references discovered without new blob bytes.
        try(FileChannel file=FileChannel.open(blobPath(ref),WRITE)){file.force(true);}
        probe.at("BLOB_REUSED_FILE_FORCED");
        DurableFiles.directory(root.resolve("blobs"));
        probe.at("BLOB_REUSED_DIRECTORY_FORCED");
    }
    private BlobContent readBlob(BlobRef ref) throws IOException {
        Path p=blobPath(ref);
        if(Files.isSymbolicLink(p)||ref.bytes()>BlobContent.MAX_BYTES || Files.size(p)>BlobCodec.MAX_FRAME_BYTES)throw new IOException("blob length/path");
        try{var content=BlobCodec.decode(Files.readAllBytes(p));if(!content.reference().equals(ref))throw new IOException("blob codec/reference mismatch");return content;}catch(IllegalArgumentException e){throw new IOException("blob digest",e);}
    }
    @Override public CompletionStage<BlobReadResult> readBlobs(BlobReadRequest request) {
        long bytes=request.references().stream().mapToLong(BlobRef::bytes).reduce(0,Math::addExact);
        if(bytes>BlobContent.MAX_BATCH_BYTES)return CompletableFuture.completedFuture(new BlobReadResult(AckStatus.REJECTED,List.of(),List.of()));
        return submit(Math.max(1,bytes),() -> {
            if(!request.worldId().equals(world)||!request.storeGeneration().equals(session.storeGeneration()))return new BlobReadResult(AckStatus.REJECTED,List.of(),List.of());
            List<BlobContent> result=new ArrayList<>();for(var ref:request.references())result.add(readBlob(ref));
            return new BlobReadResult(AckStatus.DURABLE,result,List.of());
        },()->new BlobReadResult(AckStatus.UNAVAILABLE,List.of(),List.of()));
    }
    /**
     * Experimental J04 cold checkpoint-only read: independently verifies
     * content-addressed archived WAL parts and committed transaction hashes.
     * Unlike read(), the returned snapshot ends at the last checkpoint,
     * NOT at the newest writer commit. This is a separate qualification path;
     * it does NOT implement full cold eviction/hydration or aggregate lookup.
     */
    public CompletionStage<StoreReadResult> readArchivedCheckpoint(StoreQuery query) {
        Objects.requireNonNull(query);
        var boundSession=session;
        if(readOnly || boundSession==null || !query.worldId().equals(world))
            return CompletableFuture.completedFuture(
                TypedProjectionReads.status(ReadStatus.UNAVAILABLE));
        return submit(Math.max(1L,Math.min(options.queueBytes(),
            Math.multiplyExact((long)query.limit(),512L))),()-> {
                if(readOnly || !boundSession.equals(session))
                    return TypedProjectionReads.status(ReadStatus.STALE);
                // Deliberately capped: without a cold seek index large archives
                // report NOT_RESIDENT rather than scanning unbounded history.
                long maxScan=Math.min(16_777_216L,
                    Math.multiplyExact(options.segmentBytes(),4L));
                return ArchivedCheckpointPages.scan(
                    checkpoints,manifest,boundSession,query,options.frameBytes(),
                    options.transactionBytes(),maxScan);
            },()->TypedProjectionReads.status(ReadStatus.UNAVAILABLE));
    }
    /** Indexed cold archive read. Never blocks the server actor: index creation,
     * SHA256 verification and bounded page decoding execute on J04's IO writer.
     * Does not evict the live projection or grant execution permission.
     */
    public CompletionStage<StoreReadResult> readArchivedIndexed(StoreQuery query) {
        Objects.requireNonNull(query);
        StoreSession bound=session;
        if(readOnly || bound==null || !query.worldId().equals(world))
            return CompletableFuture.completedFuture(
                TypedProjectionReads.status(ReadStatus.UNAVAILABLE));
        long charge=Math.max(1,Math.min(options.queueBytes(),
            Math.multiplyExact((long)query.limit(),512L)));
        return submit(charge,()->{
            if(readOnly || !bound.equals(session))
                return TypedProjectionReads.status(ReadStatus.STALE);
            var now=manifest;
            if(now==null) return TypedProjectionReads.status(ReadStatus.UNAVAILABLE);
            if(now.checkpoint().isEmpty() || query.sequence().value()>now.sequence())
                return TypedProjectionReads.status(ReadStatus.NOT_RESIDENT);
            if(!query.aggregateKeys().isEmpty()) {
                if(verifiedColdAggregateIndex==null ||
                   !verifiedColdAggregateIndex.manifest.equals(now)) {
                    try {
                        verifiedColdAggregateIndex=ArchivedAggregatePages.verified(
                            root,checkpoints,now,options.frameBytes(),
                            options.transactionBytes());
                    }catch(ArchivedWalIndex.CapacityExceeded full) {
                        return TypedProjectionReads.status(ReadStatus.NOT_RESIDENT);
                    }
                }
                checkpoints.verify(now);
                return verifiedColdAggregateIndex.page(checkpoints,bound,query,
                    options.frameBytes(),options.transactionBytes());
            }
            if(verifiedColdTransactionIndex==null ||
                !verifiedColdTransactionIndex.manifest.equals(now)) {
                try {
                    verifiedColdTransactionIndex=ArchivedWalIndex.verified(
                        root,checkpoints,now,options.frameBytes(),options.transactionBytes());
                }catch(ArchivedWalIndex.CapacityExceeded full) {
                    return TypedProjectionReads.status(ReadStatus.NOT_RESIDENT);
                }
            }
            return IndexedCheckpointPages.read(root,checkpoints,now,
                bound,query,options.frameBytes(),options.transactionBytes(),
                verifiedColdTransactionIndex);
        },()->TypedProjectionReads.status(ReadStatus.UNAVAILABLE));
    }
    /** Explicit lab-only release of the chronological read ledger; NEVER evicts
     * current facts, uniqueness, ownership, PREPARE/results, outbox or receipts.
     * Gameplay writer operation remains disabled unless called explicitly.
     */
    public record ReadRowEviction(ReadStatus status,long archivedSequence,
                                  long archivedRows,int residentRows,int releasedRows) {
        public ReadRowEviction {
            Objects.requireNonNull(status);
            if(archivedSequence<0 || archivedRows<0 || residentRows<0 || releasedRows<0)
                throw new IllegalArgumentException("invalid row eviction report");
        }
    }
    public CompletionStage<ReadRowEviction> evictVerifiedCheckpointRows() {
        return submit(options.transactionBytes(),this::evictVerifiedCheckpointRowsNow,
            ()->new ReadRowEviction(ReadStatus.UNAVAILABLE,
                published.archivedThroughSequence,published.archivedReadRows,
                published.entries.size(),0));
    }
    /** Writer-only; may run directly after checkpoint fsync without self-queueing. */
    private ReadRowEviction evictVerifiedCheckpointRowsNow() throws IOException {
            StoreProjection before=published;
            if(readOnly || manifest==null)
                return new ReadRowEviction(ReadStatus.UNAVAILABLE,
                    before.archivedThroughSequence,before.archivedReadRows,before.entries.size(),0);
            var snapshot=manifest;
            if(snapshot.checkpoint().isEmpty() || snapshot.sequence()>before.sequence)
                return new ReadRowEviction(ReadStatus.NOT_RESIDENT,
                    before.archivedThroughSequence,before.archivedReadRows,before.entries.size(),0);
            ArchivedWalIndex indexed;
            try {
                indexed=ArchivedWalIndex.verified(root,checkpoints,snapshot,
                    options.frameBytes(),options.transactionBytes());
            } catch(ArchivedWalIndex.CapacityExceeded bounded) {
                return new ReadRowEviction(ReadStatus.NOT_RESIDENT,
                    before.archivedThroughSequence,before.archivedReadRows,before.entries.size(),0);
            }
            // All historical rows represented by the complete checkpoint
            // index, including synthetic uniqueness markers, must match the
            // complete resident prefix prior to releasing any reference.
            long prunable=0;
            for(var row:before.entries) {
                if(row.sequence()<=snapshot.sequence())prunable++;
            }
            long expected=Math.addExact(before.archivedReadRows,prunable);
            if(expected!=indexed.totalRows)
                throw new IOException("checkpoint row count differs from resident chronology");
            verifiedColdTransactionIndex=indexed;
            if(prunable==0)
                return new ReadRowEviction(ReadStatus.AVAILABLE,
                    before.archivedThroughSequence,before.archivedReadRows,before.entries.size(),0);
            StoreProjection after=new StoreProjection(before,snapshot.sequence(),indexed.totalRows);
            // Publication of the new immutable copy is the ONLY eviction.
            // Old snapshots remain valid for any readers already holding them.
            published=after;
            return new ReadRowEviction(ReadStatus.AVAILABLE,
                after.archivedThroughSequence,after.archivedReadRows,
                after.entries.size(),Math.toIntExact(prunable));
    }
    /**
     * Laboratory policy is opt-in and never sourced from player/network state.
     * Only the existing J04 writer may configure it; gameplay remains disabled.
     * A null policy turns off automatic release without touching durable state.
     */
    public CompletionStage<Boolean> configureLabAutoReadEviction(
            AutomaticReadEvictionPolicy policy) {
        return submit(1,()->{
            if(readOnly)return false;
            labPolicy=policy;
            if(policy==null) {
                lastAutoEviction=new AutomaticReadEvictionPolicy.Outcome(
                    AutomaticReadEvictionPolicy.Reason.DISABLED,ReadStatus.NOT_RESIDENT,
                    manifest==null?0:manifest.generation(),published.entries.size(),
                    published.entries.size(),0,published.archivedReadRows);
            }
            return true;
        },()->false);
    }
    public AutomaticReadEvictionPolicy.Outcome lastAutomaticReadEviction() {
        return lastAutoEviction;
    }
    private void maybeEvictAfterCheckpoint() throws IOException {
        var policy=labPolicy;
        var state=published;
        var current=manifest;
        int resident=state.entries.size();
        long generation=current==null?0:current.generation();
        AutomaticReadEvictionPolicy.Reason reason;
        ReadStatus status=ReadStatus.NOT_RESIDENT;
        int released=0;
        if(policy==null)reason=AutomaticReadEvictionPolicy.Reason.DISABLED;
        else if(readOnly || current==null || current.checkpoint().isEmpty())
            reason=AutomaticReadEvictionPolicy.Reason.NO_VERIFIED_CHECKPOINT;
        else if(resident<policy.minimumEligibleRows())
            reason=AutomaticReadEvictionPolicy.Reason.BELOW_THRESHOLD;
        else if(lastAutomaticEvictionGeneration>0 &&
                generation-lastAutomaticEvictionGeneration<policy.minimumGenerationSpacing())
            reason=AutomaticReadEvictionPolicy.Reason.COOLDOWN;
        else if(current.walBytes()>policy.maximumCheckpointBytes())
            reason=AutomaticReadEvictionPolicy.Reason.ARCHIVE_TOO_LARGE;
        else {
            ReadRowEviction result=evictVerifiedCheckpointRowsNow();
            status=result.status();
            released=result.releasedRows();
            if(released>0) {
                reason=AutomaticReadEvictionPolicy.Reason.EVICTED;
                lastAutomaticEvictionGeneration=generation;
            }else
                reason=AutomaticReadEvictionPolicy.Reason.BELOW_THRESHOLD;
        }
        lastAutoEviction=new AutomaticReadEvictionPolicy.Outcome(
            reason,status,generation,resident,published.entries.size(),
            released,published.archivedReadRows);
    }
    /** Facts remain in the canonical index after read-row eviction. A fault is never absence. */
    public Optional<DirectorFact> retainedFact(FactId id){
        Objects.requireNonNull(id);var bound=session;var snapshot=published;
        require(!readOnly&&!closing.get()&&bound!=null&&"RUNNING".equals(diagnostic),"fact projection unavailable");
        var fact=snapshot.facts.get(id);
        require(!readOnly&&bound.equals(session),"fact projection changed");return Optional.ofNullable(fact);
    }
    /** Server-local immutable fact-index cut, never the chronology or a persistence cursor.
     * The path-copy map fork is O(1). Later appends cannot alter this cut. */
    public static final class RetainedFactSnapshot {
        private final StoreSession session;
        private final Sequence cut;
        private final FactType type;
        private final ScopeRef owner;
        private final SharedMap<FactId,DirectorFact> facts;
        private RetainedFactSnapshot(StoreSession session,StoreProjection projection,FactType type,ScopeRef owner){
            this.session=session;this.cut=new Sequence(projection.sequence);this.type=type;this.owner=owner;
            this.facts=new SharedMap<>(projection.facts);
        }
        public StoreSession session(){return session;}
        public Sequence cut(){return cut;}
    }
    public record RetainedFactPage(RetainedFactSnapshot snapshot,List<DirectorFact> facts,Optional<FactId> next){
        public RetainedFactPage {Objects.requireNonNull(snapshot);facts=List.copyOf(facts);Objects.requireNonNull(next);}
    }
    /** Existing writer/queue only: at most 512 canonical facts examined and 16 matches copied.
     * An empty page with next is a sparse index portion, never proof of an empty journal.
     * Empty Optional means unavailable. Global StoreQuery cursors retain their STALE contract. */
    public CompletionStage<Optional<RetainedFactPage>> readRetainedFacts(FactType type,ScopeRef owner){
        Objects.requireNonNull(type);Objects.requireNonNull(owner);
        return submit(8192,()->{
            var bound=session;var projection=published;
            if(!retainedFactsAvailable(bound))return Optional.empty();
            return retainedFactPage(new RetainedFactSnapshot(bound,projection,type,owner),Optional.empty());
        },Optional::empty);
    }
    public CompletionStage<Optional<RetainedFactPage>> readRetainedFacts(RetainedFactSnapshot snapshot,FactId after){
        Objects.requireNonNull(snapshot);Objects.requireNonNull(after);
        return submit(8192,()->retainedFactPage(snapshot,Optional.of(after)),Optional::empty);
    }
    private boolean retainedFactsAvailable(StoreSession bound){
        return !readOnly&&!closing.get()&&bound!=null&&bound.equals(session)&&"RUNNING".equals(diagnostic);
    }
    private Optional<RetainedFactPage> retainedFactPage(RetainedFactSnapshot snapshot,Optional<FactId> after){
        if(!retainedFactsAvailable(snapshot.session()))return Optional.empty();
        var rows=after.isPresent()?snapshot.facts.tailValues(after.orElseThrow()).iterator():snapshot.facts.values().iterator();
        // tailValues is inclusive; skipping only the exact previous examined key makes retries deterministic.
        if(after.isPresent()&&(!rows.hasNext()||!rows.next().factId().equals(after.orElseThrow())))return Optional.empty();
        List<DirectorFact> selected=new ArrayList<>();FactId last=null;int scanned=0;
        while(rows.hasNext()&&scanned<512&&selected.size()<16){
            var fact=rows.next();last=fact.factId();scanned++;
            if(fact.factType().equals(snapshot.type)&&fact.subjectRefs().equals(List.of(snapshot.owner)))selected.add(fact);
        }
        boolean more=rows.hasNext();
        if(!retainedFactsAvailable(snapshot.session()))return Optional.empty();
        return Optional.of(new RetainedFactPage(snapshot,selected,more?Optional.of(Objects.requireNonNull(last)):Optional.empty()));
    }
    /** API 1.0.2 memory projection. Returns STALE instead of treating obsolete cursors as empty. */
    @Override public StoreReadResult view(StoreQuery query) {
        Objects.requireNonNull(query);
        if(readOnly) return TypedProjectionReads.status(ReadStatus.UNAVAILABLE);
        return TypedProjectionReads.store(query,published,session);
    }
    /** J04 experimental transparent resident + verified archive read.
     * All IO and snapshot decisions are serialized on the durable writer.
     * Off-actor work is mandatory: never await checkpoint bytes on Minecraft.
     * Entirely resident pages retain their existing API 1.0.2 behavior.
     */
    @Override public CompletionStage<StoreReadResult> read(StoreQuery query) {
        Objects.requireNonNull(query);
        if(readOnly || !query.worldId().equals(world))
            return CompletableFuture.completedFuture(
                TypedProjectionReads.status(ReadStatus.UNAVAILABLE));
        long charge=Math.max(1L,Math.min(options.queueBytes(),
            Math.multiplyExact((long)query.limit(),512L)));
        return submit(charge,()->{
            if(readOnly)
                return TypedProjectionReads.status(ReadStatus.UNAVAILABLE);
            var snapshot=published;
            var bound=session;
            // Aggregates and all operational maps are NEVER evicted.
            // Fully resident chronological reads need no disk IO.
            if(!query.aggregateKeys().isEmpty() ||
               snapshot.archivedReadRows==0 ||
               query.sequence().value()>snapshot.archivedThroughSequence)
                return TypedProjectionReads.store(query,snapshot,bound);
            var current=manifest;
            if(current==null || current.checkpoint().isEmpty() ||
               snapshot.archivedThroughSequence>current.sequence())
                return TypedProjectionReads.status(ReadStatus.NOT_RESIDENT);
            if(verifiedColdTransactionIndex==null ||
               !verifiedColdTransactionIndex.manifest.equals(current)) {
                try {
                    verifiedColdTransactionIndex=ArchivedWalIndex.verified(
                        root,checkpoints,current,options.frameBytes(),
                        options.transactionBytes());
                }catch(ArchivedWalIndex.CapacityExceeded insufficientIndex) {
                    return TypedProjectionReads.status(ReadStatus.NOT_RESIDENT);
                }
            }
            return HydratedChronologyPages.read(root,checkpoints,current,
                bound,snapshot,query,verifiedColdTransactionIndex,
                options.frameBytes(),options.transactionBytes());
        },()->TypedProjectionReads.status(ReadStatus.UNAVAILABLE));
    }
    /** Immutable published durable consumer position; never subscribes, acknowledges, or performs IO. */
    public record ConsumerCursor(StoreSession session,Sequence cursor) {
        public ConsumerCursor {Objects.requireNonNull(session);Objects.requireNonNull(cursor);}
    }
    public Optional<ConsumerCursor> consumerCursor(ConsumerId consumer) {
        Objects.requireNonNull(consumer);
        var bound=session;var snapshot=published;
        if(readOnly||closing.get()||bound==null||!"RUNNING".equals(diagnostic))return Optional.empty();
        var value=new ConsumerCursor(bound,new Sequence(snapshot.cursors.getOrDefault(consumer,0L)));
        return !readOnly&&bound.equals(session)?Optional.of(value):Optional.empty();
    }

    /** Snapshot-bound subscription read. No cursor is acknowledged or advanced. */
    @Override public SubscriptionReadResult subscribe(ConsumerSpec consumer) {
        Objects.requireNonNull(consumer);
        if(readOnly)return TypedProjectionReads.subscriptionStatus(ReadStatus.UNAVAILABLE);
        require(consumer.batchLimit()>0 && consumer.batchLimit()<=options.consumerBatchLimit(),"consumer batch bound");
        ConsumerSpec prior=consumers.putIfAbsent(consumer.consumerId(),consumer);
        require(prior==null||prior.filters().equals(consumer.filters()),"consumer filter change needs new consumer ID");
        StoreProjection p=published; long cursor=p.cursors.getOrDefault(consumer.consumerId(),0L);
        long start=Math.incrementExact(cursor);
        require(consumer.nextSequence().value()<=start,"cannot skip durable cursor");
        List<DirectorEnvelope> batch=new ArrayList<>();long scanned=cursor; boolean more=false;
        int scannedCount=0;
        for(var m:p.outbox.tailValues(start)) {
            if(scannedCount++==options.consumerBatchLimit()){more=true;break;}
            long seq=m.worldSequence().orElseThrow().value();
            boolean matches=ReadContracts.matches(consumer,m);
            if(matches && batch.size()==consumer.batchLimit()){more=true;break;}
            scanned=seq;if(matches)batch.add(m);
        }
        var sub=new Subscription(consumer,batch,new Sequence(Math.incrementExact(scanned)),more);
        var response=TypedProjectionReads.subscription(consumer,p,session,sub);
        if(response.status()==ReadStatus.AVAILABLE)deliveries.put(consumer.consumerId(),sub);
        return response;
    }
    /** Async bounded subscription page, never an implicit ACK. */
    @Override public CompletionStage<SubscriptionReadResult> readSubscription(ConsumerSpec consumer) {
        Objects.requireNonNull(consumer);
        if(readOnly || consumer.batchLimit()>options.consumerBatchLimit())
            return CompletableFuture.completedFuture(TypedProjectionReads.subscriptionStatus(ReadStatus.UNAVAILABLE));
        return submit(Math.max(1,consumer.batchLimit()),()->subscribe(consumer),
            ()->TypedProjectionReads.subscriptionStatus(ReadStatus.UNAVAILABLE));
    }
    private static boolean validAckCursor(StoreProjection state, ConsumerAck ack) {
        return ack.expectedCursor().value()==state.cursors.getOrDefault(ack.consumerId(),0L)
            && ack.contiguousAckSequence().value()>=ack.expectedCursor().value()
            && ack.contiguousAckSequence().value()<=state.messageSequence;
    }
    @Override public CompletionStage<ConsumeAck> acknowledge(ConsumerAck ack) {
        return submit(options.transactionBytes(),()-> {
            long current=published.cursors.getOrDefault(ack.consumerId(),0L);
            if(readOnly)return new ConsumeAck(ack.consumerId(),new Sequence(current),AckStatus.UNAVAILABLE);
            Subscription delivery=deliveries.get(ack.consumerId());
            if(!validAckCursor(published,ack) || delivery==null || ack.contiguousAckSequence().value()!=delivery.nextSequence().value()-1
                || !ack.messageIds().equals(delivery.batch().stream().map(DirectorEnvelope::messageId).collect(java.util.stream.Collectors.toSet())))
                return new ConsumeAck(ack.consumerId(),new Sequence(current),AckStatus.REJECTED);
            long seq=writeTransaction(UUID.randomUUID(),Map.of("kind",text("ACK"),"count",text(0),"ack",model(ConsumerAck.class,ack)),List.of());
            var next=new StoreProjection(published);next.cursors.put(ack.consumerId(),ack.contiguousAckSequence().value());next.sequence=seq;published=next;
            deliveries.remove(ack.consumerId(),delivery);return new ConsumeAck(ack.consumerId(),ack.contiguousAckSequence(),AckStatus.DURABLE);
        },()->new ConsumeAck(ack.consumerId(),new Sequence(published.cursors.getOrDefault(ack.consumerId(),0L)),AckStatus.UNAVAILABLE));
    }
    public CompletionStage<Long> checkpoint() {
        return submit(options.transactionBytes(),()-> {
            if(readOnly)throw new IOException("read-only store");checkSpace();if(wal!=null)wal.force(true);
            if(wal!=null){wal.close();wal=null;}
            probe.at("BEFORE_CHECKPOINT");manifest=checkpoints.checkpoint(manifest,sequence,List.copyOf(segments));probe.at("MANIFEST_DURABLE");checkpoints.retain(manifest,options.checkpointGenerations(),segments,published.references);
            // This is the ONLY automatic-eviction trigger: strictly after
            // checkpoint fsync and authoritative retention, on the writer.
            maybeEvictAfterCheckpoint();
            return manifest.generation();
        },()->-1L);
    }
    /** Offline lifecycle operation: caller first drains gameplay. This store becomes read-only;
     * only a binary supporting the explicitly supplied target schema may reopen it for writes.
     * The original manifest, checkpoint, WAL and blobs are retained, never overwritten in place.
     */
    public CompletionStage<Long> migrate(SnapshotMigration migration) {
        Objects.requireNonNull(migration);
        return submit(options.transactionBytes(),()-> {
            if(readOnly)throw new IOException("read-only store");
            if(migration.sourceSchema()!=1 || migration.targetSchema()!=2)throw new IllegalArgumentException("adjacent v1 migration required");
            checkSpace();if(wal!=null){wal.force(true);wal.close();wal=null;}
            manifest=checkpoints.checkpoint(manifest,sequence,List.copyOf(segments));
            // Fence all old permits before installing the new schema.
            failSafe("schema migration in progress; restart with target schema implementation");
            manifest=checkpoints.migrate(manifest,migration,options.frameBytes());
            diagnostic="migration durable; target schema "+manifest.schema()+" requires compatible binary";
            return manifest.generation();
        },()->-1L);
    }
    public CompletionStage<Void> closeAsync() {
        if(!closing.compareAndSet(false,true))return CompletableFuture.failedFuture(new IllegalStateException("already closing"));
        CompletableFuture<Void> done=new CompletableFuture<>();
        writer.shutdown();
        Thread.ofVirtual().name("director-store-close").start(()-> {
            try {
                while(!writer.awaitTermination(1,TimeUnit.SECONDS)) { /* wait off actor; drain accepted IO */ }
                var closeError=release();if(closeError==null)done.complete(null);else done.completeExceptionally(closeError);
            }catch(InterruptedException e){Thread.currentThread().interrupt();done.completeExceptionally(e);}
        });return done.minimalCompletionStage();
    }
    @FunctionalInterface private interface IoTask<T>{T run() throws IOException;}
    private <T> CompletionStage<T> submit(long weight,IoTask<T> task,java.util.function.Supplier<T> unavailable) {
        if(closing.get() || weight>options.queueBytes())return CompletableFuture.completedFuture(unavailable.get());
        long size=queuedBytes.addAndGet(weight);
        if(size>options.queueBytes()){queuedBytes.addAndGet(-weight);return CompletableFuture.completedFuture(unavailable.get());}
        CompletableFuture<T> future=new CompletableFuture<>();
        try { writer.execute(()-> {
            try{future.complete(task.run());}
            catch(IOException e){failSafe("WAL_UNAVAILABLE: "+e.getMessage());future.complete(unavailable.get());}
            catch(RuntimeException e){future.completeExceptionally(e);}
            finally{queuedBytes.addAndGet(-weight);}
        }); }catch(RejectedExecutionException e){queuedBytes.addAndGet(-weight);future.complete(unavailable.get());}
        return future.minimalCompletionStage();
    }
    private void failSafe(String reason) {
        readOnly=true;diagnostic=reason;
        // Invalidate already delivered permits immediately on writer loss; no new admissions.
        session=new StoreSession(world,session.storeGeneration(),session.bootEpoch(),new SessionId(UUID.randomUUID()));
    }
    private IOException release() {
        IOException failure=null;
        for(AutoCloseable resource:new AutoCloseable[]{wal,lock,lockChannel}) {
            if(resource==null)continue;
            try{resource.close();}catch(Exception e){
                if(failure==null)failure=new IOException("store close failed",e);else failure.addSuppressed(e);
            }
        }
        if(failure!=null)diagnostic=failure.getMessage();return failure;
    }
}
