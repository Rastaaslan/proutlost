package fr.proutlost.worldprep;

import fr.proutlost.worldprep.pipeline.PassId;
import fr.proutlost.worldprep.plan.PlanFingerprint;
import fr.proutlost.worldprep.storage.PagedPlanStore;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PagedPlanStoreTest {
    @TempDir Path root;
    private static final PagedPlanStore.EntryCodec<Integer> INTS = new PagedPlanStore.EntryCodec<>() {
        public void write(DataOutput out,Integer value)throws IOException{out.writeInt(value);}
        public Integer read(DataInput in)throws IOException{return in.readInt();}
    };

    @Test void largePlansStayOnePageBoundedForEveryLivePass() throws Exception {
        for(PassId pass:List.of(PassId.BIOMES,PassId.GEOLOGY,PassId.ORES)){
            int total=50_003,pageSize=127;var id=UUID.randomUUID();
            var publication=publish(id,pass,pageSize,IntStream.range(0,total).boxed()::iterator);
            var expected=new AtomicLong();
            var metrics=PagedPlanStore.read(publication.directory(),publication.manifest(),INTS,value->assertEquals(expected.getAndIncrement(),value.longValue()));
            assertEquals(total,metrics.entriesRead());assertEquals((total+pageSize-1)/pageSize,metrics.pagesRead());
            assertEquals(1,metrics.maximumResidentPages());assertTrue(metrics.maximumResidentEntries()<=pageSize);
        }
    }

    @Test void missingCorruptWrongOwnerAndTrailingDataFailClosed() throws Exception {
        var publication=publish(UUID.randomUUID(),PassId.BIOMES,2,List.of(1,2,3,4));
        Path first=PagedPlanStore.pagePath(publication.directory(),0);byte[] original=Files.readAllBytes(first);
        Files.delete(first);assertThrows(IOException.class,()->PagedPlanStore.read(publication.directory(),publication.manifest(),INTS,x->fail()));
        Files.write(first,original);Files.write(first,new byte[]{7},StandardOpenOption.APPEND);
        assertThrows(IOException.class,()->PagedPlanStore.read(publication.directory(),publication.manifest(),INTS,x->fail()));
        Files.write(first,original);byte[] changed=original.clone();changed[12]^=1;Files.write(first,changed);
        assertThrows(IOException.class,()->PagedPlanStore.read(publication.directory(),publication.manifest(),INTS,x->fail()));
    }

    @Test void exactManifestAndLifecycleAreFailClosedAndNonDestructive() throws Exception {
        var publication=publish(UUID.randomUUID(),PassId.GEOLOGY,2,List.of(1,2,3));
        var other=publish(UUID.randomUUID(),PassId.GEOLOGY,2,List.of(1,2,3));
        assertThrows(IOException.class,()->PagedPlanStore.read(publication.directory(),other.manifest(),INTS,x->fail()));
        Path orphan=publication.directory().resolve("00000000000000000099.wpp");Files.writeString(orphan,"orphan");
        Path temporary=publication.directory().resolve("page.tmp-test");Files.writeString(temporary,"temporary");
        Path missing=PagedPlanStore.pagePath(publication.directory(),1);Files.delete(missing);
        var report=PagedPlanStore.diagnose(publication.directory(),publication.manifest());
        assertEquals(List.of(orphan),report.unreferencedPages());assertEquals(List.of(temporary),report.temporaryFiles());
        assertEquals(List.of(missing),report.missingReferencedPages());assertFalse(report.healthy());
        assertTrue(Files.exists(orphan));assertTrue(Files.exists(temporary));
    }

    @Test void pageBudgetDoesNotChangeSemanticSequence() throws Exception {
        var entries=IntStream.range(-5000,5001).boxed().toList();
        var small=publish(UUID.randomUUID(),PassId.ORES,7,entries);var large=publish(UUID.randomUUID(),PassId.ORES,991,entries);
        var a=new ArrayList<Integer>();var b=new ArrayList<Integer>();
        PagedPlanStore.read(small.directory(),small.manifest(),INTS,a::add);PagedPlanStore.read(large.directory(),large.manifest(),INTS,b::add);
        assertEquals(entries,a);assertEquals(a,b);
    }

    private PagedPlanStore.Publication publish(UUID id,PassId pass,int pageSize,Iterable<Integer> values)throws IOException{
        String digest=PlanFingerprint.of("semantic",pass.name()).value();
        return PagedPlanStore.publish(root,id,pass,"proutlost:test","minecraft:overworld","development",digest,"2","2",pageSize,values,INTS);
    }
}
