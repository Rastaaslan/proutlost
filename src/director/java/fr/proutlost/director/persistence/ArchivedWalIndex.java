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
 * Experimental durable seek directory for immutable WDS2 checkpoint generations.
 *
 * Read or build ONLY from the exclusive J04 writer, with checkpoint SHA-256
 * verification before use. Entry offsets are transaction BEGIN boundaries; a
 * read independently checks every transaction that supplies returned records.
 * The sidecar index never establishes authority for a mutation or rollback.
 */
final class ArchivedWalIndex {
    private static final int MAGIC=0x57444932; // WDI2
    private static final int VERSION=1;
    private static final int HEADER=4+4+16+16+8+8+8+32+4;
    private static final int ROW_BYTES=40;
    private static final int DIGEST_BYTES=32;
    private static final int MAX_INDEX_BYTES=4*1024*1024;
    private static final long MAX_ARCHIVE_BYTES=512L*1024*1024;
    static final class CapacityExceeded extends IOException {
        CapacityExceeded(String message){super(message);}
    }
    private static final int MAX_ROWS=(MAX_INDEX_BYTES-HEADER-DIGEST_BYTES)/ROW_BYTES;
    private static final ModelCodec<DurableRecord> RECORD=new ModelCodec<>(DurableRecord.class);

    record Entry(long byteOffset,long beginSequence,long commitSequence,
                 long firstOrdinal,long endOrdinal) {
        Entry {
            if(byteOffset<0||beginSequence<=0||commitSequence<=beginSequence||
               firstOrdinal<0||endOrdinal<=firstOrdinal)
                throw new IllegalArgumentException("invalid archived transaction entry");
        }
    }
    final List<Entry> entries;
    final long totalRows;
    final CheckpointStore.Manifest manifest;
    private ArchivedWalIndex(CheckpointStore.Manifest manifest,List<Entry> entries) {
        this.manifest=manifest;
        this.entries=List.copyOf(entries);
        this.totalRows=entries.isEmpty()?0:entries.getLast().endOrdinal();
    }
    private static void require(boolean value,String message)throws IOException {
        if(!value)throw new IOException(message);
    }
    private static byte[] bytes(String sha) {
        return HexFormat.of().parseHex(sha);
    }
    private static Path path(Path root,CheckpointStore.Manifest manifest)throws IOException {
        var directory=root.resolve("checkpoints/cold-index");
        if(Files.isSymbolicLink(directory))
            throw new IOException("cold index directory symlink");
        Files.createDirectories(directory);
        return directory.resolve(manifest.generation()+".wdidx");
    }

