package fr.proutlost.quest;

import fr.proutlost.quest.core.*;
import fr.proutlost.quest.content.*;
import fr.proutlost.quest.interaction.Interactions;
import fr.proutlost.ambient.CampRoutineMemory;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static fr.proutlost.quest.QuestCoreTest.*;

/** Isolated producers, not a Minecraft end-to-end test. No helper injecting JSON resolution facts. */
class InteractionFlowTest {
    QuestCore open(String... ids){
        var wanted=Set.of(ids);
        var defs=Campaign.load().stream().filter(q->wanted.contains(q.questId())).map(q->new QuestDefinition(q.questId(),q.authorTitle(),q.playerTitle(),Set.of(),q.availabilityFacts(),q.visibilityScope(),q.observations(),q.resolutionConditions(),q.context(),q.action(),q.resolvedSummary(),Set.of(),q.recoveryPolicy(),false)).toList();
        return new QuestCore(defs,new QuestCore.Calibration(2,.6,0));
    }
    @Test void namesResolveOnlyAfterActualReadsSharesAndTwoOnSiteComparisons() {
        var c=open("A1","A2","A3");var i=new Investigation();
        var a=i.examine(c,A,"daily_1","OLD_CAMP","old",0);var b=i.examine(c,A,"daily_2","OLD_CAMP","old",1);var signature=i.examine(c,A,"signature","OLD_CAMP","old",2);
        for(var o:List.of(a,b,signature))c.share(A,o.id(),Set.of(B));c.evaluate(2);assertEquals(QuestCore.State.RESOLVED,c.state("A1"));
        var other=i.examine(c,B,"other_camille","ARCHIVE","archive",3);c.share(B,other.id(),Set.of(A));
        i.prepare(c,A,"dates",signature.id(),other.id());assertEquals("PARTIAL_RESPONSE",i.compareHere(c,A,"OLD_CAMP","old",4));
        assertNotEquals(QuestCore.State.RESOLVED,c.state("A2"));i.compareHere(c,A,"ARCHIVE","archive",5);
        assertNotEquals(QuestCore.State.RESOLVED,c.state("A2"));
        i=Investigation.restore(i.save());i.prepare(c,A,"family",signature.id(),other.id());i.compareHere(c,A,"ARCHIVE","archive",6);
        assertEquals(QuestCore.State.RESOLVED,c.state("A2"));
    }
    @Test void arrangementsSurviveRestoreAndWrongLocationCannotValidate() {
        var s=new CampaignState(open("B2","B3"));var actions=new CampaignActions();
        var arrangement=new Interactions.Arrangement("camp",List.of(new Interactions.Slot(0,"minecraft:stick"),new Interactions.Slot(1,"minecraft:flint"),new Interactions.Slot(2,"minecraft:feather")),Set.of(A),0);
        actions.recordArrangement(s,arrangement,"our_trace");assertThrows(IllegalStateException.class,()->actions.recordArrangement(s,arrangement,"overwrite"));assertEquals(QuestCore.State.RESOLVED,s.core().state("B2"));
        var o=actions.examineAncientArrangement(s,B,"shelter",1);actions.prepareArrangement(s,B,o.id());
        var restored=CampaignActions.restore(actions.save());assertFalse(restored.compareArrangement(s,B,"camp",2));
        assertTrue(restored.compareArrangement(s,B,"shelter",3));assertEquals(QuestCore.State.RESOLVED,s.core().state("B3"));
    }
    @Test void signalRequiresVoluntaryRecordAndTwoRegions() {
        var s=new CampaignState(open("E1"));var actions=new CampaignActions();actions.listen(s,A,"north","a",0);
        assertNotEquals(QuestCore.State.RESOLVED,s.core().state("E1"));var first=actions.recordSignal(s,A);
        actions.listen(s,A,"north","b",1);var same=actions.recordSignal(s,A);
        assertThrows(IllegalArgumentException.class,()->actions.compareSignals(s,A,first.id(),same.id(),2));
        actions.listen(s,B,"south","c",3);var second=actions.recordSignal(s,B);
        assertThrows(NoSuchElementException.class,()->actions.compareSignals(s,A,first.id(),second.id(),4));
        s.core().share(B,second.id(),Set.of(A));actions.compareSignals(s,A,first.id(),second.id(),5);
        assertEquals(QuestCore.State.RESOLVED,s.core().state("E1"));
    }
    @Test void ambientMemoryExecutesWithoutCoreAndSameReplayCanFeedAccessibleB1() {
        var memory=new CampRoutineMemory();assertTrue(memory.sample("fire",Map.of(A,0,B,1),0,2,10,30).isEmpty());
        memory=CampRoutineMemory.restore(memory.save());var replay=memory.sample("fire",Map.of(A,0,B,1),10,2,10,30).orElseThrow();
        assertEquals(Set.of(A,B),replay.witnesses());
        var c=open("B1");c.accept(new WorldFact("replay","CAMP_ROUTINE_REPLAY_OBSERVED","ambient",Set.of(),"","",replay.location(),replay.timestamp(),Map.of(),true,WorldFact.Category.HYBRID));c.evaluate(10);
        assertEquals(QuestCore.State.RESOLVED,c.state("B1"));assertTrue(memory.sample("fire",Map.of(A,0),11,2,10,30).isEmpty());
    }
    @Test void noActionMeansLockedUntilAdapterBecomesReady() {
        var c=open("A1");c.actionReadiness(q->false);assertTrue(c.journal(A).isEmpty());
        c.actionReadiness(q->true);assertEquals(QuestCore.State.AVAILABLE,c.journal(A).getFirst().state());
    }
    @Test void notebookSynthesisDoesNotBypassMissingBranches() {
        var c=core();assertThrows(IllegalStateException.class,()->new Investigation().synthesis(c,A,"J1",0));
    }
    @Test void complementarySceneDataDoesNotGiveEitherFamilyCompleteSolution() {
        var d=PerceptionContent.load();assertEquals(7,d.supportPitch().size());assertNotEquals(d.symbols(),d.sequence());
        for(var sound:d.sounds())assertTrue(d.supportPitch().values().stream().filter(sound::equals).count()>1);
        assertEquals(d.sounds(),d.sequence().stream().map(d.supportPitch()::get).toList());
    }
}
