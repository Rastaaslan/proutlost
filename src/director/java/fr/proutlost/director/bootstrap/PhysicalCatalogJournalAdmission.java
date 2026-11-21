package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.model.PhysicalCatalogFacts.Manifest;
import fr.proutlost.director.api.port.PhysicalCatalogAdmissionPort;
import fr.proutlost.director.api.model.PhysicalCatalogModels.*;
import fr.proutlost.director.persistence.JournalStore;
import fr.proutlost.director.runtime.ResultTransaction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * One optional lane INSIDE the existing Director actor/J04 owner. It never opens
 * a store, creates a player/session, grants a permit or mutates Minecraft.
 * Compiler callbacks build immutable derived views off actor. Publication is
 * possible only after exact WAL index + forced source/binding blob readback.
 */
public final class PhysicalCatalogJournalAdmission implements PhysicalCatalogAdmissionPort {
    public enum BindingUse { ADMISSION, PUBLICATION, RECOVERY }
    /** Owner-only actor check against issued frozen binding AND current real UUID ownership.
     * RECOVERY is called only for an actual verified J04 manifest+blob. It may
     * restore prior ownership, never authorize a producer's new binding.
     */
    @FunctionalInterface public interface BindingAuthority { void requireApproved(Binding binding,BindingUse use); }
    /** Pure, deterministic compiler. Unknown required semantics MUST be refused. */
    @FunctionalInterface public interface Compiler { Prepared compile(Document document); }
    /** Publication callback may publish its immutable index; it grants no world authority. */
    public record Prepared(Fingerprint digest,Consumer<Publication> publish) {
        public Prepared {Objects.requireNonNull(digest);Objects.requireNonNull(publish);}
    }
    /** Opaque proof object: unlike a Readback DTO, callers cannot construct this. */
    public static final class Publication {
        private final Readback readback;private final Binding binding;private final byte[] source;
        private Publication(Readback readback,Binding binding,byte[] source) {
            this.readback=readback;this.binding=binding;this.source=source.clone();
        }
        public Readback readback(){return readback;}
        public Binding binding(){return binding;}
        public byte[] source(){return source.clone();}
    }
    private static final int MAX_REVISIONS=1024,MAX_IDENTITIES=65536;
    private final JournalStore store;private final StoreSession session;private final WorldId world;
    private final Runnable requireActor;private final Predicate<SessionId> authorized;
    private final BindingAuthority bindingAuthority;
    private final BooleanSupplier quiescent;private final Predicate<Object> acquireLane;
    private final Consumer<Object> releaseLane;private final Supplier<Map<ScopeRef,Revision>> revisions;
    private final Executor worker;private final Compiler compiler;private final Object laneOwner=new Object();
    private record Retained(Readback readback,Binding binding) {}
    // Historical source bytes stay in J04, not one heap copy per revision.
    private final Map<Key,Retained> publications=new LinkedHashMap<>();
    private final Map<String,Key> revisionPins=new HashMap<>();
    private record Source(ResourceId world,ResourceId build) {}
    private final Map<Source,Manifest> sourceHeads=new HashMap<>();
    private final Map<UUID,String> identityOwners=new HashMap<>();
    private final Map<String,UUID> externalIdentities=new HashMap<>();
    private final ArrayDeque<Manifest> recovering=new ArrayDeque<>();
    private final Map<Key,Manifest> recoveredIndexes=new LinkedHashMap<>();
    private CompletableFuture<Optional<JournalStore.RetainedFactPage>> reading;
    private JournalStore.RetainedFactSnapshot recoverySnapshot;private Optional<FactId> cursor=Optional.empty();
    private Sequence recoveryCut=new Sequence(0);
    private Manifest hydrating;private CompletableFuture<BlobReadResult> blobs;
    private CompletableFuture<Captured> compiling;
    private Pending pending;private boolean recovered,scanned,failed,laneHeld,closed;
    private String diagnostic="RECOVERING_INDEX";private Key refusedKey;private String refusalReason;
    private record PendingSourceRead(SessionId actor,Readback readback,CompletableFuture<BlobReadResult> io,
                                     CompletableFuture<BlobReadResult> delivered) {}
    private final ArrayDeque<PendingSourceRead> pendingReads=new ArrayDeque<>();
    private static BlobReadResult refusedRead(){return new BlobReadResult(AckStatus.REJECTED,List.of(),List.of());}
    private void rejectReads(){while(!pendingReads.isEmpty())pendingReads.removeFirst().delivered().complete(refusedRead());}

