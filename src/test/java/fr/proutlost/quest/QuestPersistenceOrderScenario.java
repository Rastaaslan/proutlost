package fr.proutlost.quest;

import com.google.gson.*;
import fr.proutlost.quest.core.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Pure regression replaying an observed, declared TEST_ONLY native SavedData snapshot.
 * This scenario does not run Minecraft, perform any world effect or certify durability. */
public final class QuestPersistenceOrderScenario {
    private static int checks;
    private QuestPersistenceOrderScenario() {}
    private static final QuestCore.Calibration CAL = new QuestCore.Calibration(2,.6,0);
    private static List<QuestDefinition> definitions() {
        return List.of(new QuestDefinition("QA_PUBLIC_GROUP_READ","QA_PUBLIC_GROUP_READ","TEST Public GROUP native read",Set.of(),Set.of(),"GROUP",
            Set.of("proutlost_director:qa_public_group_observed"),List.of(Set.of("proutlost_director:qa_public_group_observed")),
            "TEST_ONLY declared synthetic groups","Read the confirmed native QA result","TEST Native result retained",Set.of(),"Read retained QA journal",false));
    }
    private static void check(boolean good,String message) { checks++;if(!good)throw new AssertionError(message); }
    private static String sha(String value) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    private static QuestCore liveReplay(String raw) {
        var snapshot = new Gson().fromJson(raw,QuestCore.Snapshot.class);
        var live = new QuestCore(definitions(),CAL);
        snapshot.groups().forEach(live::assignGroup);
        snapshot.facts().values().forEach(live::accept);
        live.evaluate(snapshot.facts().values().iterator().next().timestamp());
        return live;
    }
    private static void orderDifferences(JsonElement before,JsonElement after,String path,List<String> differences) {
        if(before.isJsonObject()&&after.isJsonObject()) {
            var a=before.getAsJsonObject();var b=after.getAsJsonObject();
            if(!new ArrayList<>(a.keySet()).equals(new ArrayList<>(b.keySet()))) differences.add(path);
            for(var key:a.keySet()) if(b.has(key))orderDifferences(a.get(key),b.get(key),path+"/"+key,differences);
        } else if(before.isJsonArray()&&after.isJsonArray()) {
            for(int n=0;n<Math.min(before.getAsJsonArray().size(),after.getAsJsonArray().size());n++)orderDifferences(before.getAsJsonArray().get(n),after.getAsJsonArray().get(n),path+"/"+n,differences);
        }
    }
    public static void main(String[] args) throws Exception {
        if(args.length<2)throw new IllegalArgumentException("mode diagnostic|test|emit and actual-diagnostic-directory required");
        if(args[0].equals("emit-general")){System.out.println(historyFixture().save());return;}
        var dir=Path.of(args[1]);String raw=Files.readString(dir.resolve("quest-core-before-stop.json"));
        String campaignRaw=Files.readString(dir.resolve("quest-campaign-before-stop.json"));
        var restored=QuestCore.restore(definitions(),CAL,raw);
        if(args[0].equals("emit")){System.out.println(restored.save());return;}
        var rawTree=JsonParser.parseString(raw);var restoredTree=JsonParser.parseString(restored.save());
        var differencePaths=new ArrayList<String>();orderDifferences(rawTree,restoredTree,"",differencePaths);
        System.out.println("actual_core_raw_sha256="+sha(raw));
        System.out.println("actual_restore_save_sha256="+sha(restored.save()));
        System.out.println("actual_raw_vs_restore_save_semantic_equal="+rawTree.equals(restoredTree));
        System.out.println("actual_object_order_difference_paths="+differencePaths);
        var live=liveReplay(raw);String liveSave=live.save();String roundTrip=QuestCore.restore(definitions(),CAL,liveSave).save();
        System.out.println("actual_reconstructed_live_vs_readback_bytes_equal="+liveSave.equals(roundTrip));
        System.out.println("actual_reconstructed_live_vs_readback_semantic_equal="+JsonParser.parseString(liveSave).equals(JsonParser.parseString(roundTrip)));
        check(rawTree.equals(restoredTree),"actual restore changed a persisted value (object order excluded only for diagnostic)");
        check(rawTree.equals(JsonParser.parseString(liveSave)),"actual mutation replay changed persisted values");
        var savedSnapshot=new Gson().fromJson(raw,QuestCore.Snapshot.class);
        for(var entry:savedSnapshot.facts().entrySet())check(entry.getValue().equals(restored.factById(entry.getKey()).orElseThrow()),"actual fact changed");
        for(var actor:savedSnapshot.groups().keySet()) {
            check(restored.state("QA_PUBLIC_GROUP_READ",actor)==QuestCore.State.RESOLVED,"actual group resolution missing");
            check(live.journal(actor).equals(restored.journal(actor)),"actual journal changed");
        }
        check(restored.state("QA_PUBLIC_GROUP_READ",new UUID(0,1))==QuestCore.State.LOCKED,"actual ungrouped reader widened");
        var campaign=CampaignState.restore(definitions(),CAL,campaignRaw);
        check(JsonParser.parseString(campaign.save()).getAsJsonObject().get("core").getAsString().equals(restored.save()),"campaign restored another core");
        System.out.println("actual_field_value_checks="+checks+" PASS");
        if(args[0].equals("diagnostic"))return;
        check(liveSave.equals(roundTrip),"actual native-like memory/disk save bytes differ");
        check(restored.save().equals(QuestCore.restore(definitions(),CAL,restored.save()).save()),"repeated actual restore/save unstable");
        check(campaign.save().equals(CampaignState.restore(definitions(),CAL,campaign.save()).save()),"campaign repeated restore/save unstable");
        historyAndUnorderedSetRegression();
        System.out.println("QuestPersistenceOrderScenario checks="+checks+" PASS scope=PURE_ACTUAL_QA_SNAPSHOT_NO_MINECRAFT");
    }
    private static QuestDefinition q(String id,String scope) { return new QuestDefinition(id,id,"TEST "+id,Set.of(),Set.of(),scope,Set.of("READ"),List.of(Set.of("READ")),"TEST","TEST","TEST",Set.of(),"TEST",false); }
    private static List<QuestDefinition> historyDefinitions() { return List.of(q("Z_SERVER","SERVER"),q("A_SERVER","SERVER"),q("Z_GROUP","GROUP"),q("A_GROUP","GROUP")); }
    private static QuestCore historyFixture() {
        var defs=List.of(q("Z_SERVER","SERVER"),q("A_SERVER","SERVER"),q("Z_GROUP","GROUP"),q("A_GROUP","GROUP"));
        var a=UUID.fromString("a699c93c-f3af-3778-9aff-b6861335245d");
        var b=UUID.fromString("b80ff39b-2a4c-3531-8324-aa7764ff90a8");
        var c=UUID.fromString("546a2db8-16c4-3dc5-8ba0-f48145c4e2db");
        var observations=new LinkedHashMap<String,QuestCore.Observation>();
        observations.put("z_obs",new QuestCore.Observation("z_obs",a,"READ","TEST z","TEST",1));
        observations.put("a_obs",new QuestCore.Observation("a_obs",b,"READ","TEST a","TEST",2));
        var order=new LinkedHashMap<String,Long>();order.put("Z_SERVER",3L);order.put("A_SERVER",4L);
        var groupOrder=new LinkedHashMap<String,Long>();groupOrder.put("Z_GROUP",3L);groupOrder.put("A_GROUP",4L);
        var facts=new LinkedHashMap<String,WorldFact>();
        facts.put("z_fact",new WorldFact("z_fact","READ","TEST",Set.of(a,b,c),"","r","l",Long.MAX_VALUE-123,Map.of("z","last","a","first","m","middle"),true,WorldFact.Category.NARRATIVE));
        facts.put("a_fact",new WorldFact("a_fact","READ","TEST",Set.of(c,b),"","r","l",1,Map.of("other","value"),true,WorldFact.Category.NARRATIVE));
        var snapshot=new QuestCore.Snapshot(1,observations,Map.of("z_obs",Set.of(b,c),"a_obs",Set.of(a,c)),facts,
            Map.of(a,2L,b,2L,c,2L),order,Map.of("A_SERVER",2L,"Z_SERVER",1L),Map.of(a,1L),Set.of("z_obs","a_obs"),Map.of(a,"X",b,"X",c,"Y"),
            Map.of("GROUP/X",groupOrder),Map.of("GROUP/X",Map.of("A_GROUP",2L,"Z_GROUP",1L)),Map.of("GROUP/X",Set.of("z_obs","a_obs")),Map.of(a+"/GROUP/X",1));
        return QuestCore.restore(defs,CAL,new Gson().toJson(snapshot));
    }
    private static void historyAndUnorderedSetRegression() {
        var defs=historyDefinitions();var core=historyFixture();
        var a=UUID.fromString("a699c93c-f3af-3778-9aff-b6861335245d");
        var c=UUID.fromString("546a2db8-16c4-3dc5-8ba0-f48145c4e2db");
        String save=core.save();var again=QuestCore.restore(defs,CAL,save);
        check(save.equals(again.save()),"unordered set/map canonical save unstable");
        check(core.news(a,5,false).stream().map(QuestCore.Established::quest).toList().equals(List.of("A_SERVER","A_GROUP")),"news cursor/order changed");
        check(again.news(a,5,false).equals(core.news(a,5,false)),"news cursor changed after restart");
        check(again.journal(a).equals(core.journal(a)),"ordered journal evidence changed");
        var tree=JsonParser.parseString(save).getAsJsonObject();
        check(new ArrayList<>(tree.getAsJsonObject("resolved").keySet()).equals(List.of("Z_SERVER","A_SERVER")),"server history was sorted");
        check(new ArrayList<>(tree.getAsJsonObject("scopedResolved").getAsJsonObject("GROUP/X").keySet()).equals(List.of("Z_GROUP","A_GROUP")),"group history was sorted");
        check(new ArrayList<>(tree.getAsJsonObject("observations").keySet()).equals(List.of("z_obs","a_obs")),"evidence history was sorted");
        check(new ArrayList<>(tree.getAsJsonObject("facts").keySet()).equals(List.of("z_fact","a_fact")),"fact history was sorted");
        check(core.factById("z_fact").equals(again.factById("z_fact")),"fact scope/payload lost");
        check(again.factById("z_fact").orElseThrow().timestamp()==Long.MAX_VALUE-123,"long timestamp rounded");
        boolean refused=false;
        try {var original=again.factById("z_fact").orElseThrow();again.accept(new WorldFact(original.factId(),original.factType(),original.sourceSystem(),original.playerScope(),original.groupScope(),original.regionId(),original.locationId(),original.timestamp()-1,original.payload(),true,original.narrativeVisibility()));}
        catch(IllegalArgumentException expected){refused=true;}
        check(refused,"contradictory persisted fact no longer refused");
        check(save.equals(again.save()),"refused fact mutated stored state");
        core.assignGroup(c,"X");check(!core.save().equals(save),"real group mutation hidden");
        core.active(a,8);check(!core.save().equals(again.save()),"real activity mutation hidden");
    }
}
