package fr.proutlost.quest;

import fr.proutlost.quest.core.*;
import fr.proutlost.quest.choice.CollectiveVote;
import fr.proutlost.quest.interaction.Interactions;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static fr.proutlost.quest.QuestCoreTest.*;

class CampaignStateTest {
    CampaignState untilD3() {
        var c=core();c.active(A,0);c.active(B,0);
        for(var id:List.of("P0","P1","P2","P3","A1","A2","A3","B1","B2","B3","C1","C2","C3","J1","D1","D2")) {
            var q=Campaign.load().stream().filter(d->d.questId().equals(id)).findFirst().orElseThrow();fact(c,q.resolutionConditions().getFirst().iterator().next(),B,0);resolve(c,id,0);
        }
        return new CampaignState(c);
    }
    @Test void TEST_MID_001_bothOptionsRequiredBeforeDossier() {
        var state=untilD3();var rules=new CollectiveVote.Rules(0,10,.5);
        assertThrows(IllegalStateException.class,()->state.prepareVote("D3",rules,0));
        fact(state.core(),"REPAIR_EXPLORED",A,0);assertThrows(IllegalStateException.class,()->state.prepareVote("D3",rules,0));
        fact(state.core(),"DETOUR_EXPLORED",B,0);assertNotNull(state.prepareVote("D3",rules,0));
    }
    @Test void TEST_MID_002_onlyWinningWorkCanCompleteAndVoteSurvivesAggregateReload() {
        for(var winner:List.of("REPAIR","DETOUR")) {
            var state=untilD3();fact(state.core(),"REPAIR_EXPLORED",A,0);fact(state.core(),"DETOUR_EXPLORED",B,0);
            var vote=state.prepareVote("D3",new CollectiveVote.Rules(0,10,.5),0);vote.consult(A);vote.consult(B);vote.open(Set.of(A,B),0);vote.confirm(A,winner,1);
            state=CampaignState.restore(Campaign.load(),new QuestCore.Calibration(2,.6,10),state.save());vote=state.vote("D3").orElseThrow();vote.confirm(B,winner,2);vote.close(10);state.applyVote("D3",10);
            assertNotEquals(QuestCore.State.RESOLVED,state.core().state("D3"));
            var current=state;assertThrows(IllegalStateException.class,()->current.completePassage(winner.equals("REPAIR")?"DETOUR":"REPAIR","passage",A,11));
            state.completePassage(winner,"passage",A,11);assertEquals(QuestCore.State.RESOLVED,state.core().state("D3"));
        }
    }
    @Test void TEST_CAMP_003_persistentFirstCampAndIdempotentClaim() {
        var state=new CampaignState(core());resolve(state.core(),"P0",0);
        state.rememberCamp(new Interactions.Camp("home",A,1,new Interactions.CampSignals(100,2,true,true,true)));
        state.rememberCamp(new Interactions.Camp("home",B,2,new Interactions.CampSignals(100,2,true,true,true)));
        state=CampaignState.restore(Campaign.load(),new QuestCore.Calibration(2,.6,10),state.save());assertEquals(QuestCore.State.RESOLVED,state.core().state("P1"));
    }
    @Test void optionalRecoveryRequiresActualDisturbanceRetreatRepair() {
        var s=new CampaignState(core());assertThrows(IllegalStateException.class,()->s.recordRecovery("signal",A,0));
        fact(s.core(),"AMPLIFY_DISTURBED",A,0);fact(s.core(),"RETREAT_REACHED",A,0);assertThrows(IllegalStateException.class,()->s.recordRecovery("signal",A,0));
        fact(s.core(),"SITE_REPAIRED",B,0);s.recordRecovery("signal",B,1);assertTrue(s.core().hasCollective("RECOVERY_COMPLETED"));
    }
    @Test void TEST_FINAL_005_epilogueUsesPersistedChoicesCampsAndTraces() {
        var s=untilD3();fact(s.core(),"REPAIR_EXPLORED",A,0);fact(s.core(),"DETOUR_EXPLORED",B,0);
        var vote=s.prepareVote("D3",new CollectiveVote.Rules(0,10,.5),0);vote.consult(A);vote.open(Set.of(A),0);vote.confirm(A,"DETOUR",1);vote.close(10);s.applyVote("D3",10);s.completePassage("DETOUR","east_passage",A,11);
        s.rememberCamp(new Interactions.Camp("our_home",A,1,new Interactions.CampSignals(100,2,true,true,true)));
        for(var id:List.of("E1","E2","E3","J2","F1","F2")){var q=Campaign.load().stream().filter(d->d.questId().equals(id)).findFirst().orElseThrow();fact(s.core(),q.resolutionConditions().getFirst().iterator().next(),B,20);resolve(s.core(),id,20);}
        assertTrue(s.epilogue().isEmpty());
        var f=s.prepareVote("F3",new CollectiveVote.Rules(0,10,.5),40);f.consult(A);f.consult(B);f.open(Set.of(A,B),40);f.confirm(A,"DISTRIBUTE",41);f.confirm(B,"DISTRIBUTE",41);f.close(50);s.applyVote("F3",50);s.core().evaluate(60);
        fact(s.core(),"FRAGMENTS_LINKED",B,70);resolve(s.core(),"F4",70);s=CampaignState.restore(Campaign.load(),new QuestCore.Calibration(2,.6,10),s.save());
        var epilogue=String.join("\n",s.epilogue());assertTrue(epilogue.contains("DETOUR"));assertTrue(epilogue.contains("DISTRIBUTE"));assertTrue(epilogue.contains("our_home"));assertTrue(epilogue.contains("east_passage"));assertTrue(epilogue.contains("Alpa se réveille"));
    }
}
