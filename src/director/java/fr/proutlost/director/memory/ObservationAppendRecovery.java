package fr.proutlost.director.memory;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.*;
import java.util.*;

/** Pure read-only decision after a definitive J01 pre-IO rejection.
 * Unknown receipts, structural refusals, wrong identities and uncertain IO
 * never authorize rebasing or consumption of a captured observation window. */
public final class ObservationAppendRecovery {
    public enum Status { REBASE_ALLOWED, WAIT_FOR_INDEX, UNSAFE }
    private ObservationAppendRecovery(){}
    public static Status verify(MetadataReadPortProvider source,StoreSession session,DurableBatch batch,CommitAck rejection) {
        var store=Objects.requireNonNull(source).metadataReads();
        Objects.requireNonNull(store);Objects.requireNonNull(session);Objects.requireNonNull(batch);Objects.requireNonNull(rejection);
        if(store.readOnlySafe()||!session.equals(store.session())||rejection.ackStatus()!=AckStatus.REJECTED
            ||!rejection.transactionId().equals(batch.transactionId())||!rejection.transactionHash().equals(batch.transactionHash())
            ||!rejection.storeGeneration().equals(session.storeGeneration())||rejection.durableSequence().value()!=0
            ||rejection.permitAttempt().isPresent()||!rejection.forcedBlobs().isEmpty()
            ||batch.permitAttempt().isPresent()||!batch.expectedStoreGeneration().equals(session.storeGeneration()))return Status.UNSAFE;
        var receipt=store.readback(batch.transactionId());
        if(receipt.isEmpty())return Status.WAIT_FOR_INDEX;
        if(!receipt.get().session().equals(session)||receipt.get().receipt().isPresent())return Status.UNSAFE;
        var current=store.snapshot();if(current.isEmpty())return Status.WAIT_FOR_INDEX;
        if(!current.get().session().equals(session))return Status.UNSAFE;
        boolean changed=false;
        for(var record:batch.records()) {
            if(!(record instanceof DurableAggregate aggregate)||aggregate.state().header().revision().value()<1)return Status.UNSAFE;
            var before=new Revision(aggregate.state().header().revision().value()-1);
            if(!store.metadataRevision(current.get(),aggregate.state().key()).equals(before))changed=true;
        }
        if(!session.equals(store.session())||store.readOnlySafe())return Status.UNSAFE;
        return changed?Status.REBASE_ALLOWED:Status.UNSAFE;
    }
}
