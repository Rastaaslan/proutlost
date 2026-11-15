package fr.proutlost.director.persistence;

import fr.proutlost.director.api.model.*;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import static java.nio.file.StandardOpenOption.*;
import static fr.proutlost.director.persistence.StoreJson.*;

/** Immutable, content-addressed checkpoint parts shared by retained generations.
 * Only new WAL ranges are copied. The small snapshot catalog has a complete SHA-256 footer.
 */
final class SnapshotParts {
    record Part(String hash,long bytes) {
        Part { if(!hash.matches("[a-f0-9]{64}")||bytes<1)throw new IllegalArgumentException("snapshot part"); }
    }
    private final Path directory;
    SnapshotParts(Path checkpoints)throws IOException {
        directory=checkpoints.resolve("objects");
        if(Files.isSymbolicLink(directory))throw new IOException("symlink snapshot object directory");
        Files.createDirectories(directory);DurableFiles.directory(checkpoints);
    }
    Path path(Part part){return directory.resolve(part.hash()+".part");}
    List<Part> read(Path catalog,long expectedBytes)throws IOException {
        if(Files.size(catalog)>1_048_576+36)throw new IOException("snapshot catalog capacity");
        byte[] bytes=Files.readAllBytes(catalog);
        if(bytes.length<36)throw new IOException("snapshot footer missing");
        int n=bytes.length-36;ByteBuffer footer=ByteBuffer.wrap(bytes,n,36);byte[] expected=new byte[32];
        if(footer.getInt()!=0x57444332)throw new IOException("snapshot format/footer");footer.get(expected);
        byte[] content=Arrays.copyOf(bytes,n);
        if(!Arrays.equals(DurableFiles.digest().digest(content),expected))throw new IOException("snapshot catalog checksum");
        try {
            var fields=fields(content);keys(fields,"format","parts","wal_bytes");
            if(!string(fields,"format").equals("WDS2")||number(fields,"wal_bytes")!=expectedBytes)throw new IOException("snapshot format/size");
            List<Part> parts=new ArrayList<>();long total=0;
            for(var item:((JsonValue.Array)fields.get("parts")).values()) {
                var f=((JsonValue.ObjectValue)item).values();keys(f,"hash","bytes");
                var part=new Part(string(f,"hash"),number(f,"bytes"));parts.add(part);total=Math.addExact(total,part.bytes());
            }
            if(total!=expectedBytes)throw new IOException("snapshot part sum");return List.copyOf(parts);
        }catch(IllegalArgumentException|ClassCastException e){throw new IOException("snapshot catalog",e);}
    }
    void verify(List<Part> parts)throws IOException {
        for(var part:parts)if(Files.isSymbolicLink(path(part))||Files.size(path(part))!=part.bytes()||!DurableFiles.hash(path(part)).equals(part.hash()))throw new IOException("snapshot part checksum/length");
    }
    Part archive(Path source,long bytes)throws IOException {
        Path temp=directory.resolve("part.tmp-"+UUID.randomUUID());var digest=DurableFiles.digest();
        try {
            try(FileChannel in=FileChannel.open(source,READ);FileChannel out=FileChannel.open(temp,CREATE_NEW,WRITE)) {
                ByteBuffer buffer=ByteBuffer.allocate(65536);long remaining=bytes;
                while(remaining>0){buffer.clear().limit((int)Math.min(buffer.capacity(),remaining));WalFrame.readFully(in,buffer);digest.update(buffer.array(),0,buffer.position());remaining-=buffer.position();buffer.flip();while(buffer.hasRemaining())out.write(buffer);}out.force(true);
            }
            Part part=new Part(HexFormat.of().formatHex(digest.digest()),bytes);Path target=path(part);
            if(Files.exists(target)) {
                verify(List.of(part));try(FileChannel out=FileChannel.open(target,WRITE)){out.force(true);}
            }else Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE);
            DurableFiles.directory(directory);return part;
        }finally{Files.deleteIfExists(temp);}
    }
    void write(Path catalog,List<Part> parts)throws IOException {
        long total=parts.stream().mapToLong(Part::bytes).reduce(0,Math::addExact);
        List<JsonValue> refs=parts.stream().map(p->(JsonValue)object(Map.of("hash",text(p.hash()),"bytes",text(p.bytes())))).toList();
        byte[] content=encode(Map.of("format",text("WDS2"),"wal_bytes",text(total),"parts",new JsonValue.Array(refs)));
        ByteBuffer output=ByteBuffer.allocate(content.length+36).put(content).putInt(0x57444332).put(DurableFiles.digest().digest(content));
        DurableFiles.replace(catalog,output.array());
    }
    SeekableByteChannel open(List<Part> parts)throws IOException { return new Joined(parts); }
    void collect(Set<String> live)throws IOException {
        try(var paths=Files.list(directory)) {
            for(Path path:paths.toList()) {
                String name=path.getFileName().toString();
                if(name.matches("[a-f0-9]{64}\\.part")&&!live.contains(name.substring(0,64)))Files.delete(path);
            }
        }DurableFiles.directory(directory);
    }
    private final class Joined implements SeekableByteChannel {
        private final List<Part> parts;private final long[] ends;private long position;private FileChannel file;private int index=-1;private boolean open=true;
        Joined(List<Part> parts){this.parts=parts;ends=new long[parts.size()];long n=0;for(int i=0;i<parts.size();i++){n=Math.addExact(n,parts.get(i).bytes());ends[i]=n;}}
        public long size(){return ends.length==0?0:ends[ends.length-1];}
        public long position(){return position;}
        public SeekableByteChannel position(long value){if(value<0||value>size())throw new IllegalArgumentException("snapshot position");position=value;return this;}
        public int read(ByteBuffer out)throws IOException {
            if(!open)throw new ClosedChannelException();if(!out.hasRemaining())return 0;if(position==size())return -1;
            int low=0,high=ends.length;while(low<high){int mid=(low+high)>>>1;if(ends[mid]<=position)low=mid+1;else high=mid;}
            if(low!=index){if(file!=null)file.close();index=low;file=FileChannel.open(path(parts.get(index)),READ);}
            file.position(position-(index==0?0:ends[index-1]));int limit=out.limit();out.limit(out.position()+(int)Math.min(out.remaining(),ends[index]-position));
            int n;try{n=file.read(out);}finally{out.limit(limit);}if(n<0)throw new EOFException("checkpoint part truncated");position+=n;return n;
        }
        public int write(ByteBuffer in){throw new NonWritableChannelException();}
        public SeekableByteChannel truncate(long size){throw new NonWritableChannelException();}
        public boolean isOpen(){return open;}
        public void close()throws IOException{open=false;if(file!=null)file.close();}
    }
}
