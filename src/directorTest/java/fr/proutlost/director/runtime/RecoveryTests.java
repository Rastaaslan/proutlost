package fr.proutlost.director.runtime;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.DurableStore;
import java.util.*;
import java.util.concurrent.*;

public final class RecoveryTests {
    static int assertions;static void check(boolean b,String label){if(!b)throw new AssertionError(label);assertions++;}
    static void table(){
        var key=new ResourceKey(OwnershipDomain.BLOCK,new DimensionId("minecraft:overworld"),"0,64,0");var owner=new OwnerToken(new UUID(0,1));
        var pre=BlobContent.hash(new byte[]{1});var post=BlobContent.hash(new byte[]{2});var expected=Map.of(key,new RecoveryClassifier.Expected(owner,new Epoch(1),new Epoch(0),pre,post));
        check(RecoveryClassifier.classify(expected,List.of()).get(key)==ReconcileStatus.UNAVAILABLE,"unloaded deferred");
        var proof=new RecoveryEvidence(key,EvidenceStatus.CONFIRMED,Optional.of(post),Optional.of(owner),new Epoch(1),new Epoch(0),false);
        check(RecoveryClassifier.classify(expected,List.of(proof)).get(key)==ReconcileStatus.SAME_POST,"known post provenance");
        var preOnly=new RecoveryEvidence(key,EvidenceStatus.CONFIRMED,Optional.of(pre),Optional.of(owner),new Epoch(1),new Epoch(0),false);
        check(RecoveryClassifier.classify(expected,List.of(preOnly)).get(key)==ReconcileStatus.AMBIGUOUS,"pre hash alone is not no-action proof");
        var absent=new RecoveryEvidence(key,EvidenceStatus.CONFIRMED,Optional.of(pre),Optional.of(owner),new Epoch(1),new Epoch(0),true);
        check(RecoveryClassifier.classify(expected,List.of(absent)).get(key)==ReconcileStatus.SAME_PRE_PROVEN,"local absence proof");
        var yielded=new RecoveryEvidence(key,EvidenceStatus.CONFIRMED,Optional.of(post),Optional.of(owner),new Epoch(2),new Epoch(1),false);
        check(RecoveryClassifier.classify(expected,List.of(yielded)).get(key)==ReconcileStatus.PLAYER_OVERRIDE,"ABA tombstone wins over matching hash");
        check(RecoveryClassifier.classify(expected,List.of(proof,preOnly)).get(key)==ReconcileStatus.AMBIGUOUS,"conflicting evidence isolated");
        var same=Map.of(key,new RecoveryClassifier.Expected(owner,new Epoch(1),new Epoch(0),post,post));
        check(RecoveryClassifier.classify(same,List.of(proof)).get(key)==ReconcileStatus.AMBIGUOUS,"identical pre/post needs absence or application marker");
    }
    static final class Store implements DurableStore {
        StoreSession session=new StoreSession(new WorldId(new UUID(0,1)),new Epoch(1),new Epoch(1),new SessionId(new UUID(0,2)));
        CompletableFuture<BlobReadResult> future;
        public StoreSession session(){return session;}
        public CompletionStage<BlobReadResult> readBlobs(BlobReadRequest r){future=new CompletableFuture<>();return future;}
        public CompletionStage<CommitAck> append(DurableBatch b){throw new AssertionError("hydration must not write");}
        public StoreReadResult view(StoreQuery q){throw new AssertionError("hydration must use async read");}
        public CompletionStage<StoreReadResult> read(StoreQuery q){throw new AssertionError("blob hydration must not read page");}
    }
    static void hydration(){
        var store=new Store();var h=new BlobHydrator(store,1,1024);var blob=BlobContent.capture(new ResourceId("proutlost_director:test"),1,new byte[]{1,2});var op=new OperationToken(new UUID(0,3));
        h.request(op,Set.of(blob.reference()),0,100);check(h.queuedBytes()==2&&h.poll(op,1).status()==BlobHydrator.Status.PENDING,"asynchronous bounded bytes");
        store.future.complete(new BlobReadResult(AckStatus.DURABLE,List.of(blob),List.of()));
        check(h.poll(op,2).blobs().orElseThrow().contents().equals(List.of(blob))&&h.queuedBytes()==0,"complete exact hydration");
        h.request(op,Set.of(blob.reference()),0,100);store.future.complete(new BlobReadResult(AckStatus.DURABLE,List.of(),List.of()));check(h.poll(op,1).status()==BlobHydrator.Status.FAILED,"missing blob cannot hydrate");
        h.request(op,Set.of(blob.reference()),Long.MAX_VALUE-10,20);check(h.poll(op,Long.MIN_VALUE+10).status()==BlobHydrator.Status.FAILED,"monotone nanoTime wrap timeout");
        h.request(op,Set.of(blob.reference()),0,100);store.session=new StoreSession(store.session.worldId(),new Epoch(1),new Epoch(2),new SessionId(new UUID(0,4)));store.future.complete(new BlobReadResult(AckStatus.DURABLE,List.of(blob),List.of()));check(h.poll(op,1).status()==BlobHydrator.Status.FAILED,"obsolete store session cannot hydrate current world");
    }
    public static void main(String[] args){table();hydration();System.out.println("PASS recovery/hydration assertions="+assertions);}
}
