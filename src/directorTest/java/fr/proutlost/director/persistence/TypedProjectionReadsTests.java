package fr.proutlost.director.persistence;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** Pure 1.0.2 DTO and stale-cursor regression; no durable IO or gameplay. */
public final class TypedProjectionReadsTests {
    private static int count;
    static void check(boolean pass,String reason) {
        if(!pass)throw new AssertionError(reason);
        count++;
    }
    public static void main(String[] ignored) {
        var world=new WorldId(new UUID(0,101));
        var session=new StoreSession(world,new Epoch(1),new Epoch(0),new SessionId(new UUID(0,102)));
        var projection=new StoreProjection();
        var query=new StoreQuery(world,Set.of(),new Sequence(0),Optional.empty(),4);
        var page=TypedProjectionReads.store(query,projection,session);
        check(page.status()==ReadStatus.AVAILABLE,"empty resident snapshot valid");
        ReadContracts.validate(page,query,session);
        check(page.page().orElseThrow().records().isEmpty(),"empty view payload");
        check(page.stamp().orElseThrow().snapshotSequence().equals(new Sequence(0)),"commit cut zero");
        var wrongWorld=new StoreQuery(new WorldId(new UUID(0,103)),Set.of(),new Sequence(0),Optional.empty(),4);
        check(TypedProjectionReads.store(wrongWorld,projection,session).status()==ReadStatus.UNAVAILABLE,
            "foreign world unavailable");
        var invalidCursor=new StoreQuery(world,Set.of(),new Sequence(0),Optional.of("not-a-cursor"),4);
        check(TypedProjectionReads.store(invalidCursor,projection,session).status()==ReadStatus.STALE,
            "invalid cursor never proves empty");
        var cursor=ReadContracts.encodeCursor(new StoreReadCursor(page.stamp().orElseThrow(),"1"));
        var pastEnd=new StoreQuery(world,Set.of(),new Sequence(0),Optional.of(cursor),4);
        check(TypedProjectionReads.store(pastEnd,projection,session).status()==ReadStatus.STALE,
            "unverified offset stale");
        projection.sequence=1;
        check(TypedProjectionReads.store(pastEnd,projection,session).status()==ReadStatus.STALE,
            "new commit invalidates retained snapshot");
        var consumer=new ConsumerSpec(new ConsumerId(new UUID(0,104)),Set.of(),new Sequence(0),4);
        var emptySub=new Subscription(consumer,List.of(),new Sequence(1),false);
        var sub=TypedProjectionReads.subscription(consumer,new StoreProjection(),session,emptySub);
        check(sub.status()==ReadStatus.AVAILABLE && sub.page().orElseThrow().batch().isEmpty(),
            "empty subscription with proven zero highwater");
        ReadContracts.validate(sub,consumer,session);
        var hidden=new StoreProjection();hidden.messageSequence=1;
        check(TypedProjectionReads.subscription(consumer,hidden,session,emptySub).status()==ReadStatus.STALE,
            "unscanned message cannot be treated as absent");
        System.out.println("PASS TypedProjectionReadsTests assertions="+count+"; cold disk IO NOT_RUN");
    }
}
