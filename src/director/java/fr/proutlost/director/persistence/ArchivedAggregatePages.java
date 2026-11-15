package fr.proutlost.director.persistence;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.util.*;
import static fr.proutlost.director.persistence.StoreJson.*;

/**
 * Immutable, derived cold-aggregate index. No world operations or gameplay authority.
 * Sidecars are verified against ALL authoritative checkpoint transactions on first
 * access in each process; hashes alone never prove the absence of an aggregate.
 */
final class ArchivedAggregatePages {
    private static final int MAGIC=0x57444131; // WDA1
    private static final int VERSION=1;
    private static final int HEADER=100, ROW=92, FOOTER=32;
    private static final int MAX_INDEX_BYTES=4*1024*1024;
    private static final int MAX_KEYS=(MAX_INDEX_BYTES-HEADER-FOOTER)/ROW;
    private static final int MAX_PAGE_KEYS=64;
    private static final long MAX_PAGE_BYTES=16L*1024*1024;
    private static final Comparator<AggregateKey> ORDER=
        Comparator.comparing(k->k.kind()+":"+k.id());
    private static final ModelCodec<DurableRecord> CODEC=
        new ModelCodec<>(DurableRecord.class);

    record Ref(AggregateKey key,long offset,long beginSequence,long commitSequence,
               int recordOrdinal,int uniqueOrdinal,long revision,Fingerprint hash) {
        Ref {
            Objects.requireNonNull(key);
            Objects.requireNonNull(hash);
            if(offset<0||beginSequence<=0||commitSequence<=beginSequence||
               recordOrdinal<0||uniqueOrdinal< -1||revision<=0)
                throw new IllegalArgumentException("invalid cold aggregate ref");
        }
    }
    private record Transaction(List<DurableRecord> records,Fingerprint hash,long bytes) {}
    final CheckpointStore.Manifest manifest;
    private final SortedMap<AggregateKey,Ref> references;
    private ArchivedAggregatePages(CheckpointStore.Manifest manifest,
                                    Map<AggregateKey,Ref> references) {
        this.manifest=Objects.requireNonNull(manifest);
        var sorted=new TreeMap<AggregateKey,Ref>(ORDER);
        sorted.putAll(references);
        this.references=Collections.unmodifiableSortedMap(sorted);
    }
    private static void require(boolean yes,String reason)throws IOException {
        if(!yes)throw new IOException(reason);
    }
    private static byte[] decodeHash(String text) {return HexFormat.of().parseHex(text);}
    private static Path sidecar(Path root,CheckpointStore.Manifest manifest)throws IOException {
        var dir=root.resolve("checkpoints/cold-index");
        if(Files.isSymbolicLink(dir))throw new IOException("aggregate index symlink directory");
        Files.createDirectories(dir);
        return dir.resolve(manifest.generation()+".wdagg");
    }

    /** Verify the WHOLE selected committed APPEND, not just the pointed record. */
    private static Transaction readTransaction(SeekableByteChannel file,
            long offset,long beginSeq,long commitSeq,int frameMax,int txMax)throws IOException {
        file.position(offset);
        var begin=WalFrame.read(file,frameMax);
        require(begin!=null&&begin.type()==WalFrame.Type.BEGIN&&
            begin.sequence()==beginSeq,"aggregate BEGIN sequence");
        var fields=fields(begin.payload());
        require("APPEND".equals(string(fields,"kind")),"aggregate source not APPEND");
        long count=number(fields,"count");
        require(count>=0&&count<=txMax/WalFrame.OVERHEAD,"aggregate record bound");
        var batch=model(DurableBatch.class,fields.get("batch"));
        require(batch.transactionId().value().equals(begin.transaction()),
            "aggregate transaction ID mismatch");
        var digest=DurableFiles.digest();
        digest.update(begin.encoded());
        long size=begin.encoded().length;
        List<DurableRecord> rows=new ArrayList<>();
        for(long n=0;n<count;n++) {
            var frame=WalFrame.read(file,frameMax);
            require(frame!=null&&frame.type()==WalFrame.Type.RECORD&&
                frame.transaction().equals(begin.transaction())&&
                frame.sequence()==beginSeq+1+n,"aggregate RECORD identity");
            size=Math.addExact(size,frame.encoded().length);
            require(size<=txMax,"aggregate transaction bytes");
            try {rows.add(CODEC.decode(frame.payload()));}
            catch(RuntimeException bad){throw new IOException("aggregate record codec",bad);}
            digest.update(frame.encoded());
        }
        var commit=WalFrame.read(file,frameMax);
        require(commit!=null&&commit.type()==WalFrame.Type.COMMIT&&
            commit.transaction().equals(begin.transaction())&&
            commit.sequence()==commitSeq&&
            commit.sequence()==beginSeq+count+1,"aggregate COMMIT identity");
        size=Math.addExact(size,commit.encoded().length);
        require(size<=txMax,"aggregate transaction complete size");
        var proof=fields(commit.payload());
        keys(proof,"count","ordered_hash");
        require(number(proof,"count")==count&&
            string(proof,"ordered_hash").equals(
                HexFormat.of().formatHex(digest.digest())),
            "aggregate commit digest/count");
        return new Transaction(List.copyOf(rows),batch.transactionHash(),size);
    }
    private static void remember(Map<AggregateKey,Ref> index,
                                 Map<AggregateKey,Tick> worldTicks,
                                 AggregateState state,ArchivedWalIndex.Entry tx,
                                 int ordinal,int uniqueOrdinal,Fingerprint hash)throws IOException {
        if(state==null)return;
        var key=state.key();
        var previous=index.get(key);
        long revision=state.header().revision().value();
        require(revision==(previous==null?1:Math.incrementExact(previous.revision())) &&
            state.header().lastSequence().value()<=tx.commitSequence(),
            "historical aggregate revision/sequence");
        if(state instanceof WorldAggregate world) {
            var prior=worldTicks.get(key);
            if(prior!=null)
                require(world.value().simTick().compareTo(prior)>=0,
                    "historical world simulation clock regression");
            worldTicks.put(key,world.value().simTick());
        }
        if(previous==null&&index.size()>=MAX_KEYS)
            throw new ArchivedWalIndex.CapacityExceeded("aggregate key capacity");
        index.put(key,new Ref(key,tx.byteOffset(),tx.beginSequence(),
            tx.commitSequence(),ordinal,uniqueOrdinal,revision,hash));
    }

