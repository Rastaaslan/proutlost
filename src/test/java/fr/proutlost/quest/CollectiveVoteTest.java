package fr.proutlost.quest;

import fr.proutlost.quest.choice.CollectiveVote;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static fr.proutlost.quest.QuestCoreTest.*;

class CollectiveVoteTest {
    CollectiveVote vote() { return new CollectiveVote("D3",new CollectiveVote.Rules(10,20,.6),new CollectiveVote.Dossier(List.of("REPAIR","DETOUR"),"Histoire conservée ou nouvelle exploration","Deux options explorées","Conséquences inconnues ; égalité persistante : tirage annoncé."),0); }
    @Test void TEST_VOTE_001_002_003_eligibilityDuplicateMajority() {
        var v=vote();v.consult(A);v.consult(B);v.consult(C);
        assertThrows(IllegalStateException.class,() -> v.open(Set.of(A,B),9));
        v.open(Set.of(A,B),10);assertThrows(SecurityException.class,() -> v.confirm(C,"REPAIR",11));
        v.confirm(A,"REPAIR",11);assertThrows(IllegalStateException.class,() -> v.confirm(A,"DETOUR",12));
        v.confirm(B,"REPAIR",12);assertEquals(CollectiveVote.Phase.DECIDED,v.close(30));assertEquals("REPAIR",v.result());
    }
    @Test void activeWithoutDossierCannotVote() {
        var v=vote();v.consult(A);v.open(Set.of(A,B),10);
        assertThrows(SecurityException.class,() -> v.confirm(B,"REPAIR",11));
    }
    @Test void TEST_VOTE_004_insufficientParticipationNeverDraws() {
        var v=vote();for(var p:List.of(A,B,C))v.consult(p);v.open(Set.of(A,B,C),10);v.confirm(A,"REPAIR",11);
        assertEquals(CollectiveVote.Phase.RECONSULT,v.close(30));assertTrue(v.result().isEmpty());
        v.open(Set.of(A,B),40);assertEquals(Set.of(A,B),v.eligible());
    }
    @Test void pluralityIsNotAbsoluteMajority() {
        var v=vote();for(var p:List.of(A,B,C))v.consult(p);v.open(Set.of(A,B,C),10);
        v.confirm(A,"REPAIR",11);v.confirm(B,"DETOUR",12);assertEquals(CollectiveVote.Phase.RUNOFF,v.close(30));
    }
    @Test void TEST_VOTE_005_006_tieRunoffRestartAuditableDraw() {
        var v=vote();v.consult(A);v.consult(B);v.open(Set.of(A,B),10);v.confirm(A,"REPAIR",11);v.confirm(B,"DETOUR",12);
        String commitment=v.commitment();assertEquals(CollectiveVote.Phase.RUNOFF,v.close(30));
        v=CollectiveVote.restore(v.save());assertEquals(commitment,v.commitment());
        v.confirm(A,"DETOUR",31);v.confirm(B,"REPAIR",32);
        var second=CollectiveVote.restore(v.save()); assertEquals(CollectiveVote.Phase.DECIDED,v.close(50));second.close(50);
        assertEquals(v.result(),second.result());assertEquals(v.audit(),second.audit());assertTrue(v.audit().contains("drawn=true"));
    }
    @Test void restartRetainsConfirmedBallot() {
        var v=vote();v.consult(A);v.consult(B);v.open(Set.of(A,B),10);v.confirm(A,"REPAIR",11);
        var restored=CollectiveVote.restore(v.save());assertThrows(IllegalStateException.class,() -> restored.confirm(A,"REPAIR",12));
        restored.confirm(B,"REPAIR",12);restored.close(30);assertEquals("REPAIR",restored.result());
    }
}
