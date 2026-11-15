package fr.proutlost.director.persistence;

import fr.proutlost.director.api.model.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * J04 experimental transparent cold+hot read on the ONE durable writer.
 * Immutable archive checkpoint supplies the evicted prefix. The current
 * published projection supplies only WAL records newer than that checkpoint.
 * This is a read operation, never an admission or rollback capability.
 *
 * Pages have one common StoreSession and published sequence cut. A cursor
 * also pins the checkpoint generation; rotating it without changing the
 * published sequence still returns STALE, never a subtly changed page.
 */
final class HydratedChronologyPages {
    private static final String CURSOR_PREFIX="c:";
    private HydratedChronologyPages() {}

    static StoreReadResult read(Path root,CheckpointStore archive,
            CheckpointStore.Manifest checkpoint,StoreSession session,
            StoreProjection published,StoreQuery query,ArchivedWalIndex index,
            int frameCap,int transactionCap)throws IOException {
        if(checkpoint==null||session==null||published==null||
            index==null||!query.worldId().equals(session.worldId())||
            !checkpoint.world().equals(session.worldId())||
            !index.manifest.equals(checkpoint))
            return TypedProjectionReads.status(ReadStatus.UNAVAILABLE);
        if(!query.aggregateKeys().isEmpty())
            return TypedProjectionReads.status(ReadStatus.NOT_RESIDENT);
        if(checkpoint.checkpoint().isEmpty() ||
            query.sequence().value()>published.sequence ||
            published.archivedThroughSequence>checkpoint.sequence() ||
            published.archivedReadRows>index.totalRows)
            return TypedProjectionReads.status(ReadStatus.NOT_RESIDENT);

        // Validate snapshot provenance: the independent checkpoint index is a
        // complete list of committed projected rows through this checkpoint.
        var stamp=new ReadStamp(session,new Sequence(published.sequence),
            ReadContracts.queryHash(query));
        long offset=0;
        if(query.pageCursor().isPresent()){
            try {
                var cursor=ReadContracts.decodeCursor(query.pageCursor().orElseThrow());
                if(!cursor.stamp().equals(stamp))
                    return TypedProjectionReads.status(ReadStatus.STALE);
                var position=cursor.position();
                String prefix=CURSOR_PREFIX+checkpoint.generation()+":";
                if(!position.startsWith(prefix))
                    return TypedProjectionReads.status(ReadStatus.STALE);
                String number=position.substring(prefix.length());
                offset=Long.parseLong(number);
                if(offset<0||!Long.toString(offset).equals(number))
                    return TypedProjectionReads.status(ReadStatus.STALE);
            } catch(IllegalArgumentException malformed){
                return TypedProjectionReads.status(ReadStatus.STALE);
            }
        }

        long archivalBase=index.firstCommitAtLeast(query.sequence().value())==
            index.entries.size()?index.totalRows:
            index.entries.get(index.firstCommitAtLeast(query.sequence().value())).firstOrdinal();
        long coldCount=Math.subtractExact(index.totalRows,archivalBase);

        // The checkpoint is a prefix of the durable WAL. Some archive rows may
        // still be resident (e.g. a checkpoint just rotated before next eviction).
        // Never add those twice. Hot includes strictly later durable commits.
        int low=0,high=published.entries.size();
        long afterCut=Math.max(checkpoint.sequence(),query.sequence().value()==0?
            0:query.sequence().value()-1);
        while(low<high){
            int mid=(low+high)>>>1;
            if(published.entries.get(mid).sequence()<=afterCut)low=mid+1;
            else high=mid;
        }
        int hotStart=low;
        long hotCount=published.entries.size()-hotStart;
        long total=Math.addExact(coldCount,hotCount);
        if(offset>total)
            return TypedProjectionReads.status(ReadStatus.STALE);
        long requestedRemaining=Math.subtractExact(total,offset);
        int required=(int)Math.min(query.limit(),requestedRemaining);
        var rows=new ArrayList<DurableRecord>(required);

        // Fetch the archived portion using the independently checked index.
        // The inner cursor has its own checkpoint cut; the public cursor
        // belongs only to the current published StoreSession/cut.
        if(offset<coldCount && required>0) {
            long countFromArchive=Math.min(coldCount-offset,required);
            var archivedQuery=new StoreQuery(query.worldId(),Set.of(),
                query.sequence(),Optional.empty(),(int)countFromArchive);
            if(offset>0) {
                var archiveStamp=new ReadStamp(session,
                    new Sequence(checkpoint.sequence()),
                    ReadContracts.queryHash(archivedQuery));
                archivedQuery=new StoreQuery(query.worldId(),Set.of(),query.sequence(),
                    Optional.of(ReadContracts.encodeCursor(new StoreReadCursor(
                        archiveStamp,Long.toString(offset)))),(int)countFromArchive);
            }
            var page=IndexedCheckpointPages.read(root,archive,checkpoint,session,
                archivedQuery,frameCap,transactionCap,index);
            if(page.status()!=ReadStatus.AVAILABLE)
                return page.status()==ReadStatus.STALE?
                    TypedProjectionReads.status(ReadStatus.STALE):
                    TypedProjectionReads.status(ReadStatus.UNAVAILABLE);
            rows.addAll(page.page().orElseThrow().records());
            if(rows.size()!=countFromArchive)
                throw new IOException("cold checkpoint page length mismatch");
        }

        // Resident tail is read from a published immutable projection; old
        // snapshots and the writer's operational indexes remain untouched.
        if(rows.size()<required) {
            long hotOffset=Math.max(0,offset-coldCount);
            long from=(long)hotStart+hotOffset;
            long remaining=required-rows.size();
            if(from<hotStart || from+remaining>published.entries.size())
                throw new IOException("hot chronology cannot bridge checkpoint cut");
            for(long n=from;n<from+remaining;n++)
                rows.add(published.entries.get((int)n).record());
        }
        if(rows.size()!=required)
            throw new IOException("hybrid page incomplete after verified reads");

        long next=Math.addExact(offset,rows.size());
        boolean more=next<total;
        var view=new StoreView(List.copyOf(rows),List.of(),
            stamp.snapshotSequence(),more,
            more?Optional.of(ReadContracts.encodeCursor(new StoreReadCursor(
                stamp,CURSOR_PREFIX+checkpoint.generation()+":"+next))):
                Optional.empty());
        var result=new StoreReadResult(ReadStatus.AVAILABLE,Optional.of(view),
            Optional.of(stamp),List.of());
        ReadContracts.validate(result,query,session);
        return result;
    }
}
