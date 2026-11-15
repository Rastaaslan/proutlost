package fr.proutlost.worldprep;

import fr.proutlost.worldprep.world.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VerifiedChunkWindowTest {
    static final class Port implements ExistingChunkLease.Port {
        int reads,promotions,releases;boolean ready=true,fail,releaseFails;
        public boolean loadAndPinExistingFull(ExistingChunkLease.Position p){reads++;return !fail;}
        public void requestFull(ExistingChunkLease.Position p){assertEquals(9,reads);promotions++;}
        public boolean isFullReady(ExistingChunkLease.Position p){return ready;}
        public void release(ExistingChunkLease.Position p,List<ExistingChunkLease.Position> pins){releases++;assertTrue(pins.size()<=9);if(releaseFails)throw new IllegalStateException("release failure");}
    }
    static List<ExistingChunkLease.Position> center(int x,int z){return List.of(new ExistingChunkLease.Position(x,z));}
    static void ready(VerifiedChunkWindow w,List<ExistingChunkLease.Position> c){for(int i=0;i<50;i++)if(w.advance(c))return;fail("Never ready");}
    @Test void distantTransitionClosesPreviousBeforeNewPreflight(){
        var ports=new ArrayList<Port>();var w=new VerifiedChunkWindow(p->{if(!ports.isEmpty())assertEquals(1,ports.getLast().releases);var port=new Port();ports.add(port);return new ExistingChunkLease(p.x(),p.z(),port);});
        ready(w,center(-2,-2));ready(w,center(2,-2));assertEquals(1,w.ownedLeases());w.close();assertEquals(1,ports.getLast().releases);
    }
    @Test void onlyOneHaloReadPerAdvanceEvenForNineCenters(){
        var ports=new ArrayList<Port>();var w=new VerifiedChunkWindow(p->{var port=new Port();ports.add(port);return new ExistingChunkLease(p.x(),p.z(),port);});
        var centers=new ArrayList<ExistingChunkLease.Position>();for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)centers.add(new ExistingChunkLease.Position(x,z));
        boolean ready=false;for(int i=0;i<150&&!ready;i++){int before=ports.stream().mapToInt(p->p.reads).sum();ready=w.advance(centers);assertTrue(ports.stream().mapToInt(p->p.reads).sum()-before<=1);assertTrue(w.ownedLeases()<=9);}
        assertTrue(ready);assertEquals(81,ports.stream().mapToInt(p->p.reads).sum());w.close();assertTrue(ports.stream().allMatch(p->p.releases==1));
    }
    @Test void missingHaloRefusesBeforePromotionAndReleasesPartialLease(){
        var port=new Port();port.fail=true;var w=new VerifiedChunkWindow(p->new ExistingChunkLease(p.x(),p.z(),port));
        assertThrows(IllegalStateException.class,()->w.advance(center(0,0)));assertEquals(0,port.promotions);assertEquals(1,port.releases);assertEquals(0,w.ownedLeases());
    }
    @Test void oversizedFootprintRefusesAndClosesOwnedLease(){
        var port=new Port();var w=new VerifiedChunkWindow(p->new ExistingChunkLease(p.x(),p.z(),port));ready(w,center(0,0));
        assertThrows(IllegalArgumentException.class,()->w.advance(List.of(new ExistingChunkLease.Position(-2,0),new ExistingChunkLease.Position(2,0))));assertEquals(1,port.releases);
    }
    @Test void pauseCloseCannotBeReusedButNewOwnerCanResumeSameCenter(){
        var port=new Port();var w=new VerifiedChunkWindow(p->new ExistingChunkLease(p.x(),p.z(),port));w.advance(center(0,0));w.close();w.close();assertEquals(1,port.releases);
        assertThrows(IllegalStateException.class,()->w.advance(center(0,0)));var next=new Port();var resumed=new VerifiedChunkWindow(p->new ExistingChunkLease(p.x(),p.z(),next));ready(resumed,center(0,0));assertEquals(9,next.reads);resumed.close();
    }
    @Test void cleanupFailureStillAttemptsEveryOwnedLease(){
        var ports=new ArrayList<Port>();var w=new VerifiedChunkWindow(p->{var port=new Port();ports.add(port);return new ExistingChunkLease(p.x(),p.z(),port);});
        ready(w,List.of(new ExistingChunkLease.Position(0,0),new ExistingChunkLease.Position(1,0)));
        ports.getFirst().releaseFails=true;assertThrows(IllegalStateException.class,w::close);
        assertEquals(1,ports.getLast().releases);assertEquals(0,w.ownedLeases());
    }
    @Test void promotionTimeoutClosesAllPins(){
        var port=new Port();port.ready=false;var w=new VerifiedChunkWindow(p->new ExistingChunkLease(p.x(),p.z(),port));
        assertThrows(IllegalStateException.class,()->{for(int i=0;i<220;i++)w.advance(center(0,0));});assertEquals(1,port.promotions);assertEquals(1,port.releases);
    }
}
