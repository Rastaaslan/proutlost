package fr.proutlost.quest;

import fr.proutlost.quest.runtime.*;
import fr.proutlost.quest.core.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ActionReadinessTest {
    @Test void twoSignalAnchorsInSameRegionDoNotMakeInvestigationAvailable(){
        var q=Campaign.load().stream().filter(d->d.questId().equals("E1")).findFirst().orElseThrow();
        var def=new QuestDefinition(q.questId(),q.authorTitle(),q.playerTitle(),Set.of(),Set.of(),"SERVER",q.observations(),q.resolutionConditions(),q.context(),q.action(),q.resolvedSummary(),Set.of(),q.recoveryPolicy(),false);
        var core=new QuestCore(List.of(def),new QuestCore.Calibration(2,.6,0));var sites=new PreparedSites();
        sites.bind("one",new PreparedSites.Site(PreparedSites.Kind.SIGNAL_REGION,"world",0,65,0));sites.bind("two",new PreparedSites.Site(PreparedSites.Kind.SIGNAL_REGION,"world",10,65,10));
        ActionReadiness.refresh(core,sites,false);assertEquals(QuestCore.State.LOCKED,core.state("E1"));
        sites.bind("three",new PreparedSites.Site(PreparedSites.Kind.SIGNAL_REGION,"world",256,65,0));ActionReadiness.refresh(core,sites,false);assertEquals(QuestCore.State.AVAILABLE,core.state("E1"));
    }
    @Test void duplicatePhysicalAnchorCannotCountAsTwoWorkPoints(){
        var sites=new PreparedSites();var site=new PreparedSites.Site(PreparedSites.Kind.PASSAGE,"world",0,65,0);sites.bind("one",site);
        assertThrows(IllegalArgumentException.class,()->sites.bind("alias",site));
    }
}
