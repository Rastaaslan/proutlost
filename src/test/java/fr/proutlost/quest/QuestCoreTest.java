package fr.proutlost.quest;

import fr.proutlost.quest.core.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class QuestCoreTest {
    static final UUID A=UUID.fromString("00000000-0000-0000-0000-000000000001"), B=UUID.fromString("00000000-0000-0000-0000-000000000002"), C=UUID.fromString("00000000-0000-0000-0000-000000000003");
    static QuestCore core() { return new QuestCore(Campaign.load(),new QuestCore.Calibration(2,.6,10)); }
    static void fact(QuestCore c, String type, UUID actor, long now) {
        c.accept(new WorldFact(UUID.randomUUID().toString(),type,"test",Set.of(),"","region","location",now,Map.of("actor",actor.toString()),true,WorldFact.Category.NARRATIVE));
    }
    static void resolve(QuestCore c, String id, long now) {
        var q=Campaign.load().stream().filter(x -> x.questId().equals(id)).findFirst().orElseThrow();
        q.resolutionConditions().getFirst().forEach(t -> fact(c,t,A,now)); c.evaluate(now); c.evaluate(now+10);
    }
    @Test void TEST_QCORE_001_002_lockedHiddenAvailableAction() {
        var c=core(); var pages=c.journal(A); assertEquals(1,pages.size());
        assertEquals("P0",pages.getFirst().id()); assertEquals(QuestCore.State.AVAILABLE,pages.getFirst().state()); assertFalse(pages.getFirst().action().isBlank());
    }
    @Test void TEST_QCORE_003_004_005_earlyEvidenceAndAutomaticResolution() {
        var c=core(); fact(c,"WRECK_MOTIF",A,0); assertEquals(1,c.journal(A).size());
        resolve(c,"P0",0); resolve(c,"P1",0); resolve(c,"P2",0);
        assertEquals(QuestCore.State.IN_PROGRESS,c.state("P3")); fact(c,"OLD_CAMP_MOTIF",A,1); c.evaluate(1);
        assertNotEquals(QuestCore.State.RESOLVED,c.state("P3")); fact(c,"MOTIF_EXACT_COMPARISON",A,2); c.evaluate(2);
        assertEquals(QuestCore.State.RESOLVED,c.state("P3"));
        for (var id:List.of("A1","B1","C1")) assertEquals(QuestCore.State.AVAILABLE,c.state(id));
    }
    @Test void TEST_KNOW_001_002_004_privateAndChosenRecipients() {
        var c=core(); c.observe(new QuestCore.Observation("private",A,"SAFE_SHORE_REACHED","private exact text","origin",1));
        assertEquals(1,c.journal(A).getFirst().evidence().size()); assertTrue(c.journal(B).getFirst().evidence().isEmpty());
        c.evaluate(2); assertNotEquals(QuestCore.State.RESOLVED,c.state("P0"));
        assertThrows(SecurityException.class,() -> c.share(B,"private",Set.of(C)));
        c.share(A,"private",Set.of(B));
        assertTrue(c.journal(C).getFirst().evidence().isEmpty()); c.evaluate(2);
        assertEquals(1,c.journal(B).getFirst().evidence().size()); assertEquals(QuestCore.Knowledge.ESTABLISHED_KNOWLEDGE,c.journal(C).getFirst().evidence().getFirst().level());
        assertEquals(QuestCore.State.RESOLVED,c.state("P0"));
    }
    @Test void TEST_QCORE_006_007_KNOW_003_restartAndLateJoiner() {
        var c=core(); resolve(c,"P0",1); c.observe(new QuestCore.Observation("secret",A,"CAMP_CLAIMED","secret","camp",2));
        var restored=QuestCore.restore(Campaign.load(),new QuestCore.Calibration(2,.6,10),c.save());
        assertEquals(c.journal(A),restored.journal(A)); assertEquals(1,restored.news(C,3,true).size());
        assertTrue(restored.news(C,4,false).isEmpty()); assertTrue(restored.journal(C).stream().flatMap(p -> p.evidence().stream()).findAny().isEmpty());
        assertEquals(QuestCore.State.AVAILABLE,restored.state("P1"));
    }
    @Test void immutableEvidenceAndFactIdentity() {
        var c=core(); var o=new QuestCore.Observation("same",A,"SIGNAL_ORIGINAL","old","source",1);
        c.observe(o); c.observe(o);
        assertThrows(IllegalArgumentException.class,() -> c.observe(new QuestCore.Observation("same",A,"SIGNAL_MASKED","new","source",2)));
        var f=new WorldFact("same","x","system",Set.of(),"","r","l",0,Map.of(),true,WorldFact.Category.NARRATIVE); c.accept(f); c.accept(f);
        assertThrows(IllegalArgumentException.class,() -> c.accept(new WorldFact("same","y","system",Set.of(),"","r","l",0,Map.of(),true,WorldFact.Category.NARRATIVE)));
    }
    @Test void TEST_QCORE_008_009_minorityAndCirculation() {
        var c=core(); c.active(A,0);c.active(B,0);c.active(C,0);
        for(var id:List.of("P0","P1","P2","P3","A1","A2","A3","B1","B2","B3","C1","C2","C3")) resolve(c,id,0);
        fact(c,"J1_SYNTHESIS",A,0); c.evaluate(0); c.evaluate(11); assertNotEquals(QuestCore.State.RESOLVED,c.state("J1"));
        fact(c,"J1_SYNTHESIS",B,11);c.evaluate(11);assertEquals(QuestCore.State.RESOLVED,c.state("J1"));
        assertTrue(c.activePlayers(QuestCore.ACTIVE_WINDOW+1).isEmpty());
    }
    @Test void shorelineParticipationCannotRushLaterConvergence() {
        var c=core();var players=new ArrayList<UUID>();
        for(int i=0;i<10;i++){var p=new UUID(0,100+i);players.add(p);c.active(p,0);fact(c,"SAFE_SHORE_REACHED",p,0);}
        c.active(A,0);
        for(var id:List.of("P0","P1","P2","P3","A1","A2","A3","B1","B2","B3","C1","C2","C3"))resolve(c,id,0);
        fact(c,"J1_SYNTHESIS",A,1);c.evaluate(20);
        assertNotEquals(QuestCore.State.RESOLVED,c.state("J1"));
        for(var p:players.subList(0,6))fact(c,"J1_SYNTHESIS",p,20);
        c.evaluate(30);assertEquals(QuestCore.State.RESOLVED,c.state("J1"));
    }
    @Test void TEST_WDQ_001_003_ambientCannotProgress() {
        var c=core(); c.accept(new WorldFact("ambient","SAFE_SHORE_REACHED","independent",Set.of(),"","r","l",0,Map.of(),true,WorldFact.Category.AMBIENT)); c.evaluate(0);
        assertEquals(QuestCore.State.AVAILABLE,c.state("P0"));
    }
    @Test void playerScopedAndGroupScopedFactsCannotBecomePublic() {
        var c=core();
        c.accept(new WorldFact("private","SAFE_SHORE_REACHED","world",Set.of(A),"","r","l",0,Map.of(),true,WorldFact.Category.NARRATIVE));
        c.accept(new WorldFact("group","SAFE_SHORE_REACHED","world",Set.of(),"group","r","l",0,Map.of(),true,WorldFact.Category.NARRATIVE));
        c.evaluate(0); assertEquals(QuestCore.State.AVAILABLE,c.state("P0"));
    }
    @ParameterizedTest @ValueSource(ints={1,2,3,4,5,6})
    void TEST_EARLY_001_004_variableBranchOrderAndRestart(int seed) {
        var c=core(); c.active(A,0); c.active(B,0);
        resolve(c,"P0",0); var early=new ArrayList<>(List.of("P1","P2")); Collections.shuffle(early,new Random(seed)); for(var q:early) resolve(c,q,0);
        resolve(c,"P3",0); var branches=new ArrayList<>(List.of("A","B","C")); Collections.shuffle(branches,new Random(seed));
        for(var branch:branches) { for(int i=1;i<=3;i++) resolve(c,branch+i,0); c=QuestCore.restore(Campaign.load(),new QuestCore.Calibration(2,.6,10),c.save()); }
        fact(c,"J1_SYNTHESIS",A,1); fact(c,"J1_SYNTHESIS",B,1); c.evaluate(1); assertNotEquals(QuestCore.State.RESOLVED,c.state("J1")); c.evaluate(11); assertEquals(QuestCore.State.RESOLVED,c.state("J1"));
    }
    @ParameterizedTest @ValueSource(strings={"FILTER_TRANSMISSION_COMPLETED","AMPLIFY_STABLE_COMPLETED","AMPLIFY_DISTURBED"})
    void TEST_MID_004_005_FINAL_001_004_campaignModelThroughFinale(String transmission) {
        var c=core(); c.active(A,0); c.active(B,0);
        for(var q:Campaign.load()) {
            if(q.questId().equals("R1")) continue;
            if(q.questId().equals("E3")) {
                fact(c,transmission,A,0); c.evaluate(0);
                if(transmission.equals("AMPLIFY_DISTURBED")) { assertNotEquals(QuestCore.State.RESOLVED,c.state("E3")); resolve(c,"R1",0); }
            } else { fact(c,q.resolutionConditions().getFirst().iterator().next(),B,0); resolve(c,q.questId(),0); }
        }
        assertEquals(QuestCore.State.RESOLVED,c.state("F5"));
        if(!transmission.equals("AMPLIFY_DISTURBED")) assertEquals(QuestCore.State.LOCKED,c.state("R1"));
        assertTrue(c.journal(C).stream().anyMatch(p -> p.summary().contains("Alpa se réveille")));
    }
    @Test void TEST_FINAL_002_noNameBeforeF1Convergence() {
        var c=core();c.active(A,0);c.active(B,0);
        for(var q:Campaign.load()) {
            if(q.questId().equals("R1"))continue;
            String journal=c.journal(C).toString();
            if(!c.state("F1").equals(QuestCore.State.RESOLVED)) assertFalse(journal.contains("Alpa"));
            if(q.questId().equals("F1")) break;
            fact(c,q.resolutionConditions().getFirst().iterator().next(),B,0);resolve(c,q.questId(),0);
        }
    }
    @Test void newsCursorDoesNotDropSameMillisecondResolution() {
        var c=core(); resolve(c,"P0",1); assertEquals(1,c.news(C,1,true).size());
        resolve(c,"P1",1); assertEquals("P1",c.news(C,1,false).getFirst().quest());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void restartDoesNotPublishSelectivelySharedEvidenceAddedAfterResolution(boolean emptyPublishedSet) {
        var c=core();
        if(emptyPublishedSet)resolve(c,"P0",0);
        else {
            c.observe(new QuestCore.Observation("initial",A,"SAFE_SHORE_REACHED","QA known","qa",0));
            c.share(A,"initial",Set.of(B));c.evaluate(0);
        }
        assertEquals(QuestCore.State.RESOLVED,c.state("P0"));
        c.observe(new QuestCore.Observation("late",A,"SAFE_SHORE_REACHED","QA late shared only to B","qa",1));
        c.share(A,"late",Set.of(B));c.evaluate(1);
        assertTrue(c.evidence(B,"late").isPresent());assertTrue(c.evidence(C,"late").isEmpty());
        var restored=QuestCore.restore(Campaign.load(),new QuestCore.Calibration(2,.6,10),c.save());
        assertEquals(c.journal(C),restored.journal(C));
        assertTrue(restored.evidence(C,"late").isEmpty());
        assertEquals(c.evidence(B,"late"),restored.evidence(B,"late"));
    }
    @Test void unsupportedSchemaFailsClosed() {
        assertThrows(IllegalArgumentException.class,() -> QuestCore.restore(Campaign.load(),new QuestCore.Calibration(2,.6,10),"{\"schema\":2}"));
    }
}