    /** Build an index from a FULLY authenticated checkpoint. Never use partial WAL. */
    static ArchivedWalIndex build(CheckpointStore checkpoints,CheckpointStore.Manifest manifest,
                                  int frameCap,int transactionCap)throws IOException {
        checkpoints.verify(manifest);
        var entries=new ArrayList<Entry>();
        try(SeekableByteChannel archive=checkpoints.open(manifest)) {
            require(archive.size()==manifest.walBytes(),"cold index archived size");
            long seq=0,txBytes=0,declared=0,first=0,prefix=0;
            UUID pending=null;
            String kind=null;
            var sha=DurableFiles.digest();
            List<DurableRecord> buffered=new ArrayList<>();
            while(archive.position()<archive.size()) {
                long position=archive.position();
                var frame=WalFrame.read(archive,frameCap);
                require(frame!=null&&frame.sequence()==Math.addExact(seq,1),
                    "cold index frame sequence");
                seq=frame.sequence();
                switch(frame.type()) {
                    case BEGIN -> {
                        require(pending==null,"overlapping archived transaction");
                        var begin=fields(frame.payload());
                        kind=string(begin,"kind");
                        require(Set.of("SESSION","ACK","APPEND").contains(kind),
                            "unknown cold index transaction");
                        declared=number(begin,"count");
                        require(declared>=0&&declared<=transactionCap/WalFrame.OVERHEAD,
                            "cold index declared frame count");
                        first=position;
                        pending=frame.transaction();
                        sha=DurableFiles.digest();
                        sha.update(frame.encoded());
                        txBytes=frame.encoded().length;
                        buffered.clear();
                    }
                    case RECORD -> {
                        require(pending!=null&&pending.equals(frame.transaction())&&
                            "APPEND".equals(kind)&&buffered.size()<declared,
                            "invalid cold index RECORD");
                        txBytes=Math.addExact(txBytes,frame.encoded().length);
                        require(txBytes<=transactionCap,"cold index transaction cap");
                        try {buffered.add(RECORD.decode(frame.payload()));}
                        catch(RuntimeException invalid) {
                            throw new IOException("cold index record codec",invalid);
                        }
                        sha.update(frame.encoded());
                    }
                    case COMMIT -> {
                        require(pending!=null&&pending.equals(frame.transaction()),
                            "invalid cold index COMMIT");
                        txBytes=Math.addExact(txBytes,frame.encoded().length);
                        require(txBytes<=transactionCap,"cold index total transaction cap");
                        var fields=fields(frame.payload());
                        keys(fields,"count","ordered_hash");
                        require(number(fields,"count")==declared&&
                            buffered.size()==declared&&
                            string(fields,"ordered_hash").equals(
                                HexFormat.of().formatHex(sha.digest())),
                            "cold index durable COMMIT checksum");
                        if("APPEND".equals(kind)) {
                            long count=0;
                            for(var record:buffered) {
                                if(record instanceof DurablePrepare prepare)
                                    count=Math.addExact(count,prepare.value().uniqueness().size());
                                count=Math.addExact(count,1);
                            }
                            if(count>0) {
                                if(entries.size()>=MAX_ROWS)
                                    throw new CapacityExceeded("cold index entry capacity");
                                entries.add(new Entry(first,seq-(declared+1),
                                    seq,prefix,Math.addExact(prefix,count)));
                                prefix=Math.addExact(prefix,count);
                            }
                        }
                        pending=null;kind=null;txBytes=0;buffered.clear();
                    }
                }
            }
            require(pending==null&&seq==manifest.sequence(),
                "cold index incomplete commit sequence");
        } catch(RuntimeException invalid) {
            throw new IOException("invalid cold index WAL",invalid);
        }
        return new ArchivedWalIndex(manifest,entries);
    }

    void write(Path storeRoot)throws IOException {
        Path file=path(storeRoot,manifest);
        ByteBuffer b=ByteBuffer.allocate(HEADER+entries.size()*ROW_BYTES+DIGEST_BYTES)
            .order(ByteOrder.BIG_ENDIAN);
        b.putInt(MAGIC).putInt(VERSION);
        b.putLong(manifest.store().getMostSignificantBits())
            .putLong(manifest.store().getLeastSignificantBits());
        b.putLong(manifest.world().value().getMostSignificantBits())
            .putLong(manifest.world().value().getLeastSignificantBits());
        b.putLong(manifest.generation()).putLong(manifest.sequence())
            .putLong(manifest.walBytes()).put(bytes(manifest.hash()));
        b.putInt(entries.size());
        for(var entry:entries) {
            b.putLong(entry.byteOffset()).putLong(entry.beginSequence())
                .putLong(entry.commitSequence()).putLong(entry.firstOrdinal())
                .putLong(entry.endOrdinal());
        }
        int used=b.position();
        b.put(DurableFiles.digest().digest(Arrays.copyOf(b.array(),used)));
        DurableFiles.replace(file,b.array());
    }