    /** Build from validated authoritative checkpoint transactions, never trusting
     * the sidecar's count or list of keys.
     */
    private static ArchivedAggregatePages rebuild(CheckpointStore store,
            CheckpointStore.Manifest manifest,int frameMax,int txMax)throws IOException {
        var transactionIndex=ArchivedWalIndex.build(store,manifest,frameMax,txMax);
        var latest=new TreeMap<AggregateKey,Ref>(ORDER);
        var worldTicks=new HashMap<AggregateKey,Tick>();
        try(SeekableByteChannel file=store.open(manifest)) {
            require(file.size()==manifest.walBytes(),"aggregate checkpoint length");
            for(var tx:transactionIndex.entries) {
                var data=readTransaction(file,tx.byteOffset(),
                    tx.beginSequence(),tx.commitSequence(),frameMax,txMax);
                for(int n=0;n<data.records().size();n++) {
                    var record=data.records().get(n);
                    if(record instanceof DurablePrepare prepare) {
                        for(int i=0;i<prepare.value().uniqueness().size();i++) {
                            var derived=new DurableUnique(prepare.value().uniqueness().get(i));
                            remember(latest,worldTicks,StoreProjection.aggregate(derived),
                                tx,n,i,data.hash());
                        }
                    }
                    remember(latest,worldTicks,StoreProjection.aggregate(record),
                        tx,n,-1,data.hash());
                }
            }
        }catch(RuntimeException bad) {
            throw new IOException("cold aggregate reconstruction",bad);
        }
        return new ArchivedAggregatePages(manifest,latest);
    }

    private byte[] encode() {
        ByteBuffer output=ByteBuffer.allocate(HEADER+references.size()*ROW+FOOTER)
            .order(ByteOrder.BIG_ENDIAN);
        output.putInt(MAGIC).putInt(VERSION);
        output.putLong(manifest.store().getMostSignificantBits())
              .putLong(manifest.store().getLeastSignificantBits());
        output.putLong(manifest.world().value().getMostSignificantBits())
              .putLong(manifest.world().value().getLeastSignificantBits());
        output.putLong(manifest.generation()).putLong(manifest.sequence())
              .putLong(manifest.walBytes()).put(decodeHash(manifest.hash()));
        output.putInt(references.size());
        for(var ref:references.values()) {
            output.putInt(ref.key().kind().ordinal());
            output.putLong(ref.key().id().getMostSignificantBits())
                  .putLong(ref.key().id().getLeastSignificantBits());
            output.putLong(ref.offset()).putLong(ref.beginSequence())
                  .putLong(ref.commitSequence()).putInt(ref.recordOrdinal())
                  .putInt(ref.uniqueOrdinal()).putLong(ref.revision())
                  .put(decodeHash(ref.hash().value()));
        }
        int count=output.position();
        output.put(DurableFiles.digest().digest(Arrays.copyOf(output.array(),count)));
        return output.array();
    }

    private static byte[] previouslyPersisted(Path root,
            CheckpointStore.Manifest manifest)throws IOException {
        var file=sidecar(root,manifest);
        if(!Files.exists(file))return null;
        require(!Files.isSymbolicLink(file),"aggregate sidecar symlink");
        long length=Files.size(file);
        require(length>=HEADER+FOOTER&&length<=MAX_INDEX_BYTES,
            "aggregate sidecar bounds");
        var data=Files.readAllBytes(file);
        require(Arrays.equals(
            DurableFiles.digest().digest(Arrays.copyOf(data,data.length-FOOTER)),
            Arrays.copyOfRange(data,data.length-FOOTER,data.length)),
            "aggregate sidecar SHA256");
        return data;
    }

