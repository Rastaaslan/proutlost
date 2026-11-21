package fr.proutlost.director.persistence;

import fr.proutlost.director.api.model.*;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import static java.nio.file.StandardOpenOption.*;
import static fr.proutlost.director.persistence.StoreJson.*;

/** Incremental shared checkpoint catalogs, double manifests and generation-aware retention. */
final class CheckpointStore {
    record Manifest(UUID store, WorldId world, long generation, int schema, long sequence,
                    String checkpoint, String hash, long walBytes) {}
    private final Path root;
    private final JournalStore.IoProbe probe;
    private final SnapshotParts parts;
    CheckpointStore(Path root, JournalStore.IoProbe probe) throws IOException { this.root = root; this.probe = probe;parts=new SnapshotParts(root.resolve("checkpoints")); }
    Manifest initialize(WorldId world) throws IOException {
        var m = new Manifest(UUID.randomUUID(), world, 1, 1, 0, "", DurableFiles.hash(new byte[0]), 0);
        write(m, "A"); write(m, "B"); return m;
    }
    List<Manifest> candidates() {
        List<Manifest> result = new ArrayList<>();
        for (String slot : List.of("A", "B")) {
            try { result.add(read(root.resolve("manifest-" + slot + ".json"))); }
            catch (IOException | IllegalArgumentException | ClassCastException ignored) { /* other generation is independently verified */ }
        }
        result.sort(Comparator.comparingLong(Manifest::generation).reversed()); return result;
    }
    private Manifest read(Path file) throws IOException {
        if (Files.size(file) > 8192) throw new IOException("manifest size");
        var f = new TreeMap<>(fields(Files.readAllBytes(file)));
        keys(f, "store_id", "world_id", "generation", "schema_version", "last_sequence", "checkpoint", "checkpoint_hash", "wal_bytes", "checksum");
        String checksum = string(f, "checksum"); f.remove("checksum");
        if (!DurableFiles.hash(encode(f)).equals(checksum)) throw new IOException("manifest checksum");
        String checkpoint = string(f, "checkpoint");
        if (!checkpoint.isEmpty() && !checkpoint.matches("[1-9][0-9]*\\.snapshot")) throw new IOException("checkpoint path");
        var m = new Manifest(UUID.fromString(string(f,"store_id")), new WorldId(UUID.fromString(string(f,"world_id"))),
            number(f,"generation"), Math.toIntExact(number(f,"schema_version")), number(f,"last_sequence"), checkpoint,
            string(f,"checkpoint_hash"), number(f,"wal_bytes"));
        if (m.generation() < 1 || m.schema() < 1 || m.sequence() < 0 || m.walBytes() < 0) throw new IOException("manifest bounds");
        return m;
    }
    private List<SnapshotParts.Part> catalog(Manifest m)throws IOException {
        if(m.checkpoint().isEmpty()){
            if(m.sequence()!=0||m.walBytes()!=0)throw new IOException("empty checkpoint sequence");return List.of();
        }
        if(!DurableFiles.hash(path(m)).equals(m.hash()))throw new IOException("checkpoint catalog hash");
        return parts.read(path(m),m.walBytes());
    }
    void verify(Manifest m)throws IOException { parts.verify(catalog(m)); }
    SeekableByteChannel open(Manifest m)throws IOException { return parts.open(catalog(m)); }
    Path path(Manifest m) { return root.resolve("checkpoints").resolve(m.checkpoint()); }
    Manifest checkpoint(Manifest previous,long seq,List<Path> segments)throws IOException {
        long generation=Math.incrementExact(previous.generation());
        List<SnapshotParts.Part> refs=new ArrayList<>(catalog(previous));long bytes=previous.walBytes();
        for(Path segment:segments) {
            long first=Long.parseLong(segment.getFileName().toString().replace(".wdwal",""));
            if(first<=previous.sequence())continue;
            long length=Files.size(segment);refs.add(parts.archive(segment,length));bytes=Math.addExact(bytes,length);
        }
        Path target=root.resolve("checkpoints").resolve(generation+".snapshot");
        parts.write(target,refs);probe.at("CHECKPOINT_FORCED");
        var next=new Manifest(previous.store(),previous.world(),generation,1,seq,target.getFileName().toString(),DurableFiles.hash(target),bytes);
        probe.at("BEFORE_MANIFEST");write(next,generation%2==0?"A":"B");return next;
    }
    /** Delete only artifacts whose authoritative copies are proven in every retained generation.
     * Current V1 keeps the exact history in shared archive parts. Logical cold-index compaction
     * requires the requested async read contract; it is not simulated by dropping old records.
     */
    void retain(Manifest current,int generations,List<Path> wal,Set<BlobRef> liveBlobs)throws IOException {
        Map<Long,Manifest> known=new TreeMap<>();Map<Long,Path> files=new HashMap<>();
        try(var entries=Files.list(root.resolve("checkpoints"))) {
            for(Path p:entries.toList())if(p.getFileName().toString().matches("[1-9][0-9]*\\.manifest")) {
                Manifest m=read(p);if(!m.store().equals(current.store()))throw new IOException("retention identity mismatch");
                if(m.generation()<=current.generation()){known.put(m.generation(),m);files.put(m.generation(),p);}
            }
        }
        Set<Long> keep=new HashSet<>();known.keySet().stream().sorted(Comparator.reverseOrder()).limit(generations).forEach(keep::add);
        for(Manifest m:candidates()){known.put(m.generation(),m);keep.add(m.generation());}
        // A migration source is a rollback pin, independent of ordinary generation retention.
        try(var entries=Files.list(root.resolve("recovery"))) {
            for(Path p:entries.toList())if(p.getFileName().toString().matches("migration-source-[1-9][0-9]*\\.json")) {
                Manifest m=read(p);known.put(m.generation(),m);keep.add(m.generation());
            }
        }
        Set<String> liveParts=new HashSet<>();Manifest oldest=null;List<SnapshotParts.Part> oldestParts=List.of();
        for(long generation:keep) {
            Manifest m=known.get(generation);var refs=catalog(m);for(var ref:refs)liveParts.add(ref.hash());
            if(oldest==null||m.sequence()<oldest.sequence()){oldest=m;oldestParts=refs;}
        }
        Map<String,SnapshotParts.Part> covered=new HashMap<>();for(var ref:oldestParts)covered.put(ref.hash(),ref);
        for(Path segment:List.copyOf(wal)) {
            if(!Files.exists(segment))continue;
            String hash=DurableFiles.hash(segment);var ref=covered.get(hash);
            if(ref==null||Files.size(segment)!=ref.bytes())continue;
            parts.verify(List.of(ref));probe.at("BEFORE_OLD_WAL_CLEANUP");
            Files.delete(segment);probe.at("OLD_WAL_CLEANUP");DurableFiles.directory(segment.getParent());wal.remove(segment);
        }
        for(var e:files.entrySet())if(!keep.contains(e.getKey())) {
            Manifest m=known.get(e.getKey());Files.delete(e.getValue());
            if(!m.checkpoint().isEmpty())Files.deleteIfExists(path(m));
        }
        DurableFiles.directory(root.resolve("checkpoints"));parts.collect(liveParts);
        Set<String> hashes=new HashSet<>();for(var blob:liveBlobs)hashes.add(blob.hash().value());
        // All retained V1 history is a prefix of current history: its blob references are included.
        try(var entries=Files.list(root.resolve("blobs"))) {
            for(Path p:entries.toList()) {
                String name=p.getFileName().toString();
                if(name.matches("[a-f0-9]{64}\\.blob")&&!hashes.contains(name.substring(0,64)))Files.delete(p);
            }
        }DurableFiles.directory(root.resolve("blobs"));
        // Sidecars are derived and never authority. Prune only after the
        // retained manifest set and all recovery/migration pins are known,
        // and after authoritative checkpoint cleanup is durable.
        ColdIndexRetention.prune(root.resolve("checkpoints"),current.generation(),keep,probe);
    }
    Manifest migrate(Manifest source, SnapshotMigration migration, int frameLimit) throws IOException {
        if(migration.sourceSchema()!=source.schema() || migration.targetSchema()!=source.schema()+1)
            throw new IllegalArgumentException("migration must be adjacent and match source");
        verify(source);
        if(source.checkpoint().isEmpty())throw new IOException("migration requires durable source checkpoint");
        long generation=Math.incrementExact(source.generation());
        Path target=root.resolve("checkpoints").resolve(generation+".snapshot");
        Path temp=target.resolveSibling(target.getFileName()+".tmp-"+UUID.randomUUID());
        var snapshotHash=DurableFiles.digest();var transactionHash=DurableFiles.digest();long bytes=0;
        // Keep exact source manifest alongside source snapshot + retained WAL/blobs for explicit rollback.
        Path sourceManifest=root.resolve("recovery").resolve("migration-source-"+source.generation()+".json");
        DurableFiles.replace(sourceManifest,manifestBytes(source));
        try {
            try(SeekableByteChannel in=open(source);FileChannel out=FileChannel.open(temp,CREATE_NEW,WRITE)) {
                while(in.position()<source.walBytes()) {
                    var frame=WalFrame.read(in,frameLimit);
                    if(frame==null||in.position()>source.walBytes())throw new IOException("migration source frame");
                    byte[] payload=frame.payload();
                    if(frame.type()==WalFrame.Type.BEGIN)transactionHash=DurableFiles.digest();
                    if(frame.type()==WalFrame.Type.RECORD) {
                        var original=fr.proutlost.director.api.codec.CanonicalJsonV1.decode(payload);
                        var transformed=migration.migrateRecord(original);
                        migration.validateTarget(transformed);
                        // Detect accidental non-determinism before installing any target generation.
                        var again=migration.migrateRecord(original);
                        if(!transformed.equals(again))throw new IOException("non-deterministic migration");
                        payload=fr.proutlost.director.api.codec.CanonicalJsonV1.encode(transformed);
                    } else if(frame.type()==WalFrame.Type.COMMIT) {
                        var commit=new TreeMap<>(fields(payload));
                        commit.put("ordered_hash",text(HexFormat.of().formatHex(transactionHash.digest())));payload=encode(commit);
                    }
                    byte[] encoded=WalFrame.encode(frame.type(),frame.sequence(),frame.transaction(),payload,frameLimit);
                    if(frame.type()!=WalFrame.Type.COMMIT)transactionHash.update(encoded);
                    snapshotHash.update(encoded);WalFrame.writeFully(out,encoded);bytes=Math.addExact(bytes,encoded.length);
                    probe.at("MIGRATION_RECORD");
                }
                WalFrame.writeFully(out,ByteBuffer.allocate(36).putInt(0x57444331).put(snapshotHash.digest()).array());
                out.force(true);probe.at("MIGRATION_FORCED");
            }
            var migrated=parts.archive(temp,bytes);parts.write(target,List.of(migrated));
            var next=new Manifest(source.store(),source.world(),generation,migration.targetSchema(),source.sequence(),target.getFileName().toString(),DurableFiles.hash(target),bytes);
            verify(next);probe.at("MIGRATION_BEFORE_MANIFEST");write(next,generation%2==0?"A":"B");probe.at("MIGRATION_MANIFEST_DURABLE");return next;
        }finally{Files.deleteIfExists(temp);}
    }
    private void write(Manifest m, String slot) throws IOException {
        byte[] bytes=manifestBytes(m);
        DurableFiles.replace(root.resolve("checkpoints").resolve(m.generation()+".manifest"),bytes);
        DurableFiles.replace(root.resolve("manifest-"+slot+".json"),bytes);
    }
    private byte[] manifestBytes(Manifest m) {
        Map<String,JsonValue> f = new TreeMap<>();
        f.put("store_id",text(m.store())); f.put("world_id",text(m.world().value())); f.put("generation",text(m.generation()));
        f.put("schema_version",text(m.schema())); f.put("last_sequence",text(m.sequence())); f.put("checkpoint",text(m.checkpoint()));
        f.put("checkpoint_hash",text(m.hash())); f.put("wal_bytes",text(m.walBytes())); f.put("checksum",text(DurableFiles.hash(encode(f))));
        return encode(f);
    }
}
