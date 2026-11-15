package fr.proutlost.quest;
import com.google.gson.*;
import fr.proutlost.quest.qa.NeutralLabCampaign;
import fr.proutlost.quest.core.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
/** Fixture loading/guard regression only; no real Director or multiplayer claim. */
class NeutralLabCampaignTest {
 private static byte[] resource()throws Exception{try(var in=NeutralLabCampaign.class.getResourceAsStream("/data/proutlost/quest_qa/steps.json")){return in.readAllBytes();}}
 @Test void actualPackagedNeutralFixtureIsExecutableAndStableAcrossCoreReopen()throws Exception{
  var loaded=NeutralLabCampaign.load();assertEquals(7,loaded.definitions().size());assertEquals(64,loaded.sha256().length());
  assertEquals(List.of("QA-00","QA-01A","QA-01B","QA-02","QA-03","QA-04","QA-05"),loaded.definitions().stream().map(QuestDefinition::questId).toList());
  var calibration=new QuestCore.Calibration(2,1,0);var a=UUID.randomUUID();var b=UUID.randomUUID();var core=new QuestCore(loaded.definitions(),calibration);
  core.active(a,1000);core.active(b,1000);core.accept(new WorldFact("unit:qa-start","QA_STARTED","unit:neutral",Set.of(),"","","QA_ONLY",1000,Map.of(),true,WorldFact.Category.NARRATIVE));core.evaluate(1000);
  assertEquals(QuestCore.State.RESOLVED,core.state("QA-00"));assertEquals(QuestCore.State.AVAILABLE,core.state("QA-01A"));
  var restored=QuestCore.restore(loaded.definitions(),calibration,core.save());assertEquals(core.state("QA-00"),restored.state("QA-00"));assertEquals(QuestCore.State.LOCKED,restored.state("QA-03",a));
  assertEquals(Set.of("proutlost:safe_micro_observed"),loaded.definitions().get(4).observations());
  assertEquals(List.of("OPTION_A","OPTION_B"),loaded.options());assertEquals(loaded.sha256(),NeutralLabCampaign.decode(resource()).sha256());
 }
 @Test void refusesProductionModeGraphDriftAndForgedDirectorIngress()throws Exception{
  for(String mutation:List.of("mode","proof","parents")){
   var tree=JsonParser.parseString(new String(resource(),StandardCharsets.UTF_8)).getAsJsonObject();
   if(mutation.equals("mode"))tree.addProperty("mode","PRODUCTION");
   if(mutation.equals("proof"))tree.getAsJsonArray("steps").get(4).getAsJsonObject().addProperty("proof","QA_RENDERED");
   if(mutation.equals("parents"))tree.getAsJsonArray("steps").get(6).getAsJsonObject().add("requirements",new JsonArray());
   assertThrows(IllegalArgumentException.class,()->NeutralLabCampaign.decode(tree.toString().getBytes(StandardCharsets.UTF_8)));
  }
 }
}
