package fr.proutlost.director.memory;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** Pure immutable regional snapshots from a published memory generation and live player census.
 * Run on the calculation worker with immutable inputs, then publish the returned DTO.
 * Cold/missing aggregates remain unknown; this provider never invents terrain or safety risks.
 */
public final class MemorySnapshotProvider {
    private final MemorySettings settings;
    public MemorySnapshotProvider(MemorySettings settings){this.settings=Objects.requireNonNull(settings);}
    public Map<RegionId,RegionSnapshot> regions(MemoryView memory,Map<PlayerId,PlayerSnapshot> players,Tick now) {
        Map<RegionId,RegionMemory> memories=new HashMap<>();memory.regions().forEach(m->memories.put(m.regionId(),m));
        Map<RegionId,RegionSnapshot> result=new TreeMap<>();
        for(var r:memory.regionRecords().values()) {
            if(r.status()!=RegionStatus.ACTIVE&&r.status()!=RegionStatus.DORMANT)continue;
            RegionMemory m=memories.get(r.regionId());
            if(m==null||!memory.anchors().keySet().containsAll(r.anchorIds()))continue;
            Set<TagId> tags=new HashSet<>();
            for(var id:r.anchorIds()){var a=memory.anchors().get(id);if(a.active())tags.addAll(a.tags());}
            Set<PlayerId> present=new HashSet<>();
            players.values().stream().filter(p->p.eligibility()==PlayerEligibility.ELIGIBLE&&p.regionIds().contains(r.regionId())).forEach(p->present.add(p.playerId()));
            List<CampSnapshot> camps=new ArrayList<>();
            for(var camp:m.activity().camps().values().stream().sorted(Comparator.comparing(CampRoutineMemory::anchorId)).toList()) {
                var a=memory.anchors().get(camp.anchorId());if(a==null||!a.active())continue;
                var current=ActivityWindows.camp(camp.anchorId(),camp.windows(),now,settings);var h=current.histogram();
                Map<String,Long> sectors=new TreeMap<>(),phases=new TreeMap<>();
                h.sectors().forEach((k,v)->sectors.put(k.name(),v));h.dayPhases().forEach((k,v)->phases.put(k.name(),v));
                int group=h.groups().entrySet().stream().max(Comparator.<Map.Entry<GroupSizeBucket,Long>>comparingLong(Map.Entry::getValue).thenComparing(Map.Entry::getKey)).map(e->e.getKey().ordinal()).orElse(0);
                camps.add(new CampSnapshot(camp.anchorId(),current.independentVisits(),current.occupiedTicks(),sectors,phases,group,current.confidence(),h.activityTime()));
            }
            result.put(r.regionId(),new RegionSnapshot(r,tags,present,camps,Map.of()));
        }
        return Map.copyOf(result);
    }
    public SnapshotBatch enrich(SnapshotBatch live,MemoryView memory) {
        if(!live.scopes().worldId().equals(memory.scopes().worldId()))throw new IllegalArgumentException("wrong world");
        var c=live.context();var regions=regions(memory,c.players(),live.capturedTick());
        Map<ScopeRef,ScopeSnapshot> scopes=new HashMap<>(c.scopes());Map<ScopeRef,Revision> revisions=new HashMap<>(live.scopeRevisions());
        Set<SensorKind> supplied=Set.of(SensorKind.REGION_TRAITS,SensorKind.REGION_TAGS,SensorKind.ANCHORS,SensorKind.CAMP_ACTIVITY);
        for(var r:regions.values()) {
            var scope=new ScopeRef(ScopeKind.REGION,r.region().regionId().value());var revision=memory.revisions().get(scope);
            Set<SensorKind> missing=new HashSet<>(live.missingData());missing.removeAll(supplied);
            scopes.put(scope,new ScopeSnapshot(scope,revision,supplied,missing,Map.of()));revisions.put(scope,revision);
        }
        Set<SensorKind> missing=new HashSet<>(live.missingData());
        // A resident cache cannot attest completeness of a world-wide query.
        if(!live.scopes().scopes().isEmpty()&&live.scopes().scopes().stream().allMatch(s->s.kind()==ScopeKind.REGION&&regions.containsKey(new RegionId(s.id()))))missing.removeAll(supplied);
        return new SnapshotBatch(live.requestId(),live.scopes(),live.capturedTick(),revisions,
                new ObservationContext(scopes,c.players(),c.groups(),regions,memory.anchors(),memory.locations(),c.terrain(),c.capabilities()),missing,live.loadedChunks(),live.serverHealth());
    }
}
