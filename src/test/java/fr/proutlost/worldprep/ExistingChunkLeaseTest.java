package fr.proutlost.worldprep;

import fr.proutlost.worldprep.world.ExistingChunkLease;
import fr.proutlost.worldprep.world.ExistingChunkLease.Position;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ExistingChunkLeaseTest {
    private static final class Port implements ExistingChunkLease.Port {
        final List<Position> loads=new ArrayList<>();
        List<Position> released=List.of();
        int fullRequests, releases;
        int reject=-1, fail=-1;
        boolean ready;
        @Override public boolean loadAndPinExistingFull(Position p){
            loads.add(p);
            if(loads.size()==fail)throw new IllegalStateException("simulated disk failure");
            return loads.size()!=reject;
        }
        @Override public void requestFull(Position center){
            assertEquals(9,loads.size(),"promotion must follow verification of all dependencies");
            fullRequests++;
        }
        @Override public boolean isFullReady(Position center){return ready;}
        @Override public void release(Position center,List<Position> pinned){released=pinned;releases++;}
    }
    @Test void oneReadPerStepAndPromotionOnlyAfterEntireHalo(){
        var port=new Port();
        try(var lease=new ExistingChunkLease(-1,20,port)){
            for(int i=1;i<=9;i++){assertFalse(lease.step());assertEquals(i,port.loads.size());assertEquals(0,port.fullRequests);}
            assertEquals(new Position(-2,19),port.loads.getFirst());
            assertEquals(new Position(0,21),port.loads.getLast());
            assertEquals(9,port.loads.stream().distinct().count());
            assertFalse(lease.step());assertEquals(1,port.fullRequests);
            assertFalse(lease.step());port.ready=true;
            assertTrue(lease.step());assertTrue(lease.step());assertEquals(1,port.fullRequests);
        }
        assertEquals(1,port.releases);assertEquals(port.loads,port.released);
    }
    @Test void everyMissingOrIncompleteDependencyPreventsPromotion(){
        for(int missing=1;missing<=9;missing++){
            var port=new Port();port.reject=missing;
            var lease=new ExistingChunkLease(0,0,port);
            for(int i=1;i<missing;i++)assertFalse(lease.step());
            assertThrows(IllegalStateException.class,lease::step);
            assertEquals(0,port.fullRequests);assertEquals(missing,port.released.size());
            lease.close();assertEquals(1,port.releases);
            assertThrows(IllegalStateException.class,lease::step);
        }
    }
    @Test void ioFailureReleasesEvenThePartiallyAcquiredPin(){
        var port=new Port();port.fail=3;var lease=new ExistingChunkLease(10,-10,port);
        lease.step();lease.step();
        assertThrows(IllegalStateException.class,lease::step);
        assertEquals(3,port.released.size());assertEquals(0,port.fullRequests);
    }
    @Test void pauseAndRestartDiscardOldEvidenceAndReverify(){
        var port=new Port();var old=new ExistingChunkLease(10,10,port);
        old.step();old.step();old.close();
        assertEquals(2,port.released.size());
        var freshPort=new Port();freshPort.reject=1;
        try(var fresh=new ExistingChunkLease(10,10,freshPort)){
            assertThrows(IllegalStateException.class,fresh::step);
            assertEquals(0,freshPort.fullRequests);
        }
    }
    @Test void stalledPromotionTimesOutAndReleasesAllTickets(){
        var port=new Port();var lease=new ExistingChunkLease(0,0,port);
        for(int i=0;i<210;i++)assertFalse(lease.step());
        assertThrows(IllegalStateException.class,lease::step);
        assertEquals(1,port.fullRequests);assertEquals(9,port.released.size());
    }
}
