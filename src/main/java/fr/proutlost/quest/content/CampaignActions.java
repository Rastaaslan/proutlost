package fr.proutlost.quest.content;

import com.google.gson.Gson;
import fr.proutlost.quest.core.*;
import fr.proutlost.quest.interaction.Interactions;
import java.util.*;

/** Stateful interaction producers, separate from the quest graph. Adapters validate physical inputs. */
public final class CampaignActions {
    public record Calibration(long sceneIntervalMillis,long activationIntervalMillis,long stabilizationMillis,int workContributors){
        public Calibration{if(sceneIntervalMillis<0||activationIntervalMillis<0||stabilizationMillis<0||workContributors<1)throw new IllegalArgumentException();}
    }
    private Calibration calibration=new Calibration(10000,10000,10000,2);
    public void configure(Calibration value){calibration=Objects.requireNonNull(value);}
    private final Map<String,Set<String>> workPoints=new HashMap<>();
    private final Map<String,Set<UUID>> workers=new HashMap<>();
    private final Map<UUID,QuestCore.Observation> pendingSignals=new HashMap<>();
    private final Set<String> signalRegions=new HashSet<>();
    private final Map<UUID,Integer> dailyOccurrences=new HashMap<>();
    private final Map<UUID,Long> lastOccurrence=new HashMap<>();
    private final Map<String,Long> lastActivation=new HashMap<>();
    private final Map<UUID,String> arrangementComparisons=new HashMap<>();
    private String arrangementId="",arrangementDetail="",transmissionMode="";
    private int activations;
    private long retreatAt=-1;
    private boolean disturbed,repaired,released;
    public boolean released(){return released;}
    private final Set<UUID> heardFinal=new HashSet<>();
    public void recordArrangement(CampaignState state,Interactions.Arrangement arrangement,String id){
        Investigation.requireAccessible(state.core(),"B2",arrangement.authors().iterator().next());
        if(!arrangementId.isEmpty())throw new IllegalStateException("The recorded experiment is immutable");
        state.rememberArrangement(id,arrangement);arrangementId=id;
        arrangementDetail=String.join(" · ",arrangement.slots().stream().map(s->Investigation.content().ordinaryItems().getOrDefault(s.itemId(),Investigation.text("campaign.arrangement.default_item"))).toList());
    }
    public QuestCore.Observation examineAncientArrangement(CampaignState state,UUID player,String location,long now){
        Investigation.requireAccessible(state.core(),"B3",player);
        var original=state.arrangement(arrangementId).orElseThrow();
        if(original.location().equals(location))throw new IllegalArgumentException("Another shelter required");
        String id=player+":ancient:"+arrangementId+":"+location;
        var existing=state.core().evidence(player,id);if(existing.isPresent())return existing.get();
        var o=new QuestCore.Observation(id,player,"ANCIENT_ARRANGEMENT",new Gson().toJson(original.slots()),location,now);
        state.core().observe(o);return o;
    }
    public void prepareArrangement(CampaignState state,UUID player,String observation){
        var o=state.core().evidence(player,observation).orElseThrow();
        if(!o.type().equals("ANCIENT_ARRANGEMENT"))throw new IllegalArgumentException();arrangementComparisons.put(player,observation);
    }
    public boolean compareArrangement(CampaignState state,UUID player,String location,long now){
        Investigation.requireAccessible(state.core(),"B3",player);
        var o=state.core().evidence(player,arrangementComparisons.get(player)).orElseThrow();var original=state.arrangement(arrangementId).orElseThrow();
        if(!o.origin().equals(location)||!o.text().equals(new Gson().toJson(original.slots())))return false;
        emit(state.core(),"ANCIENT_ARRANGEMENT",player,location,now);emit(state.core(),"EXACT_ARRANGEMENT_COMPARISON",player,location,now);return true;
    }
    public String dailyScene(CampaignState state,UUID player,String location,String recentDetail,long now){
        Investigation.requireAccessible(state.core(),"D1",player);
        if(lastOccurrence.containsKey(player)&&now-lastOccurrence.get(player)<calibration.sceneIntervalMillis())throw new IllegalStateException("Wait between occurrences");
        lastOccurrence.put(player,now);int occurrence=dailyOccurrences.merge(player,1,Integer::sum);
        String stable=Investigation.text("campaign.daily_scene.base");
        if(occurrence>=2){emit(state.core(),"COMPARABLE_OCCURRENCES",player,location,now);emit(state.core(),"STABLE_ELEMENT",player,location,now);}
        if(occurrence>=3&&!recentDetail.isBlank()){emit(state.core(),"RECENT_DETAIL_INTEGRATED",player,location,now);return stable+Investigation.text("campaign.daily_scene.recent_prefix")+recentDetail;}
        return stable;
    }
    public String explorePassage(CampaignState state,UUID player,String option,String location,long now){
        Investigation.requireAccessible(state.core(),"D3",player);
        if(!Set.of("REPAIR","DETOUR").contains(option))throw new IllegalArgumentException();
        emit(state.core(),option.equals("REPAIR")?"REPAIR_EXPLORED":"DETOUR_EXPLORED",player,location,now);
        return option.equals("REPAIR")?Investigation.text("campaign.passage.repair"):Investigation.text("campaign.passage.detour");
    }
    public boolean work(CampaignState state,UUID player,String option,String location,boolean changedPreparedPoint,boolean materialSupplied,long now){
        Investigation.requireAccessible(state.core(),"D3",player);
        var vote=state.vote("D3").orElseThrow();
        if(!vote.result().equals(option)||!changedPreparedPoint||!materialSupplied)throw new IllegalStateException("Chosen option, actual intervention and material required");
        if(workPoints.getOrDefault(option,Set.of()).contains(location)&&workers.getOrDefault(option,Set.of()).contains(player))return false;
        workPoints.computeIfAbsent(option,k->new HashSet<>()).add(location);workers.computeIfAbsent(option,k->new HashSet<>()).add(player);
        if(workPoints.get(option).size()>=2&&workers.get(option).size()>=calibration.workContributors())for(var point:new TreeSet<>(workPoints.get(option)))state.completePassage(option,point,player,now);
        return true;
    }
    public String listen(CampaignState state,UUID player,String region,String location,long now){
        Investigation.requireAccessible(state.core(),"E1",player);
        String text=Investigation.text("campaign.signal.base")+(signalRegions.size()>=2?Investigation.text("campaign.signal.extended"):"");
        pendingSignals.put(player,new QuestCore.Observation(UUID.randomUUID().toString(),player,"SIGNAL_FRAGMENT",text,region+"/"+location,now));
        signalRegions.add(region);return text;
    }
    public QuestCore.Observation recordSignal(CampaignState state,UUID player){
        var o=Objects.requireNonNull(pendingSignals.get(player),"Listen first");state.core().observe(o);return o;
    }
    public void compareSignals(CampaignState state,UUID player,String first,String second,long now){
        Investigation.requireAccessible(state.core(),"E1",player);
        var a=state.core().evidence(player,first).orElseThrow();var b=state.core().evidence(player,second).orElseThrow();
        if(!a.type().equals("SIGNAL_FRAGMENT")||!b.type().equals("SIGNAL_FRAGMENT")||!a.text().startsWith(Investigation.text("campaign.signal.base"))||!b.text().startsWith(Investigation.text("campaign.signal.base"))||a.origin().split("/")[0].equals(b.origin().split("/")[0]))throw new IllegalArgumentException("Two regions required");
        for(var type:List.of("SIGNAL_MULTIPLE_REGIONS","COMMON_SOUND_STRUCTURE","NO_LOCAL_SOURCE"))emit(state.core(),type,player,"journal",now);
    }
    public String transmit(CampaignState state,UUID player,String mode,String location,boolean disturbanceRoll,long now){
        Investigation.requireAccessible(state.core(),"E3",player);
        if(!Set.of("FILTER","AMPLIFY").contains(mode))throw new IllegalArgumentException();
        if(!transmissionMode.isEmpty()&&!transmissionMode.equals(mode))throw new IllegalStateException("Use the recorded method");
        if(disturbed&&!repaired&&state.core().state("F2")==QuestCore.State.LOCKED)throw new IllegalStateException("Reach the identified retreat and repair the site");
        if(lastActivation.containsKey(location)&&now-lastActivation.get(location)<calibration.activationIntervalMillis())throw new IllegalStateException("Wait between activations");
        lastActivation.put(location,now);transmissionMode=mode;
        if(state.core().state("F2")!=QuestCore.State.LOCKED){heardFinal.add(player);return Investigation.text("campaign.transmission.final");}
        if(disturbed&&!repaired)throw new IllegalStateException("Reach the identified retreat and repair the site");
        activations++;
        if(mode.equals("FILTER")){
            if(activations>=3)emit(state.core(),"FILTER_TRANSMISSION_COMPLETED",player,location,now);
            return Investigation.text("campaign.transmission.filtered");
        }
        if(activations==1&&disturbanceRoll){disturbed=true;emit(state.core(),"AMPLIFY_DISTURBED",player,location,now);return Investigation.text("campaign.transmission.disturbed");}
        if(!disturbed)emit(state.core(),"AMPLIFY_STABLE_COMPLETED",player,location,now);
        else if(repaired)state.recordRecovery(location,player,now);
        return Investigation.text("campaign.transmission.amplified");
    }
    public void reachRetreat(CampaignState state,UUID player,String location,long now){
        if(!disturbed)throw new IllegalStateException();if(retreatAt<0)retreatAt=now;emit(state.core(),"RETREAT_REACHED",player,location,now);
    }
    public boolean repair(CampaignState state,UUID player,String location,boolean supplied,long now){
        if(!disturbed||!supplied||retreatAt<0||now-retreatAt<calibration.stabilizationMillis()||!state.core().hasCollective("RETREAT_REACHED"))throw new IllegalStateException();
        if(repaired)return false;
        repaired=true;emit(state.core(),"SITE_REPAIRED",player,location,now);return true;
    }
    public void recordFinalTransmission(CampaignState state,UUID player,String location,long now){
        if(!heardFinal.contains(player))throw new IllegalStateException("Receive the intelligible call first");
        if(state.core().state("F2",player)==QuestCore.State.RESOLVED)return;
        for(var type:List.of("INTELLIGIBLE_CALL","COMA_FRAGMENTS","INNER_WORLD_ESTABLISHED"))emit(state.core(),type,player,location,now);
    }
    private final Set<String> verifiedPassagePoints=new HashSet<>();
    public Set<String> retainedPassagePoints(CampaignState state){
        var vote=state.vote("D3").orElseThrow();
        return Set.copyOf(workPoints.getOrDefault(vote.result(),Set.of()));
    }
    public void verifyPassagePoint(CampaignState state,String point,boolean open){
        if(!retainedPassagePoints(state).contains(point))throw new IllegalArgumentException("Unused point");
        if(open)verifiedPassagePoints.add(point);else verifiedPassagePoints.remove(point);
    }
    public boolean passageVerified(CampaignState state){
        var points=retainedPassagePoints(state);return points.size()>=2&&verifiedPassagePoints.containsAll(points);
    }
    public void finalLink(CampaignState state,UUID player,String first,String second,long now){
        Investigation.requireAccessible(state.core(),"F4",player);
        var a=state.core().evidence(player,first).orElseThrow();var b=state.core().evidence(player,second).orElseThrow();
        if(a.type().equals(b.type())||!Set.of(a.type(),b.type()).equals(Set.of("SOUND_RECORDED","SYMBOLS_RECORDED"))||!a.origin().equals(b.origin()))throw new IllegalArgumentException("Unrelated fragments");
        emit(state.core(),"FRAGMENTS_LINKED",player,"journal",now);
    }
    public void finalReply(CampaignState state,UUID player,List<String> sequence,String location,long now){
        Investigation.requireAccessible(state.core(),"F4",player);
        if(transmissionMode.isEmpty()||!state.core().hasCollective("FRAGMENTS_LINKED")||!sequence.equals(PerceptionContent.load().sequence()))throw new IllegalStateException("PARTIAL_RESPONSE");
        emit(state.core(),"COMPLETE_REPLY",player,location,now);
    }
    public void finalEvolve(CampaignState state,UUID player,String point,long now){
        Investigation.requireAccessible(state.core(),"F4",player);
        if(!retainedPassagePoints(state).contains(point)||!verifiedPassagePoints.contains(point))throw new IllegalStateException("Unverified retained point");
        emit(state.core(),"PLACE_EVOLVED",player,point,now);
    }
    /** Must only be invoked by an adapter after applying the three environmental responses. */
    public void confirmRelease(CampaignState state,UUID player,boolean repeatsStopped,boolean passageOpen,boolean signalClear,long now){
        if(!repeatsStopped||!passageOpen||!signalClear||!state.core().hasCollective("FRAGMENTS_LINKED")||!state.core().hasCollective("PLACE_EVOLVED")||!state.core().hasCollective("COMPLETE_REPLY"))throw new IllegalStateException();
        released=true;
        for(var type:List.of("REPEATS_STOPPED","PASSAGES_OPEN","SIGNAL_CLEAR"))emit(state.core(),type,player,"archipelago",now);
    }
    public List<String> epilogue(CampaignState state,UUID player,long now){
        Investigation.requireAccessible(state.core(),"F5",player);var text=state.epilogue();
        if(state.core().state("F5",player)==QuestCore.State.RESOLVED)return text;
        emit(state.core(),"AWAKENING",player,"journal",now);emit(state.core(),"EPILOGUE_RECORDED",player,"journal",now);return text;
    }
    public String recentArrangement(){return arrangementDetail;}
    public String transmissionMode(){return transmissionMode;}
    private static void emit(QuestCore core,String type,UUID player,String location,long now){core.accept(new WorldFact(UUID.randomUUID().toString(),type,"proutlost:campaign_action",Set.of(),"","",location,now,Map.of("actor",player.toString()),true,WorldFact.Category.NARRATIVE));core.evaluate(now);}
    public String save(){return new Gson().toJson(this);}
    public static CampaignActions restore(String json){return new Gson().fromJson(json,CampaignActions.class);}
}
