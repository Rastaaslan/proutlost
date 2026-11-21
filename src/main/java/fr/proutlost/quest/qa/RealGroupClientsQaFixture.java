package fr.proutlost.quest.qa;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.bootstrap.*;
import fr.proutlost.quest.core.*;
import java.util.*;

/** New, declared synthetic contract fixture; no canonical quest/author inputs. */
public final class RealGroupClientsQaFixture {
 private RealGroupClientsQaFixture(){}
 public static final String QUEST="QA_REAL_GROUP_CLIENTS_READ";
 public static final FactType FACT=new FactType("proutlost_director:qa_real_group_clients_observed");
 public static final DefinitionId EVENT=new DefinitionId("proutlost_director:qa_real_group_clients/observed");
 public static final ResourceId RECIPE=new ResourceId("proutlost:test_camp_plants");
 public static final QuestCore.Calibration CALIBRATION=new QuestCore.Calibration(2,.6,0);
 public static List<QuestDefinition> quests(){return List.of(new QuestDefinition(QUEST,QUEST,"TEST Real client GROUP native read",Set.of(),Set.of(),"GROUP",Set.of(FACT.value()),
  List.of(Set.of(FACT.value())),"TEST_ONLY native scoreboard groups; synthetic selection metadata","Read the confirmed native QA result","TEST Native result retained",Set.of(),"Read retained QA journal",false));}
 public static CatalogSnapshot catalog(ConfigView config){
  if(config.calibration()!=Calibration.DEFAULT_FOR_TESTING)throw new IllegalArgumentException("QA calibration required");
  return DirectorRealGroupClientsQaCatalog.snapshot();
 }
}
