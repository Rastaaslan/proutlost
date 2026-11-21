package fr.proutlost.director.memory;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** Pure reduction of sealed observation windows. The actor commits changes plus consumed-input inbox
 * atomically. Replaying a batch against its original before view produces identical CAS writes.
 * Exposure lists are operational summaries supplied by the confirmed-result producer, not client ACKs.
 */
public final class MemoryReducer {
    private final MemorySettings settings;
    public MemoryReducer(MemorySettings settings) { this.settings=Objects.requireNonNull(settings); }
    static AggregateHeader next(AggregateHeader old, UUID id, Tick now) {
        if(old!=null&&now.compareTo(old.updatedTick())<0) throw new IllegalArgumentException("clock rollback");
        return new AggregateHeader(1,old==null?id:old.id(),old==null?new Revision(1):old.revision().plus(1),old==null?new Sequence(0):old.lastSequence(),now);
    }
    static AggregateChange change(AggregateState state, AggregateHeader old) {
        return new AggregateChange(state.key(),Optional.ofNullable(old).map(AggregateHeader::revision),state);
    }
    public Reduction reduce(MemoryView before, ObservationBatch input, DirectorClock clock) {
        Tick now=clock.simTick();
        if(!before.scopes().worldId().equals(input.context().scopes().worldId())) throw new IllegalArgumentException("wrong world");
        if(input.context().capturedTick().compareTo(now)>0) throw new IllegalArgumentException("future context");
        input.beforeRevisions().forEach((scope,revision)->{
            if(!before.revisions().getOrDefault(scope,new Revision(0)).equals(revision)) throw new IllegalArgumentException("STALE_CONTEXT");
        });
        Set<ObservationId> supplied=new HashSet<>(input.inputIds()), actual=new HashSet<>();
        for(var o:input.observations()) { if(!actual.add(o.observationId())) throw new IllegalArgumentException("duplicate observation"); if(o.tick().compareTo(now)>0)throw new IllegalArgumentException("future observation"); }
        for(var o:input.campEpisodes()) if(!actual.add(o.observationId()))throw new IllegalArgumentException("duplicate observation");
        for(var o:input.routeTransitions()) if(!actual.add(o.observationId()))throw new IllegalArgumentException("duplicate observation");
        if(!supplied.equals(actual))throw new IllegalArgumentException("input identity set mismatch");
        Map<RegionId,RegionMemory> oldRegions=new TreeMap<>(); before.regions().forEach(r->oldRegions.put(r.regionId(),r));
        Set<RegionId> touched=new TreeSet<>();
        oldRegions.keySet().stream().filter(id->{var r=before.regionRecords().get(id);return r!=null&&r.status()==RegionStatus.ACTIVE;}).forEach(touched::add);
        input.observations().stream().filter(o->o.coarseScope().kind()==ScopeKind.REGION).forEach(o->touched.add(new RegionId(o.coarseScope().id())));
        input.campEpisodes().forEach(o->touched.add(o.regionId())); input.routeTransitions().forEach(o->touched.add(o.route().from()));
        List<AggregateChange> changes=new ArrayList<>(); List<AggregateDelta> deltas=new ArrayList<>(); List<PatternUpdate> patterns=new ArrayList<>();
        Set<AnchorId> confirmedCamps=new HashSet<>();
        var observations=input.observations().stream().sorted(Comparator.comparing(DirectorObservation::tick).thenComparing(DirectorObservation::observationId)).toList();
        for(RegionId id:touched) {
            RegionMemory old=oldRegions.get(id);
            boolean discovery=!before.regionRecords().containsKey(id);
            if(discovery) {
                if(input.campEpisodes().stream().noneMatch(o->o.regionId().equals(id))||input.observations().stream().anyMatch(o->o.coarseScope().equals(new ScopeRef(ScopeKind.REGION,id.value()))))throw new IllegalArgumentException("unknown region identity");
                for(var o:input.campEpisodes())if(o.regionId().equals(id)) {
                    var a=before.anchors().get(o.anchorId());
                    if(a==null||!a.active()||!a.regionIds().isEmpty()||!id.value().equals(a.anchorId().value()))throw new IllegalArgumentException("invalid discovery candidate");
                }
            } else if(before.regionRecords().get(id).status()!=RegionStatus.ACTIVE)throw new IllegalArgumentException("inactive region identity");
            var components=old==null?new EnumMap<MemoryComponent,DecayValue>(MemoryComponent.class):settings.decay(old.components(),now);
            Tick lastVisit=old==null?new Tick(0):old.lastVisitTick(), lastEvent=old==null?new Tick(0):old.lastEventTick();
            Map<RegionTrait,Double> impulses=new EnumMap<>(RegionTrait.class);
            for(var o:observations) if(o.coarseScope().equals(new ScopeRef(ScopeKind.REGION,id.value()))) {
                if(o.type().equals(new ResourceId("proutlost_director:personality"))) {
                    for(var e:o.aggregateDelta().entrySet()) {
                        RegionTrait trait=RegionTrait.valueOf(e.getKey().toUpperCase(Locale.ROOT));
                        double impulse=Math.clamp(e.getValue(),-settings.number("region.major_impulse_cap"),settings.number("region.major_impulse_cap"));
                        impulses.merge(trait,impulse,Double::sum);
                    }
                    continue;
                }
                if(!o.type().equals(new ResourceId("proutlost_director:activity")))throw new IllegalArgumentException("unknown regional observation type");
                for(var e:new TreeMap<>(o.aggregateDelta()).entrySet()) {
                    MemoryComponent c=MemoryComponent.valueOf(e.getKey().toUpperCase(Locale.ROOT));
                    components.put(c,settings.add(c,components.get(c),now,MemoryMath.decay(e.getValue(),o.tick(),now,settings.ticks(settings.halfLife(c))),o.confidence()));
                    if(c==MemoryComponent.VISIT)lastVisit=max(lastVisit,o.tick());
                    if(c==MemoryComponent.EVENT)lastEvent=max(lastEvent,o.tick());
                }
            }
            Map<AnchorId,CampRoutineMemory> camps=new HashMap<>(old==null?Map.of():old.activity().camps());
            for(var o:input.campEpisodes().stream().filter(o->o.regionId().equals(id)).sorted(Comparator.comparing(CampEpisodeObservation::windowEnd).thenComparing(CampEpisodeObservation::observationId)).toList()) {
                AnchorRecord anchor=before.anchors().get(o.anchorId());
                if(anchor==null||!anchor.active()||(!discovery&&!anchor.regionIds().contains(id)))throw new IllegalArgumentException("camp requires known active anchor");
                var current=camps.get(o.anchorId());
                List<CampWindowContribution> windows=new ArrayList<>(current==null?List.of():current.windows());
                boolean known=windows.stream().anyMatch(w->w.window().independentEpisodes().contains(o.episodeId()));
                Tick last=windows.stream().map(w->w.window().endTick()).max(Tick::compareTo).orElse(new Tick(0));
                boolean independent=known||windows.isEmpty()||o.windowStart().value()-last.value()>=settings.ticks("pattern.independent_visit_gap_ticks");
                Set<EpisodeId> episodes=independent?Set.of(o.episodeId()):Set.of();
                var incoming=new CampWindowContribution(new EvidenceWindow(o.windowId(),o.windowStart(),o.windowEnd(),episodes),episodes.size(),o.occupiedTicks(),o.histogram());
                var same=windows.stream().filter(w->w.window().id().equals(o.windowId())).findFirst();
                if(same.isPresent()) {
                    // Compare raw sufficient statistics; independent-episode qualification was made on first insertion.
                    if(!same.get().occupiedTicks().equals(o.occupiedTicks())||!same.get().histogram().equals(o.histogram())
                            ||!same.get().window().startTick().equals(o.windowStart())||!same.get().window().endTick().equals(o.windowEnd()))
                        throw new IllegalArgumentException("conflicting sealed camp window");
                } else windows.add(incoming);
                camps.put(o.anchorId(),ActivityWindows.camp(o.anchorId(),windows,now,settings));
                lastVisit=max(lastVisit,o.windowEnd());
            }
            for(var entry:new ArrayList<>(camps.entrySet())) {
                var updated=ActivityWindows.camp(entry.getKey(),entry.getValue().windows(),now,settings);camps.put(entry.getKey(),updated);
                patterns.add(new PatternUpdate(id,Pattern.CAMP_ROUTINE,updated.confidence(),updated.independentVisits()));
                // A camp already attached to an authored/known region still needs
                // the SAME genuine routine evidence as new-region discovery.
                var anchor=before.anchors().get(entry.getKey());
                if(!discovery&&anchor!=null&&anchor.active()&&anchor.origin()==AnchorOrigin.CAMP
                    &&anchor.evidence()==EvidenceStatus.POSSIBLE&&anchor.regionIds().contains(id)
                    &&updated.independentVisits()>=settings.count("pattern.camp_min_visits")
                    &&updated.confidence().value()>=settings.number("pattern.camp_confidence_min")
                    &&confirmedCamps.add(anchor.anchorId())) {
                    var confirmed=new AnchorRecord(next(anchor.header(),anchor.anchorId().value(),now),
                        anchor.anchorId(),anchor.locationId(),anchor.regionIds(),anchor.dimension(),anchor.position(),
                        anchor.radiusProfile(),anchor.tags(),anchor.origin(),EvidenceStatus.CONFIRMED,anchor.active());
                    changes.add(change(new AnchorAggregate(confirmed),anchor.header()));
                }
            }
            Map<RouteKey,RouteTransitionMemory> routes=new HashMap<>(old==null?Map.of():old.activity().routes());
            for(var o:input.routeTransitions()) if(o.route().from().equals(id)) {
                if(o.windowEnd().compareTo(now)>0)throw new IllegalArgumentException("future route");
                if(!before.regionRecords().containsKey(o.route().to()))throw new IllegalArgumentException("unknown route destination");
                var existing=routes.get(o.route());var windows=new ArrayList<>(existing==null?List.<RouteWindowContribution>of():existing.windows());
                windows.add(new RouteWindowContribution(new EvidenceWindow(o.windowId(),o.windowStart(),o.windowEnd(),Set.of(o.episodeId())),o.transitionCount(),Map.of(o.direction(),o.transitionCount())));
                routes.put(o.route(),ActivityWindows.route(o.route(),windows));
            }
            if(!camps.isEmpty()) {
                double campEvidence=0;
                for(var camp:camps.values())for(var window:camp.windows())
                    campEvidence+=MemoryMath.decay(window.occupiedTicks().value(),window.window().endTick(),now,settings.ticks("memory.camp_half_life_ticks"));
                components.put(MemoryComponent.CAMP,new DecayValue(campEvidence,now,"memory.camp_half_life_ticks",camps.values().stream().mapToLong(CampRoutineMemory::independentVisits).sum(),new UnitValue(camps.values().stream().mapToDouble(c->c.confidence().value()).max().orElse(0))));
            }
            if(!routes.isEmpty()) {
                double flow=0;long count=0;
                for(var route:routes.values())for(var window:route.windows()) {
                    flow+=MemoryMath.decay(window.transitions(),window.window().endTick(),now,settings.ticks("memory.route_half_life_ticks"));count=Math.addExact(count,window.transitions());
                }
                components.put(MemoryComponent.ROUTE,new DecayValue(flow,now,"memory.route_half_life_ticks",count,new UnitValue(1)));
            }
            Set<ResourceId> persistent=new HashSet<>(old==null?Set.of():old.persistentStateRefs());
            ResourceId abandoned=new ResourceId("proutlost:camp_abandoned");
            if(CampAbandonmentPolicy.entered(persistent).isPresent()){
                persistent=new HashSet<>(CampAbandonmentPolicy.reduceMarked(persistent,camps.values(),now,settings));
            }else if(!camps.isEmpty()&&now.value()-lastVisit.value()>=settings.ticks("pattern.abandonment_enter_ticks"))persistent.add(abandoned);
            else if(camps.values().stream().anyMatch(c->c.confidence().value()>=settings.number("pattern.abandonment_leave_confidence")))persistent.remove(abandoned);
            var memory=new RegionMemory(next(old==null?null:old.header(),id.value(),now),id,components,lastVisit,lastEvent,persistent,
                    old==null?Set.of():old.durableFactRefs(),new RegionActivityState(camps,routes));
            changes.add(change(new RegionMemoryAggregate(memory),old==null?null:old.header()));
            deltas.add(new AggregateDelta(new ScopeRef(ScopeKind.REGION,id.value()),old==null?new Revision(0):old.header().revision(),memory.header().revision(),components));
            if(discovery) {
                var camp=camps.get(new AnchorId(id.value()));
                if(camp.independentVisits()>=settings.count("pattern.camp_min_visits")&&camp.confidence().value()>=settings.number("pattern.camp_confidence_min")) {
                    for(var creation:new RegionService(settings).discoverFromCamp(id,before.anchors().get(camp.anchorId()),camp.windows(),now))
                        if(!(creation.next() instanceof RegionMemoryAggregate))changes.add(creation);
                }
                // Candidate memory is durable aggregated evidence, not an active region before the gate.
                continue;
            }
            RegionRecord record=before.regionRecords().get(id);
            var targets=new EnumMap<RegionTrait,UnitValue>(RegionTrait.class);
            double visit=value(components,MemoryComponent.VISIT),activity=value(components,MemoryComponent.ACTIVITY),social=value(components,MemoryComponent.SOCIAL),risk=value(components,MemoryComponent.DISASTER);
            targets.put(RegionTrait.FAMILIAR,new UnitValue(visit/(1+visit)));targets.put(RegionTrait.ACTIVE,new UnitValue(activity/(1+activity)));
            targets.put(RegionTrait.SOCIAL,new UnitValue(social/(1+social)));targets.put(RegionTrait.QUIET,new UnitValue(1/(1+activity)));
            targets.put(RegionTrait.WILD,new UnitValue(1/(1+visit)));targets.put(RegionTrait.DAMAGED,new UnitValue(risk/(1+risk)));
            targets.put(RegionTrait.RECOVERING,new UnitValue(record.personality().traits().getOrDefault(RegionTrait.DAMAGED,new UnitValue(0)).value()/(1+risk)));
            RegionPersonality personality=MemoryMath.personality(record.personality(),targets,impulses,record.header().updatedTick(),now,
                    settings.ticks("region.personality_half_life_ticks"),settings.number("region.major_impulse_cap"));
            var updated=new RegionRecord(next(record.header(),id.value(),now),id,record.dimension(),record.status(),record.supportShape(),record.anchorIds(),
                    new UnitValue((visit/(1+visit)+camps.values().stream().mapToDouble(c->c.confidence().value()).max().orElse(0)
                            +social/(1+social)+(persistent.isEmpty()&&memory.durableFactRefs().isEmpty()?0:1))/4),record.parentIds(),record.aliases(),record.membershipRevision(),personality,record.memoryRefs());
            changes.add(change(new RegionAggregate(updated),record.header()));
        }
        reducePlayers(before,input,now,observations,changes);
        changes.sort(Comparator.comparing((AggregateChange c)->c.key().kind()).thenComparing(c->c.key().id().toString()));
        patterns.sort(Comparator.comparing(PatternUpdate::regionId).thenComparingDouble(p->p.confidence().value()));
        return new Reduction(changes,deltas,List.of(),patterns,input.inputIds().stream().sorted().toList());
    }
    private static double value(Map<MemoryComponent,DecayValue> values,MemoryComponent c) {var v=values.get(c);return v==null?0:v.value();}
    private static Tick max(Tick a,Tick b){return a.compareTo(b)>=0?a:b;}
    private void reducePlayers(MemoryView before,ObservationBatch input,Tick now,List<DirectorObservation> observations,List<AggregateChange> changes) {
        Map<PlayerId,PlayerOperationalMemory> existing=new TreeMap<>();before.operationalPlayers().forEach(p->existing.put(p.playerId(),p));
        Set<PlayerId> ids=new TreeSet<>(existing.keySet());ids.addAll(input.exposures().keySet());
        observations.stream().filter(o->o.coarseScope().kind()==ScopeKind.PLAYER).forEach(o->ids.add(new PlayerId(o.coarseScope().id())));
        for(PlayerId id:ids) {
            var old=existing.get(id);var recentActivity=old==null?new EnumMap<MemoryComponent,DecayValue>(MemoryComponent.class):settings.decay(old.recentActivity(),now);long eligible=0; Optional<Tick> death=old==null?Optional.empty():old.recentDeathUntil();Epoch epoch=old==null?new Epoch(0):old.transitionEpoch();
            for(var o:observations) if(o.coarseScope().equals(new ScopeRef(ScopeKind.PLAYER,id.value()))) {
                switch(o.type().value()) {
                    case "proutlost_director:presence" -> {
                        if(!o.aggregateDelta().keySet().equals(Set.of("eligible_ticks")))throw new IllegalArgumentException("presence fields");
                        double ticks=o.aggregateDelta().get("eligible_ticks");
                        if(ticks<0||ticks!=Math.rint(ticks)||ticks>Long.MAX_VALUE)throw new IllegalArgumentException("eligible duration");
                        eligible=Math.addExact(eligible,(long)ticks);
                    }
                    case "proutlost_director:player_activity" -> {
                        for(var entry:o.aggregateDelta().entrySet()) {
                            MemoryComponent component=MemoryComponent.valueOf(entry.getKey().toUpperCase(Locale.ROOT));
                            if(component!=MemoryComponent.ACTIVITY&&component!=MemoryComponent.SOCIAL)throw new IllegalArgumentException("unsupported player activity component");
                            recentActivity.put(component,settings.add(component,recentActivity.get(component),now,MemoryMath.decay(entry.getValue(),o.tick(),now,settings.ticks(settings.halfLife(component))),o.confidence()));
                        }
                    }
                    case "proutlost_director:death" -> death=Optional.of(o.tick().plus(settings.ticks("fairness.death_safety_ticks")));
                    case "proutlost_director:transition" -> {
                        double count=o.aggregateDelta().getOrDefault("count",1.0);
                        if(count<1||count!=Math.rint(count)||count>=Long.MAX_VALUE)throw new IllegalArgumentException("transition count");
                        epoch=epoch.plus((long)count);
                    }
                    default -> throw new IllegalArgumentException("unknown player observation");
                }
            }
            if(eligible>settings.ticks("observation.bucket_ticks"))throw new IllegalArgumentException("eligible duration exceeds sealed observation bucket");
            var exposures=new ArrayList<>(old==null?List.<Exposure>of():old.eventExposureRing());
            var roles=new ArrayList<>(old==null?List.<Exposure>of():old.roleExposureRing());
            double cost=0;Optional<Tick> target=old==null?Optional.empty():old.lastTargetTick();
            for(var e:input.exposures().getOrDefault(id,List.of())) {
                if(e.tick().compareTo(now)>0)throw new IllegalArgumentException("future exposure");
                if(exposures.contains(e))continue;
                exposures.add(e);roles.add(e);target=Optional.of(target.map(t->max(t,e.tick())).orElse(e.tick()));
                cost+=e.intensity().value()*settings.number(e.family()==Family.SAFE_MICRO_EVENT?"fairness.micro_exposure_cost":"fairness.confirmed_exposure_cost");
            }
            var snapshot=input.context().context().players().get(id);
            Optional<RegionId> region=snapshot==null?(old==null?Optional.empty():old.lastRegionId()):SupportGeometry.primary(
                    before.regionRecords().values(),snapshot.position().position(),Map.of());
            var next=new PlayerOperationalMemory(next(old==null?null:old.header(),id.value(),now),id,
                    new DurationTicks(Math.addExact(old==null?0:old.eligibleOnlineTicks().value(),eligible)),region,target,
                    FairnessMath.recent(exposures,settings.count("memory.player_recent_event_entries")),FairnessMath.recent(roles,settings.count("memory.player_recent_role_entries")),
                    old==null?Set.of():old.cooldownRefs(),FairnessMath.debt(old==null?new UnitValue(0):old.attentionDebt(),new DurationTicks(eligible),
                    settings.number("fairness.debt_gain_per_tick"),settings.number("fairness.debt_cap"),cost),
                    recentActivity,death,epoch);
            changes.add(change(new PlayerOperationalAggregate(next),old==null?null:old.header()));
        }
    }
}
