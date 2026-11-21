package fr.proutlost.quest.qa;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.bootstrap.*;
import fr.proutlost.quest.core.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** New, declared synthetic contract fixture; no canonical quest/author inputs. */
public final class PublicGroupQaFixture {
 private PublicGroupQaFixture(){}
 public static final String QUEST="QA_PUBLIC_GROUP_READ";
 public static final FactType FACT=new FactType("proutlost_director:qa_public_group_observed");
 public static final DefinitionId EVENT=new DefinitionId("proutlost_director:qa_public_group/observed");
 public static final ResourceId RECIPE=new ResourceId("proutlost:test_camp_plants");
 public static final QuestCore.Calibration CALIBRATION=new QuestCore.Calibration(2,.6,0);
 public static List<QuestDefinition> quests(){return List.of(new QuestDefinition(QUEST,QUEST,"TEST Public GROUP native read",Set.of(),Set.of(),"GROUP",Set.of(FACT.value()),
  List.of(Set.of(FACT.value())),"TEST_ONLY declared synthetic groups","Read the confirmed native QA result","TEST Native result retained",Set.of(),"Read retained QA journal",false));}
 public static CatalogSnapshot catalog(ConfigView config){
  if(config.calibration()!=Calibration.DEFAULT_FOR_TESTING)throw new IllegalArgumentException("QA calibration required");
  return DirectorPublicGroupQaCatalog.snapshot();
 }
 public static UUID actor(WorldId world,String name){return UUID.nameUUIDFromBytes(("PUBLIC_GROUP_QA_V1\0"+world.value()+"\0"+name).getBytes(StandardCharsets.UTF_8));}
}
