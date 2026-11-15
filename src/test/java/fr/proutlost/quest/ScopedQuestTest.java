package fr.proutlost.quest;

import fr.proutlost.quest.core.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static fr.proutlost.quest.QuestCoreTest.*;

class ScopedQuestTest {
    QuestDefinition quest(String id,String scope) {
        return new QuestDefinition(id,id,id,Set.of(),Set.of(),scope,Set.of("READ"),List.of(Set.of("READ")),"Context","Examine","Established",Set.of(),"Revisit",false);
    }
    @Test void personalStateAndPlayerFactStayIsolatedAcrossRestart() {
        var defs=List.of(quest("personal","PERSONAL"));var calibration=new QuestCore.Calibration(2,.6,0);var c=new QuestCore(defs,calibration);
        c.accept(new WorldFact("p","READ","world",Set.of(A),"","r","l",0,Map.of(),true,WorldFact.Category.NARRATIVE));c.evaluate(0);
        assertEquals(QuestCore.State.RESOLVED,c.state("personal",A));assertEquals(QuestCore.State.AVAILABLE,c.state("personal",B));
        var restored=QuestCore.restore(defs,calibration,c.save());assertEquals(c.journal(A),restored.journal(A));assertEquals(c.journal(B),restored.journal(B));
    }
    @Test void groupProofsDoNotResolveAnotherGroupAndPrivateEvidenceStaysPrivate() {
        var defs=List.of(quest("group","GROUP"));var cal=new QuestCore.Calibration(2,.6,0);var c=new QuestCore(defs,cal);
        c.assignGroup(A,"first");c.assignGroup(B,"first");c.assignGroup(C,"second");
        c.observe(new QuestCore.Observation("private",A,"READ","private text","source",0));c.evaluate(0);
        assertNotEquals(QuestCore.State.RESOLVED,c.state("group",B));c.share(A,"private",Set.of(B));c.evaluate(1);
        assertEquals(QuestCore.State.RESOLVED,c.state("group",A));assertEquals(QuestCore.State.RESOLVED,c.state("group",B));
        assertEquals(QuestCore.State.AVAILABLE,c.state("group",C));assertTrue(c.journal(C).getFirst().evidence().isEmpty());
        var restored=QuestCore.restore(defs,cal,c.save());assertEquals(c.journal(B),restored.journal(B));assertEquals(c.journal(C),restored.journal(C));
    }
    @Test void hybridPlayerFactAndGroupCatchUpRespectAddressing() {
        var defs=List.of(quest("personal","PERSONAL"),quest("group","GROUP"));var cal=new QuestCore.Calibration(2,.6,0);var c=new QuestCore(defs,cal);
        c.assignGroup(A,"one");c.assignGroup(B,"one");
        c.accept(new WorldFact("hybrid","READ","world",Set.of(A),"","r","l",0,Map.of(),true,WorldFact.Category.HYBRID));c.evaluate(0);
        assertEquals(QuestCore.State.RESOLVED,c.state("personal",A));assertEquals(QuestCore.State.AVAILABLE,c.state("personal",B));
        c.observe(new QuestCore.Observation("group_proof",A,"READ","shared inside group","source",0));c.share(A,"group_proof",Set.of(B));c.evaluate(1);
        c.assignGroup(C,"one");assertTrue(c.evidence(C,"group_proof").isPresent());assertEquals(1,c.news(C,2,true).size());assertTrue(c.news(C,3,false).isEmpty());
        c.assignGroup(C,"other");assertTrue(c.evidence(C,"group_proof").isEmpty());assertTrue(c.news(C,4,false).isEmpty());
    }
    @Test void publicHybridFactReachesAnAccessiblePersonalConsumerAndSurvivesRestore() {
        var defs=List.of(quest("personal","PERSONAL"));var cal=new QuestCore.Calibration(2,.6,0);
        var c=new QuestCore(defs,cal);c.active(A,0);
        var f=new WorldFact("public_hybrid","READ","qa_world",Set.of(),"","r","l",1,Map.of(),true,WorldFact.Category.HYBRID);
        c.accept(f);assertEquals(f,c.factById(f.factId()).orElseThrow());c.evaluate(1);
        assertEquals(QuestCore.State.RESOLVED,c.state("personal",A));
        var restored=QuestCore.restore(defs,cal,c.save());
        assertEquals(f,restored.factById(f.factId()).orElseThrow());
        assertEquals(QuestCore.State.RESOLVED,restored.state("personal",A));
    }
    @Test void publicHybridFactCannotBypassPersonalPrerequisitesOrReadiness() {
        var before=new QuestDefinition("before","before","before",Set.of(),Set.of(),"SERVER",Set.of("QA_UNLOCK"),
            List.of(Set.of("QA_UNLOCK")),"QA","QA action","QA result",Set.of(),"QA",false);
        var personal=new QuestDefinition("personal","personal","personal",Set.of("before"),Set.of(),"PERSONAL",Set.of("READ"),
            List.of(Set.of("READ")),"QA","QA action","QA result",Set.of(),"QA",false);
        var c=new QuestCore(List.of(before,personal),new QuestCore.Calibration(2,.6,0));c.active(A,0);
        var f=new WorldFact("gated_public","READ","qa_world",Set.of(),"","r","l",1,Map.of(),true,WorldFact.Category.HYBRID);
        c.accept(f);assertTrue(c.factById(f.factId()).isEmpty());
        assertEquals(QuestCore.State.LOCKED,c.state("personal",A));
        c.accept(new WorldFact("unlock","QA_UNLOCK","qa_world",Set.of(),"","r","l",2,Map.of(),true,WorldFact.Category.NARRATIVE));c.evaluate(2);
        c.actionReadiness(id->!id.equals("personal"));c.accept(f);assertTrue(c.factById(f.factId()).isEmpty());
        c.actionReadiness(id->true);c.accept(f);c.evaluate(3);
        assertEquals(QuestCore.State.RESOLVED,c.state("personal",A));
    }
    @Test void publicHybridFixDoesNotWidenAnUnauthorisedPrivateFact() {
        var before=new QuestDefinition("before","before","before",Set.of(),Set.of(),"PERSONAL",Set.of("QA_UNLOCK"),
            List.of(Set.of("QA_UNLOCK")),"QA","QA action","QA result",Set.of(),"QA",false);
        var personal=new QuestDefinition("personal","personal","personal",Set.of("before"),Set.of(),"PERSONAL",Set.of("READ"),
            List.of(Set.of("READ")),"QA","QA action","QA result",Set.of(),"QA",false);
        var cal=new QuestCore.Calibration(2,.6,0);var defs=List.of(before,personal);var c=new QuestCore(defs,cal);
        c.active(A,0);c.active(B,0);
        c.accept(new WorldFact("unlock_a","QA_UNLOCK","qa_world",Set.of(A),"","r","l",1,Map.of(),true,WorldFact.Category.NARRATIVE));c.evaluate(1);
        var wrong=new WorldFact("private_b","READ","qa_world",Set.of(B),"","r","l",2,Map.of(),true,WorldFact.Category.HYBRID);
        c.accept(wrong);assertTrue(c.factById(wrong.factId()).isEmpty());
        var allowed=new WorldFact("private_a","READ","qa_world",Set.of(A),"","r","l",2,Map.of(),true,WorldFact.Category.HYBRID);
        c.accept(allowed);c.evaluate(2);
        assertEquals(QuestCore.State.RESOLVED,c.state("personal",A));assertEquals(QuestCore.State.LOCKED,c.state("personal",B));
        assertFalse(c.hasCollective("READ"));
        var restored=QuestCore.restore(defs,cal,c.save());
        assertEquals(Set.of(A),restored.factById(allowed.factId()).orElseThrow().playerScope());
        assertEquals(QuestCore.State.LOCKED,restored.state("personal",B));
    }
    @Test void newcomerCatchUpNeverRepublishesUnsharedObservation() {
        var c=core();resolve(c,"P0",0);c.observe(new QuestCore.Observation("private",A,"CAMP_CLAIMED","hidden","camp",0));
        assertEquals(1,c.catchUp(C).size());assertTrue(c.catchUp(C).getFirst().evidence().isEmpty());assertFalse(c.catchUp(C).toString().contains("hidden"));
    }
}
