package fr.proutlost.director.memory;

import fr.proutlost.director.api.model.*;
import java.time.Instant;
import java.util.*;
import static fr.proutlost.director.memory.MemoryIntegrationTest.*;

/** Pure deterministic J01 fixtures. These are not native/human observations. */
final class CampAbandonmentPolicyTests {
    private static CampWindowContribution window(long number,long start,EpisodeId episode){
        return new CampWindowContribution(new EvidenceWindow(new EvidenceWindowId(id(number)),
            tick(start),tick(start+200),Set.of(episode)),1,new DurationTicks(200),ActivityWindows.emptyHistogram());
    }
    static void run(){
        var rid=new RegionId(id(21000));var aid=new AnchorId(id(21001));
        var r=region(21000,Set.of(aid),0);var a=anchor(21001,rid,0);
        List<CampWindowContribution> old=new ArrayList<>();
        for(int n=0;n<7;n++)old.add(window(21100+n,1000+n*1600L,new EpisodeId(id(21200+n))));
        long last=10800,entry=16800;
        var routine=ActivityWindows.camp(aid,old,tick(entry),settings);
        check(CampAbandonmentPolicy.recognized(routine,tick(entry),settings),
            "genuine retained seven-episode fixture remains a recognized routine at entry");
        Set<ResourceId> unrelated=Set.of(new ResourceId("test:durable_scar"));
        var entered=CampAbandonmentPolicy.enter(unrelated,tick(entry),Set.of(aid));
        check(entered.contains(CampAbandonmentPolicy.FLAG)
            &&CampAbandonmentPolicy.entered(entered).equals(Optional.of(tick(entry)))
            &&CampAbandonmentPolicy.ownedTags(entered).equals(Set.of(aid)),
            "entry stores the actual supplied writer tick and only its owned anchor identity");
        check(CampAbandonmentPolicy.reduceMarked(unrelated,List.of(routine),tick(entry),settings).equals(unrelated),
            "unmarked legacy state is unchanged by the marked-state policy");
        var m=new RegionMemory(header(rid.value(),1,entry),rid,Map.of(),tick(last),tick(0),entered,
            Set.of(),new RegionActivityState(Map.of(aid,routine),Map.of()));
        var before=MemoryProjection.of(all,List.of(new RegionAggregate(r),new RegionMemoryAggregate(m),new AnchorAggregate(a)));
        for(int n=0;n<4;n++){
            long start=17000+n*1600L,end=start+200;
            var observation=new CampEpisodeObservation(new ObservationId(id(21300+n)),rid,aid,
                new EvidenceWindowId(id(21400+n)),new EpisodeId(id(21500+n)),
                tick(start),tick(end),new DurationTicks(200),ActivityWindows.emptyHistogram());
            var reduced=new MemoryReducer(settings).reduce(before,
                batch(end,before,List.of(),List.of(observation),List.of(),Map.of()),
                new DirectorClock(tick(end),0,Instant.EPOCH));
            before=apply(before,reduced);
            var actual=before.regions().getFirst().persistentStateRefs();
            if(n<3)check(actual.contains(CampAbandonmentPolicy.FLAG)
                &&CampAbandonmentPolicy.resumed(actual).isEmpty(),
                "one/two/three genuinely new independent visits do not clear abandoned state");
            else check(!actual.contains(CampAbandonmentPolicy.FLAG)
                &&CampAbandonmentPolicy.resumed(actual).equals(Optional.of(tick(end)))
                &&CampAbandonmentPolicy.ownedTags(actual).equals(Set.of(aid)),
                "four new independent visits and real leave confidence confirm return while retaining cleanup ownership");
        }
        var resumed=before.regions().getFirst().persistentStateRefs();
        check(!CampAbandonmentPolicy.reduceMarked(resumed,List.of(),tick(22200),settings)
            .contains(CampAbandonmentPolicy.FLAG),
            "a durably confirmed return is not undone by later rolling-window eviction");
        var foreign=new AnchorId(id(21999));
        var partial=CampAbandonmentPolicy.cleaned(resumed,Set.of(foreign));
        check(CampAbandonmentPolicy.ownedTags(partial).equals(Set.of(aid))
            &&CampAbandonmentPolicy.entered(partial).isPresent(),
            "cleanup of an unrelated anchor cannot clear owned pending state");
        check(CampAbandonmentPolicy.cleaned(resumed,Set.of(aid)).equals(unrelated),
            "real owned cleanup removes only policy markers and preserves unrelated persistent state");
        List<CampWindowContribution> boundary=new ArrayList<>(old);
        var crossing=new EpisodeId(id(21600));
        boundary.add(window(21700,16700,crossing));
        boundary.add(window(21701,17000,crossing));
        for(int n=0;n<3;n++)boundary.add(window(21702+n,18600+n*1600L,new EpisodeId(id(21800+n))));
        var ambiguous=ActivityWindows.camp(aid,boundary,tick(22000),settings);
        check(!CampAbandonmentPolicy.resumedRoutine(List.of(ambiguous),tick(entry),tick(22000),settings),
            "partial entry bucket and its later continuation cannot become a fourth fresh return episode");
        rejects(()->CampAbandonmentPolicy.reduceMarked(entered,List.of(routine),tick(entry-1),settings),
            "a future entry marker cannot be backdated into current evidence");
    }
}
