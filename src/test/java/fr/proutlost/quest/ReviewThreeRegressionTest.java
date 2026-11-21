package fr.proutlost.quest;

import fr.proutlost.quest.core.*;
import fr.proutlost.quest.content.*;
import fr.proutlost.quest.perception.PerceptionSession;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static fr.proutlost.quest.QuestCoreTest.*;

class ReviewThreeRegressionTest {
    @Test void absentActivePlayerCanAcknowledgePublishedChoiceWithoutChangingIt(){
        var q=Campaign.load().stream().filter(d->d.questId().equals("F3")).findFirst().orElseThrow();
        var definition=new QuestDefinition(q.questId(),q.authorTitle(),q.playerTitle(),Set.of(),Set.of(),q.visibilityScope(),q.observations(),q.resolutionConditions(),q.context(),q.action(),q.resolvedSummary(),Set.of(),q.recoveryPolicy(),true);
        var state=new CampaignState(new QuestCore(List.of(definition),new QuestCore.Calibration(2,.6,0)));
        state.core().active(A,0);state.core().active(B,0);state.core().active(C,0);
        var vote=state.prepareVote("F3",new fr.proutlost.quest.choice.CollectiveVote.Rules(0,10,.6),0);
        vote.consult(A);vote.open(Set.of(A,B,C),0);vote.confirm(A,"UNITE",1);vote.close(10);state.applyVote("F3",10);
        assertNotEquals(QuestCore.State.RESOLVED,state.core().state("F3"));
        assertThrows(IllegalStateException.class,()->state.acknowledgeMemoryChoice(B,11));
        vote.consult(B);state.acknowledgeMemoryChoice(B,12);
        assertEquals("UNITE",vote.result());assertEquals(QuestCore.State.RESOLVED,state.core().state("F3"));
    }
    @Test void earlyRuntimeDocumentIsRetainedWithoutRevealingLockedPage(){
        var core=core();var investigation=new Investigation();
        var support=Investigation.content().supports().stream().filter(s->s.id().equals("other_camille")).findFirst().orElseThrow();
        var proof=investigation.examine(core,A,support.id(),support.siteKind(),"archive",0);
        assertEquals(proof,core.evidence(A,proof.id()).orElseThrow());
        assertEquals(QuestCore.State.LOCKED,core.state("A2"));
        assertTrue(core.journal(A).stream().noneMatch(p->p.id().equals("A2")));
        for(var id:List.of("P0","P1","P2","P3","A1"))resolve(core,id,1);
        assertTrue(core.journal(A).stream().filter(p->p.id().equals("A2")).anyMatch(p->p.toString().contains(proof.text())));
    }
    @Test void unrecordedPerceptionSurvivesDisconnectAndCanBeVoluntarilyRecovered(){
        var core=core();var scene=new PerceptionSession("scene",List.of("a","b","c"),List.of("ICI","VOIR","LIER"));
        scene.play(Map.of(A,new PerceptionSession.Position("world",0,0,0),B,new PerceptionSession.Position("world",4,0,0)),1);
        var original=scene.forPlayer(A).getLast();
        var restored=PerceptionSession.restore(scene.save());
        var recalled=restored.forPlayer(A).getLast();assertEquals(original,recalled);
        assertTrue(core.evidence(A,recalled.id()).isEmpty());assertEquals(1,restored.trial());
        restored.record(A,recalled.id(),core);assertTrue(core.evidence(A,recalled.id()).isPresent());
        assertTrue(core.evidence(B,recalled.id()).isEmpty());
    }
    @Test void finaleRejectsMissingUnrelatedAndIncompleteInputs(){
        var fixture=new CampaignActionProgressionTest();
        var state=new CampaignState(new QuestCore(fixture.subset(Set.of("F4","E3","F2","R1")),fixture.calibration));
        var actions=new CampaignActions();
        assertThrows(NoSuchElementException.class,()->actions.finalLink(state,A,"missing","missing",0));
        state.core().observe(new QuestCore.Observation("sound",A,"SOUND_RECORDED","aigu grave moyen","scene",0));
        state.core().observe(new QuestCore.Observation("wrong",A,"SYMBOLS_RECORDED","ICI VOIR LIER","other scene",0));
        assertThrows(IllegalArgumentException.class,()->actions.finalLink(state,A,"sound","wrong",1));
        assertFalse(state.core().hasCollective("FRAGMENTS_LINKED"));
        state.core().observe(new QuestCore.Observation("right",A,"SYMBOLS_RECORDED","ICI VOIR LIER","scene",0));
        actions.finalLink(state,A,"sound","right",1);
        actions.transmit(state,A,"FILTER","transmitter",false,0);
        assertThrows(IllegalStateException.class,()->actions.finalReply(state,A,List.of("LIER","ICI"),"transmitter",2));
        assertThrows(IllegalStateException.class,()->actions.finalReply(state,A,List.of("ICI","LIER","VOIR"),"transmitter",2));
        assertFalse(state.core().hasCollective("COMPLETE_REPLY"));
        actions.finalReply(state,A,PerceptionContent.load().sequence(),"transmitter",3);
        assertTrue(state.core().hasCollective("COMPLETE_REPLY"));
    }
}
