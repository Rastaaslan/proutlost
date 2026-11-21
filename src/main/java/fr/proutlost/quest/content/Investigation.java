package fr.proutlost.quest.content;

import com.google.gson.Gson;
import fr.proutlost.quest.core.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Server interaction handlers. Text placeholders are replaceable; evidence identities are immutable. */
public final class Investigation {
    public record Support(String id,String quest,String siteKind,String factType,String text) {}
    public record Comparison(String id,String quest,String firstType,String secondType,String siteKind,List<String> results,String summary) {}
    public record Content(String assetStatus,List<Support> supports,List<Comparison> comparisons,Map<String,String> ordinaryItems,Map<String,String> texts) {
        public Content { texts=Map.copyOf(texts); }
    }
    public record Prepared(String comparison,String first,String second) {}
    private static final Content CONTENT=load();
    private final Map<UUID,Prepared> prepared=new HashMap<>();
    public static Content content(){return CONTENT;}
    public static String text(String key){return Objects.requireNonNull(CONTENT.texts().get(key),"Missing narrative text: "+key);}
    private static Content load(){
        try(var in=Investigation.class.getResourceAsStream("/data/proutlost/quest/investigation.json")){
            if(in==null)throw new IllegalStateException("Missing investigation assets");
            return new Gson().fromJson(new InputStreamReader(in,StandardCharsets.UTF_8),Content.class);
        }catch(IOException e){throw new IllegalStateException(e);}
    }
    public static void requireAccessible(QuestCore core,String quest,UUID player){
        if(core.state(quest,player)==QuestCore.State.LOCKED)throw new IllegalStateException("Investigation unavailable");
    }
    public QuestCore.Observation examine(QuestCore core,UUID player,String support,String siteKind,String location,long now){
        var s=CONTENT.supports().stream().filter(v->v.id().equals(support)).findFirst().orElseThrow();
        if(!s.siteKind().equals(siteKind))throw new IllegalStateException("Wrong support location");
        String id=player+":"+s.id()+":"+location;
        var existing=core.evidence(player,id);if(existing.isPresent())return existing.get();
        var o=new QuestCore.Observation(id,player,s.factType(),s.text(),location,now);core.observe(o);return o;
    }
    public void prepare(QuestCore core,UUID player,String comparison,String first,String second){
        var c=CONTENT.comparisons().stream().filter(v->v.id().equals(comparison)).findFirst().orElseThrow();
        requireAccessible(core,c.quest(),player);
        var a=core.evidence(player,first).orElseThrow();var b=core.evidence(player,second).orElseThrow();
        if(!Set.of(a.type(),b.type()).equals(Set.of(c.firstType(),c.secondType())))throw new IllegalArgumentException("Wrong evidence pair");
        prepared.put(player,new Prepared(comparison,first,second));
    }
    public String preparedSiteKind(UUID player){
        var preparation=Objects.requireNonNull(prepared.get(player));
        return CONTENT.comparisons().stream().filter(c->c.id().equals(preparation.comparison())).findFirst().orElseThrow().siteKind();
    }
    public String compareHere(QuestCore core,UUID player,String siteKind,String location,long now){
        var p=Objects.requireNonNull(prepared.get(player),"Prepare a comparison first");
        var c=CONTENT.comparisons().stream().filter(v->v.id().equals(p.comparison())).findFirst().orElseThrow();
        requireAccessible(core,c.quest(),player);
        if(!c.siteKind().equals(siteKind)||core.evidence(player,p.first()).isEmpty()||core.evidence(player,p.second()).isEmpty())return "PARTIAL_RESPONSE";
        for(var type:c.results())emit(core,type,player,location,now);
        return c.summary();
    }
    public List<String> synthesis(QuestCore core,UUID player,String quest,long now){
        requireAccessible(core,quest,player);
        List<String> prerequisites=switch(quest){case "J1"->List.of("A3","B3","C3");case "J2"->List.of("D3","E3");case "F1"->List.of("J2","A3","B3","C3","E2");default->throw new IllegalArgumentException("Not a notebook convergence");};
        if(prerequisites.stream().anyMatch(id->core.state(id,player)!=QuestCore.State.RESOLVED))throw new IllegalStateException("Missing established knowledge");
        var summaries=core.journal(player).stream().filter(p->prerequisites.contains(p.id())).map(QuestCore.Page::summary).toList();
        emit(core,quest.equals("F1")?"RECIPIENT_CONVERGENCE":quest+"_SYNTHESIS",player,"journal",now);
        return summaries;
    }
    private static void emit(QuestCore core,String type,UUID player,String location,long now){
        core.accept(new WorldFact(UUID.randomUUID().toString(),type,"proutlost:investigation",Set.of(),"","",location,now,Map.of("actor",player.toString()),true,WorldFact.Category.NARRATIVE));core.evaluate(now);
    }
    public String save(){return new Gson().toJson(this);}
    public static Investigation restore(String json){return new Gson().fromJson(json,Investigation.class);}
}
