package fr.proutlost.quest;

import fr.proutlost.quest.core.*;
import fr.proutlost.quest.perception.PerceptionSession;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static fr.proutlost.quest.QuestCoreTest.*;

class ReviewFourRegressionTest {
    @Test void replacementsKeepTheirOriginTrialEvenWhenTimestampsDiffer(){
        var s=new PerceptionSession("scene",List.of("a","b","c"),List.of("ICI","VOIR","LIER"));
        var left=new PerceptionSession.Position("world",0,0,0);var right=new PerceptionSession.Position("world",4,0,0);
        s.play(Map.of(A,left,B,right),1);var first=s.forPlayer(A).getLast();
        var replacement=s.replaceWitness(B,C,right,Set.of(A,C),2,ignored -> false).orElseThrow();
        assertEquals(1,replacement.trial());s=PerceptionSession.restore(s.save());
        s.play(Map.of(A,right,C,left),3);
        assertFalse(s.comparesTrials(first.id(),replacement.id()));
        assertTrue(s.comparesTrials(first.id(),s.forPlayer(A).getLast().id()));
        assertFalse(s.comparesTrials("unknown",s.forPlayer(A).getLast().id()));
    }
    @Test void legacyRecoveryWithoutTrialIsNeverGuessedFromTimestamp(){
        var s=new PerceptionSession("scene",List.of("a","b","c"),List.of("ICI","VOIR","LIER"));
        var left=new PerceptionSession.Position("world",0,0,0);var right=new PerceptionSession.Position("world",4,0,0);
        s.play(Map.of(A,left,B,right),1);var first=s.forPlayer(A).getLast();var replacement=s.replaceWitness(B,C,right,Set.of(A,C),2,ignored -> false).orElseThrow();
        s.play(Map.of(A,right,C,left),3);var next=s.forPlayer(A).getLast();
        var legacy=com.google.gson.JsonParser.parseString(s.save()).getAsJsonObject();
        legacy.getAsJsonObject("fragments").entrySet().forEach(e->e.getValue().getAsJsonObject().remove("trial"));
        var restored=PerceptionSession.restore(legacy.toString());
        assertTrue(restored.comparesTrials(first.id(),next.id()));
        assertFalse(restored.comparesTrials(replacement.id(),next.id()));
    }
    @Test void supportingEvidenceSurvivesReconnectSharingAndEstablishment(){
        for(var entry:Map.of("A3",List.of("OTHER_STORY_1","OTHER_STORY_2"),"E1",List.of("SIGNAL_FRAGMENT")).entrySet()){
            var core=new InteractionFlowTest().open(entry.getKey());
            for(var type:entry.getValue())core.observe(new QuestCore.Observation(type,A,type,"kept text "+type,"site",1));
            var defs=Campaign.load().stream().filter(q->q.questId().equals(entry.getKey())).map(q->new QuestDefinition(q.questId(),q.authorTitle(),q.playerTitle(),Set.of(),q.availabilityFacts(),q.visibilityScope(),q.observations(),q.resolutionConditions(),q.context(),q.action(),q.resolvedSummary(),Set.of(),q.recoveryPolicy(),false)).toList();
            core=QuestCore.restore(defs,new QuestCore.Calibration(2,.6,0),core.save());
            assertEquals(entry.getValue().size(),core.journal(A).getFirst().evidence().size());
            assertTrue(core.journal(B).getFirst().evidence().isEmpty());
            for(var type:entry.getValue())core.share(A,type,Set.of(B));
            assertEquals(entry.getValue().size(),core.journal(B).getFirst().evidence().size());
            assertTrue(core.journal(C).getFirst().evidence().isEmpty());
            core.observe(new QuestCore.Observation("never_shared",A,entry.getValue().getFirst(),"private","site",1));
            for(var type:defs.getFirst().resolutionConditions().getFirst())fact(core,type,A,2);
            core.evaluate(2);
            var legacy=com.google.gson.JsonParser.parseString(core.save()).getAsJsonObject();
            legacy.remove("establishedEvidence");
            core=QuestCore.restore(defs,new QuestCore.Calibration(2,.6,0),legacy.toString());
            assertTrue(core.evidence(C,"never_shared").isEmpty());
            assertEquals(QuestCore.State.RESOLVED,core.state(entry.getKey()));
            assertEquals(entry.getValue().size(),core.journal(C).getFirst().evidence().size());
        }
    }
}
