package fr.proutlost.worldprep;

import fr.proutlost.worldprep.storage.BatchManifestStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class BatchManifestTest {
    @TempDir Path root;
    private BatchManifestStore.Header header(UUID id){return new BatchManifestStore.Header(1,id,"minecraft:overworld","proutlost:fixture",99L,0,0,15,15,2,"test-v1");}
    private String artifact(String text)throws Exception{
        var bytes=text.getBytes(java.nio.charset.StandardCharsets.UTF_8);String path="discovery/"+BatchManifestStore.hash(bytes)+".json";
        Files.createDirectories(root.resolve("discovery"));Files.write(root.resolve(path),bytes);return path;
    }
    private BatchManifestStore.Stage stage(BatchManifestStore.Header h,long sequence,String artifact)throws Exception{
        return new BatchManifestStore.Stage(1,h.batchId(),sequence,0,"proutlost:tile",sequence==0?"STRUCTURES":"HABITATS",artifact,
                BatchManifestStore.artifactHash(root,artifact),"DISCOVERY_ONLY_NOT_DIRECTOR_READY","COMPLETED",null,null,null,0,0,
                BatchManifestStore.previousHash(root,h.batchId(),sequence));
    }
    @Test void receiptsSurviveCursorCrashAndRepeatPublicationExactly()throws Exception{
        var h=header(UUID.randomUUID());BatchManifestStore.initialize(root,h);
        var first=stage(h,0,artifact("{\"phase\":\"before\"}"));BatchManifestStore.record(root,h,first);
        var bytes=Files.readAllBytes(BatchManifestStore.stagePath(root,h.batchId(),0));
        // Fresh reader after receipt persisted but before SavedData cursor advanced.
        assertEquals(first,BatchManifestStore.read(root,h,0));BatchManifestStore.record(root,h,first);
        assertArrayEquals(bytes,Files.readAllBytes(BatchManifestStore.stagePath(root,h.batchId(),0)));
        var last=stage(h,1,artifact("{\"phase\":\"after\"}"));BatchManifestStore.record(root,h,last);
        BatchManifestStore.complete(root,h);BatchManifestStore.validateAll(root,h);
        assertNotEquals(first.artifactSha256(),last.artifactSha256());
        assertTrue(Files.readString(BatchManifestStore.directory(root,h.batchId()).resolve("completed.json")).contains("DIRECTOR_NOT_READY"));
    }
    @Test void mutatedExportAndMissingPreviousReceiptRefuseCompletion()throws Exception{
        var h=header(UUID.randomUUID());BatchManifestStore.initialize(root,h);var a=artifact("{}");
        assertThrows(java.io.IOException.class,()->BatchManifestStore.complete(root,h));
        BatchManifestStore.record(root,h,stage(h,0,a));BatchManifestStore.record(root,h,stage(h,1,a));
        Files.writeString(root.resolve(a),"changed");
        assertThrows(java.io.IOException.class,()->BatchManifestStore.read(root,h,1));
        Files.delete(BatchManifestStore.stagePath(root,h.batchId(),0));
        assertThrows(java.io.IOException.class,()->BatchManifestStore.complete(root,h));
    }
    @Test void completionChecksEarlierEvidenceAcrossThreeStages() throws Exception {
        for(boolean deleteReceipt: new boolean[]{true,false}) {
            var id=UUID.randomUUID();
            var h=new BatchManifestStore.Header(1,id,"minecraft:overworld","proutlost:fixture",99,0,0,15,15,3,"test-v1");
            BatchManifestStore.initialize(root,h);
            String first=artifact("first-"+id);
            BatchManifestStore.record(root,h,stage(h,0,first));
            BatchManifestStore.record(root,h,stage(h,1,artifact("middle-"+id)));
            BatchManifestStore.record(root,h,stage(h,2,artifact("last-"+id)));
            // The final receipt is still valid: it cannot establish integrity of the entire chain.
            if(deleteReceipt)Files.delete(BatchManifestStore.stagePath(root,id,0));
            else Files.writeString(root.resolve(first),"corrupted");
            assertDoesNotThrow(()->BatchManifestStore.read(root,h,2));
            assertThrows(java.io.IOException.class,()->BatchManifestStore.complete(root,h));
            var scan=new BatchManifestStore.CompletionScan(root,h);
            assertThrows(java.io.IOException.class,scan::step);
            assertFalse(Files.exists(BatchManifestStore.directory(root,id).resolve("completed.json")));
        }
    }
    @Test void incrementalCompletionAndRestartNeverPublishPartialValidation() throws Exception {
        var id=UUID.randomUUID();
        var h=new BatchManifestStore.Header(1,id,"minecraft:overworld","proutlost:fixture",99,0,0,15,15,3,"test-v1");
        BatchManifestStore.initialize(root,h);
        for(int i=0;i<3;i++)BatchManifestStore.record(root,h,stage(h,i,artifact("stage-"+i)));
        var scan=new BatchManifestStore.CompletionScan(root,h);
        assertFalse(scan.step());
        assertFalse(Files.exists(BatchManifestStore.directory(root,id).resolve("completed.json")));
        scan=new BatchManifestStore.CompletionScan(root,h); // process loss restarts the full scan
        assertFalse(scan.step());assertFalse(scan.step());assertTrue(scan.step());
        assertTrue(scan.step()); // identical repeat publication
    }
    @Test void twentyTwoReceiptsDoNotAuthorizeBlindCursorAdvance() throws Exception {
        var batch=UUID.randomUUID();var childId=UUID.randomUUID();var journal=UUID.randomUUID();
        var h=new BatchManifestStore.Header(1,batch,"minecraft:overworld","proutlost:fixture",99,0,0,47,0,30,"ecology-batch-v3");
        BatchManifestStore.initialize(root,h);String artifact=artifact("fixture evidence");
        for(int i=0;i<22;i++) {
            String name=fr.proutlost.worldprep.runtime.EcologyBatchRuntime.STAGES.get(i%10);
            BatchManifestStore.record(root,h,new BatchManifestStore.Stage(1,batch,i,i/10,"proutlost:tile"+(i/10),name,
                    artifact,BatchManifestStore.artifactHash(root,artifact),"TEST_ONLY","COMPLETED",
                    i==2?childId:UUID.randomUUID(),UUID.randomUUID(),i==2?journal:null,55,i==2?55:0,
                    BatchManifestStore.previousHash(root,batch,i)));
        }
        var receipt=BatchManifestStore.read(root,h,2);
        var d=new fr.proutlost.worldprep.persistence.WorldPrepSavedData();
        var parent=new fr.proutlost.worldprep.persistence.WorldPrepSavedData.Job(batch,
                fr.proutlost.worldprep.persistence.WorldPrepSavedData.Operation.BATCH_ECOLOGY,"proutlost:fixture",
                fr.proutlost.worldprep.persistence.WorldPrepSavedData.JobState.RUNNING,2,"",childId);
        var child=new fr.proutlost.worldprep.persistence.WorldPrepSavedData.Job(childId,
                fr.proutlost.worldprep.persistence.WorldPrepSavedData.Operation.APPLY_SOILS,"proutlost:tile0",
                fr.proutlost.worldprep.persistence.WorldPrepSavedData.JobState.PAUSED,39,"",journal);
        d.jobs().put(batch,parent);d.jobs().put(childId,child);
        var loaded=fr.proutlost.worldprep.persistence.WorldPrepSavedData.load(d.save(new net.minecraft.nbt.CompoundTag(),null),null);
        var candidate=fr.proutlost.worldprep.runtime.EcologyBatchRuntime.receiptChildCandidate(loaded.jobs().get(batch),loaded.jobs().get(childId),receipt);
        assertEquals(55,candidate.cursor());assertEquals(2,loaded.jobs().get(batch).cursor());
        assertEquals(child,loaded.jobs().get(childId)); // proposal only, terrain/journal validation still required
        assertThrows(java.io.IOException.class,()->fr.proutlost.worldprep.runtime.EcologyBatchRuntime.receiptChildCandidate(parent,null,receipt));
        assertThrows(java.io.IOException.class,()->fr.proutlost.worldprep.runtime.EcologyBatchRuntime.receiptChildCandidate(parent.withSnapshot(UUID.randomUUID()),child,receipt));
        Files.writeString(root.resolve(artifact),"corruption");
        assertThrows(java.io.IOException.class,()->BatchManifestStore.read(root,h,2));
        assertEquals(22,Files.list(BatchManifestStore.directory(root,batch)).filter(f->f.getFileName().toString().matches("[0-9]{20}\\.json")).count());
    }
    @Test void differentBatchIdentityOrRepeatContentCannotOverwrite()throws Exception{
        var h=header(UUID.randomUUID());BatchManifestStore.initialize(root,h);
        BatchManifestStore.record(root,h,stage(h,0,artifact("before")));
        assertThrows(java.io.IOException.class,()->BatchManifestStore.record(root,h,stage(h,0,artifact("different"))));
        var changed=new BatchManifestStore.Header(1,h.batchId(),"minecraft:the_nether",h.area(),h.worldSeed(),0,0,15,15,2,"test-v1");
        assertThrows(java.io.IOException.class,()->BatchManifestStore.initialize(root,changed));
        assertThrows(java.io.IOException.class,()->BatchManifestStore.artifactHash(root,"../outside"));
    }
}
