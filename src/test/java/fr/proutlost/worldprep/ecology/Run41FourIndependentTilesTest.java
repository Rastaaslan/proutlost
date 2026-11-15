package fr.proutlost.worldprep.ecology;

import fr.proutlost.worldprep.ecology.BoundedEcologyCandidatePool.Column;
import fr.proutlost.worldprep.ecology.BoundedTilePipeline.TileInput;
import fr.proutlost.worldprep.ecology.BoundedTilePipeline.TileResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class Run41FourIndependentTilesTest {
    private static TileInput tile(long index) {
        int x0=(int)index*16+1024,z0=2048;
        var cols=new ArrayList<Column>();
        for(int z=z0;z<z0+16;z++)for(int x=x0;x<x0+16;x++) {
            int floor=55+Math.floorMod(x*3+z*5,40);
            int depth=Math.floorMod(x*11+z*7,19);
            cols.add(new Column(x,z,floor+depth,floor,Math.floorMod(x+z,17)==0));
        }
        return new TileInput(index,123456789L,List.copyOf(cols));
    }
    private static TileResult await(BoundedTilePipeline pipeline,long id) {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        TileResult result;
        do {
            result=pipeline.poll(id);
            if(result!=null)return result;
            Thread.onSpinWait();
        }while(System.nanoTime()<end);
        fail("Bounded tile result did not become available");
        return null;
    }
    @Test @Timeout(20)
    void fourDifferentTilesActuallyExecuteConcurrentlyOnFourCpuThreads()throws Exception {
        var started=new CountDownLatch(4);
        var release=new CountDownLatch(1);
        try(var pool=new BoundedTilePipeline(4,input->{
            started.countDown();
            if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Timed out");
            return BoundedTilePipeline.compute(input);
        })) {
            for(int i=0;i<4;i++)pool.offer(tile(i));
            try {
                assertTrue(started.await(5,TimeUnit.SECONDS),"Not four distinct live CPU tasks");
                assertEquals(4,pool.peakConcurrentWorkers());
                assertEquals(4,pool.outstanding());
                assertThrows(IllegalStateException.class,()->pool.offer(tile(4)));
            }finally{release.countDown();}
            for(int i=0;i<4;i++) {
                assertEquals(i,await(pool,i).index());
                assertEquals(BoundedTilePipeline.compute(tile(i)),await(pool,i));
            }
            pool.retireBefore(2);
            assertEquals(2,pool.outstanding());
            pool.retireBefore(4);
            assertEquals(0,pool.outstanding());
        }
    }
    @Test @Timeout(20)
    void fourIndependentTilesEqualSingleThreadAndTwoEightWorkerResults() {
        var expected=new ArrayList<TileResult>();
        for(int i=0;i<8;i++)expected.add(BoundedTilePipeline.compute(tile(i)));
        for(int workers:List.of(1,2,4,8)) {
            try(var pool=new BoundedTilePipeline(workers)) {
                for(int from=0;from<8;from+=workers) {
                    for(int i=from;i<from+workers;i++)pool.offer(tile(i));
                    for(int i=from;i<from+workers;i++) {
                        assertEquals(expected.get(i),await(pool,i));
                        assertEquals(Set.of("SOILS","FLORA","AQUATIC"),
                                await(pool,i).passes().keySet());
                    }
                    pool.retireBefore(from+workers);
                }
            }
        }
    }
    @Test @Timeout(15)
    void outOfOrderFutureCompletionCannotChangeTileIdentityOrPlan() {
        try(var pool=new BoundedTilePipeline(4,input->{
            if(input.index()==0)Thread.sleep(60);
            return BoundedTilePipeline.compute(input);
        })) {
            for(int i=0;i<4;i++)pool.offer(tile(i));
            assertEquals(BoundedTilePipeline.compute(tile(0)),await(pool,0));
            for(int i=1;i<4;i++)assertEquals(BoundedTilePipeline.compute(tile(i)),await(pool,i));
        }
    }
    @Test
    void duplicateInvalidInputAndOverflowAreRejectedWithoutPublication() {
        assertThrows(IllegalArgumentException.class,()->new BoundedTilePipeline(0));
        assertThrows(IllegalArgumentException.class,()->new BoundedTilePipeline(9));
        assertThrows(IllegalArgumentException.class,()->new TileInput(-1,0,List.of(new Column(0,0,64,64,false))));
        assertThrows(IllegalArgumentException.class,()->new TileInput(1,0,List.of()));
        var many=new ArrayList<>(tile(1).columns());
        many.add(many.getFirst());
        assertThrows(IllegalArgumentException.class,()->new TileInput(1,0,many));
        try(var pool=new BoundedTilePipeline(1)) {
            pool.offer(tile(0));
            assertThrows(IllegalStateException.class,()->pool.offer(tile(0)));
            assertThrows(IllegalStateException.class,()->pool.offer(tile(1)));
            assertEquals(0,await(pool,0).index());
            pool.invalidate(0);
            assertEquals(0,pool.outstanding());
            pool.offer(tile(1));
            assertEquals(1,await(pool,1).index());
        }
    }
    @Test @Timeout(15)
    void cancellationOfWorkersFailsClosedAndReleasesResources()throws Exception {
        var begun=new CountDownLatch(2);
        try(var pool=new BoundedTilePipeline(2,input->{
            begun.countDown();
            Thread.sleep(5000);
            return BoundedTilePipeline.compute(input);
        })) {
            pool.offer(tile(0));pool.offer(tile(1));
            assertTrue(begun.await(5,TimeUnit.SECONDS));
            pool.invalidate(0);
            assertNull(pool.poll(0), "A cancelled result must not publish");
        }
    }
}
