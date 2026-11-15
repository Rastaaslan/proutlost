package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.DurableStore;
import fr.proutlost.director.admin.server.*;
import fr.proutlost.director.engine.WorldDirector;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;

/** Admin-only immutable inputs. Files and J04 hydration run on the existing worker. */
public final class DirectorReadOnlyInputs {
    private final Thread actor=Thread.currentThread();
    private final DurableStore store;private final StoreSession session;private final ProviderMode mode;
    private final Executor worker;private final Path directory;
    private volatile CompletableFuture<Map<ResourceId,SnapshotBatch>> fixtures;
    public DirectorReadOnlyInputs(DurableStore store,ProviderMode mode,Executor worker,Path fixtureDirectory) {
        this.store=Objects.requireNonNull(store);this.session=store.session();this.mode=Objects.requireNonNull(mode);
        this.worker=Objects.requireNonNull(worker);this.directory=fixtureDirectory.toAbsolutePath().normalize();reloadFixtures();
    }
    public void reloadFixtures(){fixtures=CompletableFuture.supplyAsync(()->{
        try{return loadFixtures(directory,session.worldId());}
        catch(IOException invalid){throw new CompletionException(invalid);}
    },worker);}
    /** Memory-only: safe for command parsing on the actor. */
    public SnapshotBatch fixture(ResourceId id) {
        bound();var pending=fixtures;
        if(!pending.isDone())throw new IllegalArgumentException("operator fixtures are loading");
        final Map<ResourceId,SnapshotBatch> loaded;
        try{loaded=pending.getNow(Map.of());}catch(CompletionException invalid){throw new IllegalArgumentException("operator fixture verification failed",invalid);}
        var found=loaded.get(id);if(found==null)throw new IllegalArgumentException("unregistered operator fixture");return found;
    }
    public AdminReadOnlyCalculations calculations(){return new AdminReadOnlyCalculations(new WorldDirector(mode),this::simulationInput,this::replay);}
    private void bound(){if(!session.equals(store.session()))throw new IllegalArgumentException("obsolete read-only input session");}
    private void worker(){if(Thread.currentThread()==actor)throw new IllegalStateException("read-only input IO cannot run on server actor");bound();}
    /** Layout: namespace/path.json plus path.json.sha256, containing an existing SnapshotBatch. */
    public static Map<ResourceId,SnapshotBatch> loadFixtures(Path folder,WorldId world)throws IOException {
        var root=folder.toAbsolutePath().normalize();if(!Files.exists(root))return Map.of();
        if(Files.isSymbolicLink(root)||!root.toRealPath().equals(root))throw new IOException("fixture directory symlink");
        var loaded=new LinkedHashMap<ResourceId,SnapshotBatch>();long total=0;
        try(var paths=Files.walk(root,8)){
            var entries=paths.limit(513).toList();if(entries.size()>512)throw new IOException("fixture directory entry capacity");
            for(var path:entries.stream().sorted().toList()){
                if(Files.isSymbolicLink(path))throw new IOException("fixture symlink");
                if(!Files.isRegularFile(path)||!path.getFileName().toString().endsWith(".json"))continue;
                var relative=root.relativize(path);if(relative.getNameCount()<2||loaded.size()>=64)throw new IOException("fixture namespace/count");
                long size=Files.size(path);total=Math.addExact(total,size);
                if(size>fr.proutlost.director.api.codec.CanonicalJsonV1.MAX_BYTES||total>16L*1024*1024)throw new IOException("fixture byte capacity");
                var hash=path.resolveSibling(path.getFileName()+".sha256");
                if(Files.isSymbolicLink(hash)||Files.size(hash)>128)throw new IOException("fixture SHA sidecar");
                String expected=Files.readString(hash,StandardCharsets.US_ASCII).strip().toLowerCase(Locale.ROOT);
                var bytes=Files.readAllBytes(path);
                if(!expected.matches("[a-f0-9]{64}")||!expected.equals(BlobContent.hash(bytes).value()))throw new IOException("fixture hash mismatch");
                var snapshot=new ModelCodec<>(SnapshotBatch.class).decode(bytes);
                if(!snapshot.scopes().worldId().equals(world)||!snapshot.scopeRevisions().keySet().containsAll(snapshot.scopes().scopes()))
                    throw new IOException("fixture world/scopes");
                String name=relative.subpath(1,relative.getNameCount()).toString().replace('\\','/');
                var id=new ResourceId(relative.getName(0)+":"+name.substring(0,name.length()-5));
                if(loaded.putIfAbsent(id,snapshot)!=null)throw new IOException("fixture identity conflict");
            }
        }
        return Map.copyOf(loaded);
    }
    public DecisionInput simulationInput(ResourceId id) {
        worker();final SnapshotBatch injected;
        try{injected=fixture(id);}catch(IllegalArgumentException invalid){throw new AdminProjectionPages.Rejected(ReasonCode.CATALOG_INVALID);}
        var found=find(a->sameCapture(a.context(),injected));
        var contents=hydrate(found.getKey());
        // Verify the original audit and planning capture before substituting the operator snapshot.
        RecordedDecisionArchive.replay(found.getKey(),found.getValue(),contents,session,mode,false);
        var ref=new ModelCodec<>(BlobRef.class).decode(fr.proutlost.director.api.codec.CanonicalJsonV1.encode(found.getKey().payload().get("input")));
        var original=new ModelCodec<>(DecisionInput.class).decode(contents.stream().filter(b->b.reference().equals(ref)).findFirst().orElseThrow().bytes());
        if(!sameCapture(original.context(),injected))throw new AdminProjectionPages.Rejected(ReasonCode.STALE_CONTEXT);
        bound();return new DecisionInput(injected,original.memory(),original.catalog(),original.config(),original.requests(),original.chains(),
                original.seed(),original.state(),original.advisorySnapshot());
    }
    private static boolean sameCapture(SnapshotBatch a,SnapshotBatch b){return a.requestId().equals(b.requestId())&&a.scopes().equals(b.scopes())
            &&a.scopeRevisions().equals(b.scopeRevisions())&&a.capturedTick().equals(b.capturedTick());}
    public DecisionProposal replay(DecisionId id,boolean withoutAi) {
        worker();var found=find(a->a.decisionId().equals(id));
        try{var result=RecordedDecisionArchive.replay(found.getKey(),found.getValue(),hydrate(found.getKey()),session,mode,withoutAi);bound();return result;}
        catch(IllegalArgumentException invalid){throw new AdminProjectionPages.Rejected(ReasonCode.STORE_CORRUPT);}
    }
    /** Resolve the actual immutable audit on the existing IO worker. Unlike
     * replay this also supports older audits without a recorded-input archive.
     * A fresh durable audit is visible without rebuilding a boot-only cache. */
    public AuditDecision audit(DecisionId id) {
        worker();Objects.requireNonNull(id);long deadline=deadline();
        for(int attempt=0;attempt<3;attempt++){
            Optional<String> cursor=Optional.empty();boolean stale=false;
            for(int pages=0;pages<512;pages++){
                var query=new StoreQuery(session.worldId(),Set.of(),new Sequence(0),cursor,512);
                var result=await(store.read(query),deadline);ReadContracts.validate(result,query,session);
                if(result.status()==ReadStatus.STALE){stale=true;break;}
                if(result.status()!=ReadStatus.AVAILABLE)throw new AdminProjectionPages.Rejected(ReasonCode.WAL_UNAVAILABLE);
                var page=result.page().orElseThrow();
                for(var record:page.records())if(record instanceof DurableAudit row&&row.value().decisionId().equals(id)){
                    var audit=row.value();
                    if(!audit.context().scopes().worldId().equals(session.worldId())||!audit.scopes().worldId().equals(session.worldId()))
                        throw new AdminProjectionPages.Rejected(ReasonCode.STORE_CORRUPT);
                    bound();return audit;
                }
                if(!page.hasMore())throw new AdminProjectionPages.Rejected(ReasonCode.NO_TARGET);
                cursor=page.nextCursor();
            }
            if(!stale)throw new AdminProjectionPages.Rejected(ReasonCode.BUDGET_EXHAUSTED);
        }
        throw new AdminProjectionPages.Rejected(ReasonCode.STALE_CONTEXT);
    }
    private static long deadline(){return System.nanoTime()+TimeUnit.SECONDS.toNanos(30);}
    private <T> T await(CompletionStage<T> stage,long deadline) {
        bound();try{
            long remaining=deadline-System.nanoTime();if(remaining<=0)throw new TimeoutException();
            var result=stage.toCompletableFuture().get(remaining,TimeUnit.NANOSECONDS);bound();return result;
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AdminProjectionPages.Rejected(ReasonCode.STALE_CONTEXT);}
        catch(ExecutionException|TimeoutException failure){throw new AdminProjectionPages.Rejected(ReasonCode.WAL_UNAVAILABLE);}
    }
    private Map.Entry<DirectorFact,AuditDecision> find(Predicate<AuditDecision> target) {
        long deadline=deadline();
        for(int attempt=0;attempt<3;attempt++){
            Optional<String> cursor=Optional.empty();var audits=new HashMap<DecisionId,AuditDecision>();var indexes=new HashMap<DecisionId,DirectorFact>();boolean stale=false;
            do{
                var query=new StoreQuery(session.worldId(),Set.of(),new Sequence(0),cursor,512);
                var result=await(store.read(query),deadline);ReadContracts.validate(result,query,session);
                if(result.status()==ReadStatus.STALE){stale=true;break;}
                if(result.status()!=ReadStatus.AVAILABLE)throw new AdminProjectionPages.Rejected(ReasonCode.WAL_UNAVAILABLE);
                var page=result.page().orElseThrow();
                for(var record:page.records()){
                    if(record instanceof DurableAudit row&&target.test(row.value()))audits.put(row.value().decisionId(),row.value());
                    else if(record instanceof DurableFact row&&row.value().factType().equals(AdminContracts.RECORDED_DECISION_INPUT_FACT)){
                        var id=RecordedDecisionArchive.decision(row.value());if(audits.containsKey(id))indexes.put(id,row.value());
                    }
                    for(var entry:audits.entrySet())if(indexes.containsKey(entry.getKey())){
                        var index=indexes.get(entry.getKey());RecordedDecisionArchive.validateIndex(index,entry.getValue(),session,mode);
                        return Map.entry(index,entry.getValue());
                    }
                    if(audits.size()>64)throw new AdminProjectionPages.Rejected(ReasonCode.BUDGET_EXHAUSTED);
                }
                cursor=page.nextCursor();
                if(!page.hasMore())break;
            }while(true);
            if(!stale)throw new AdminProjectionPages.Rejected(ReasonCode.NO_TARGET);
        }
        throw new AdminProjectionPages.Rejected(ReasonCode.STALE_CONTEXT);
    }
    private List<BlobContent> hydrate(DirectorFact index) {
        var contents=new ArrayList<BlobContent>();long deadline=deadline();
        for(var ref:DurableBlobReferences.recordedDecision(index)){
            var result=await(store.readBlobs(new BlobReadRequest(session.worldId(),session.storeGeneration(),Set.of(ref))),deadline);
            if(result.status()!=AckStatus.DURABLE||result.contents().size()!=1||!result.contents().getFirst().reference().equals(ref))
                throw new AdminProjectionPages.Rejected(ReasonCode.STORE_CORRUPT);
            contents.add(result.contents().getFirst());
        }
        return List.copyOf(contents);
    }
}
