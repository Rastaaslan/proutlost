package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.persistence.JournalStore;
import fr.proutlost.director.runtime.ResultTransaction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Secondary metadata lane on the SAME J04 writer and actor, never a new store.
 * Immutable config first, then exact ancillary event-plan pins. No world effect authority.
 */
public final class PhysicalMetadataJournal {
    public enum State{PENDING,DURABLE,REFUSED,FAILED_SAFE}
    public enum RecoveryUse{WAIT,HYDRATE,HISTORY}
    public record Prepared(Consumer<Commit> publish){public Prepared{Objects.requireNonNull(publish);}}
    @FunctionalInterface public interface Compiler{Prepared compile(PhysicalMetadataIndex index,Map<BlobRef,BlobContent> actualBytes);}
    /** Caller cannot manufacture a successful actual-J04 commit publication. */
    public static final class Commit{
        private final PhysicalMetadataIndex index;private final StoreSession session;private final Sequence cut;private final boolean recovery;
        private Commit(PhysicalMetadataIndex index,StoreSession session,Sequence cut,boolean recovery){this.index=index;this.session=session;this.cut=cut;this.recovery=recovery;}
        public PhysicalMetadataIndex index(){return index;}public StoreSession session(){return session;}public Sequence cut(){return cut;}public boolean recovery(){return recovery;}
    }
    private final JournalStore store;private final StoreSession session;private final Runnable actor;
    private final BiConsumer<PhysicalMetadataIndex,Boolean> approval;private final BooleanSupplier quiescent;
    private final Predicate<Object> acquire;private final Consumer<Object> release;private final Supplier<Map<ScopeRef,Revision>> revisions;
    private final Executor worker;private final Compiler compiler;private final Function<PhysicalMetadataIndex,RecoveryUse> recoveryUse;private final Object lane=new Object();
    private final Map<FactId,PhysicalMetadataIndex> committed=new LinkedHashMap<>();
    private final Map<FactId,PhysicalMetadataIndex> recovered=new LinkedHashMap<>();
    private final ArrayDeque<PhysicalMetadataIndex> hydration=new ArrayDeque<>();
    private CompletableFuture<Optional<JournalStore.RetainedFactPage>> scan;private JournalStore.RetainedFactSnapshot snapshot;
    private Optional<FactId> cursor=Optional.empty();private boolean scanDone,ready,failed,held,closed;
    private PhysicalMetadataIndex recovering;private CompletableFuture<BlobReadResult> recoveryBlobs;private CompletableFuture<Prepared> recoveryCompile;
    private Pending pending;private String diagnostic="RECOVERING";private FactId refused;private String refusal;
    private static final class Pending{
        final PhysicalMetadataIndex index;final List<BlobContent> supplied;CompletableFuture<Prepared> compilation;Prepared prepared;
        DurableBatch batch;CompletableFuture<CommitAck> append;CommitAck ack;CompletableFuture<BlobReadResult> read;CompletableFuture<Prepared> recompiled;
        Pending(PhysicalMetadataIndex index,List<BlobContent> supplied){this.index=index;this.supplied=List.copyOf(supplied);}
    }
    public PhysicalMetadataJournal(JournalStore store,Runnable actor,BiConsumer<PhysicalMetadataIndex,Boolean> approval,
            BooleanSupplier quiescent,Predicate<Object> acquire,Consumer<Object> release,Supplier<Map<ScopeRef,Revision>> revisions,
            Executor worker,Compiler compiler,Function<PhysicalMetadataIndex,RecoveryUse> recoveryUse){
        this.store=Objects.requireNonNull(store);this.actor=Objects.requireNonNull(actor);this.approval=Objects.requireNonNull(approval);
        this.quiescent=Objects.requireNonNull(quiescent);this.acquire=Objects.requireNonNull(acquire);this.release=Objects.requireNonNull(release);
        this.revisions=Objects.requireNonNull(revisions);this.worker=Objects.requireNonNull(worker);this.compiler=Objects.requireNonNull(compiler);this.recoveryUse=Objects.requireNonNull(recoveryUse);
        owner();session=Objects.requireNonNull(store.session());
    }
    private void owner(){actor.run();if(closed)throw new IllegalStateException("physical metadata owner closed");}
    private void current(){if(store.readOnlySafe()||!session.equals(store.session()))throw new IllegalStateException("physical metadata J04 session lost");}
    private static void require(boolean value,String why){if(!value)throw new IllegalArgumentException(why);}
    private void unlock(){if(held){release.accept(lane);held=false;}}
    private BlobReadRequest request(PhysicalMetadataIndex index){return new BlobReadRequest(session.worldId(),session.storeGeneration(),PhysicalMetadataFacts.references(index));}
    private static Map<BlobRef,BlobContent> exact(PhysicalMetadataIndex index,Collection<BlobContent> bytes,boolean supplied){
        Map<BlobRef,BlobContent> result=new HashMap<>();for(var row:bytes)require(result.putIfAbsent(row.reference(),row)==null,"duplicate physical metadata bytes");
        var expected=new HashSet<>(PhysicalMetadataFacts.references(index));
        if(supplied&&index instanceof PhysicalSitePlanIndex p){expected.remove(p.pin().catalog().completeSource());expected.remove(p.binding());}
        require(result.keySet().equals(expected),"physical metadata exact blob set");return Map.copyOf(result);
    }
    private static Map<BlobRef,BlobContent> actual(PhysicalMetadataIndex index,BlobReadResult value){
        require(value!=null&&value.status()==AckStatus.DURABLE&&value.errors().isEmpty(),"physical metadata blob read unavailable");return exact(index,value.contents(),false);
    }
    public State submit(PhysicalMetadataIndex index,List<BlobContent> bytes){
        owner();Objects.requireNonNull(index);if(failed)return State.FAILED_SAFE;if(!ready)return State.PENDING;current();
        require(PhysicalMetadataFacts.world(index).equals(session.worldId()),"physical metadata foreign world");var id=PhysicalMetadataFacts.id(index);
        var old=committed.get(id);if(old!=null){require(old.equals(index),"physical metadata identity reused");approval.accept(index,false);return State.DURABLE;}
        if(id.equals(refused))return State.REFUSED;
        if(pending!=null){if(PhysicalMetadataFacts.id(pending.index).equals(id))require(pending.index.equals(index)&&pending.supplied.equals(bytes),"physical metadata pending body changed");return State.PENDING;}
        if(committed.size()>=2048){refused=id;refusal="physical metadata committed capacity";return State.REFUSED;}
        try{approval.accept(index,false);var supplied=exact(index,bytes,true);pending=new Pending(index,bytes);
            pending.compilation=CompletableFuture.supplyAsync(()->compiler.compile(index,supplied),worker);refused=null;refusal=null;return State.PENDING;
        }catch(IllegalArgumentException|RejectedExecutionException invalid){pending=null;refused=id;refusal=invalid.getMessage();return State.REFUSED;}
    }
    public State state(PhysicalMetadataIndex index){owner();if(failed)return State.FAILED_SAFE;current();var id=PhysicalMetadataFacts.id(index);
        var value=committed.get(id);if(value!=null){require(value.equals(index),"physical metadata committed identity changed");return State.DURABLE;}
        return id.equals(refused)?State.REFUSED:State.PENDING;}
    public void tick(){
        owner();if(failed)return;
        try{current();if(!ready){recover();return;}if(pending!=null)advance();}
        catch(RuntimeException invalid){Throwable cause=invalid instanceof CompletionException&&invalid.getCause()!=null?invalid.getCause():invalid;
            if(ready&&pending!=null&&pending.append==null&&cause instanceof IllegalArgumentException){refused=PhysicalMetadataFacts.id(pending.index);refusal=cause.getMessage();pending=null;unlock();diagnostic="REFUSED "+refusal;}
            else{failed=true;diagnostic="FAILED_SAFE "+invalid;if(pending==null||pending.append==null)unlock();}
        }
    }
    private void recover(){
        if(!scanDone){
            if(scan==null){scan=snapshot==null?store.readRetainedFacts(PhysicalMetadataFacts.TYPE,new ScopeRef(ScopeKind.WORLD,session.worldId().value())).toCompletableFuture()
                :store.readRetainedFacts(snapshot,cursor.orElseThrow()).toCompletableFuture();return;}
            if(!scan.isDone())return;var value=scan.getNow(null);scan=null;require(value!=null&&value.isPresent(),"physical metadata recovery index unavailable");var page=value.orElseThrow();
            require(page.snapshot().session().equals(session),"physical metadata recovery session");if(snapshot==null)snapshot=page.snapshot();else require(snapshot==page.snapshot(),"physical metadata recovery snapshot changed");
            for(var fact:page.facts()){var index=PhysicalMetadataFacts.index(fact);require(PhysicalMetadataFacts.world(index).equals(session.worldId()),"foreign physical metadata index");
                var old=recovered.putIfAbsent(fact.factId(),index);require(old==null||old.equals(index),"physical metadata index conflict");require(recovered.size()<=2048,"physical metadata recovery capacity");}
            cursor=page.next();if(cursor.isEmpty()){scanDone=true;hydration.addAll(recovered.values().stream().sorted(Comparator.comparingInt(v->v instanceof PhysicalCatalogConfiguration?0:1)).toList());}return;
        }
        if(recovering==null){if(hydration.isEmpty()){ready=true;diagnostic="IDLE";return;}
            var next=hydration.getFirst();var use=Objects.requireNonNull(recoveryUse.apply(next));if(use==RecoveryUse.WAIT)return;
            hydration.removeFirst();if(use==RecoveryUse.HISTORY){committed.put(PhysicalMetadataFacts.id(next),next);return;}
            recovering=next;recoveryBlobs=store.readBlobs(request(recovering)).toCompletableFuture();return;}
        if(recoveryBlobs!=null){if(!recoveryBlobs.isDone())return;var index=recovering;var bytes=actual(index,recoveryBlobs.getNow(null));recoveryBlobs=null;
            recoveryCompile=CompletableFuture.supplyAsync(()->compiler.compile(index,bytes),worker);return;}
        if(!recoveryCompile.isDone())return;publish(recovering,recoveryCompile.getNow(null),snapshot.cut(),true);recovering=null;recoveryCompile=null;
    }
    private void advance(){
        var p=pending;
        if(p.prepared==null){if(!p.compilation.isDone())return;p.prepared=p.compilation.getNow(null);require(p.prepared!=null,"physical metadata compiler result");}
        // Acquisition is actor-atomic and makes the shared anchor owner busy.
        // Rechecking external quiescence here would wait on our own held lane.
        if(p.append==null){if(!held){if(!quiescent.getAsBoolean()||!acquire.test(lane))return;held=true;}
            approval.accept(p.index,false);var cas=Map.copyOf(revisions.get());require(cas.containsKey(new ScopeRef(ScopeKind.WORLD,session.worldId().value())),"actual metadata world CAS absent");
            var id=PhysicalMetadataFacts.id(p.index);var transaction=new TransactionId(UUID.nameUUIDFromBytes(("physical-metadata-transaction-v1/"+id.value()).getBytes(StandardCharsets.UTF_8)));
            p.batch=ResultTransaction.batch(transaction,session.storeGeneration(),cas,List.of(new DurableFact(PhysicalMetadataFacts.fact(p.index))),p.supplied);
            p.append=store.append(p.batch).toCompletableFuture();diagnostic="WAL_PENDING";return;}
        if(p.ack==null){if(!p.append.isDone())return;var ack=p.append.getNow(null);
            require(ack!=null&&ack.ackStatus()==AckStatus.DURABLE&&ack.transactionId().equals(p.batch.transactionId())&&ack.transactionHash().equals(p.batch.transactionHash())
                &&ack.storeGeneration().equals(session.storeGeneration())&&ack.durableSequence().value()>0&&ack.permitAttempt().isEmpty()
                &&ack.forcedBlobs().equals(PhysicalMetadataFacts.references(p.index)),"physical metadata ACK uncertain");
            var fact=store.retainedFact(PhysicalMetadataFacts.id(p.index));require(fact.isPresent()&&fact.orElseThrow().equals(PhysicalMetadataFacts.fact(p.index)),"physical metadata actual index differs");
            p.ack=ack;p.read=store.readBlobs(request(p.index)).toCompletableFuture();diagnostic="EXACT_READBACK";return;}
        if(p.read!=null){if(!p.read.isDone())return;var bytes=actual(p.index,p.read.getNow(null));p.read=null;
            for(var supplied:p.supplied)require(supplied.equals(bytes.get(supplied.reference())),"physical metadata reopened supplied bytes differ");
            p.recompiled=CompletableFuture.supplyAsync(()->compiler.compile(p.index,bytes),worker);return;}
        if(!p.recompiled.isDone())return;publish(p.index,p.recompiled.getNow(null),p.ack.durableSequence(),false);pending=null;unlock();diagnostic="DURABLE";
    }
    private void publish(PhysicalMetadataIndex index,Prepared prepared,Sequence cut,boolean recovery){
        current();require(prepared!=null,"physical metadata empty projection");approval.accept(index,recovery);prepared.publish().accept(new Commit(index,session,cut,recovery));
        var old=committed.putIfAbsent(PhysicalMetadataFacts.id(index),index);require(old==null||old.equals(index),"physical metadata publication changed");
        require(committed.size()<=2048,"physical metadata committed capacity");
    }
    public List<PhysicalSitePin> recordedSites(){owner();return committed.values().stream().filter(v->v instanceof PhysicalSitePlanIndex).map(v->((PhysicalSitePlanIndex)v).pin()).toList();}
    public boolean configurationsRecovered(){owner();return scanDone&&!(recovering instanceof PhysicalCatalogConfiguration)&&hydration.stream().noneMatch(v->v instanceof PhysicalCatalogConfiguration);}
    public boolean ready(){owner();return ready&&!failed;}public boolean busy(){owner();return pending!=null||!ready;}
    public String diagnostic(){owner();return diagnostic;}
    public void close(){owner();if(pending!=null&&pending.append!=null)throw new IllegalStateException("physical metadata append requires drain/recovery");unlock();closed=true;}
    public void closeForRecovery(){owner();if(pending==null||pending.append==null)unlock();closed=true;}
}
