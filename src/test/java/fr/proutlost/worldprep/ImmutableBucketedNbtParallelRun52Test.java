package fr.proutlost.worldprep;

import fr.proutlost.worldprep.persistence.ImmutableBucketedNbtCheckpoint;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** RUN52 experimental only: externalize captured immutable NBT on bounded workers,
 * while serialized and parallel modes publish bitwise equal canonical roots.
 * No Minecraft world / source / receipts are modified by these tests.
 */
class ImmutableBucketedNbtParallelRun52Test {
    @TempDir Path work;
    private static final String FLAG="proutlost.run52.parallelNbtBuckets";
    private static final String STORE="proutlost_worldprep";
    @Test void boundedFourWorkersEmitExactlySamePagesRootAndCrashSafeEarlierGeneration() throws Exception {
        var old=System.getProperty(FLAG);
        try {
            var full=ImmutableBucketedNbtCheckpointTest.root(STORE,180);
            var a=work.resolve("serial/data/"+STORE+".dat");
            var b=work.resolve("parallel/data/"+STORE+".dat");
            System.clearProperty(FLAG);
            var legacy=ImmutableBucketedNbtCheckpoint.externalize(full,a,STORE);
            assertTrue(legacy.metrics().newPages()>0);
            NbtIo.writeCompressed(legacy.compactRoot(),a);
            System.setProperty(FLAG,"true");
            var parallel=ImmutableBucketedNbtCheckpoint.externalize(full,b,STORE);
            assertTrue(parallel.metrics().newPages()>0);
            assertEquals(legacy.compactRoot(),parallel.compactRoot());
            assertEquals(legacy.metrics().referencedPages(),parallel.metrics().referencedPages());
            assertEquals(full,ImmutableBucketedNbtCheckpoint.expand(parallel.compactRoot(),b,STORE));
            NbtIo.writeCompressed(parallel.compactRoot(),b);
            assertEquals(full,ImmutableBucketedNbtCheckpoint.expand(
                    NbtIo.readCompressed(b,NbtAccounter.unlimitedHeap()),b,STORE));

            var updated=full.copy();
            var list=updated.getCompound("data").getList("jobs",Tag.TAG_COMPOUND);
            var record=list.getCompound(42).copy();
            record.putLong("cursor",123456789);
            list.set(42,record);
            System.clearProperty(FLAG);
            var nextSerial=ImmutableBucketedNbtCheckpoint.externalize(updated,a,STORE);
            System.setProperty(FLAG,"true");
            var nextParallel=ImmutableBucketedNbtCheckpoint.externalize(updated,b,STORE);
            assertEquals(nextSerial.compactRoot(),nextParallel.compactRoot());
            assertEquals(nextSerial.metrics().newPages(),nextParallel.metrics().newPages());
            assertTrue(nextParallel.metrics().newPages()>=1);
            assertTrue(nextParallel.metrics().newPages()<=2);
            assertEquals(updated,ImmutableBucketedNbtCheckpoint.expand(nextParallel.compactRoot(),b,STORE));
            // The new pages were published but the old root was never swapped:
            // a crash must leave the old cold generation authoritative.
            assertEquals(full,ImmutableBucketedNbtCheckpoint.expand(
                    NbtIo.readCompressed(b,NbtAccounter.unlimitedHeap()),b,STORE));
            long n=Thread.getAllStackTraces().keySet().stream()
                    .filter(t->t.getName().startsWith("worldprep-run52-nbt-cpu-")).count();
            assertEquals(4,n,"RUN52 bounded four CPU workers were actually started");
        }finally{
            if(old==null)System.clearProperty(FLAG);
            else System.setProperty(FLAG,old);
        }
    }
    @Test void missingImmutablePageCannotBeReconstructedSilently() throws Exception {
        var prior=System.getProperty(FLAG);
        try{
            System.setProperty(FLAG,"true");
            var full=ImmutableBucketedNbtCheckpointTest.root(STORE,40);
            var target=work.resolve("new/data/"+STORE+".dat");
            var paged=ImmutableBucketedNbtCheckpoint.externalize(full,target,STORE);
            try(Stream<Path> items=Files.walk(target.getParent().resolve("worldprep-paged-v1"))){
                var page=items.filter(p->p.getFileName().toString().endsWith(".nbt.gz"))
                        .findFirst().orElseThrow();
                Files.writeString(page,"CORRUPT");
            }
            assertThrows(java.io.IOException.class,
                    ()->ImmutableBucketedNbtCheckpoint.expand(paged.compactRoot(),target,STORE));
            assertThrows(java.io.IOException.class,
                    ()->ImmutableBucketedNbtCheckpoint.externalize(full,target,STORE));
        }finally{
            if(prior==null)System.clearProperty(FLAG);
            else System.setProperty(FLAG,prior);
        }
    }
}
