package fr.proutlost.director.persistence;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.util.*;
import static fr.proutlost.director.persistence.StoreJson.*;

/**
 * Experimental cold checkpoint read. Runs ONLY on the durable writer, never on
 * Minecraft's actor thread. Streams verified checkpoint WAL; a transaction
 * contributes records only after its count and ordered frame hash are proven.
 *
 * The saved immutable checkpoint generation is the snapshot cut. This method
 * does not infer absence outside that cut; live newer records stay invisible.
 * Physical cleanup and evicting memory projections are NOT implemented here.
 */
final class ArchivedCheckpointPages {
    private static final ModelCodec<DurableRecord> CODEC =
        new ModelCodec<>(DurableRecord.class);
    private ArchivedCheckpointPages() {}

    static StoreReadResult scan(CheckpointStore checkpoints,
            CheckpointStore.Manifest manifest,StoreSession session,
            StoreQuery query,int maximumFrameBytes,int maximumTransactionBytes,
            long maximumScanBytes)
            throws IOException {
        if(session==null || manifest==null ||
            !query.worldId().equals(session.worldId()) ||
            !manifest.world().equals(session.worldId()) ||
            manifest.schema()!=1)
            return TypedProjectionReads.status(ReadStatus.UNAVAILABLE);
        // Cold aggregate reconstruction needs an indexed versioned snapshot.
        // A cache miss must never masquerade as an empty valid aggregate page.
        if(!query.aggregateKeys().isEmpty())
            return TypedProjectionReads.status(ReadStatus.NOT_RESIDENT);
        if(manifest.checkpoint().isEmpty() ||
            manifest.walBytes()>maximumScanBytes ||
            query.sequence().value()>manifest.sequence())
            return TypedProjectionReads.status(ReadStatus.NOT_RESIDENT);

        var stamp=new ReadStamp(session,new Sequence(manifest.sequence()),
            ReadContracts.queryHash(query));
        long offset=0;
        if(query.pageCursor().isPresent()) {
            try {
                var cursor=ReadContracts.decodeCursor(query.pageCursor().orElseThrow());
                if(!cursor.stamp().equals(stamp))
                    return TypedProjectionReads.status(ReadStatus.STALE);
                offset=Long.parseLong(cursor.position());
                if(offset<0 || !Long.toString(offset).equals(cursor.position()))
                    return TypedProjectionReads.status(ReadStatus.STALE);
            } catch(IllegalArgumentException rejected) {
                return TypedProjectionReads.status(ReadStatus.STALE);
            }
        }
        if(offset>Integer.MAX_VALUE)
            return TypedProjectionReads.status(ReadStatus.STALE);

        // All retained parts are SHA-256 checked BEFORE issuing an AVAILABLE page.
        // Bounds are enforced per frame, transaction and returned page.
        checkpoints.verify(manifest);
        var page=new ArrayList<DurableRecord>(query.limit());
        final long[] matched={0};
        final boolean[] hasMore={false};
        try(SeekableByteChannel bytes=checkpoints.open(manifest)) {
            if(bytes.size()!=manifest.walBytes())
                throw new IOException("checkpoint length mismatch");
            long sequence=0;
            long batchBytes=0;
            long expectedCount=0;
            UUID pending=null;
            String kind=null;
            var digest=DurableFiles.digest();
            var transaction=new ArrayList<DurableRecord>();

            while(bytes.position()<bytes.size()) {
                var frame=WalFrame.read(bytes,maximumFrameBytes);
                if(frame==null || frame.sequence()!=Math.incrementExact(sequence))
                    throw new IOException("checkpoint WAL frame sequence mismatch");
                sequence=frame.sequence();
                if(frame.type()==WalFrame.Type.BEGIN) {
                    if(pending!=null)throw new IOException("interleaved cold transaction");
                    var begin=fields(frame.payload());
                    kind=string(begin,"kind");
                    if(!Set.of("SESSION","ACK","APPEND").contains(kind))
                        throw new IOException("unknown cold transaction");
                    expectedCount=number(begin,"count");
                    if(expectedCount<0 ||
                       expectedCount>maximumTransactionBytes/WalFrame.OVERHEAD)
                        throw new IOException("cold transaction record count bound");
                    pending=frame.transaction();
                    batchBytes=frame.encoded().length;
                    transaction.clear();
                    digest=DurableFiles.digest();
                    digest.update(frame.encoded());
                } else {
                    if(pending==null || !pending.equals(frame.transaction()))
                        throw new IOException("orphan cold transaction frame");
                    batchBytes=Math.addExact(batchBytes,frame.encoded().length);
                    if(batchBytes>maximumTransactionBytes)
                        throw new IOException("cold transaction byte bound");
                    if(frame.type()==WalFrame.Type.RECORD) {
                        if(!"APPEND".equals(kind) ||
                           transaction.size()>=expectedCount)
                            throw new IOException("unexpected cold transaction record");
                        try {
                            transaction.add(CODEC.decode(frame.payload()));
                        } catch(RuntimeException invalid) {
                            throw new IOException("cold record decode",invalid);
                        }
                        digest.update(frame.encoded());
                    } else if(frame.type()==WalFrame.Type.COMMIT) {
                        var commit=fields(frame.payload());
                        keys(commit,"count","ordered_hash");
                        if(number(commit,"count")!=expectedCount ||
                           transaction.size()!=expectedCount ||
                           !string(commit,"ordered_hash").equals(
                               HexFormat.of().formatHex(digest.digest())))
                            throw new IOException("cold transaction commit checksum/count");
                        if("APPEND".equals(kind) &&
                           sequence>=query.sequence().value()) {
                            for(var record:transaction) {
                                // Match StoreProjection.add: each durable PREPARE
                                // projects its embedded uniqueness markers BEFORE
                                // the PREPARE itself. The markers are not separate
                                // physical WAL frames; omitting them would change
                                // historical pagination and replay semantics.
                                var projected=new ArrayList<DurableRecord>();
                                if(record instanceof DurablePrepare prepare)
                                    for(var unique:prepare.value().uniqueness())
                                        projected.add(new DurableUnique(unique));
                                projected.add(record);
                                for(var row:projected) {
                                    long ordinal=matched[0]++;
                                    if(ordinal>=offset) {
                                        if(page.size()<query.limit())page.add(row);
                                        else hasMore[0]=true;
                                    }
                                }
                            }
                        }
                        pending=null;
                        kind=null;
                        transaction.clear();
                        batchBytes=0;
                    } else {
                        throw new IOException("invalid cold frame type");
                    }
                }
            }
            if(pending!=null || sequence!=manifest.sequence() ||
               bytes.position()!=manifest.walBytes())
                throw new IOException("incomplete or stale checkpoint cut");
        } catch(RuntimeException invalid) {
            throw new IOException("cold archive bounds or content invalid",invalid);
        }
        if(offset>matched[0])
            return TypedProjectionReads.status(ReadStatus.STALE);
        String continuation=Long.toString(Math.addExact(offset,page.size()));
        var view=new StoreView(page,List.of(),stamp.snapshotSequence(),
            hasMore[0],hasMore[0]?
                Optional.of(ReadContracts.encodeCursor(
                    new StoreReadCursor(stamp,continuation))):Optional.empty());
        var result=new StoreReadResult(ReadStatus.AVAILABLE,
            Optional.of(view),Optional.of(stamp),List.of());
        ReadContracts.validate(result,query,session);
        return result;
    }
}
