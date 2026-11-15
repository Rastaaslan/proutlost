package fr.proutlost.worldprep;

import fr.proutlost.worldprep.orchestration.*;
import fr.proutlost.worldprep.physical.PhysicalStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PerimeterExecutionTest {
    @TempDir Path root;
    PerimeterGroups groups(){return new PerimeterGroups(UUID.fromString("00000000-0000-0000-0000-000000000201"),"a".repeat(64),"minecraft:overworld",4496,-1666,5236,-943);}
    @Test void all36GroupsHaveDurableIntentAndBoundedResumeWithoutDuplicateExecution()throws Exception {
        var g=groups();var store=new PhysicalStore(root);var calls=new HashSet<UUID>();
        PerimeterExecution.Factory factory=group->{
            assertEquals(group.operation(),store.read("intent-"+group.ordinal(),PerimeterExecution.Intent.class).child());
            assertTrue(calls.add(group.operation()));return ()->Optional.of("audit:"+group.operation());
        };
        PerimeterExecution.Validator validator=(group,text)->assertEquals("audit:"+group.operation(),text);
        var run=new PerimeterExecution(g,store,factory,validator);
        while(run.cursor()<9)run.step();run.close();
        run=new PerimeterExecution(g,store,factory,validator);
        for(int i=0;i<9;i++){assertEquals(i,run.cursor());assertTrue(run.step().isEmpty());}
        int ticks=0;while(run.step().isEmpty())assertTrue(++ticks<100);
        assertEquals(36,calls.size());var aggregate=store.read("aggregate",PerimeterExecution.Aggregate.class);
        assertEquals(2209,aggregate.chunks());assertEquals(36,aggregate.groups());assertNotEquals("0".repeat(64),aggregate.terminal());
    }
    @Test void boundaryCrashReopensSameChildIdentityAndRetainsEvidence()throws Exception {
        var store=new PhysicalStore(root);var first=new PerimeterExecution(groups(),store,g->()->Optional.empty(),(g,e)->{});
        first.step();UUID child=store.read("intent-0",PerimeterExecution.Intent.class).child();first.close();
        var next=new PerimeterExecution(groups(),store,g->{assertEquals(child,g.operation());return ()->Optional.of("physical");},(g,e)->{});
        next.step();next.step();assertEquals(1,next.cursor());
        assertEquals("physical",store.read("group-0",PerimeterExecution.Receipt.class).evidence());
    }
    @Test void invalidEvidenceCannotAdvanceAndFailurePoisonsDriver()throws Exception {
        var store=new PhysicalStore(root);var closes=new AtomicInteger();
        var run=new PerimeterExecution(groups(),store,g->new QualificationOrchestrator.Task(){
            public Optional<String> step(){return Optional.of("unproven");}public void close(){closes.incrementAndGet();}
        },(g,e)->{throw new IOException("physical mismatch");});
        run.step();assertThrows(IOException.class,run::step);assertEquals(0,run.cursor());
        assertFalse(store.exists("group-0"));assertEquals(1,closes.get());assertThrows(IOException.class,run::step);
    }
    @Test void configurationChangeCannotReuseParentDirectory()throws Exception {
        var g=groups();var store=new PhysicalStore(root);new PerimeterExecution(g,store,x->()->Optional.empty(),(x,e)->{});
        var changed=new PerimeterGroups(g.batch(),"b".repeat(64),g.dimension(),g.minX(),g.minZ(),g.maxX(),g.maxZ());
        assertThrows(IOException.class,()->new PerimeterExecution(changed,store,x->()->Optional.empty(),(x,e)->{}));
    }
    @Test void partialReceiptDiskFailureNeverAdvancesChildCursor()throws Exception {
        var store=new PhysicalStore(root);
        var run=new PerimeterExecution(groups(),store,g->()->{
            java.nio.file.Files.createDirectory(root.resolve("group-0.json"));return Optional.of("physical");
        },(g,e)->{});
        run.step();assertThrows(IOException.class,run::step);assertEquals(0,run.cursor());
        assertFalse(store.exists("aggregate"));assertThrows(IOException.class,run::step);
    }
    @Test void retainedWrongPredecessorRefusesResumeBeforeEngineOpens()throws Exception {
        var g=groups();var store=new PhysicalStore(root);var child=g.group(0);
        store.write("intent-0",new PerimeterExecution.Intent(1,g.batch(),0,child.operation(),g.configurationSha256()));
        store.write("group-0",new PerimeterExecution.Receipt(1,g.batch(),0,child.operation(),"f".repeat(64),"physical",PhysicalStore.sha("physical")));
        var run=new PerimeterExecution(g,store,x->{fail("Corrupt chain must not open engine");return null;},(x,e)->{});
        assertThrows(IOException.class,run::step);assertEquals(0,run.cursor());
    }
}
