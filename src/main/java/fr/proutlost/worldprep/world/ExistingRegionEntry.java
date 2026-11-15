package fr.proutlost.worldprep.world;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Read-only Anvil envelope preflight, NOT proof of FULL status or valid NBT.
 * Native EMPTY deserialization must still verify persisted FULL before promotion.
 */
public final class ExistingRegionEntry {
    public static void requirePresent(Path regionDirectory,int x,int z) throws IOException {
        Path file=regionDirectory.resolve("r."+Math.floorDiv(x,32)+"."+Math.floorDiv(z,32)+".mca").toAbsolutePath().normalize();
        for(Path p=file;p!=null;p=p.getParent())
            if(Files.isSymbolicLink(p))throw new IOException("Symlink region path refused");
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("Existing region required: "+file.getFileName());
        try(var channel=FileChannel.open(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            if(channel.size()<8192)throw new IOException("Truncated region header");
            int slot=Math.floorMod(x,32)+32*Math.floorMod(z,32);
            var header=ByteBuffer.allocate(4);
            read(channel,header,slot*4L);
            int location=header.getInt();int sector=location>>>8,count=location&255;
            long size=channel.size();
            // Native RegionFile.write writes the payload, then the header. flush() forces
            // those bytes but only close() pads the last sector. Padding is not chunk data.
            long sectorsInFile=(size+4095)/4096;
            if(sector<2||count==0||sector+(long)count>sectorsInFile||sector*4096L+5>size)
                throw new IOException("Missing or truncated existing chunk entry: "+x+","+z);
            var envelope=ByteBuffer.allocate(5);read(channel,envelope,sector*4096L);
            int length=envelope.getInt(),compression=Byte.toUnsignedInt(envelope.get());
            // External .mcc chunks are deliberately unsupported until independently validated.
            if(length<=1||length>count*4096-4||compression<1||compression>4)
                throw new IOException("Unsupported/corrupt chunk envelope");
            if(sector*4096L+4+length>size)
                throw new IOException("Truncated chunk payload: "+x+","+z);
            // Do not accept an entry that was relocated while we inspected its envelope.
            header.clear();read(channel,header,slot*4L);
            if(header.getInt()!=location)throw new IOException("Chunk entry changed during preflight: "+x+","+z);
        }
    }
    private static void read(FileChannel channel,ByteBuffer target,long offset)throws IOException{
        while(target.hasRemaining()){
            int n=channel.read(target,offset+target.position());
            if(n<=0)throw new IOException("Truncated region entry");
        }
        target.flip();
    }
    private ExistingRegionEntry(){}
}