    /** Read a previously generated index with its own CRC-resistant SHA-256 footer.
     * Source checkpoint is independently verified by the caller, not this file.
     */
    static ArchivedWalIndex load(Path storeRoot,CheckpointStore.Manifest manifest)
            throws IOException {
        Path file=path(storeRoot,manifest);
        if(!Files.exists(file))return null;
        require(!Files.isSymbolicLink(file),"cold index symlink");
        long n=Files.size(file);
        require(n>=HEADER+DIGEST_BYTES&&n<=MAX_INDEX_BYTES,
            "cold index file size");
        byte[] raw=Files.readAllBytes(file);
        int cutoff=raw.length-DIGEST_BYTES;
        require(Arrays.equals(DurableFiles.digest().digest(Arrays.copyOf(raw,cutoff)),
            Arrays.copyOfRange(raw,cutoff,raw.length)),
            "cold index checksum");
        try {
            ByteBuffer b=ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
            require(b.getInt()==MAGIC&&b.getInt()==VERSION,"cold index header version");
            UUID store=new UUID(b.getLong(),b.getLong());
            UUID world=new UUID(b.getLong(),b.getLong());
            long generation=b.getLong(),seq=b.getLong(),length=b.getLong();
            byte[] catalog=new byte[32];b.get(catalog);
            require(store.equals(manifest.store())&&
                world.equals(manifest.world().value())&&
                generation==manifest.generation()&&seq==manifest.sequence()&&
                length==manifest.walBytes()&&Arrays.equals(catalog,bytes(manifest.hash())),
                "cold index manifest identity/generation/hash");
            int count=b.getInt();
            require(count>=0&&count<=MAX_ROWS&&
                raw.length==HEADER+count*ROW_BYTES+DIGEST_BYTES,
                "cold index entry count/length");
            var entries=new ArrayList<Entry>(count);
            long previousOffset=-1,previousEnd=0,previousSeq=0;
            for(int i=0;i<count;i++) {
                Entry e=new Entry(b.getLong(),b.getLong(),b.getLong(),
                    b.getLong(),b.getLong());
                require(e.firstOrdinal()==previousEnd&&
                    e.byteOffset()>previousOffset&&
                    e.beginSequence()>previousSeq&&
                    e.commitSequence()<=manifest.sequence()&&
                    e.byteOffset()<manifest.walBytes(),
                    "cold index monotonicity/cut");
                entries.add(e);
                previousEnd=e.endOrdinal();
                previousOffset=e.byteOffset();
                previousSeq=e.commitSequence();
            }
            require(b.position()==cutoff,"cold index trailing bytes");
            return new ArchivedWalIndex(manifest,entries);
        } catch(RuntimeException invalid) {
            throw new IOException("malformed cold index",invalid);
        }
    }

    static ArchivedWalIndex verified(Path root,CheckpointStore checkpoints,
                                     CheckpointStore.Manifest manifest,
                                     int frameCap,int transactionCap)throws IOException {
        if(manifest.walBytes()>MAX_ARCHIVE_BYTES)
            throw new CapacityExceeded("cold archive exceeds indexed read budget");
        checkpoints.verify(manifest); // Check every authoritative content-addressed part.
        // A sidecar checksum does NOT prove completeness. Independently rebuild
        // from the verified WAL and compare every recorded transaction reference
        // before trusting the sidecar to prove absence.
        var generated=build(checkpoints,manifest,frameCap,transactionCap);
        var saved=load(root,manifest);
        if(saved!=null) {
            require(saved.entries.equals(generated.entries),
                "persisted index is incomplete or disagrees with the WAL");
            return generated;
        }
        generated.write(root);
        return generated;
    }

    int firstCommitAtLeast(long sequence) {
        int lo=0,hi=entries.size();
        while(lo<hi) {
            int mid=(lo+hi)>>>1;
            if(entries.get(mid).commitSequence()<sequence)lo=mid+1;
            else hi=mid;
        }
        return lo;
    }
    int containingOrdinal(long absolute) {
        int lo=0,hi=entries.size();
        while(lo<hi) {
            int mid=(lo+hi)>>>1;
            if(entries.get(mid).endOrdinal()<=absolute)lo=mid+1;
            else hi=mid;
        }
        return lo;
    }
}
