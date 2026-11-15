package fr.proutlost.worldprep;

import fr.proutlost.worldprep.orchestration.*;
import static fr.proutlost.worldprep.orchestration.QualificationPipeline.Phase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class QualificationOrchestratorTest {
    @TempDir Path root;
    final UUID batch=new UUID(10,20);
    final String config="{\"enabled\":false,\"version\":1}";
    EnumMap<Phase,QualificationOrchestrator.Factory> engines(AtomicInteger calls) {
        var engines=new EnumMap<Phase,QualificationOrchestrator.Factory>(Phase.class);
        for(var phase:Phase.values())engines.put(phase,pending->()->{calls.incrementAndGet();return Optional.of(phase.name());});
        return engines;
    }
    @Test void actualConfigurationArchivedAndTwoPhasesAdvanceAutomatically()throws Exception {
        var calls=new AtomicInteger();
        try(var driver=new QualificationOrchestrator(root,batch,config,engines(calls),()->{},(p,e)->assertEquals(p.name(),e))) {
            driver.tick();driver.tick();assertEquals(2,driver.cursor());assertEquals(2,calls.get());
            assertEquals(config,Files.readString(root.resolve("configuration")));
        }
    }
    @Test void restartAtPhaseBoundaryNeverReplaysCompletedEcology()throws Exception {
        var calls=new AtomicInteger();var engines=engines(calls);
        engines.put(Phase.INTEGRITY_AND_BACKUP,p->()->{throw new java.io.IOException("Crash boundary");});
        try(var driver=new QualificationOrchestrator(root,batch,config,engines,()->{},(p,e)->{})) {
            driver.tick();driver.tick();assertEquals(QualificationOrchestrator.Status.PAUSED,driver.status());assertEquals(1,driver.cursor());
        }
        engines.put(Phase.ECOLOGY_RECEIPTS,p->{throw new AssertionError("Ecology replayed");});
        engines.put(Phase.INTEGRITY_AND_BACKUP,p->{assertTrue(p.reconciliationRequired());return ()->Optional.of("backup verified");});
        try(var driver=new QualificationOrchestrator(root,batch,config,engines,()->{},(p,e)->{})) {
            driver.tick();assertEquals(2,driver.cursor());
        }
    }
    @Test void missingGateOrInvalidEvidencePausesBeforeAdvancing()throws Exception {
        var calls=new AtomicInteger();
        try(var driver=new QualificationOrchestrator(root,batch,config,engines(calls),()->{throw new IllegalStateException("Player present");},(p,e)->{})) {
            driver.tick();assertEquals(0,driver.cursor());assertEquals(0,calls.get());assertEquals(QualificationOrchestrator.Status.PAUSED,driver.status());
        }
        try(var driver=new QualificationOrchestrator(root,batch,config,engines(calls),()->{},(p,e)->{throw new IllegalArgumentException("Evidence mismatch");})) {
            driver.tick();assertEquals(0,driver.cursor());assertEquals(QualificationOrchestrator.Status.PAUSED,driver.status());
        }
    }
    @Test void absentEngineRefusedBeforeAnyIntent() {
        assertThrows(IllegalArgumentException.class,()->new QualificationOrchestrator(root,batch,config,Map.of(),()->{},(p,e)->{}));
    }
}
