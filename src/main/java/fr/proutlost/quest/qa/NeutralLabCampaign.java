package fr.proutlost.quest.qa;
import com.google.gson.*;
import fr.proutlost.quest.core.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
/** Fixed neutral resource loader. Never accepts a campaign path or production resource. */
public final class NeutralLabCampaign {
 private NeutralLabCampaign(){}
 public record Loaded(String sha256,List<QuestDefinition> definitions,List<String> sounds,List<String> symbols,List<String> options){
  public Loaded{definitions=List.copyOf(definitions);sounds=List.copyOf(sounds);symbols=List.copyOf(symbols);options=List.copyOf(options);}
 }
 private static void require(boolean yes,String why){if(!yes)throw new IllegalArgumentException(why);}
 private static List<String> strings(JsonArray a){return java.util.stream.StreamSupport.stream(a.spliterator(),false).map(JsonElement::getAsString).toList();}
 public static Loaded load(){
  try(var input=NeutralLabCampaign.class.getResourceAsStream("/data/proutlost/quest_qa/steps.json")){
   if(input==null)throw new IllegalStateException("Missing neutral QA fixture");byte[] bytes=input.readNBytes(131073);require(bytes.length<=131072,"neutral fixture byte bound");return decode(bytes);
  }catch(java.io.IOException e){throw new IllegalStateException("Neutral fixture unavailable",e);}
 }
 public static Loaded decode(byte[] bytes){
  require(bytes.length>0&&bytes.length<=131072,"neutral fixture byte bound");
  var root=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();
  require(root.keySet().equals(Set.of("schema_version","id","mode","noNarrative","steps"))&&root.get("schema_version").getAsInt()==1
   &&root.get("id").getAsString().equals("QA_NEUTRAL_CAMPAIGN")&&root.get("mode").getAsString().equals("TEST_ONLY")&&root.get("noNarrative").getAsBoolean(),"neutral fixture guard");
  var ids=List.of("QA-00","QA-01A","QA-01B","QA-02","QA-03","QA-04","QA-05");
  var kinds=List.of("START","PRIVATE_AUDIO","PRIVATE_SYMBOLS","EXPLICIT_SHARE_COMPARE","NEUTRAL_RENDERER","COLLECTIVE_VOTE","FINISH_AND_PERSIST");
  var proofs=List.of("QA_STARTED","SOUND_RECORDED","SYMBOLS_RECORDED","QA_JOINED","proutlost:safe_micro_observed","QA_VOTED","QA_COMPLETE");
  var steps=root.getAsJsonArray("steps");require(steps.size()==7,"exact neutral steps required");var definitions=new ArrayList<QuestDefinition>();
  for(int i=0;i<7;i++){
   var row=steps.get(i).getAsJsonObject();require(row.get("id").getAsString().equals(ids.get(i))&&row.get("kind").getAsString().equals(kinds.get(i))
    &&row.get("proof").getAsString().equals(proofs.get(i))&&row.get("title").getAsString().startsWith("TEST "),"neutral step identity/ingress");
   var parents=new HashSet<>(strings(row.getAsJsonArray("requirements")));
   var expected=i==0?Set.<String>of():i<=2?Set.of("QA-00"):i==3?Set.of("QA-01A","QA-01B"):Set.of(ids.get(i-1));
   require(parents.equals(expected),"neutral prerequisite graph differs");var scope=i>=4?"PERSONAL":"SERVER";require(row.get("scope").getAsString().equals(scope),"neutral privacy scope");
   definitions.add(new QuestDefinition(ids.get(i),ids.get(i),row.get("title").getAsString(),parents,Set.of(),scope,Set.of(proofs.get(i)),List.of(Set.of(proofs.get(i))),
    "Laboratoire fictif TEST_ONLY",row.get("action").getAsString(),"Action QA enregistrée : "+proofs.get(i),Set.of(),"Rappel et journal QA",false));
  }
  Campaign.validate(definitions);var audio=strings(steps.get(1).getAsJsonObject().getAsJsonArray("payload"));
  require(audio.equals(List.of("ALPHA","BRAVO","CHARLIE")),"closed neutral sound vocabulary");
  var symbols=strings(steps.get(2).getAsJsonObject().getAsJsonArray("payload"));require(symbols.equals(List.of("SIGN-A","SIGN-B","SIGN-C")),"closed neutral symbols");
  var options=strings(steps.get(5).getAsJsonObject().getAsJsonArray("options"));require(options.equals(List.of("OPTION_A","OPTION_B")),"closed neutral options");
  try{return new Loaded(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),definitions,List.of("grave","moyen","aigu"),symbols,options);}
  catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
 }
}
