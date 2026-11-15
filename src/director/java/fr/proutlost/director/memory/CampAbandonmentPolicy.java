package fr.proutlost.director.memory;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** Derived camp state over genuine retained J01 windows. No visits or clock
 * values are manufactured. Markers preserve entry/return evidence across
 * ordinary regional counter updates and later cleanup of owned anchor tags. */
public final class CampAbandonmentPolicy {
    private CampAbandonmentPolicy(){}
    public static final ResourceId FLAG=new ResourceId("proutlost:camp_abandoned");
    public static final TagId ANCHOR_TAG=new TagId("proutlost:abandoned_camp");
    private static final String ENTERED="proutlost_director:camp_abandoned_since/";
    private static final String RESUMED="proutlost_director:camp_resumed_since/";
    private static final String OWNED="proutlost_director:camp_abandonment_owned_anchor/";
    private static Optional<Tick> timestamp(Set<ResourceId> refs,String prefix){
        Tick found=null;
        for(var ref:refs)if(ref.value().startsWith(prefix)){
            String raw=ref.value().substring(prefix.length());
            Checks.require(raw.matches("0|[1-9][0-9]{0,18}"),"canonical camp state tick");
            var tick=new Tick(Long.parseLong(raw));
            Checks.require(found==null,"duplicate camp state timestamp");found=tick;
        }
        return Optional.ofNullable(found);
    }
    public static Optional<Tick> entered(Set<ResourceId> refs){return timestamp(refs,ENTERED);}
    public static Optional<Tick> resumed(Set<ResourceId> refs){return timestamp(refs,RESUMED);}
    public static Set<AnchorId> ownedTags(Set<ResourceId> refs){
        Set<AnchorId> result=new HashSet<>();
        for(var ref:refs)if(ref.value().startsWith(OWNED)){
            var raw=ref.value().substring(OWNED.length());var id=AnchorId.parse(raw);
            Checks.require(result.add(id),"duplicate owned camp tag");
        }
        return Set.copyOf(result);
    }
    public static boolean recognized(CampRoutineMemory camp,Tick now,MemorySettings settings){
        var current=ActivityWindows.camp(camp.anchorId(),camp.windows(),now,settings);
        return current.independentVisits()>=settings.count("pattern.camp_min_visits")
            &&current.confidence().value()>=settings.number("pattern.camp_confidence_min");
    }
    /** A partial boundary bucket cannot contribute a fresh return episode.
     * Episodes present in an older/boundary bucket are excluded from every
     * later contribution too; only genuinely new independent episodes count. */
    public static boolean resumedRoutine(Collection<CampRoutineMemory> camps,Tick entered,Tick now,
            MemorySettings settings){
        Checks.require(now.compareTo(entered)>=0,"camp state clock rollback");
        for(var camp:camps){
            Set<EpisodeId> boundary=new HashSet<>();
            for(var window:camp.windows())if(window.window().startTick().compareTo(entered)<0)
                boundary.addAll(window.window().independentEpisodes());
            List<CampWindowContribution> fresh=new ArrayList<>();
            for(var window:camp.windows()){
                var w=window.window();
                if(w.startTick().compareTo(entered)<0||w.endTick().compareTo(now)>0)continue;
                Set<EpisodeId> episodes=new HashSet<>(w.independentEpisodes());episodes.removeAll(boundary);
                // This is a conservative calculation view of existing source
                // evidence, never a persisted replacement of the raw windows.
                var bounded=new EvidenceWindow(w.id(),w.startTick(),w.endTick(),episodes);
                fresh.add(new CampWindowContribution(bounded,episodes.size(),
                    window.occupiedTicks(),window.histogram()));
            }
            var recent=ActivityWindows.camp(camp.anchorId(),fresh,now,settings);
            if(recent.independentVisits()>=settings.count("pattern.camp_min_visits")
                &&recent.confidence().value()>=settings.number("pattern.abandonment_leave_confidence"))
                return true;
        }
        return false;
    }
    /** Used by the normal J01 MemoryReducer only for already marked state.
     * Legacy unmarked records keep the reducer's existing behavior. */
    public static Set<ResourceId> reduceMarked(Set<ResourceId> before,
            Collection<CampRoutineMemory> camps,Tick now,MemorySettings settings){
        var entered=entered(before);
        if(entered.isEmpty())return Set.copyOf(before);
        Checks.require(entered.orElseThrow().compareTo(now)<=0,"future camp abandonment marker");
        var resumed=resumed(before);
        if(resumed.isPresent())Checks.require(resumed.orElseThrow().compareTo(entered.orElseThrow())>=0
            &&resumed.orElseThrow().compareTo(now)<=0,"camp return marker clock");
        Set<ResourceId> result=new HashSet<>(before);
        if(resumed.isPresent()||resumedRoutine(camps,entered.orElseThrow(),now,settings)){
            result.remove(FLAG);
            if(resumed.isEmpty())result.add(new ResourceId(RESUMED+now.value()));
        }else result.add(FLAG);
        return Set.copyOf(result);
    }
    /** Caller must prove the fresh complete native absence census, physical
     * loaded camp and current routine confidence before committing these refs. */
    public static Set<ResourceId> enter(Set<ResourceId> before,Tick now,Set<AnchorId> newlyOwnedTags){
        Checks.require(entered(before).isEmpty()&&resumed(before).isEmpty(),"camp state already marked");
        Set<ResourceId> result=new HashSet<>(before);result.add(FLAG);
        result.add(new ResourceId(ENTERED+now.value()));
        for(var id:newlyOwnedTags)result.add(new ResourceId(OWNED+id.value()));
        return Set.copyOf(result);
    }
    /** After a real return, remove only markers for tags actually cleaned.
     * Unloaded remaining anchors keep their ownership markers for later cleanup. */
    public static Set<ResourceId> cleaned(Set<ResourceId> before,Set<AnchorId> cleaned){
        Checks.require(resumed(before).isPresent(),"camp cleanup requires confirmed return state");
        Set<ResourceId> result=new HashSet<>(before);result.remove(FLAG);
        for(var id:cleaned)result.remove(new ResourceId(OWNED+id.value()));
        if(ownedTags(result).isEmpty())result.removeIf(ref->ref.value().startsWith(ENTERED)
            ||ref.value().startsWith(RESUMED));
        return Set.copyOf(result);
    }
}
