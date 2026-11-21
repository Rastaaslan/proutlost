package fr.proutlost.quest;

import fr.proutlost.quest.perception.PerceptionSession;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static fr.proutlost.quest.QuestCoreTest.*;

class PerceptionReplacementTest {
    @Test void absentWitnessReplacementCanCompleteSecondTrialAfterRestore() {
        var scene=new PerceptionSession("c",List.of("1","2","3"),List.of("ICI","VOIR","LIER"));
        var a=new PerceptionSession.Position("island",0,0,0);
        var b=new PerceptionSession.Position("island",5,0,0);
        scene.play(Map.of(A,a,B,b),0);
        var old=scene.forPlayer(B).getFirst();
        assertThrows(IllegalStateException.class,()->scene.replaceWitness(B,C,b,Set.of(A,B,C),1,ignored -> false));
        var replacement=scene.replaceWitness(B,C,b,Set.of(A,C),2,ignored -> false).orElseThrow();
        assertEquals(old.family(),replacement.family()); assertEquals(1,scene.trial());
        var restored=PerceptionSession.restore(scene.save());
        assertThrows(IllegalStateException.class,()->restored.play(Map.of(A,b,C,b),3));
        restored.play(Map.of(A,b,C,a),4);
        assertEquals(2,restored.trial()); assertEquals(old,restored.forPlayer(B).getFirst());
        assertNotEquals(old.family(),restored.forPlayer(C).getLast().family());
        assertNotEquals(restored.forPlayer(A).getLast().family(),restored.forPlayer(C).getLast().family());
    }
    @Test void availableFamilyHolderPreventsDisclosureWithoutBlockingRosterRecovery() {
        UUID replacement = new UUID(0, 4);
        var scene = new PerceptionSession("qa", List.of("note1", "note2", "note3"), List.of("glyph1", "glyph2", "glyph3"));
        var a = new PerceptionSession.Position("qa", 0, 0, 0);
        var b = new PerceptionSession.Position("qa", 5, 0, 0);
        var c = new PerceptionSession.Position("qa", 10, 0, 0);
        scene.play(Map.of(A, a, B, b, C, c), 0);
        var original = scene.forPlayer(A).getFirst();
        assertEquals(original.family(), scene.forPlayer(C).getFirst().family());
        assertTrue(scene.replaceWitness(A, replacement, a, Set.of(B, C, replacement), 1, ignored -> false).isEmpty());
        assertTrue(scene.forPlayer(replacement).isEmpty());
        assertEquals(original, scene.forPlayer(A).getFirst());
        var restored = PerceptionSession.restore(scene.save());
        restored.play(Map.of(B, new PerceptionSession.Position("qa", 8, 0, 0),
            C, new PerceptionSession.Position("qa", 13, 0, 0),
            replacement, new PerceptionSession.Position("qa", 3, 0, 0)), 2);
        assertEquals(2, restored.trial());
        assertEquals(1, restored.forPlayer(replacement).size());
        assertEquals(original, restored.forPlayer(A).getFirst());
    }
    @Test void explicitSharedHolderAlsoPreventsExactRecovery() {
        var scene = new PerceptionSession("qa_shared", List.of("note1", "note2", "note3"), List.of("glyph1", "glyph2", "glyph3"));
        var a = new PerceptionSession.Position("qa", 0, 0, 0);
        var b = new PerceptionSession.Position("qa", 5, 0, 0);
        scene.play(Map.of(A, a, B, b), 0);
        var original = scene.forPlayer(A).getFirst();
        assertNotEquals(original.family(), scene.forPlayer(B).getFirst().family());
        var knowledge = core();
        scene.record(A, original.id(), knowledge);
        knowledge.share(A, original.id(), Set.of(B));
        assertTrue(knowledge.evidence(B, original.id()).isPresent());
        assertTrue(knowledge.evidence(C, original.id()).isEmpty());
        var active = Set.of(B, C);
        java.util.function.Predicate<PerceptionSession.Fragment> availableKnowledge =
            candidate -> active.stream().anyMatch(holder -> knowledge.evidence(holder, candidate.id()).isPresent());
        String before = scene.save();
        assertThrows(IllegalStateException.class, () -> scene.recover(original.family(), C, active, 1, availableKnowledge));
        assertEquals(before, scene.save());
        assertTrue(scene.replaceWitness(A, C, a, active, 2, availableKnowledge).isEmpty());
        assertTrue(scene.forPlayer(C).isEmpty());
        assertEquals(original, scene.forPlayer(A).getFirst());
        var restored = PerceptionSession.restore(scene.save());
        restored.play(Map.of(B, new PerceptionSession.Position("qa", 8, 0, 0),
            C, new PerceptionSession.Position("qa", 3, 0, 0)), 3);
        assertEquals(2, restored.trial());
        assertEquals(1, restored.forPlayer(C).size());
    }
}
