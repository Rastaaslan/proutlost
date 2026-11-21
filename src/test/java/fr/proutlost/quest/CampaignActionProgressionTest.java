package fr.proutlost.quest;

import fr.proutlost.quest.core.*;
import fr.proutlost.quest.content.*;
import fr.proutlost.quest.choice.CollectiveVote;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static fr.proutlost.quest.QuestCoreTest.*;

/** Producer integration with a focused graph; physical adapter booleans are fixtures, not gameplay proof. */
class CampaignActionProgressionTest {
    List<QuestDefinition> subset(Set<String> ids){return Campaign.load().stream().filter(q->ids.contains(q.questId()))
        .map(q->new QuestDefinition(q.questId(),q.authorTitle(),q.playerTitle(),q.prerequisites().stream().filter(ids::contains).collect(java.util.stream.Collectors.toSet()),
            q.questId().equals("F2")?Set.of("UNAVAILABLE_IN_THIS_FIXTURE"):q.availabilityFacts(),q.visibilityScope(),q.observations(),q.resolutionConditions(),q.context(),q.action(),q.resolvedSummary(),Set.of(),q.recoveryPolicy(),false)).toList();}
    final QuestCore.Calibration calibration=new QuestCore.Calibration(2,.6,0);
    @Test void filterNeedsThreeActivationsAndAmplifyDisturbanceNeedsRetreatRepairAndResumption(){
        var defs=subset(Set.of("E3","R1","F2"));
        var filter=new CampaignState(new QuestCore(defs,calibration));var a=new CampaignActions();
        a.transmit(filter,A,"FILTER","signal",false,0);assertNotEquals(QuestCore.State.RESOLVED,filter.core().state("E3"));
        a.transmit(filter,A,"FILTER","signal",false,10000);a.transmit(filter,B,"FILTER","signal",false,20000);assertEquals(QuestCore.State.RESOLVED,filter.core().state("E3"));
        assertEquals(QuestCore.State.LOCKED,filter.core().state("R1"));
        var disturbed=new CampaignState(new QuestCore(defs,calibration));var actions=new CampaignActions();
        actions.transmit(disturbed,A,"AMPLIFY","signal",true,0);assertNotEquals(QuestCore.State.RESOLVED,disturbed.core().state("E3"));
        assertThrows(IllegalStateException.class,()->actions.repair(disturbed,A,"signal",true,10000));
        actions.reachRetreat(disturbed,B,"refuge",10000);assertThrows(IllegalStateException.class,()->actions.repair(disturbed,A,"signal",true,10001));
        actions.repair(disturbed,A,"signal",true,20000);assertNotEquals(QuestCore.State.RESOLVED,disturbed.core().state("E3"));
        var restoredState=CampaignState.restore(defs,calibration,disturbed.save());var restoredActions=CampaignActions.restore(actions.save());
        restoredActions.transmit(restoredState,B,"AMPLIFY","signal",false,30000);assertEquals(QuestCore.State.RESOLVED,restoredState.core().state("E3"));assertEquals(QuestCore.State.RESOLVED,restoredState.core().state("R1"));
    }
    @Test void calibrationControlsActivationWindowAndRejectsInvalidValues(){
        var state=new CampaignState(new QuestCore(subset(Set.of("E3","R1","F2")),calibration));var actions=new CampaignActions();
        actions.configure(new CampaignActions.Calibration(0,0,0,1));
        actions.transmit(state,A,"FILTER","signal",false,0);actions.transmit(state,A,"FILTER","signal",false,0);actions.transmit(state,A,"FILTER","signal",false,0);
        assertEquals(QuestCore.State.RESOLVED,state.core().state("E3"));
        assertThrows(IllegalArgumentException.class,()->new CampaignActions.Calibration(-1,0,0,1));
    }
    @Test void recordedFinalTransmissionIsIdempotentAfterRestoreAndForLateWitnesses(){
        var defs=subset(Set.of("E3","F2"));var state=new CampaignState(new QuestCore(defs,calibration));var actions=new CampaignActions();
        // Only makes F2 available in this focused fixture; no resolution facts are injected.
        fact(state.core(),"UNAVAILABLE_IN_THIS_FIXTURE",A,0);state.core().evaluate(0);
        assertEquals(QuestCore.State.AVAILABLE,state.core().state("F2"));
        actions.transmit(state,A,"FILTER","transmitter",false,1);actions.recordFinalTransmission(state,A,"transmitter",2);
        assertEquals(QuestCore.State.RESOLVED,state.core().state("F2"));
        var canon=com.google.gson.JsonParser.parseString(state.core().save());
        actions.recordFinalTransmission(state,A,"transmitter",3);assertEquals(canon,com.google.gson.JsonParser.parseString(state.core().save()));
        state=CampaignState.restore(defs,calibration,state.save());actions=CampaignActions.restore(actions.save());
        actions.recordFinalTransmission(state,A,"transmitter",4);assertEquals(canon,com.google.gson.JsonParser.parseString(state.core().save()));
        actions.transmit(state,B,"FILTER","transmitter",false,10001);actions.recordFinalTransmission(state,B,"transmitter",10002);
        assertEquals(canon,com.google.gson.JsonParser.parseString(state.core().save()));
        var restoredState=state;var restoredActions=actions;
        assertThrows(IllegalStateException.class,()->restoredActions.recordFinalTransmission(restoredState,C,"transmitter",10003));
    }
    @ParameterizedTest @CsvSource({"REPAIR,UNITE","REPAIR,DISTRIBUTE","DETOUR,UNITE","DETOUR,DISTRIBUTE"})
    void allChoiceVariantsUseConfirmedWorkAndPersistentFinalContributions(String passage,String memory){
        var defs=subset(Set.of("D3","F3","F4","F5"));var state=new CampaignState(new QuestCore(defs,calibration));var actions=new CampaignActions();
        state.core().active(A,0);state.core().active(B,0);
        actions.explorePassage(state,A,"REPAIR","old_path",0);actions.explorePassage(state,B,"DETOUR","new_path",1);
        var rules=new CollectiveVote.Rules(0,10,.6);var vote=state.prepareVote("D3",rules,2);vote.consult(A);vote.consult(B);vote.open(Set.of(A,B),2);vote.confirm(A,passage,3);vote.confirm(B,passage,4);vote.close(12);state.applyVote("D3",12);
        var originalState=state;var originalActions=actions;
        assertThrows(IllegalStateException.class,()->originalActions.work(originalState,A,passage,"one",false,true,20));
        assertTrue(actions.work(state,A,passage,"one",true,true,20));assertFalse(actions.work(state,A,passage,"one",true,true,20));assertNotEquals(QuestCore.State.RESOLVED,state.core().state("D3"));
        actions.work(state,B,passage,"two",true,true,21);assertEquals(QuestCore.State.RESOLVED,state.core().state("D3"));
        var finalVote=state.prepareVote("F3",rules,30);finalVote.consult(A);finalVote.consult(B);finalVote.open(Set.of(A,B),30);finalVote.confirm(A,memory,31);finalVote.confirm(B,memory,32);finalVote.close(40);state.applyVote("F3",40);
        var transmissionFixture=new CampaignState(new QuestCore(subset(Set.of("E3","R1","F2")),calibration));
        actions.transmit(transmissionFixture,A,"FILTER","transmitter",false,0);actions.transmit(transmissionFixture,A,"FILTER","transmitter",false,10000);actions.transmit(transmissionFixture,A,"FILTER","transmitter",false,20000);
        state.core().observe(new QuestCore.Observation("sound",A,"SOUND_RECORDED","aigu grave moyen","scene",1));
        state.core().observe(new QuestCore.Observation("symbols",A,"SYMBOLS_RECORDED","ICI VOIR LIER","scene",1));
        actions.finalLink(state,A,"sound","symbols",50);
        state=CampaignState.restore(defs,calibration,state.save());actions=CampaignActions.restore(actions.save());
        actions.verifyPassagePoint(state,"one",true);
        actions=CampaignActions.restore(actions.save());
        assertFalse(actions.passageVerified(state));
        actions.verifyPassagePoint(state,"two",true);assertTrue(actions.passageVerified(state));
        actions.finalEvolve(state,B,"two",60);actions.finalReply(state,A,PerceptionContent.load().sequence(),"transmitter",70);
        assertNotEquals(QuestCore.State.RESOLVED,state.core().state("F4"));
        actions.confirmRelease(state,B,true,true,true,80);assertEquals(QuestCore.State.RESOLVED,state.core().state("F4"));
        var epilogue=String.join("\n",actions.epilogue(state,A,90));assertTrue(epilogue.contains(passage));assertTrue(epilogue.contains(memory));assertTrue(epilogue.contains("Alpa se réveille."));assertEquals(QuestCore.State.RESOLVED,state.core().state("F5"));
        var canon=com.google.gson.JsonParser.parseString(state.core().save());
        assertEquals(epilogue,String.join("\n",actions.epilogue(state,B,91)));assertEquals(canon,com.google.gson.JsonParser.parseString(state.core().save()));
        state=CampaignState.restore(defs,calibration,state.save());actions=CampaignActions.restore(actions.save());
        assertEquals(epilogue,String.join("\n",actions.epilogue(state,A,92)));assertEquals(canon,com.google.gson.JsonParser.parseString(state.core().save()));
    }
}
