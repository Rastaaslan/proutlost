package fr.proutlost.director.persistence;

import fr.proutlost.director.api.model.*;
import java.util.*;

/**
 * API 1.0.2 projection adapter. Pages are bounded, immutable and bound to the
 * exact published commit cut and current StoreSession; stale cursors fail closed.
 * This is not evidence that historical cold data has been hydrated from disk.
 */
final class TypedProjectionReads {
    private TypedProjectionReads() {}

    static StoreReadResult status(ReadStatus status) {
        return new StoreReadResult(status,Optional.empty(),Optional.empty(),List.of());
    }
    static SubscriptionReadResult subscriptionStatus(ReadStatus status) {
        return new SubscriptionReadResult(status,Optional.empty(),Optional.empty(),List.of());
    }

    static StoreReadResult store(StoreQuery query, StoreProjection projection, StoreSession session) {
        if(session==null || projection==null || !query.worldId().equals(session.worldId()))
            return status(ReadStatus.UNAVAILABLE);
        // A query starting past the latest published sequence has no proven
        // interval at all. Never describe this unobserved future as empty.
        if(query.sequence().value()>projection.sequence)
            return status(ReadStatus.NOT_RESIDENT);
        // Evicted chronological rows remain in verified indexed checkpoints,
        // never in this resident projection. A partial hot tail cannot prove
        // a complete historical query, including pages from older cursors.
        if(query.aggregateKeys().isEmpty() &&
            query.sequence().value()<=projection.archivedThroughSequence &&
            projection.archivedReadRows>0)
            return status(ReadStatus.NOT_RESIDENT);
        ReadStamp stamp=new ReadStamp(session,new Sequence(projection.sequence),ReadContracts.queryHash(query));
        long offset=0;
        if(query.pageCursor().isPresent()) {
            try {
                StoreReadCursor cursor=ReadContracts.decodeCursor(query.pageCursor().orElseThrow());
                if(!cursor.stamp().equals(stamp)) return status(ReadStatus.STALE);
                offset=Long.parseLong(cursor.position());
                if(offset<0 || !Long.toString(offset).equals(cursor.position()))
                    return status(ReadStatus.STALE);
            } catch(IllegalArgumentException error) {
                return status(ReadStatus.STALE);
            }
        }
        if(offset>Integer.MAX_VALUE) return status(ReadStatus.STALE);
        StoreView view;
        if(!query.aggregateKeys().isEmpty()) {
            var aggregates=query.aggregateKeys().stream()
                .sorted(Comparator.comparing(k->k.kind()+":"+k.id()))
                .map(projection.aggregates::get).filter(Objects::nonNull)
                .filter(a->a.committedSequence().compareTo(query.sequence())>=0).toList();
            if(offset>aggregates.size())return status(ReadStatus.STALE);
            int end=(int)Math.min(aggregates.size(),offset+query.limit());
            boolean more=end<aggregates.size();
            view=new StoreView(List.of(),aggregates.subList((int)offset,end),
                stamp.snapshotSequence(),more,
                more?Optional.of(ReadContracts.encodeCursor(new StoreReadCursor(stamp,Integer.toString(end))))
                    :Optional.empty());
        } else {
            int low=0,high=projection.entries.size();
            while(low<high) {
                int mid=(low+high)>>>1;
                if(projection.entries.get(mid).sequence()<query.sequence().value())low=mid+1;
                else high=mid;
            }
            long start=(long)low+offset;
            if(start>projection.entries.size())return status(ReadStatus.STALE);
            int end=(int)Math.min(projection.entries.size(),start+query.limit());
            boolean more=end<projection.entries.size();
            List<DurableRecord> records=new ArrayList<>(end-(int)start);
            for(int i=(int)start;i<end;i++)records.add(projection.entries.get(i).record());
            view=new StoreView(records,List.of(),stamp.snapshotSequence(),more,
                more?Optional.of(ReadContracts.encodeCursor(new StoreReadCursor(stamp,Integer.toString(end-low))))
                    :Optional.empty());
        }
        try {
            var result=new StoreReadResult(ReadStatus.AVAILABLE,Optional.of(view),Optional.of(stamp),List.of());
            ReadContracts.validate(result,query,session);
            return result;
        } catch(IllegalArgumentException badProjection) {
            return status(ReadStatus.CORRUPT);
        }
    }

    static SubscriptionReadResult subscription(
            ConsumerSpec consumer, StoreProjection projection, StoreSession session, Subscription page) {
        if(session==null || projection==null)return subscriptionStatus(ReadStatus.UNAVAILABLE);
        long scanned=page.nextSequence().value()-1;
        if(scanned<Math.max(0,consumer.nextSequence().value()-1)
            || scanned>projection.messageSequence
            || page.hasMore()!=(scanned<projection.messageSequence))
            return subscriptionStatus(ReadStatus.STALE);
        try {
            var stamp=new SubscriptionReadStamp(
                new ReadStamp(session,new Sequence(projection.sequence),ReadContracts.queryHash(consumer)),
                consumer.nextSequence(),new Sequence(projection.messageSequence),new Sequence(scanned));
            var result=new SubscriptionReadResult(
                ReadStatus.AVAILABLE,Optional.of(page),Optional.of(stamp),List.of());
            ReadContracts.validate(result,consumer,session);
            return result;
        } catch(IllegalArgumentException invalid) {
            return subscriptionStatus(ReadStatus.CORRUPT);
        }
    }
}
