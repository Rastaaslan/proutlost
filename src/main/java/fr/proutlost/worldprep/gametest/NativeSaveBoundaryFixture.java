package fr.proutlost.worldprep.gametest;

import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.runtime.WorldPrepRuntime;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Disposable GameTest metadata only. Tests the installed listener, never calls flushNativeSave. */
final class NativeSaveBoundaryFixture {
    static void verify(GameTestHelper helper) {
        var level=helper.getLevel();
        var data=WorldPrepRuntime.data(level);
        CheckpointFixture.drain(level);
        var key=UUID.randomUUID();
        var first=new WorldPrepSavedData.Selection(1,2,3,4);
        var later=new WorldPrepSavedData.Selection(5,6,7,8);
        var entered=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var timedOut=new AtomicBoolean();
        var observed=new AtomicBoolean();
        Consumer<LevelEvent.Save> beforeProductionListener=event->{
            if(event.getLevel()!=level)return;
            try {
                helper.assertTrue(data.isDirty(),"Native save discarded later dirty state");
                helper.assertTrue(!data.checkpointReady(),"Fixture checkpoint was not delayed at native boundary");
                observed.set(true);
            } finally {release.countDown();}
        };
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST,LevelEvent.Save.class,beforeProductionListener);
        IOUtilities.withIOWorker(()->{
            entered.countDown();
            try {if(!release.await(10,TimeUnit.SECONDS))timedOut.set(true);}
            catch(InterruptedException e){Thread.currentThread().interrupt();timedOut.set(true);}
        });
        try {
            helper.assertTrue(entered.await(5,TimeUnit.SECONDS),"Fixture worker did not start");
            data.selections().put(key,first);data.changed();
            level.getDataStorage().save(); // first snapshot waits behind the blocked worker
            data.selections().put(key,later);data.changed();
            long started=System.nanoTime();
            level.save(null,true,false); // native serialization + real event + final native wait
            long elapsed=System.nanoTime()-started;
            helper.assertTrue(observed.get()&&!timedOut.get(),"Real native save event did not release delayed writer");
            helper.assertTrue(data.checkpointReady(),"Native flush left an unacknowledged/dirty store");
            var disk=WorldPrepSavedData.load(level.getDataStorage()
                    .readTagFromDisk(WorldPrepSavedData.FILE_ID,null,0).getCompound("data"),level.registryAccess());
            helper.assertTrue(later.equals(disk.selections().get(key)),"Native flush omitted later mutation on disk");
            System.out.println("CB200 native-save-boundary later-dirty persisted; elapsedNanos="+elapsed);
        } catch(java.io.IOException e) {
            throw new IllegalStateException("Native flush disk verification failed",e);
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();throw new IllegalStateException(e);
        } finally {
            release.countDown();
            NeoForge.EVENT_BUS.unregister(beforeProductionListener);
            IOUtilities.waitUntilIOWorkerComplete();
            data.selections().remove(key);data.changed();
            CheckpointFixture.drain(level);
        }
    }
    private NativeSaveBoundaryFixture() {}
}
