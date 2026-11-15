package fr.proutlost.quest.core;

import com.google.gson.Gson;
import fr.proutlost.quest.choice.CollectiveVote;
import fr.proutlost.quest.content.Investigation;
import fr.proutlost.quest.interaction.Interactions;
import fr.proutlost.quest.perception.PerceptionSession;
import java.util.*;

/** Aggregate persistence boundary for canon, in-flight sessions and recorded history. */
public final class CampaignState {
    private final QuestCore core;
    private final Map<String,CollectiveVote> votes=new LinkedHashMap<>();
    private final Map<String,PerceptionSession> perceptions=new LinkedHashMap<>();
    private final Map<String,Interactions.Arrangement> arrangements=new LinkedHashMap<>();
    private final Map<String,Interactions.Camp> camps=new LinkedHashMap<>();
    private final Map<String,String> choices=new LinkedHashMap<>();
    private final Set<String> transformedPlaces=new TreeSet<>();
    private boolean scar;
    public record Snapshot(int schema, String core, Map<String,String> votes, Map<String,String> perceptions,
        Map<String,Interactions.Arrangement> arrangements, Map<String,Interactions.Camp> camps,
        Map<String,String> choices, Set<String> transformedPlaces, boolean scar) {}
    public CampaignState(QuestCore core) { this.core=Objects.requireNonNull(core); }
    public QuestCore core() { return core; }
    public void rememberCamp(Interactions.Camp camp) {
        if(camps.putIfAbsent(camp.location(),camp)!=null)return; emit("camp:"+camp.location(),"CAMP_CLAIMED",camp.claimant(),camp.timestamp());
    }
    public void rememberArrangement(String id, Interactions.Arrangement arrangement) {
        var old=arrangements.putIfAbsent(id,arrangement);
        if(old!=null&&!old.equals(arrangement))throw new IllegalArgumentException("Arrangement is immutable");
        emit("arrangement:"+id,"ARRANGEMENT_RECORDED",arrangement.authors().stream().sorted().findFirst().orElseThrow(),arrangement.timestamp());
    }
    public Optional<Interactions.Arrangement> arrangement(String id) { return Optional.ofNullable(arrangements.get(id)); }
    public PerceptionSession scene(String id, List<String> sounds, List<String> symbols) {
        return perceptions.computeIfAbsent(id,k -> new PerceptionSession(id,sounds,symbols));
    }
    public CollectiveVote prepareVote(String id, CollectiveVote.Rules rules, long now) {
        if(votes.containsKey(id))return votes.get(id);
        if(core.state(id)==QuestCore.State.LOCKED || core.state(id)==QuestCore.State.RESOLVED)throw new IllegalStateException("Choice unavailable");
        CollectiveVote.Dossier dossier;
        if(id.equals("D3")) {
            if(!core.hasCollective("REPAIR_EXPLORED") || !core.hasCollective("DETOUR_EXPLORED"))throw new IllegalStateException("Explore both options first");
            dossier=new CollectiveVote.Dossier(List.of("REPAIR","DETOUR"),Investigation.text("vote.D3.consequences"),Investigation.text("vote.D3.established"),Investigation.text("vote.D3.warning"));
        } else if(id.equals("F3")) dossier=new CollectiveVote.Dossier(List.of("UNITE","DISTRIBUTE"),Investigation.text("vote.F3.consequences"),Investigation.text("vote.F3.established"),Investigation.text("vote.F3.warning"));
        else throw new IllegalArgumentException("Unknown collective choice");
        var vote=new CollectiveVote(id,rules,dossier,now);votes.put(id,vote);return vote;
    }
    public Optional<CollectiveVote> vote(String id) { return Optional.ofNullable(votes.get(id)); }
    public void applyVote(String id,long now) {
        var vote=Objects.requireNonNull(votes.get(id));
        if(vote.phase()!=CollectiveVote.Phase.DECIDED)throw new IllegalStateException("Undecided");
        if(choices.containsKey(id))return;
        choices.put(id,vote.result());
        for(var voter:vote.confirmedVoters()) emit("choice:"+id+":"+voter,id.equals("D3")?"PASSAGE_CHOICE_CONFIRMED":"MEMORY_CHOICE_CONFIRMED",voter,now);
    }
    /** An absent active player can acknowledge the published result without changing the ballot. */
    public void acknowledgeMemoryChoice(UUID player,long now) {
        var vote=votes.get("F3");
        if(vote==null||vote.phase()!=CollectiveVote.Phase.DECIDED||!vote.hasConsulted(player)||!core.activePlayers(now).contains(player))throw new IllegalStateException();
        emit("choice-ack:"+UUID.randomUUID(),"MEMORY_CHOICE_CONFIRMED",player,now);
    }
    public void completePassage(String performedOption,String location,UUID actor,long now) {
        if(!performedOption.equals(choices.get("D3")))throw new IllegalStateException("Only chosen work can complete D3");
        if(!transformedPlaces.add(location))return;emit("passage:"+location,"PASSAGE_WORK_COMPLETED",actor,now);
    }
    public void recordRecovery(String location,UUID actor,long now) {
        if(!core.hasCollective("AMPLIFY_DISTURBED") || !core.hasCollective("RETREAT_REACHED") || !core.hasCollective("SITE_REPAIRED"))
            throw new IllegalStateException("Retreat and repair required");
        if(scar)return;scar=true;transformedPlaces.add(location);emit("recovery:"+location,"RECOVERY_COMPLETED",actor,now);
    }
    /** Visible only after F4. Uses actual saved history; no claim that external world hooks ran. */
    public List<String> epilogue() {
        if(core.state("F4")!=QuestCore.State.RESOLVED)return List.of();
        return List.of(Investigation.text("epilogue.awakening"),Investigation.text("epilogue.passage_prefix")+choices.getOrDefault("D3",Investigation.text("epilogue.not_recorded")),
            Investigation.text("epilogue.memory_prefix")+choices.getOrDefault("F3",Investigation.text("epilogue.not_recorded")),Investigation.text("epilogue.scar_prefix")+scar,
            Investigation.text("epilogue.camps_prefix")+String.join(", ",camps.keySet()),Investigation.text("epilogue.places_prefix")+String.join(", ",transformedPlaces),
            Investigation.text("epilogue.traces_prefix")+String.join(", ",arrangements.keySet()),Investigation.text("epilogue.open_ending"));
    }
    private void emit(String id,String type,UUID actor,long now) {
        core.accept(new WorldFact(id,type,"proutlost:quest_interaction",Set.of(),"","","",now,
            actor==null?Map.of():Map.of("actor",actor.toString()),true,WorldFact.Category.NARRATIVE));core.evaluate(now);
    }
    public String save() {
        var v=new LinkedHashMap<String,String>();votes.forEach((id,s)->v.put(id,s.save()));
        var p=new LinkedHashMap<String,String>();perceptions.forEach((id,s)->p.put(id,s.save()));
        return new Gson().toJson(new Snapshot(1,core.save(),v,p,arrangements,camps,choices,transformedPlaces,scar));
    }
    public static CampaignState restore(List<QuestDefinition> defs,QuestCore.Calibration calibration,String json) {
        var s=new Gson().fromJson(json,Snapshot.class);if(s==null||s.schema()!=1)throw new IllegalArgumentException("Unsupported campaign schema");
        var state=new CampaignState(QuestCore.restore(defs,calibration,s.core()));
        s.votes().forEach((id,v)->state.votes.put(id,CollectiveVote.restore(v)));
        s.perceptions().forEach((id,p)->state.perceptions.put(id,PerceptionSession.restore(p)));
        state.arrangements.putAll(s.arrangements());state.camps.putAll(s.camps());state.choices.putAll(s.choices());
        state.transformedPlaces.addAll(s.transformedPlaces());state.scar=s.scar();return state;
    }
}