    private record Captured(Document document,BlobContent binding,Prepared prepared) {}
    private static final class Pending {
        final SessionId actor;final Document document;BlobContent bindingBlob;Manifest manifest;
        CompletableFuture<Captured> compilation;Prepared prepared;DurableBatch batch;
        CompletableFuture<CommitAck> append;CommitAck ack;
        CompletableFuture<BlobReadResult> blobs;CompletableFuture<Captured> reopenedCompile;
        boolean indexVerified;
        Pending(SessionId actor,Document d){this.actor=actor;document=d;}
    }
    public PhysicalCatalogJournalAdmission(JournalStore store,Runnable requireActor,
            Predicate<SessionId> authorized,BindingAuthority bindingAuthority,BooleanSupplier quiescent,Predicate<Object> acquireLane,
            Consumer<Object> releaseLane,Supplier<Map<ScopeRef,Revision>> revisions,
            Executor worker,Compiler compiler){
        this.store=Objects.requireNonNull(store);this.requireActor=Objects.requireNonNull(requireActor);
        this.authorized=Objects.requireNonNull(authorized);this.bindingAuthority=Objects.requireNonNull(bindingAuthority);this.quiescent=Objects.requireNonNull(quiescent);
        this.acquireLane=Objects.requireNonNull(acquireLane);this.releaseLane=Objects.requireNonNull(releaseLane);
        this.revisions=Objects.requireNonNull(revisions);this.worker=Objects.requireNonNull(worker);this.compiler=Objects.requireNonNull(compiler);
        actor();session=Objects.requireNonNull(store.session());world=session.worldId();
        if(store.readOnlySafe())throw new IllegalArgumentException("physical catalogue writer unavailable");
    }
    private void actor(){requireActor.run();if(closed)throw new IllegalStateException("physical catalogue lane closed");}
    private void current(){if(!session.equals(store.session())||store.readOnlySafe())throw new IllegalStateException("physical catalogue J04 session lost");}
    private boolean authority(SessionId actor){return actor!=null&&authorized.test(actor);}
    private static void require(boolean value,String why){if(!value)throw new IllegalArgumentException(why);}
    private static Progress progress(State state,Key key,String why){return new Progress(state,key,Optional.empty(),why);}
    private void release(){if(laneHeld){releaseLane.accept(laneOwner);laneHeld=false;}}
    private void fail(RuntimeException failure){failed=true;diagnostic="FAILED_SAFE "+failure.getClass().getSimpleName()+":"+failure.getMessage();
        rejectReads();
        // An uncertain append retains the lane until process recovery, never blind retry.
        if(pending==null||pending.append==null)release();}
    private static String revisionIdentity(Key key){return key.sourceWorld().value()+"\0"+key.sourceBuild().value()+"\0"+key.sourceRevision();}
    private static List<BlobContent> content(Document document,BlobContent binding){return List.of(document.completeSource(),binding);}
    private static Set<BlobRef> references(Manifest m){return Set.of(m.source(),m.binding());}
    private static BlobReadRequest blobRequest(StoreSession session,Set<BlobRef> refs){
        return new BlobReadRequest(session.worldId(),session.storeGeneration(),refs);
    }
    private Manifest describe(Document document,BlobContent binding){
        var key=document.key();var head=sourceHeads.get(new Source(key.sourceWorld(),key.sourceBuild()));
        return new Manifest(1,key,document.completeSource().reference(),binding.reference(),
            PhysicalCatalogFacts.projectionDigest(key,document.completeSource().reference(),binding.reference()),
            head==null?1:Math.addExact(head.sourceOrdinal(),1),head==null?Optional.empty():Optional.of(PhysicalCatalogFacts.factId(head)));
    }
    /** Large canonical encode/decode/digest and semantic compilation stay off actor. */
    private Captured capture(Document document){
        require(PhysicalCatalogFacts.bindingDigest(document.binding()).equals(document.binding().bindingSha()),"physical binding digest");
        var binding=BlobContent.capture(PhysicalCatalogFacts.BINDING_CODEC,1,new ModelCodec<>(Binding.class).encode(document.binding()));
        var digest=PhysicalCatalogFacts.projectionDigest(document.key(),document.completeSource().reference(),binding.reference());var prepared=compiler.compile(document);
        require(prepared!=null&&prepared.digest().equals(digest),"physical compiler projection digest");
        return new Captured(document,binding,prepared);
    }
    private void validateBinding(Document document,BindingUse use){
        var binding=document.binding();require(document.key().directorWorld().equals(world),"physical source Director world");
        bindingAuthority.requireApproved(binding,use);
        var existing=revisionPins.get(revisionIdentity(document.key()));
        require(existing==null||existing.equals(document.key()),"physical revision changed digest/binding");
        require(publications.containsKey(document.key())||publications.size()<MAX_REVISIONS,"physical revision capacity");
        var owners=identities(binding);
        require((long)identityOwners.size()+owners.keySet().stream().filter(id->!identityOwners.containsKey(id)).count()<=MAX_IDENTITIES,
            "physical historical binding capacity");
        for(var entry:owners.entrySet()){
            var old=identityOwners.get(entry.getKey());require(old==null||old.equals(entry.getValue()),"physical UUID belongs to another source identity");
            var prior=externalIdentities.get(entry.getValue());require(prior==null||prior.equals(entry.getKey()),"physical source identity rebound");
        }
    }
    private static Map<UUID,String> identities(Binding b){
        String prefix=b.sourceWorld().value()+"\0"+b.sourceBuild().value()+"\0";Map<UUID,String> ids=new HashMap<>();
        b.regions().forEach((from,to)->ids.put(to.value(),prefix+"region\0"+from.value()));
        b.locations().forEach((from,to)->ids.put(to.value(),prefix+"location\0"+from.value()));
        b.anchors().forEach(a->ids.put(a.directorAnchor().value(),prefix+"anchor\0"+a.sourceLocation().value()+"\0"+a.sourceAnchor().value()));
        return Map.copyOf(ids);
    }
    @Override public Progress submit(SessionId actorSession,Document document){
        actor();Objects.requireNonNull(document);var key=document.key();
        if(!authority(actorSession))return progress(State.STALE_SESSION,key,"real current authority required");
        if(failed)return progress(State.FAILED_SAFE,key,diagnostic);
        if(!recovered)return progress(State.PENDING,key,"recovering physical catalogue");
        current();var known=publications.get(key);
        if(known!=null){
            if(!known.binding().equals(document.binding())||!known.readback().completeSource().equals(document.completeSource().reference()))
                return progress(State.REFUSED,key,"submitted bytes/binding differ from actual durable publication");
            try{bindingAuthority.requireApproved(document.binding(),BindingUse.ADMISSION);}
            catch(IllegalArgumentException refused){return progress(State.REFUSED,key,refused.getMessage());}
            return new Progress(State.DURABLE,key,Optional.of(known.readback()),"already durable");
        }
        if(pending!=null){
            if(pending.document.key().equals(key)&&!pending.document.equals(document))
                return progress(State.REFUSED,key,"pending key reused with different source/binding bytes");
            return progress(pending.document.key().equals(key)?State.PENDING:State.REFUSED,key,
                pending.document.key().equals(key)?"same import pending":"physical lane busy; not submitted");
        }
        try{
            require(document.key().directorWorld().equals(world),"physical source Director world");
            pending=new Pending(actorSession,document);
            pending.compilation=CompletableFuture.supplyAsync(()->capture(document),worker);refusedKey=null;refusalReason=null;
            diagnostic="COMPILING_BEFORE_WAL";return progress(State.PENDING,key,diagnostic);
        }catch(IllegalArgumentException|RejectedExecutionException invalid){pending=null;return progress(State.REFUSED,key,invalid.getMessage());}
    }
    @Override public Progress poll(SessionId actorSession,Key key){
        actor();Objects.requireNonNull(key);
        if(!authority(actorSession))return progress(State.STALE_SESSION,key,"real current authority required");
        if(failed)return progress(State.FAILED_SAFE,key,diagnostic);current();
        var publication=publications.get(key);if(publication!=null)return new Progress(State.DURABLE,key,Optional.of(publication.readback()),"verified durable source and binding");
        if(!recovered)return progress(State.PENDING,key,"recovering physical catalogue");
        if(pending!=null&&pending.document.key().equals(key))return progress(State.PENDING,key,diagnostic);
        if(key.equals(refusedKey))return progress(State.REFUSED,key,refusalReason);
        return progress(State.REFUSED,key,"UNKNOWN_PHYSICAL_IMPORT_KEY");
    }
    @Override public CompletionStage<BlobReadResult> readSource(SessionId actorSession,Readback readback){
        actor();Objects.requireNonNull(readback);var publication=publications.get(readback.key());
        if(failed||!session.equals(store.session())||store.readOnlySafe()||!authority(actorSession)
            ||publication==null||!publication.readback().equals(readback)||!readback.session().equals(session)||pendingReads.size()>=16)
            return CompletableFuture.completedFuture(refusedRead());
        var delivered=new CompletableFuture<BlobReadResult>();
        var io=store.readBlobs(blobRequest(session,Set.of(readback.completeSource()))).toCompletableFuture();
        pendingReads.addLast(new PendingSourceRead(actorSession,readback,io,delivered));
        // Never expose the writer future. The actor rechecks all authority after IO.
        return delivered.minimalCompletionStage();
    }
    private void deliverReads(){
        for(var iterator=pendingReads.iterator();iterator.hasNext();){var pending=iterator.next();
            if(pending.delivered().isDone()){iterator.remove();continue;}
            if(!pending.io().isDone())continue;
            BlobReadResult output=refusedRead();
            try{
                var retained=publications.get(pending.readback().key());
                if(!failed&&session.equals(store.session())&&!store.readOnlySafe()&&authority(pending.actor())
                    &&retained!=null&&retained.readback().equals(pending.readback())&&pending.readback().session().equals(session)){
                    var actual=pending.io().getNow(null);
                    if(actual!=null&&actual.status()==AckStatus.DURABLE&&actual.errors().isEmpty()
                        &&actual.contents().size()==1&&actual.contents().getFirst().reference().equals(pending.readback().completeSource()))output=actual;
                }
            }catch(RuntimeException invalid){ /* explicit refusal, never writer-thread authority */ }
            pending.delivered().complete(output);iterator.remove();
        }
    }
    /** Called once by the owning actor each tick, before other metadata writers. */
    public void tick(){
        actor();if(failed)return;
        try{current();deliverReads();if(!recovered){recover();return;}if(pending!=null)advance();}
        catch(RuntimeException invalid){
            Throwable cause=invalid instanceof CompletionException&&invalid.getCause()!=null?invalid.getCause():invalid;
            if(recovered&&pending!=null&&pending.append==null&&cause instanceof IllegalArgumentException){
                refusedKey=pending.document.key();refusalReason=cause.getMessage();pending=null;release();diagnostic="REFUSED "+refusalReason;
            }else fail(invalid);
        }
    }
    private void recover(){
        if(!scanned){
            if(reading==null){
                reading=recoverySnapshot==null?store.readRetainedFacts(PhysicalCatalogFacts.TYPE,new ScopeRef(ScopeKind.WORLD,world.value())).toCompletableFuture()
                    :store.readRetainedFacts(recoverySnapshot,cursor.orElseThrow()).toCompletableFuture();return;
            }
            if(!reading.isDone())return;var value=reading.getNow(null);reading=null;
            require(value!=null&&value.isPresent(),"physical retained recovery index unavailable");var page=value.orElseThrow();
            require(page.snapshot().session().equals(session),"physical retained recovery session");
            if(recoverySnapshot==null){recoverySnapshot=page.snapshot();recoveryCut=recoverySnapshot.cut();}
            else require(page.snapshot()==recoverySnapshot,"physical retained recovery snapshot changed");
            for(var fact:page.facts()){
                var manifest=PhysicalCatalogFacts.manifest(fact);require(manifest.key().directorWorld().equals(world),"foreign physical recovery manifest");
                var old=recoveredIndexes.putIfAbsent(manifest.key(),manifest);require(old==null||old.equals(manifest),"conflicting physical recovery index");
                require(recoveredIndexes.size()<=MAX_REVISIONS,"physical recovery revision capacity");
            }
            cursor=page.next();if(cursor.isEmpty()){
                var ordered=recoveredIndexes.values().stream().sorted(Comparator
                    .comparing((Manifest m)->m.key().sourceWorld().value()).thenComparing(m->m.key().sourceBuild().value())
                    .thenComparingLong(Manifest::sourceOrdinal)).toList();
                Map<Source,Manifest> checked=new HashMap<>();
                for(var manifest:ordered){var source=new Source(manifest.key().sourceWorld(),manifest.key().sourceBuild());var prior=checked.get(source);
                    require(manifest.sourceOrdinal()==(prior==null?1:prior.sourceOrdinal()+1)
                        &&manifest.previousImport().equals(prior==null?Optional.empty():Optional.of(PhysicalCatalogFacts.factId(prior))),"physical source history gap/fork/order");
                    checked.put(source,manifest);
                }
                scanned=true;recovering.addAll(ordered);
            }
            return;
        }
        if(hydrating==null){
            if(recovering.isEmpty()){recovered=true;diagnostic="IDLE";return;}
            hydrating=recovering.removeFirst();blobs=store.readBlobs(blobRequest(session,references(hydrating))).toCompletableFuture();return;
        }
        if(blobs!=null){
            if(!blobs.isDone())return;var result=blobs.getNow(null);var manifest=hydrating;blobs=null;
            compiling=CompletableFuture.supplyAsync(()->capture(reopen(manifest,result)),worker);return;
        }
        if(!compiling.isDone())return;var captured=compiling.getNow(null);
        require(describe(captured.document(),captured.binding()).equals(hydrating),"recovered physical manifest differs from rebuilt source/binding/order");
        publish(hydrating,captured.document(),captured.prepared(),recoveryCut,BindingUse.RECOVERY);hydrating=null;compiling=null;
    }
    private Document reopen(Manifest manifest,BlobReadResult result){
        require(result!=null&&result.status()==AckStatus.DURABLE&&result.errors().isEmpty(),"physical blob read unavailable");
        Map<BlobRef,BlobContent> values=new HashMap<>();for(var value:result.contents())require(values.putIfAbsent(value.reference(),value)==null,"duplicate physical blob result");
        require(values.keySet().equals(references(manifest)),"physical blob readback differs");
        byte[] bindingBytes=values.get(manifest.binding()).bytes();var bindingCodec=new ModelCodec<>(Binding.class);
        var binding=bindingCodec.decode(bindingBytes);require(Arrays.equals(bindingCodec.encode(binding),bindingBytes),"physical binding bytes noncanonical");
        var document=new Document(manifest.key(),binding,values.get(manifest.source()));
        require(PhysicalCatalogFacts.bindingDigest(binding).equals(binding.bindingSha()),"reopened binding digest");return document;
    }
    private void advance(){
        var p=pending;require(authority(p.actor),"physical actor authority revoked");
        if(p.prepared==null){
            if(!p.compilation.isDone())return;var captured=p.compilation.getNow(null);
            require(captured.document().equals(p.document),"physical captured immutable input differs");
            p.bindingBlob=captured.binding();p.manifest=describe(p.document,captured.binding());p.prepared=captured.prepared();
        }
        if(p.append==null){
            if(!laneHeld){if(!quiescent.getAsBoolean()||!acquireLane.test(laneOwner))return;laneHeld=true;}
            if(!quiescent.getAsBoolean())return;validateBinding(p.document,BindingUse.ADMISSION);
            Map<ScopeRef,Revision> cas=Map.copyOf(revisions.get());
            require(cas.containsKey(new ScopeRef(ScopeKind.WORLD,world.value()))&&cas.values().stream().allMatch(r->r.value()>0),"actual world CAS required");
            var transaction=new TransactionId(UUID.nameUUIDFromBytes(("physical-catalog-transaction-v1/"+PhysicalCatalogFacts.factId(p.manifest).value()).getBytes(StandardCharsets.UTF_8)));
            p.batch=ResultTransaction.batch(transaction,session.storeGeneration(),cas,List.of(new DurableFact(PhysicalCatalogFacts.fact(p.manifest))),content(p.document,p.bindingBlob));
            p.append=store.append(p.batch).toCompletableFuture();diagnostic="WAL_PENDING";return;
        }
        if(p.ack==null){
            if(!p.append.isDone())return;var ack=p.append.getNow(null);
            require(ack!=null&&ack.ackStatus()==AckStatus.DURABLE&&ack.transactionId().equals(p.batch.transactionId())
                &&ack.transactionHash().equals(p.batch.transactionHash())&&ack.storeGeneration().equals(session.storeGeneration())
                &&ack.durableSequence().value()>0&&ack.permitAttempt().isEmpty()&&ack.forcedBlobs().equals(references(p.manifest)),"physical J04 ACK uncertain/refused");
            p.ack=ack;diagnostic="INDEX_READBACK";
        }
        if(!p.indexVerified){
            var actual=store.retainedFact(PhysicalCatalogFacts.factId(p.manifest));
            require(actual.isPresent()&&actual.orElseThrow().equals(PhysicalCatalogFacts.fact(p.manifest)),"physical exact committed manifest absent/different in retained index");
            p.indexVerified=true;
            p.blobs=store.readBlobs(blobRequest(session,references(p.manifest))).toCompletableFuture();diagnostic="BLOB_READBACK";return;
        }
        if(p.blobs!=null){
            if(!p.blobs.isDone())return;var result=p.blobs.getNow(null);p.blobs=null;
            p.reopenedCompile=CompletableFuture.supplyAsync(()->capture(reopen(p.manifest,result)),worker);return;
        }
        if(!p.reopenedCompile.isDone())return;
        var captured=p.reopenedCompile.getNow(null);
        require(captured.document().equals(p.document)&&captured.binding().reference().equals(p.manifest.binding())
            &&captured.prepared().digest().equals(p.manifest.projectionDigest()),"physical exact source/binding readback changed");
        publish(p.manifest,p.document,captured.prepared(),p.ack.durableSequence(),BindingUse.PUBLICATION);
        pending=null;release();diagnostic="DURABLE";
    }
    private void publish(Manifest manifest,Document document,Prepared prepared,Sequence cut,BindingUse use){
        current();require(prepared!=null&&prepared.digest().equals(manifest.projectionDigest()),"physical rebuilt projection differs");
        validateBinding(document,use);
        var readback=new Readback(manifest.key(),session,cut,manifest.source(),manifest.projectionDigest(),
            "J04/"+session.worldId().value()+"/"+session.storeGeneration().value()+"/"+session.bootEpoch().value()+"/"+cut.value());
        var publication=new Publication(readback,document.binding(),document.completeSource().bytes());
        // Callback installs one immutable derived view. No producer DTO can call this constructor.
        prepared.publish().accept(publication);
        publications.put(manifest.key(),new Retained(readback,document.binding()));revisionPins.put(revisionIdentity(manifest.key()),manifest.key());
        sourceHeads.put(new Source(manifest.key().sourceWorld(),manifest.key().sourceBuild()),manifest);
        for(var entry:identities(document.binding()).entrySet()){identityOwners.put(entry.getKey(),entry.getValue());externalIdentities.put(entry.getValue(),entry.getKey());}
    }
    public boolean ready(){actor();return recovered&&!failed;}
    public boolean metadataLaneHeld(){actor();return laneHeld;}
    public String diagnostic(){actor();return diagnostic;}
    /** Store/executor belong to bootstrap. An uncertain pending append is not cancelled. */
    public void close(){actor();rejectReads();if(pending!=null&&pending.append!=null)throw new IllegalStateException("physical append must drain or recover");release();closed=true;}
    /** Shutdown fences handles and leaves uncertain WAL work to the same store's drain/recovery. */
    public void closeForRecovery(){
        actor();rejectReads();
        if(pending==null||pending.append==null)release();
        closed=true;
    }
}
