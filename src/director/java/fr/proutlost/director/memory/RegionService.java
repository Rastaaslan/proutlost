package fr.proutlost.director.memory;

import fr.proutlost.director.api.model.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Pure region plans; caller commits every returned CAS change in one durable transaction.
 * Birth ticks come from the durable creation ledger, never the mutable updatedTick.
 */
public final class RegionService {
    private final MemorySettings settings;
    public RegionService(MemorySettings settings){this.settings=settings;}
    public List<AggregateChange> discover(RegionId id, AnchorRecord anchor, Tick now) {
        if(!anchor.active()||anchor.evidence()!=EvidenceStatus.CONFIRMED)throw new IllegalArgumentException("confirmed active anchor required");
        var header=MemoryReducer.next(null,id.value(),now);
        var shape=new SupportShape(List.of(),List.of(new SupportDisc(anchor.position(),settings.number(anchor.radiusProfile()))),List.of());
        var record=new RegionRecord(header,id,anchor.dimension(),RegionStatus.ACTIVE,shape,Set.of(anchor.anchorId()),new UnitValue(0),Set.of(),Set.of(),new Revision(1),new RegionPersonality(Map.of()),Set.of(new MemoryId(id.value())));
        var memory=new RegionMemory(header,id,Map.of(),now,new Tick(0),Set.of(),Set.of(),new RegionActivityState(Map.of(),Map.of()));
        Set<RegionId> regions=new HashSet<>(anchor.regionIds());regions.add(id);
        var updated=new AnchorRecord(MemoryReducer.next(anchor.header(),anchor.anchorId().value(),now),anchor.anchorId(),anchor.locationId(),regions,anchor.dimension(),anchor.position(),anchor.radiusProfile(),anchor.tags(),anchor.origin(),anchor.evidence(),anchor.active());
        return List.of(MemoryReducer.change(new RegionAggregate(record),null),MemoryReducer.change(new RegionMemoryAggregate(memory),null),MemoryReducer.change(new AnchorAggregate(updated),anchor.header()));
    }
    /** Discovery requires repeated independent camp evidence, never a newly placed fire alone. */
    public List<AggregateChange> discoverFromCamp(RegionId id,AnchorRecord anchor,List<CampWindowContribution> windows,Tick now) {
        var camp=ActivityWindows.camp(anchor.anchorId(),windows,now,settings);
        if(camp.independentVisits()<settings.count("pattern.camp_min_visits")||camp.confidence().value()<settings.number("pattern.camp_confidence_min"))
            throw new IllegalArgumentException("camp evidence not persistent enough");
        var confirmed=new AnchorRecord(anchor.header(),anchor.anchorId(),anchor.locationId(),anchor.regionIds(),anchor.dimension(),anchor.position(),anchor.radiusProfile(),anchor.tags(),anchor.origin(),EvidenceStatus.CONFIRMED,anchor.active());
        List<AggregateChange> changes=new ArrayList<>(discover(id,confirmed,now));
        for(int i=0;i<changes.size();i++)if(changes.get(i).next() instanceof RegionMemoryAggregate value) {
            var m=value.value();var updated=new RegionMemory(m.header(),m.regionId(),m.components(),now,m.lastEventTick(),m.persistentStateRefs(),m.durableFactRefs(),new RegionActivityState(Map.of(anchor.anchorId(),camp),Map.of()));
            changes.set(i,MemoryReducer.change(new RegionMemoryAggregate(updated),null));
        }
        return List.copyOf(changes);
    }
    public RegionId canonical(RegionId id,Map<RegionId,RegionRecord> records) {
        Set<RegionId> seen=new HashSet<>();
        while(true) {
            if(!seen.add(id))throw new IllegalArgumentException("alias cycle");
            RegionRecord record=Objects.requireNonNull(records.get(id),"unknown region");
            if(record.status()!=RegionStatus.ALIAS)return id;
            final RegionId alias=id;
            var owners=records.values().stream().filter(r->r.status()!=RegionStatus.ALIAS&&r.aliases().contains(alias)).toList();
            if(owners.size()!=1)throw new IllegalArgumentException("alias requires exactly one canonical owner");
            id=owners.getFirst().regionId();
        }
    }
    public Set<RegionId> lineage(RegionId id,Map<RegionId,RegionRecord> records) {
        Set<RegionId> seen=new TreeSet<>();Deque<RegionId> todo=new ArrayDeque<>();todo.add(id);
        while(!todo.isEmpty()) {var next=todo.removeFirst();if(!seen.add(next))continue;
            var r=Objects.requireNonNull(records.get(next),"incomplete lineage");todo.addAll(r.parentIds());todo.addAll(r.aliases());
        }
        return Set.copyOf(seen);
    }
    /** Evidence must remain above both thresholds for the configured persistence interval. */
    public boolean mergeReady(double overlap,double similarity,Tick firstQualifying,Tick now) {
        if(!Double.isFinite(overlap)||!Double.isFinite(similarity)||overlap<0||overlap>1||similarity<0||similarity>1||now.compareTo(firstQualifying)<0)throw new IllegalArgumentException("merge evidence");
        return overlap>=settings.number("region.merge_overlap_min")&&similarity>=settings.number("region.merge_similarity_min")
                &&now.value()-firstQualifying.value()>=settings.ticks("region.merge_persistence_ticks");
    }
    public List<AggregateChange> merge(MemoryView before,Set<RegionId> ids,Map<RegionId,Tick> births,
                                      List<UniqueUseRecord> unique,Tick now) {
        if(ids.size()<2)throw new IllegalArgumentException("merge needs two regions");
        var sorted=ids.stream().sorted(Comparator.<RegionId,Tick>comparing(id->Objects.requireNonNull(births.get(id),"birth ledger missing")).thenComparing(id->id)).toList();
        RegionId survivor=sorted.getFirst();RegionRecord base=Objects.requireNonNull(before.regionRecords().get(survivor));
        Map<RegionId,RegionMemory> memories=new HashMap<>();before.regions().forEach(m->memories.put(m.regionId(),m));
        Set<AnchorId> anchors=new HashSet<>();Set<RegionId> aliases=new HashSet<>(),parents=new HashSet<>();Set<MemoryId> refs=new HashSet<>();
        List<Aabb> boxes=new ArrayList<>();List<SupportDisc> discs=new ArrayList<>();List<RouteSegment> segments=new ArrayList<>();
        Set<ResourceId> states=new HashSet<>();Set<FactId> facts=new HashSet<>();Map<MemoryComponent,DecayValue> components=new EnumMap<>(MemoryComponent.class);
        RegionActivityState activity=new RegionActivityState(Map.of(),Map.of());Tick visit=new Tick(0),event=new Tick(0);
        Map<RegionTrait,Double> traitSums=new EnumMap<>(RegionTrait.class);double importance=0;
        for(RegionId id:sorted) {
            RegionRecord r=Objects.requireNonNull(before.regionRecords().get(id));RegionMemory m=Objects.requireNonNull(memories.get(id),"memory missing");
            if(r.status()!=RegionStatus.ACTIVE||!r.dimension().equals(base.dimension()))throw new IllegalArgumentException("incompatible merge");
            anchors.addAll(r.anchorIds());aliases.addAll(r.aliases());if(!id.equals(survivor))aliases.add(id);parents.addAll(r.parentIds());refs.addAll(r.memoryRefs());
            boxes.addAll(r.supportShape().boxes());discs.addAll(r.supportShape().discs());segments.addAll(r.supportShape().routes());
            states.addAll(m.persistentStateRefs());facts.addAll(m.durableFactRefs());
            for(var entry:settings.decay(m.components(),now).entrySet()) components.merge(entry.getKey(),entry.getValue(),(a,b)->{
                double value=a.value()+b.value();if(!Double.isFinite(value))throw new IllegalArgumentException("merged counter overflow");
                return new DecayValue(value,now,a.halfLifeProfile(),Math.addExact(a.evidenceCount(),b.evidenceCount()),new UnitValue(Math.max(a.confidence().value(),b.confidence().value())));
            });
            activity=ActivityWindows.merge(activity,m.activity(),now,settings);
            visit=visit.compareTo(m.lastVisitTick())>=0?visit:m.lastVisitTick();event=event.compareTo(m.lastEventTick())>=0?event:m.lastEventTick();
            r.personality().traits().forEach((trait,value)->traitSums.merge(trait,value.value()/ids.size(),Double::sum));importance+=r.importance().value()/ids.size();
        }
        // CAMP/ROUTE scalars are projections of the deduplicated window union, never sums of old projections.
        if(!activity.camps().isEmpty()) {
            double occupied=0;long visits=0;double confidence=0;
            for(var camp:activity.camps().values()) {
                visits=Math.addExact(visits,camp.independentVisits());confidence=Math.max(confidence,camp.confidence().value());
                for(var window:camp.windows())occupied+=MemoryMath.decay(window.occupiedTicks().value(),window.window().endTick(),now,settings.ticks("memory.camp_half_life_ticks"));
            }
            components.put(MemoryComponent.CAMP,new DecayValue(occupied,now,"memory.camp_half_life_ticks",visits,new UnitValue(confidence)));
        }
        if(!activity.routes().isEmpty()) {
            double flow=0;long count=0;
            for(var route:activity.routes().values())for(var window:route.windows()) {flow+=MemoryMath.decay(window.transitions(),window.window().endTick(),now,settings.ticks("memory.route_half_life_ticks"));count=Math.addExact(count,window.transitions());}
            components.put(MemoryComponent.ROUTE,new DecayValue(flow,now,"memory.route_half_life_ticks",count,new UnitValue(1)));
        }
        parents.removeAll(ids);aliases.remove(survivor);
        Map<RegionTrait,UnitValue> traits=new EnumMap<>(RegionTrait.class);traitSums.forEach((k,v)->traits.put(k,new UnitValue(Math.min(1,v))));
        var record=new RegionRecord(MemoryReducer.next(base.header(),survivor.value(),now),survivor,base.dimension(),RegionStatus.ACTIVE,
                new SupportShape(boxes.stream().distinct().toList(),discs.stream().distinct().toList(),segments.stream().distinct().toList()),anchors,new UnitValue(Math.min(1,importance)),parents,aliases,base.membershipRevision().plus(1),new RegionPersonality(traits),refs);
        RegionMemory old=memories.get(survivor);
        var memory=new RegionMemory(MemoryReducer.next(old.header(),survivor.value(),now),survivor,components,visit,event,states,facts,activity);
        List<AggregateChange> result=new ArrayList<>();result.add(MemoryReducer.change(new RegionAggregate(record),base.header()));result.add(MemoryReducer.change(new RegionMemoryAggregate(memory),old.header()));
        for(RegionId id:sorted) if(!id.equals(survivor)) {
            var r=before.regionRecords().get(id);
            var alias=new RegionRecord(MemoryReducer.next(r.header(),id.value(),now),id,r.dimension(),RegionStatus.ALIAS,r.supportShape(),r.anchorIds(),r.importance(),r.parentIds(),Set.of(),r.membershipRevision().plus(1),r.personality(),r.memoryRefs());
            result.add(MemoryReducer.change(new RegionAggregate(alias),r.header()));
        }
        for(AnchorId id:anchors) {
            AnchorRecord a=Objects.requireNonNull(before.anchors().get(id),"anchor missing");var regionIds=new HashSet<>(a.regionIds());regionIds.removeAll(ids);regionIds.add(survivor);
            var updated=new AnchorRecord(MemoryReducer.next(a.header(),id.value(),now),id,a.locationId(),regionIds,a.dimension(),a.position(),a.radiusProfile(),a.tags(),a.origin(),a.evidence(),a.active());
            result.add(MemoryReducer.change(new AnchorAggregate(updated),a.header()));
        }
        result.addAll(inheritUnique(unique,ids,Set.of(survivor),now));
        return List.copyOf(result);
    }
    /** Child supports are explicit disjoint anchor assignments. The historical parent remains archived. */
    public List<AggregateChange> split(MemoryView before,RegionId parent,Map<RegionId,Set<AnchorId>> assignments,
                                       Map<RouteKey,RegionId> routeOwners,List<UniqueUseRecord> unique,Tick now) {
        return split(before,parent,assignments,routeOwners,Map.of(),Map.of(),unique,now);
    }
    public List<AggregateChange> split(MemoryView before,RegionId parent,Map<RegionId,Set<AnchorId>> assignments,
            Map<RouteKey,RegionId> routeOwners,Map<Aabb,RegionId> boxOwners,Map<RouteSegment,RegionId> segmentOwners,List<UniqueUseRecord> unique,Tick now) {
        RegionRecord old=Objects.requireNonNull(before.regionRecords().get(parent));
        if(!boxOwners.keySet().equals(new HashSet<>(old.supportShape().boxes()))||!segmentOwners.keySet().equals(new HashSet<>(old.supportShape().routes()))
                ||!assignments.keySet().containsAll(boxOwners.values())||!assignments.keySet().containsAll(segmentOwners.values()))throw new IllegalArgumentException("explicit ownership required for every non-anchor support");
        RegionMemory memory=before.regions().stream().filter(m->m.regionId().equals(parent)).findFirst().orElseThrow();
        if(old.status()!=RegionStatus.ACTIVE||assignments.size()<2)throw new IllegalArgumentException("split active region into >=2 children");
        Set<AnchorId> all=new HashSet<>();
        for(var entry:assignments.entrySet()) {
            if(before.regionRecords().containsKey(entry.getKey())||entry.getValue().isEmpty())throw new IllegalArgumentException("children need new identities and supports");
            for(AnchorId anchor:entry.getValue())if(!all.add(anchor))throw new IllegalArgumentException("anchor duplicated in split");
        }
        if(!all.equals(old.anchorIds()))throw new IllegalArgumentException("split must account for all anchors");
        if(!routeOwners.keySet().equals(memory.activity().routes().keySet())||!assignments.keySet().containsAll(routeOwners.values()))throw new IllegalArgumentException("every route requires one child owner");
        List<AggregateChange> result=new ArrayList<>();
        var archived=new RegionRecord(MemoryReducer.next(old.header(),parent.value(),now),parent,old.dimension(),RegionStatus.ARCHIVED,old.supportShape(),old.anchorIds(),old.importance(),old.parentIds(),old.aliases(),old.membershipRevision().plus(1),old.personality(),old.memoryRefs());
        result.add(MemoryReducer.change(new RegionAggregate(archived),old.header()));
        for(RegionId child:new TreeSet<>(assignments.keySet())) {
            Set<AnchorId> anchors=assignments.get(child);List<SupportDisc> discs=new ArrayList<>();
            for(AnchorId id:anchors) {
                AnchorRecord a=Objects.requireNonNull(before.anchors().get(id));discs.add(new SupportDisc(a.position(),settings.number(a.radiusProfile())));
                Set<RegionId> regions=new HashSet<>(a.regionIds());regions.remove(parent);regions.add(child);
                var updated=new AnchorRecord(MemoryReducer.next(a.header(),id.value(),now),id,a.locationId(),regions,a.dimension(),a.position(),a.radiusProfile(),a.tags(),a.origin(),a.evidence(),a.active());
                result.add(MemoryReducer.change(new AnchorAggregate(updated),a.header()));
            }
            var r=new RegionRecord(MemoryReducer.next(null,child.value(),now),child,old.dimension(),RegionStatus.ACTIVE,new SupportShape(old.supportShape().boxes().stream().filter(b->child.equals(boxOwners.get(b))).toList(),discs,old.supportShape().routes().stream().filter(segment->child.equals(segmentOwners.get(segment))).toList()),anchors,old.importance(),Set.of(parent),Set.of(),new Revision(1),old.personality(),Set.of(new MemoryId(child.value())));
            Map<MemoryComponent,DecayValue> counters=new EnumMap<>(MemoryComponent.class);
            double share=(double)anchors.size()/all.size();
            settings.decay(memory.components(),now).forEach((k,v)->counters.put(k,new DecayValue(v.value()*share,now,v.halfLifeProfile(),v.evidenceCount(),v.confidence())));
            Map<AnchorId,CampRoutineMemory> camps=new HashMap<>();memory.activity().camps().forEach((id,c)->{if(anchors.contains(id))camps.put(id,c);});
            Map<RouteKey,RouteTransitionMemory> routes=new HashMap<>();memory.activity().routes().forEach((key,v)->{if(child.equals(routeOwners.get(key))) {
                RouteKey target=new RouteKey(child,key.to());routes.put(target,ActivityWindows.route(target,v.windows()));
            }});
            var m=new RegionMemory(MemoryReducer.next(null,child.value(),now),child,counters,memory.lastVisitTick(),memory.lastEventTick(),memory.persistentStateRefs(),memory.durableFactRefs(),new RegionActivityState(camps,routes));
            result.add(MemoryReducer.change(new RegionAggregate(r),null));result.add(MemoryReducer.change(new RegionMemoryAggregate(m),null));
        }
        result.addAll(inheritUnique(unique,Set.of(parent),assignments.keySet(),now));
        return List.copyOf(result);
    }
    private List<AggregateChange> inheritUnique(List<UniqueUseRecord> input,Set<RegionId> origins,Set<RegionId> targets,Tick now) {
        Set<UUID> originIds=new HashSet<>();origins.forEach(id->originIds.add(id.value()));
        Map<DefinitionId,List<UniqueUseRecord>> byDefinition=new TreeMap<>();
        for(var u:input) if(u.scopeKind()==UniquenessScope.ONCE_PER_REGION&&(originIds.contains(u.canonicalScopeId())||!Collections.disjoint(originIds,u.originScopeIds()))) {
            if(u.status()==UniqueStatus.RESERVED)throw new IllegalArgumentException("defer lineage change while uniqueness reserved");
            if(u.status()==UniqueStatus.RELEASED_BEFORE_EFFECT)continue;
            byDefinition.computeIfAbsent(u.definitionId(),k->new ArrayList<>()).add(u);
        }
        List<AggregateChange> changes=new ArrayList<>();
        for(RegionId target:new TreeSet<>(targets)) for(var entry:byDefinition.entrySet()) {
            var records=entry.getValue().stream().sorted(Comparator.comparing(u->u.header().id().toString())).toList();var first=records.getFirst();
            Set<UUID> inherited=new HashSet<>(originIds);records.forEach(u->{inherited.add(u.canonicalScopeId());inherited.addAll(u.originScopeIds());});
            UniqueStatus status=records.stream().anyMatch(u->u.status()==UniqueStatus.SPENT_CONFIRMED)?UniqueStatus.SPENT_CONFIRMED:UniqueStatus.SPENT_POSSIBLE;
            var evidenceSource=records.stream().filter(u->u.status()==status).findFirst().orElseThrow();
            UUID id=UUID.nameUUIDFromBytes(("proutlost_director:lineage/"+entry.getKey().value()+"/"+target.value()).getBytes(StandardCharsets.UTF_8));
            var existing=input.stream().filter(u->u.header().id().equals(id)).findFirst().orElse(null);
            var value=new UniqueUseRecord(MemoryReducer.next(existing==null?null:existing.header(),id,now),entry.getKey(),UniquenessScope.ONCE_PER_REGION,target.value(),status,Optional.empty(),evidenceSource.instanceId(),evidenceSource.consumptionEvidence(),inherited);
            changes.add(MemoryReducer.change(new UniqueAggregate(value),existing==null?null:existing.header()));
        }
        return changes;
    }
    public AggregateChange dormant(RegionRecord region,RegionMemory memory,Tick now) {
        if(now.value()-memory.lastVisitTick().value()<settings.ticks("region.dormant_after_ticks"))throw new IllegalArgumentException("region still recently active");
        var next=new RegionRecord(MemoryReducer.next(region.header(),region.regionId().value(),now),region.regionId(),region.dimension(),RegionStatus.DORMANT,region.supportShape(),region.anchorIds(),region.importance(),region.parentIds(),region.aliases(),region.membershipRevision(),region.personality(),region.memoryRefs());
        return MemoryReducer.change(new RegionAggregate(next),region.header());
    }
}
