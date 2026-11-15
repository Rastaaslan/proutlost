package fr.proutlost.worldprep;

import fr.proutlost.worldprep.world.ExistingRegionEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ExistingRegionEntryTest {
    @TempDir Path root;
    private Path fixture(int x,int z,int location,int length,int compression)throws Exception{
        var bytes=ByteBuffer.allocate(12288);
        bytes.putInt((Math.floorMod(x,32)+32*Math.floorMod(z,32))*4,location);
        bytes.putInt(8192,length);bytes.put(8196,(byte)compression);
        var path=root.resolve("r."+Math.floorDiv(x,32)+"."+Math.floorDiv(z,32)+".mca");
        Files.write(path,bytes.array());return path;
    }
    @Test void negativeCoordinatesMapToExactEntryWithoutChangingBytes()throws Exception{
        var file=fixture(-1,-33,513,100,2);var before=Files.readAllBytes(file);
        ExistingRegionEntry.requirePresent(root,-1,-33);
        assertArrayEquals(before,Files.readAllBytes(file));
        assertThrows(IOException.class,()->ExistingRegionEntry.requirePresent(root,-2,-33));
    }
    @Test void absentRegionNeverCreatesFiles()throws Exception{
        assertThrows(IOException.class,()->ExistingRegionEntry.requirePresent(root,0,0));
        try(var files=Files.list(root)){assertEquals(0,files.count());}
    }
    @Test void corruptOffsetsLengthsAndExternalChunksAreRefused()throws Exception{
        for(int location:new int[]{0,1,257,768,514}){
            fixture(0,0,location,100,2);
            assertThrows(IOException.class,()->ExistingRegionEntry.requirePresent(root,0,0));
        }
        for(int length:new int[]{0,1,4093,Integer.MAX_VALUE}){
            fixture(0,0,513,length,2);
            assertThrows(IOException.class,()->ExistingRegionEntry.requirePresent(root,0,0));
        }
        fixture(0,0,513,100,130);
        assertThrows(IOException.class,()->ExistingRegionEntry.requirePresent(root,0,0));
    }
    @Test void nativeOpenRegionAfterFlushDoesNotRequireCloseTimePadding()throws Exception{
        var file=root.resolve("r.0.0.mca");
        var dimension=net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                net.minecraft.resources.ResourceLocation.parse("minecraft:overworld"));
        var info=new net.minecraft.world.level.chunk.storage.RegionStorageInfo("cb200-test",dimension,"chunk");
        try(var region=new net.minecraft.world.level.chunk.storage.RegionFile(info,file,root,true)){
            byte[] payload=new byte[6000];new java.util.Random(200).nextBytes(payload);
            try(var output=region.getChunkDataOutputStream(new net.minecraft.world.level.ChunkPos(0,0))){
                output.write(payload);
            }
            region.flush();
            long unpaddedSize=Files.size(file);
            assertNotEquals(0,unpaddedSize%4096,"Fixture must exercise native unpadded final sector");
            var before=Files.readAllBytes(file);
            ExistingRegionEntry.requirePresent(root,0,0);
            assertArrayEquals(before,Files.readAllBytes(file),"Preflight must never pad or modify the region");
            // Actual native reallocation after another write, matching save/reload of a chunk.
            byte[] replacement=new byte[7000];new java.util.Random(201).nextBytes(replacement);
            try(var output=region.getChunkDataOutputStream(new net.minecraft.world.level.ChunkPos(0,0))){
                output.write(replacement);
            }
            region.flush();
            assertNotEquals(0,Files.size(file)%4096);
            ExistingRegionEntry.requirePresent(root,0,0);
        }
        assertEquals(0,Files.size(file)%4096,"Native close pads the final sector");
        ExistingRegionEntry.requirePresent(root,0,0);
    }
    @Test void onlyPaddingMayBeMissingNeverDeclaredPayload()throws Exception{
        var file=fixture(0,0,513,100,2);
        try(var channel=java.nio.channels.FileChannel.open(file,java.nio.file.StandardOpenOption.WRITE)){
            channel.truncate(8192+4+100);
        }
        ExistingRegionEntry.requirePresent(root,0,0);
        try(var channel=java.nio.channels.FileChannel.open(file,java.nio.file.StandardOpenOption.WRITE)){
            channel.truncate(8192+4+99);
        }
        assertThrows(IOException.class,()->ExistingRegionEntry.requirePresent(root,0,0));
    }
    @Test void truncationAndSymlinkAreRefused()throws Exception{
        var file=fixture(0,0,513,100,2);Files.write(file,new byte[100]);
        assertThrows(IOException.class,()->ExistingRegionEntry.requirePresent(root,0,0));
        Files.delete(file);Files.write(root.resolve("other"),new byte[12288]);
        Files.createSymbolicLink(file,root.resolve("other"));
        assertThrows(IOException.class,()->ExistingRegionEntry.requirePresent(root,0,0));
    }
}