    /** Full WAL reconstruction once per immutable checkpoint in each process.
     * No hash-only sidecar may supply an absence proof.
     */
    static ArchivedAggregatePages verified(Path root,CheckpointStore checkpoints,
            CheckpointStore.Manifest manifest,int frameMax,int txMax)throws IOException {
        if(manifest.walBytes()>512L*1024*1024)
            throw new ArchivedWalIndex.CapacityExceeded("aggregate archive over 512MiB");
        checkpoints.verify(manifest);
        var current=rebuild(checkpoints,manifest,frameMax,txMax);
        var saved=previouslyPersisted(root,manifest);
        if(saved==null)DurableFiles.replace(sidecar(root,manifest),current.encode());
        else require(Arrays.equals(saved,current.encode()),
            "aggregate sidecar incomplete or disagrees with authoritative WAL");
        return current;
    }
    /** Exact latest aggregate states as of this archive generation.
     * Reads selected source transactions and checks their durable COMMITs again.
     */
    StoreReadResult page(CheckpointStore checkpoints,StoreSession session,
            StoreQuery query,int frameMax,int txMax)throws IOException {
        if(!manifest.world().equals(session.worldId())||
            !query.worldId().equals(session.worldId()))
            return TypedProjectionReads.status(ReadStatus.UNAVAILABLE);
        if(query.aggregateKeys().isEmpty() ||
            query.sequence().value()>manifest.sequence() ||
            query.limit()>MAX_PAGE_KEYS)
            return TypedProjectionReads.status(ReadStatus.NOT_RESIDENT);
        var stamp=new ReadStamp(session,new Sequence(manifest.sequence()),
            ReadContracts.queryHash(query));
        long offset=0;
        if(query.pageCursor().isPresent()) {
            try {
                var cursor=ReadContracts.decodeCursor(query.pageCursor().orElseThrow());
                if(!stamp.equals(cursor.stamp()))
                    return TypedProjectionReads.status(ReadStatus.STALE);
                offset=Long.parseLong(cursor.position());
                if(offset<0||!Long.toString(offset).equals(cursor.position()))
                    return TypedProjectionReads.status(ReadStatus.STALE);
            }catch(IllegalArgumentException invalid) {
                return TypedProjectionReads.status(ReadStatus.STALE);
            }
        }
        var available=references.values().stream().filter(ref->
            query.aggregateKeys().contains(ref.key())&&
            ref.commitSequence()>=query.sequence().value()).toList();
        if(offset>available.size())return TypedProjectionReads.status(ReadStatus.STALE);
        int begin=(int)offset;
        int finish=Math.min(available.size(),begin+query.limit());
        var needed=available.subList(begin,finish);
        var transactionCache=new HashMap<Long,Transaction>();
        var results=new ArrayList<RestoredAggregate>(needed.size());
        long bytes=0;
        try(SeekableByteChannel data=checkpoints.open(manifest)) {
            require(data.size()==manifest.walBytes(),"cold aggregate source length");
            for(var ref:needed) {
                var transaction=transactionCache.get(ref.offset());
                if(transaction==null) {
                    transaction=readTransaction(data,ref.offset(),
                        ref.beginSequence(),ref.commitSequence(),frameMax,txMax);
                    bytes=Math.addExact(bytes,transaction.bytes());
                    if(bytes>MAX_PAGE_BYTES)
                        return TypedProjectionReads.status(ReadStatus.NOT_RESIDENT);
                    transactionCache.put(ref.offset(),transaction);
                }
                require(transaction.hash().equals(ref.hash())&&
                    ref.recordOrdinal()<transaction.records().size(),
                    "aggregate pointer/hash differs from durable source");
                var record=transaction.records().get(ref.recordOrdinal());
                if(ref.uniqueOrdinal()>=0) {
                    require(record instanceof DurablePrepare,
                        "synthetic unique has no PREPARE source");
                    var uniques=((DurablePrepare)record).value().uniqueness();
                    require(ref.uniqueOrdinal()<uniques.size(),
                        "synthetic unique position changed");
                    record=new DurableUnique(uniques.get(ref.uniqueOrdinal()));
                }
                var state=StoreProjection.aggregate(record);
                require(state!=null&&state.key().equals(ref.key())&&
                    state.header().revision().value()==ref.revision(),
                    "aggregate key or revision changed");
                results.add(new RestoredAggregate(state,
                    new Sequence(ref.commitSequence()),transaction.hash()));
            }
        }catch(RuntimeException invalid) {
            throw new IOException("historical aggregate page invalid",invalid);
        }
        boolean more=finish<available.size();
        var view=new StoreView(List.of(),results,stamp.snapshotSequence(),more,
            more?Optional.of(ReadContracts.encodeCursor(
                new StoreReadCursor(stamp,Integer.toString(finish)))):Optional.empty());
        var response=new StoreReadResult(ReadStatus.AVAILABLE,Optional.of(view),
            Optional.of(stamp),List.of());
        ReadContracts.validate(response,query,session);
        return response;
    }
}
