package fr.proutlost.worldprep;

import fr.proutlost.worldprep.persistence.AcknowledgedSavedData;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.neoforged.neoforge.common.IOUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class AcknowledgedSavedDataTest {
    @TempDir Path directory;
    private static class Store extends AcknowledgedSavedData {
        int captures, value;
        IOException fail;
        void persistFixture(CompoundTag root, Path path) throws IOException { super.persist(root,path); }
        @Override public CompoundTag save(CompoundTag root, HolderLookup.Provider registries) {
            captures++; root.putInt("value", value); return root;
        }
        @Override protected void persist(CompoundTag root, Path file) throws IOException {
            if(fail!=null)throw fail;
            super.persist(root,file);
        }
    }
    @Test void nativeAutosaveAndManualSaveShareAdmissionAndPersistLaterDirtyState() throws Exception {
        SharedConstants.tryDetectVersion();
        var blocked=new CountDownLatch(1);var release=new CountDownLatch(1);
        var store=new Store();var file=directory.resolve("store.dat");
        IOUtilities.withIOWorker(()->{blocked.countDown();try {
            if(!release.await(15,TimeUnit.SECONDS))throw new IllegalStateException("timeout");
        }catch(InterruptedException e){throw new IllegalStateException(e);}});
        try {
            assertTrue(blocked.await(5,TimeUnit.SECONDS));
            store.value=1;store.setDirty();store.save(file.toFile(),null);
            store.value=2;store.setDirty();
            for(int i=0;i<22090;i++)store.save(file.toFile(),null);
            assertEquals(1,store.captures);assertTrue(store.isDirty());
            assertFalse(store.checkpointReady());assertFalse(java.nio.file.Files.exists(file));
        }finally {release.countDown();IOUtilities.waitUntilIOWorkerComplete();}
        assertEquals(1,NbtIo.readCompressed(file,NbtAccounter.unlimitedHeap()).getCompound("data").getInt("value"));
        assertFalse(store.checkpointReady());
        store.save(file.toFile(),null);IOUtilities.waitUntilIOWorkerComplete();
        assertTrue(store.checkpointReady());assertEquals(2,store.captures);
        assertEquals(2,NbtIo.readCompressed(file,NbtAccounter.unlimitedHeap()).getCompound("data").getInt("value"));
    }
    @Test void failedNativeWriteIsSurfacedAndCannotBeMistakenForAcknowledgement() {
        SharedConstants.tryDetectVersion();
        var store=new Store();store.fail=new IOException("disk full");store.setDirty();
        // Failure may complete before save returns, so either point must refuse.
        try {store.save(directory.resolve("failed.dat").toFile(),null);}catch(IllegalStateException expected){}
        IOUtilities.waitUntilIOWorkerComplete();
        var error=assertThrows(IllegalStateException.class,store::checkpointReady);
        assertSame(store.fail,error.getCause());
        assertThrows(IllegalStateException.class,()->store.save(directory.resolve("failed.dat").toFile(),null));
        assertEquals(1,store.captures);
    }
    @Test void finalNativeBoundaryPersistsLaterDirtyStateWithoutAnotherManualSave() throws Exception {
        SharedConstants.tryDetectVersion();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var store=new Store();var file=directory.resolve("shutdown.dat");
        IOUtilities.withIOWorker(()->{entered.countDown();try {
            if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("test timeout");
        }catch(InterruptedException e){throw new IllegalStateException(e);}});
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            store.value=1;store.setDirty();store.save(file.toFile(),null);
            store.value=2;store.setDirty();
            store.save(file.toFile(),null); // final native serialization declines while pending
            assertEquals(1,store.captures);
            assertFalse(store.checkpointReady());
        } finally {release.countDown();}
        // Same protocol invoked by LevelEvent.Save, not an additional caller save.
        store.flushNativeSave();
        assertTrue(store.checkpointReady());assertEquals(2,store.captures);
        assertEquals(2,NbtIo.readCompressed(file,NbtAccounter.unlimitedHeap()).getCompound("data").getInt("value"));
    }

    @Test void finalBoundarySurfacesFailureInsteadOfClaimingCleanShutdown() {
        SharedConstants.tryDetectVersion();
        var store=new Store();store.fail=new IOException("final checkpoint failed");store.setDirty();
        try {store.save(directory.resolve("failed-flush.dat").toFile(),null);}catch(IllegalStateException expected){}
        assertThrows(IllegalStateException.class,store::flushNativeSave);
        IOUtilities.waitUntilIOWorkerComplete();
        assertEquals(1,store.captures);
    }

    @Test void fastCompressionRetainsVanillaNbtAndDurableReplacement() throws Exception {
        SharedConstants.tryDetectVersion();
        var store=new Store();
        var payload=new CompoundTag();
        payload.putInt("cursor",645160);
        var jobs=new ListTag();
        for(int i=0;i<512;i++){
            var entry=new CompoundTag();
            entry.putString("id","stage-"+i+"-durable-receipt-"+("x".repeat(300)));
            entry.putLong("cursor",i);
            jobs.add(entry);
        }
        payload.put("jobs",jobs);
        var envelope=new CompoundTag();envelope.put("data",payload);
        var destination=directory.resolve("checkpoint.dat");
        store.persistFixture(envelope,destination);
        byte[] bytes=java.nio.file.Files.readAllBytes(destination);
        assertEquals(0x1f,Byte.toUnsignedInt(bytes[0]));
        assertEquals(0x8b,Byte.toUnsignedInt(bytes[1]));
        var actual=NbtIo.readCompressed(destination,NbtAccounter.unlimitedHeap()).getCompound("data");
        assertEquals(645160,actual.getInt("cursor"));
        assertEquals(jobs,actual.getList("jobs",Tag.TAG_COMPOUND));
        var replacement=new CompoundTag();replacement.putInt("cursor",645161);
        var e2=new CompoundTag();e2.put("data",replacement);
        store.persistFixture(e2,destination);
        assertEquals(645161,NbtIo.readCompressed(destination,NbtAccounter.unlimitedHeap()).getCompound("data").getInt("cursor"));
    }

}
