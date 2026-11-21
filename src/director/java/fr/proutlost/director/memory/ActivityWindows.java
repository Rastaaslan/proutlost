package fr.proutlost.director.memory;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** Sufficient-statistic window union. Never sums totals on top of their contributing windows. */
public final class ActivityWindows {
    private ActivityWindows() {}
    public static ActivityHistogram emptyHistogram() { return new ActivityHistogram(Map.of(),Map.of(),Map.of(),Map.of()); }
    private static <K> Map<K,Long> sum(Map<K,Long> a, Map<K,Long> b) {
        Map<K,Long> out = new HashMap<>(a); b.forEach((k,v)->out.merge(k,v,Math::addExact)); return out;
    }
    public static ActivityHistogram sum(ActivityHistogram a, ActivityHistogram b) {
        return new ActivityHistogram(sum(a.sectors(),b.sectors()),sum(a.rings(),b.rings()),sum(a.dayPhases(),b.dayPhases()),sum(a.groups(),b.groups()),sumTime(a.activityTime(),b.activityTime()));
    }
    private static Optional<ActivityTimeCounts> sumTime(Optional<ActivityTimeCounts> a,Optional<ActivityTimeCounts> b) {
        if(a.isEmpty())return b;if(b.isEmpty())return a;
        if(a.get().binCount()!=b.get().binCount())throw new IllegalArgumentException("incompatible indexed time calibration");
        List<Long> counts=new ArrayList<>();
        for(int i=0;i<a.get().binCount();i++)counts.add(Math.addExact(a.get().counts().get(i),b.get().counts().get(i)));
        return Optional.of(new ActivityTimeCounts(counts.size(),counts));
    }
    public static CampRoutineMemory camp(AnchorId anchor, Collection<CampWindowContribution> input,
                                          Tick now, MemorySettings settings) {
        Map<EvidenceWindowId,CampWindowContribution> unique = new HashMap<>();
        for (var w : input) {
            if (w.window().endTick().compareTo(now)>0) throw new IllegalArgumentException("future camp window");
            var old = unique.putIfAbsent(w.window().id(),w);
            if (old != null && !old.equals(w)) throw new IllegalArgumentException("conflicting camp window");
        }
        // Ordinary evidence is a sliding window; durable facts are stored separately and never evicted here.
        var windows = unique.values().stream().sorted(Comparator.comparing((CampWindowContribution w)->w.window().endTick())
                .thenComparing(w->w.window().id().value().toString())).toList();
        windows = windows.subList(Math.max(0,windows.size()-16),windows.size());
        Set<EpisodeId> episodes = new HashSet<>(); long occupied = 0; ActivityHistogram histogram = emptyHistogram();
        double evidence = 0, occupancyMean = 0, occupancyM2 = 0; int count = 0;
        for (var w : windows) {
            if (w.independentVisits()!=w.window().independentEpisodes().size()) throw new IllegalArgumentException("inconsistent visit count");
            Set<EpisodeId> fresh = new HashSet<>(w.window().independentEpisodes()); fresh.removeAll(episodes);
            episodes.addAll(fresh);
            evidence += MemoryMath.decay(fresh.size(),w.window().endTick(),now,settings.ticks("memory.camp_half_life_ticks"));
            occupied = Math.addExact(occupied,w.occupiedTicks().value()); histogram = sum(histogram,w.histogram());
            double occupancy = Math.min(1,(double)w.occupiedTicks().value()/(w.window().endTick().value()-w.window().startTick().value()));
            double delta = occupancy-occupancyMean; occupancyMean += delta/++count; occupancyM2 += delta*(occupancy-occupancyMean);
        }
        double variance = count<2?0:occupancyM2/count;
        double confidence = (1-StrictMath.exp(-evidence/settings.number("pattern.evidence_scale")))/(1+variance);
        if (episodes.size()<settings.count("pattern.camp_min_visits")) confidence=0;
        return new CampRoutineMemory(anchor,windows,episodes.size(),new DurationTicks(occupied),histogram,new UnitValue(confidence));
    }
    public static RouteTransitionMemory route(RouteKey route, Collection<RouteWindowContribution> input) {
        Map<EvidenceWindowId,RouteWindowContribution> unique = new HashMap<>();
        for (var w : input) {
            var old=unique.putIfAbsent(w.window().id(),w);
            if (old!=null&&!old.equals(w)) throw new IllegalArgumentException("conflicting route window");
        }
        var windows=unique.values().stream().sorted(Comparator.comparing((RouteWindowContribution w)->w.window().endTick())
                .thenComparing(w->w.window().id().value().toString())).toList();
        windows=windows.subList(Math.max(0,windows.size()-16),windows.size());
        long total=0; Map<Sector,Long> directions=new EnumMap<>(Sector.class);
        for(var w:windows) { total=Math.addExact(total,w.transitions()); directions=sum(directions,w.directionHistogram()); }
        return new RouteTransitionMemory(route,total,directions,windows);
    }
    public static RegionActivityState merge(RegionActivityState a,RegionActivityState b,Tick now,MemorySettings settings) {
        Map<AnchorId,CampRoutineMemory> camps=new HashMap<>(a.camps());
        b.camps().forEach((id,value)->camps.merge(id,value,(x,y)->{ var windows=new ArrayList<>(x.windows());windows.addAll(y.windows());return camp(id,windows,now,settings); }));
        camps.replaceAll((id,value)->camp(id,value.windows(),now,settings));
        Map<RouteKey,RouteTransitionMemory> routes=new HashMap<>(a.routes());
        b.routes().forEach((id,value)->routes.merge(id,value,(x,y)->{var windows=new ArrayList<>(x.windows());windows.addAll(y.windows());return route(id,windows);}));
        return new RegionActivityState(camps,routes);
    }
}
