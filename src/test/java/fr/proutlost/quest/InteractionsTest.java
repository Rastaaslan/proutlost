package fr.proutlost.quest;

import fr.proutlost.quest.interaction.Interactions;
import fr.proutlost.quest.perception.PerceptionSession;
import fr.proutlost.quest.core.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static fr.proutlost.quest.QuestCoreTest.*;

class InteractionsTest {
    @Test void TEST_CAMP_001_002_confirmationRechecks() {
        var rules=new Interactions.CampRules(100,2);
        var valid=new Interactions.CampSignals(101,2,true,true,true);
        assertTrue(Interactions.campCandidate(valid,rules));
        assertTrue(Interactions.claimCamp("camp",A,0,rules,() -> new Interactions.CampSignals(101,2,false,true,true)).isEmpty());
        assertTrue(Interactions.claimCamp("camp",A,0,rules,() -> valid).isPresent());
    }
    @Test void decorativeCampRejected() {
        assertFalse(Interactions.campCandidate(new Interactions.CampSignals(0,0,true,true,true),new Interactions.CampRules(100,2)));
    }
    @Test void distressRequiresVisibilityAndCoastOrHeight() {
        assertFalse(Interactions.distressSignal(() -> new Interactions.Signal(true,true,12,1,false,false),9,.5));
        assertFalse(Interactions.distressSignal(() -> new Interactions.Signal(true,false,12,1,true,false),9,.5));
        assertTrue(Interactions.distressSignal(() -> new Interactions.Signal(true,true,12,1,true,false),9,.5));
        assertTrue(Interactions.distressSignal(() -> new Interactions.Signal(true,true,12,1,false,true),9,.5));
    }
    @Test void TEST_C_003_004_sequenceNeedsBothSharedFragments() {
        var expected=List.of("a","b","c");
        assertEquals(Interactions.Response.PARTIAL_RESPONSE,Interactions.sequenceInput(expected,List.of("a","c","b"),true));
        assertEquals(Interactions.Response.PARTIAL_RESPONSE,Interactions.sequenceInput(expected,expected,false));
        assertEquals(Interactions.Response.COMPLETE_RESPONSE,Interactions.sequenceInput(expected,expected,true));
    }
    @Test void arrangementCanonicalImmutableAndCompareOnSite() {
        var a=Interactions.recordArrangement("camp",List.of(new Interactions.Slot(2,"minecraft:stick"),new Interactions.Slot(0,"minecraft:stone"),new Interactions.Slot(1,"minecraft:dirt")),Set.of(A,B),1,Set.of("minecraft:stick","minecraft:stone","minecraft:dirt"));
        assertEquals(0,a.slots().getFirst().index());assertEquals(Set.of(A,B),a.authors());
        assertThrows(UnsupportedOperationException.class,() -> a.slots().clear());
        var c=Interactions.prepareCompare(A,"first","second","ancient_camp");assertFalse(c.tested());
        assertFalse(Interactions.testCompare(c,"wrong",true).tested());assertTrue(Interactions.testCompare(c,"ancient_camp",true).tested());
    }
    @Test void TEST_C_001_002_perceptionsVoluntaryPrivateRedistributedPersisted() {
        var core=core();var s=new PerceptionSession("scene",List.of("1","2","3"),List.of("ICI","VOIR","LIER"));
        var first=Map.of(A,new PerceptionSession.Position("overworld",0,0,0),B,new PerceptionSession.Position("overworld",5,0,0));
        s.play(first,0);var fa=s.forPlayer(A).getFirst();var fb=s.forPlayer(B).getFirst();
        assertNotEquals(fa.family(),fb.family());assertTrue(core.save().contains("\"observations\":{}"));
        assertThrows(SecurityException.class,() -> s.record(B,fa.id(),core));s.record(A,fa.id(),core);
        assertThrows(IllegalStateException.class,() -> s.play(first,1));
        var restored=PerceptionSession.restore(s.save()); restored.play(Map.of(A,first.get(B),B,first.get(A)),2);
        assertNotEquals(fa.family(),restored.forPlayer(A).getLast().family());assertEquals(fa,restored.forPlayer(A).getFirst());
    }
    @Test void recoveryRetainsEarlierFragment() {
        var s=new PerceptionSession("scene",List.of("1","2","3"),List.of("ICI","VOIR","LIER"));
        s.play(Map.of(A,new PerceptionSession.Position("o",0,0,0),B,new PerceptionSession.Position("o",5,0,0)),0);
        var f=s.forPlayer(A).getFirst();assertThrows(IllegalStateException.class,() -> s.recover(f.family(),C,Set.of(A),2,ignored -> false));
        var recovery=s.recover(f.family(),C,Set.of(C),3,ignored -> false);assertEquals(f.sequence(),recovery.sequence());assertEquals(f,s.forPlayer(A).getFirst());
    }
}
