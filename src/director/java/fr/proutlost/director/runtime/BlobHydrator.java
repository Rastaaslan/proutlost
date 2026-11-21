package fr.proutlost.director.runtime;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.DurableStore;
import java.util.*;
import java.util.concurrent.*;

/** Bounded asynchronous blob hydration. Completion callbacks never access world or actor state. */
public final class BlobHydrator {
    public enum Status { PENDING, READY, FAILED }
    public record Result(Status status,Optional<BlobBundle> blobs) {}
    private record Delivery(BlobReadResult result,Throwable error) {}
    private record Pending(StoreSession session,BlobReadRequest request,long deadline,long bytes,CompletableFuture<Delivery> delivery) {}
    private final Thread actor=Thread.currentThread();private final DurableStore store;private final int capacity;private final long maximumBytes;
    private final Map<OperationToken,Pending> pending=new HashMap<>();private long queuedBytes;
    public BlobHydrator(DurableStore store,int capacity,long maximumBytes){this.store=Objects.requireNonNull(store);if(capacity<1||maximumBytes<1||maximumBytes>BlobContent.MAX_BATCH_BYTES)throw new IllegalArgumentException("hydration bounds");this.capacity=capacity;this.maximumBytes=maximumBytes;}
    private void actor(){if(Thread.currentThread()!=actor)throw new IllegalStateException("server actor required");}
    public void request(OperationToken operation,Set<BlobRef> references,long nowNanos,long timeoutNanos){
        actor();if(timeoutNanos<=0)throw new IllegalArgumentException("hydration timeout");
        long bytes=0;for(var ref:references)bytes=Math.addExact(bytes,ref.bytes());
        if(pending.size()>=capacity||pending.containsKey(operation)||bytes>maximumBytes-queuedBytes)throw new IllegalStateException("hydration backpressure");
        var session=store.session();var request=new BlobReadRequest(session.worldId(),session.storeGeneration(),references);
        var mailbox=new CompletableFuture<Delivery>();var entry=new Pending(session,request,nowNanos+timeoutNanos,bytes,mailbox);
        pending.put(operation,entry);queuedBytes+=bytes;
        try{store.readBlobs(request).whenComplete((result,error)->mailbox.complete(new Delivery(result,error)));}
        catch(RuntimeException failure){mailbox.complete(new Delivery(null,failure));}
    }
    public Result poll(OperationToken operation,long nowNanos){
        actor();Pending p=Objects.requireNonNull(pending.get(operation),"unknown hydration");
        if(!p.session().equals(store.session())||nowNanos-p.deadline()>=0){remove(operation,p);return new Result(Status.FAILED,Optional.empty());}
        Delivery delivered=p.delivery().getNow(null);if(delivered==null)return new Result(Status.PENDING,Optional.empty());remove(operation,p);
        if(delivered.error()!=null||delivered.result()==null||delivered.result().status()!=AckStatus.DURABLE)return new Result(Status.FAILED,Optional.empty());
        var contents=delivered.result().contents();Set<BlobRef> actual=new HashSet<>();
        for(var blob:contents)if(!actual.add(blob.reference()))return new Result(Status.FAILED,Optional.empty());
        if(!actual.equals(p.request().references()))return new Result(Status.FAILED,Optional.empty());
        return new Result(Status.READY,Optional.of(new BlobBundle(contents)));
    }
    public void cancel(OperationToken operation){actor();Pending p=pending.get(operation);if(p!=null)remove(operation,p);}
    private void remove(OperationToken operation,Pending p){pending.remove(operation);queuedBytes-=p.bytes();}
    public long queuedBytes(){actor();return queuedBytes;}
}
