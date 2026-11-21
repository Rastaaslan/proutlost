package fr.proutlost.worldprep.physical;

import fr.proutlost.worldprep.world.ExistingRegionEntry;
import net.minecraft.nbt.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.zip.*;

/** Read actual Anvil bytes, never ChunkStorage's pending-write cache. No RegionFile writer opened. */
public final class ReadOnlyChunkNbt {
    public static CompoundTag read(Path region,int x,int z)throws IOException {
        var tag=readPayload(region,x,z);
        if(!tag.getString("Status").equals("minecraft:full")||tag.getInt("xPos")!=x||tag.getInt("zPos")!=z)
            throw new IOException("Absent/incomplete/wrong persisted chunk");
        return tag;
    }
    public static CompoundTag readBounded(Path region,int x,int z)throws IOException {
        var tag=readPayload(region,x,z,128*1024);
        if(!tag.getString("Status").equals("minecraft:full")||tag.getInt("xPos")!=x||tag.getInt("zPos")!=z)
            throw new IOException("Absent/incomplete/wrong persisted chunk");
        return tag;
    }
    public static CompoundTag entities(Path region,int x,int z)throws IOException {
        var tag=readPayload(region,x,z);int[] position=tag.getIntArray("Position");
        if(position.length!=2||position[0]!=x||position[1]!=z)throw new IOException("Wrong entity chunk identity");
        return tag;
    }
    public static CompoundTag entitiesBounded(Path region,int x,int z)throws IOException {
        var tag=readPayload(region,x,z,128*1024);
        int[] position=tag.getIntArray("Position");
        if(position.length!=2||position[0]!=x||position[1]!=z)throw new IOException("Wrong entity chunk identity");
        return tag;
    }
    private static CompoundTag readPayload(Path region,int x,int z)throws IOException {return readPayload(region,x,z,1024*1024);}
    private static CompoundTag readPayload(Path region,int x,int z,int payloadLimit)throws IOException {
        ExistingRegionEntry.requirePresent(region,x,z);
        var file=region.resolve("r."+Math.floorDiv(x,32)+"."+Math.floorDiv(z,32)+".mca");
        try(var channel=FileChannel.open(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
            int slot=Math.floorMod(x,32)+32*Math.floorMod(z,32);
            var header=ByteBuffer.allocate(4);read(channel,header,slot*4L);int location=header.getInt();
            var info=ByteBuffer.allocate(5);read(channel,info,(location>>>8)*4096L);
            int length=info.getInt(),compression=Byte.toUnsignedInt(info.get());
            if(length<2||length>payloadLimit||length>(location&255)*4096-4)throw new IOException("Unsupported bounded chunk envelope");
            var payload=ByteBuffer.allocate(length-1);read(channel,payload,(location>>>8)*4096L+5);
            InputStream bytes=new ByteArrayInputStream(payload.array());
            InputStream decoded=switch(compression) {case 1->new GZIPInputStream(bytes);case 2->new InflaterInputStream(bytes);case 3->bytes;default->throw new IOException("Compression unsupported for read-only audit");};
            CompoundTag tag;
            try(var input=new DataInputStream(decoded)){tag=NbtIo.read(input,NbtAccounter.create(16L*1024*1024));}
            header.clear();read(channel,header,slot*4L);if(header.getInt()!=location)throw new IOException("Region relocated during audit");
            var again=ByteBuffer.allocate(length-1);read(channel,again,(location>>>8)*4096L+5);
            if(!java.util.Arrays.equals(payload.array(),again.array()))throw new IOException("Chunk changed during disk audit");
            return tag;
        }
    }
    private static void read(FileChannel channel,ByteBuffer buffer,long offset)throws IOException {
        while(buffer.hasRemaining())if(channel.read(buffer,offset+buffer.position())<=0)throw new EOFException();buffer.flip();
    }
    private ReadOnlyChunkNbt(){}
}
