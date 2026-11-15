package fr.proutlost.integration.atlas;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Pure consent/routing checks only. They do not qualify a Minecraft session or Atlas SavedData. */
final class AtlasShareGateTest {
    private static UUID id(long n){return new UUID(0,n);}
    private static final UUID SESSION=id(1);
    private static AtlasShareGate.Actor actor(long player,int atlas){return new AtlasShareGate.Actor(id(player),SESSION,id(1000+player),atlas,"minecraft:overworld");}
    private static AtlasShareGate.Marker marker(){return new AtlasShareGate.Marker(3,"antiqueatlas:google","Camp", "minecraft:overworld",4,8,true,false);}
    private static final AtlasShareGate.Actor SOURCE=actor(2,10),TARGET=actor(3,11);
    @Test void oneAcceptedMarkerUsesOneCanonicalWriteAndOnlyTheChosenRecipient(){
        var gate=new AtlasShareGate();var offer=gate.offer(SOURCE,TARGET,marker(),0,0);
        var accepted=gate.claim(offer.token(),SOURCE,TARGET,marker(),0,1);
        var writes=new AtomicInteger();var recipients=new ArrayList<UUID>();
        var thirdPlayer=id(4);
        String created=AtlasShareGate.deliver(accepted,()->{writes.incrementAndGet();return "canonical-marker-4";},
            (recipient,entity)->{recipients.add(recipient);assertEquals("canonical-marker-4",entity);});
        assertEquals("canonical-marker-4",created);assertEquals(1,writes.get());
        assertEquals(List.of(TARGET.player()),recipients);assertFalse(recipients.contains(thirdPlayer));
        assertFalse(recipients.contains(SOURCE.player()));
        assertThrows(IllegalArgumentException.class,()->gate.claim(offer.token(),SOURCE,TARGET,marker(),0,2));
    }
    @Test void anotherPlayerCannotConsumeTheRecipientsToken(){
        var gate=new AtlasShareGate();var offer=gate.offer(SOURCE,TARGET,marker(),0,0);
        assertThrows(IllegalArgumentException.class,()->gate.claim(offer.token(),SOURCE,actor(4,11),marker(),0,1));
        assertEquals(offer,gate.claim(offer.token(),SOURCE,TARGET,marker(),0,2));
    }
    @Test void expiredAndUnknownNoncesDoNotAdmitAnything(){
        var gate=new AtlasShareGate();var offer=gate.offer(SOURCE,TARGET,marker(),0,0);
        assertTrue(gate.peek(offer.token(),1199).isPresent());assertTrue(gate.peek(offer.token(),1200).isEmpty());
        assertThrows(IllegalArgumentException.class,()->gate.claim(offer.token(),SOURCE,TARGET,marker(),0,1200));
        assertThrows(IllegalArgumentException.class,()->gate.claim(id(123456),SOURCE,TARGET,marker(),0,1));
    }
    @Test void aReconnectedPlayerMustObtainANewOffer(){
        var gate=new AtlasShareGate();var offer=gate.offer(SOURCE,TARGET,marker(),0,0);
        var reconnected=new AtlasShareGate.Actor(TARGET.player(),SESSION,id(999),TARGET.atlas(),TARGET.dimension());
        assertThrows(IllegalArgumentException.class,()->gate.claim(offer.token(),SOURCE,reconnected,marker(),0,1));
        var sourceReconnected=new AtlasShareGate.Actor(SOURCE.player(),SESSION,id(998),SOURCE.atlas(),SOURCE.dimension());
        assertThrows(IllegalArgumentException.class,()->gate.claim(offer.token(),sourceReconnected,TARGET,marker(),0,1));
    }
    @Test void replacedAtlasOrSessionCannotReuseOldConsent(){
        var gate=new AtlasShareGate();var offer=gate.offer(SOURCE,TARGET,marker(),0,0);
        assertThrows(IllegalArgumentException.class,()->gate.claim(offer.token(),SOURCE,actor(3,12),marker(),0,1));
        assertThrows(IllegalArgumentException.class,()->gate.claim(offer.token(),actor(2,13),TARGET,marker(),0,1));
        var newBoot=new AtlasShareGate.Actor(SOURCE.player(),id(555),SOURCE.connection(),SOURCE.atlas(),SOURCE.dimension());
        assertThrows(IllegalArgumentException.class,()->gate.claim(offer.token(),newBoot,TARGET,marker(),0,1));
    }
    @Test void changedSourceMarkerCannotBeSubstitutedUnderAnExistingToken(){
        var gate=new AtlasShareGate();var offer=gate.offer(SOURCE,TARGET,marker(),0,0);
        var changed=new AtlasShareGate.Marker(3,"antiqueatlas:google","Other",SOURCE.dimension(),4,8,true,false);
        assertThrows(IllegalArgumentException.class,()->gate.claim(offer.token(),SOURCE,TARGET,changed,0,1));
    }
    @Test void totalAtlasCapacityIsCheckedAtOfferAndAgainAtAcceptance(){
        var gate=new AtlasShareGate();
        assertThrows(IllegalArgumentException.class,()->gate.offer(SOURCE,TARGET,marker(),32,0));
        var offer=gate.offer(SOURCE,TARGET,marker(),31,0);
        assertThrows(IllegalArgumentException.class,()->gate.claim(offer.token(),SOURCE,TARGET,marker(),32,1));
        assertEquals(offer,gate.claim(offer.token(),SOURCE,TARGET,marker(),31,2));
    }
    @Test void globalMarkersAndDimensionChangesAreRefused(){
        var gate=new AtlasShareGate();var local=marker();
        var global=new AtlasShareGate.Marker(local.id(),local.type(),local.label(),local.dimension(),local.x(),local.z(),true,true);
        assertThrows(IllegalArgumentException.class,()->gate.offer(SOURCE,TARGET,global,0,0));
        var nether=new AtlasShareGate.Actor(TARGET.player(),SESSION,TARGET.connection(),11,"minecraft:the_nether");
        assertThrows(IllegalArgumentException.class,()->gate.offer(SOURCE,nether,local,0,0));
    }
    @Test void userTextAndCoordinatesStayBounded(){
        for(var label:List.of("", "x".repeat(65),"Camp\nUnexpected")){
            var bad=new AtlasShareGate.Marker(3,"antiqueatlas:google",label,SOURCE.dimension(),4,8,true,false);
            assertThrows(IllegalArgumentException.class,()->new AtlasShareGate().offer(SOURCE,TARGET,bad,0,0));
        }
        var outside=new AtlasShareGate.Marker(3,"antiqueatlas:google","Far",SOURCE.dimension(),Integer.MIN_VALUE,8,true,false);
        assertThrows(IllegalArgumentException.class,()->new AtlasShareGate().offer(SOURCE,TARGET,outside,0,0));
    }
    @Test void pendingOffersAndRateLimitsStayBounded(){
        var gate=new AtlasShareGate();gate.offer(SOURCE,TARGET,marker(),0,0);
        assertThrows(IllegalArgumentException.class,()->gate.offer(SOURCE,actor(5,12),marker(),0,1));
        assertThrows(IllegalArgumentException.class,()->gate.offer(actor(6,13),TARGET,marker(),0,1));
        var saturated=new AtlasShareGate();
        for(int n=0;n<64;n++)saturated.offer(actor(100+n,1000+n),actor(300+n,2000+n),marker(),0,0);
        assertThrows(IllegalArgumentException.class,()->saturated.offer(SOURCE,TARGET,marker(),0,0));
        assertDoesNotThrow(()->saturated.offer(SOURCE,TARGET,marker(),0,1200));
    }
    @Test void logoutCancelsOffersAndCloneIdsCannotPretendToBeIndependent(){
        var gate=new AtlasShareGate();var offer=gate.offer(SOURCE,TARGET,marker(),0,0);
        gate.disconnected(SOURCE.player());assertTrue(gate.peek(offer.token(),1).isEmpty());
        assertThrows(IllegalArgumentException.class,()->gate.offer(SOURCE,actor(3,10),marker(),0,40));
    }
    @Test void uncertainSendNeverReopensTheConsumedNonce(){
        var gate=new AtlasShareGate();var offer=gate.offer(SOURCE,TARGET,marker(),0,0);
        var accepted=gate.claim(offer.token(),SOURCE,TARGET,marker(),0,1);var writes=new AtomicInteger();
        assertThrows(IllegalStateException.class,()->AtlasShareGate.deliver(accepted,
            ()->{writes.incrementAndGet();return "saved";},(recipient,created)->{throw new IllegalStateException("unknown send outcome");}));
        assertEquals(1,writes.get());assertThrows(IllegalArgumentException.class,
            ()->gate.claim(offer.token(),SOURCE,TARGET,marker(),0,2));
    }
}
