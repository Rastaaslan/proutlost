package fr.proutlost.director.persistence;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Path;
import java.util.*;
import static fr.proutlost.director.persistence.StoreJson.*;

/**
 * Bounded indexed reads of a verified cold checkpoint. The immutable index
 * refers to durable transaction BEGIN offsets and committed row positions,
 * including synthetic PREPARE uniqueness entries.
 *
 * Every transaction supplying a result page is re-verified from its BEGIN
 * through a checked COMMIT. The content-addressed archive parts are SHA-256
 * verified before the index can be read or generated. Neither the index nor
 * the page is authority to execute a world mutation.
 */
final class IndexedCheckpointPages {
    private static final ModelCodec<DurableRecord> CODEC=
        new ModelCodec<>(DurableRecord.class);
    private IndexedCheckpointPages() {}
    private static void require(boolean yes,String reason)throws IOException {
        if(!yes)throw new IOException(reason);
    }
    static StoreReadResult read(Path root,CheckpointStore checkpoints,
            CheckpointStore.Manifest manifest,StoreSession session,StoreQuery query,
            int frameMax,int txMax,ArchivedWalIndex verifiedIndex)throws IOException {
        if(manifest==null||session==null||
            !manifest.world().equals(session.worldId())||
            !query.worldId().equals(session.worldId())||
            manifest.schema()!=1)
            return TypedProjectionReads.status(ReadStatus.UNAVAILABLE);
        if(!query.aggregateKeys().isEmpty())
            return TypedProjectionReads.status(ReadStatus.NOT_RESIDENT);
        if(manifest.checkpoint().isEmpty()||
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
                if(offset<0||!Long.toString(offset).equals(cursor.position()))
                    return TypedProjectionReads.status(ReadStatus.STALE);
            }catch(IllegalArgumentException invalid) {
                return TypedProjectionReads.status(ReadStatus.STALE);
            }
        }

        // This SHA-256 verification is intentionally O(archive bytes); the
        // persisted seek index bounds *record decoding*, not integrity checks.
        ArchivedWalIndex index;
        try {
            index=verifiedIndex==null
                ?ArchivedWalIndex.verified(root,checkpoints,manifest,frameMax,txMax)
                :verifiedIndex;
            require(index.manifest.equals(manifest),"indexed checkpoint anchor changed");
            checkpoints.verify(manifest);
        } catch(ArchivedWalIndex.CapacityExceeded expectedCapacity) {
            return TypedProjectionReads.status(ReadStatus.NOT_RESIDENT);
        }
        int first=index.firstCommitAtLeast(query.sequence().value());
        long base=first==index.entries.size() ?
            index.totalRows:index.entries.get(first).firstOrdinal();
        long available=Math.subtractExact(index.totalRows,base);
        if(offset>available)
            return TypedProjectionReads.status(ReadStatus.STALE);
        long target=Math.addExact(base,offset);
        boolean more=available-offset>query.limit();

        List<DurableRecord> page=new ArrayList<>(query.limit());
        if(target<index.totalRows) {
            int selected=index.containingOrdinal(target);
            try(SeekableByteChannel bytes=checkpoints.open(manifest)) {
                require(bytes.size()==manifest.walBytes(),"indexed archive length");
                for(int i=selected;i<index.entries.size()&&
                        page.size()<query.limit();i++) {
                    var ref=index.entries.get(i);
                    bytes.position(ref.byteOffset());
                    long firstSequence=ref.beginSequence();
                    var begin=WalFrame.read(bytes,frameMax);
                    require(begin!=null && begin.type()==WalFrame.Type.BEGIN &&
                        begin.sequence()==firstSequence,
                        "indexed transaction start mismatch");
                    var fields=fields(begin.payload());
                    require(string(fields,"kind").equals("APPEND"),
                        "indexed transaction type substitution");
                    long declared=number(fields,"count");
                    require(declared>=0&&declared<=txMax/WalFrame.OVERHEAD,
                        "indexed count bound");
                    UUID tx=begin.transaction();
                    var digest=DurableFiles.digest();
                    digest.update(begin.encoded());
                    long weight=begin.encoded().length;
                    List<DurableRecord> records=new ArrayList<>();
                    for(long j=0;j<declared;j++) {
                        var frame=WalFrame.read(bytes,frameMax);
                        require(frame!=null&&
                            frame.type()==WalFrame.Type.RECORD&&
                            frame.transaction().equals(tx)&&
                            frame.sequence()==firstSequence+1+j,
                            "indexed transaction record mismatch");
                        weight=Math.addExact(weight,frame.encoded().length);
                        require(weight<=txMax,"indexed transaction bytes");
                        try {records.add(CODEC.decode(frame.payload()));}
                        catch(RuntimeException invalid) {
                            throw new IOException("indexed record codec",invalid);
                        }
                        digest.update(frame.encoded());
                    }
                    var commit=WalFrame.read(bytes,frameMax);
                    require(commit!=null&&commit.type()==WalFrame.Type.COMMIT&&
                        commit.transaction().equals(tx)&&
                        commit.sequence()==ref.commitSequence()&&
                        commit.sequence()==firstSequence+declared+1,
                        "indexed COMMIT mismatch");
                    weight=Math.addExact(weight,commit.encoded().length);
                    require(weight<=txMax,"indexed total bytes");
                    var proof=fields(commit.payload());
                    keys(proof,"count","ordered_hash");
                    require(number(proof,"count")==declared&&
                        string(proof,"ordered_hash").equals(
                            HexFormat.of().formatHex(digest.digest())),
                        "indexed transaction hash/count");
                    long ordinal=ref.firstOrdinal();
                    for(var record:records) {
                        if(record instanceof DurablePrepare prepare) {
                            for(var unique:prepare.value().uniqueness()) {
                                if(ordinal>=target && page.size()<query.limit())
                                    page.add(new DurableUnique(unique));
                                ordinal=Math.addExact(ordinal,1);
                            }
                        }
                        if(ordinal>=target && page.size()<query.limit())
                            page.add(record);
                        ordinal=Math.addExact(ordinal,1);
                    }
                    require(ordinal==ref.endOrdinal(),"indexed row count mismatch");
                }
            }catch(RuntimeException invalid) {
                throw new IOException("indexed checkpoint data bounds",invalid);
            }
        }
        require(page.size()==Math.min((long)query.limit(),available-offset),
            "incomplete indexed page");
        var view=new StoreView(page,List.of(),stamp.snapshotSequence(),more,
            more?Optional.of(ReadContracts.encodeCursor(new StoreReadCursor(
                stamp,Long.toString(Math.addExact(offset,page.size()))))):
                Optional.empty());
        var result=new StoreReadResult(ReadStatus.AVAILABLE,
            Optional.of(view),Optional.of(stamp),List.of());
        ReadContracts.validate(result,query,session);
        return result;
    }
}
