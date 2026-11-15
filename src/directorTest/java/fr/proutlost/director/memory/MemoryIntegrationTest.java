package fr.proutlost.director.memory;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.port.*;
import fr.proutlost.director.adapter.neoforge.server.observation.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

/** TEST_ONLY boundary fixtures, real pure implementations and exact frozen API DTOs. */
public final class MemoryIntegrationTest {
    static int checks;
    static MemorySettings settings;
    static ConfigView config;
    static WorldId world=new WorldId(id(999));
    static DimensionId dimension=new DimensionId("minecraft:overworld");
    static ScopeSet all=new ScopeSet(world,Set.of(new ScopeRef(ScopeKind.WORLD,world.value())));
    static UUID id(long n){return new UUID(0,n);}
    static Tick tick(long n){return new Tick(n);}
    static AggregateHeader header(UUID id,long revision,long tick){return new AggregateHeader(1,id,new Revision(revision),new Sequence(0),tick(tick));}
    static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    static void near(double a,double b,String why){check(Math.abs(a-b)<1e-10,why+" "+a+" != "+b);}
    static void rejects(Runnable fn,String why){try{fn.run();}catch(IllegalArgumentException|IllegalStateException e){check(true,why);return;}throw new AssertionError("no rejection: "+why);}
    static AnchorRecord anchor(long n,RegionId region,int x){
        return new AnchorRecord(header(id(n),1,0),new AnchorId(id(n)),Optional.empty(),Set.of(region),dimension,new BlockPosition(dimension,x,64,0),"region.camp_radius_blocks",Set.of(new TagId("proutlost:camp")),AnchorOrigin.CAMP,EvidenceStatus.CONFIRMED,true);
    }
    static RegionRecord region(long n,Set<AnchorId> anchors,int x){
        return new RegionRecord(header(id(n),1,0),new RegionId(id(n)),dimension,RegionStatus.ACTIVE,new SupportShape(List.of(),List.of(new SupportDisc(new BlockPosition(dimension,x,64,0),32)),List.of()),anchors,new UnitValue(.5),Set.of(),Set.of(),new Revision(1),new RegionPersonality(Map.of()),Set.of(new MemoryId(id(n))));
    }
    static RegionMemory memory(RegionRecord r,double visits,Set<FactId> facts){return new RegionMemory(header(r.regionId().value(),1,0),r.regionId(),Map.of(MemoryComponent.VISIT,new DecayValue(visits,tick(0),"memory.visit_half_life_ticks",1,new UnitValue(1))),tick(0),tick(0),Set.of(new ResourceId("test:durable_scar")),facts,new RegionActivityState(Map.of(),Map.of()));}
    static SnapshotBatch snapshot(long tick,MemoryView before){return new SnapshotBatch(new RequestId(id(100)),before.scopes(),tick(tick),before.revisions(),new ObservationContext(Map.of(),Map.of(),Map.of(),Map.of(),before.anchors(),before.locations(),List.of(),Map.of()),Set.of(),Set.of(),HealthMode.NORMAL);}
    static ObservationBatch batch(long tick,MemoryView before,List<DirectorObservation> observations,List<CampEpisodeObservation> camps,List<RouteTransitionObservation> routes,Map<PlayerId,List<Exposure>> exposures){
        List<ObservationId> ids=new ArrayList<>();observations.forEach(o->ids.add(o.observationId()));camps.forEach(o->ids.add(o.observationId()));routes.forEach(o->ids.add(o.observationId()));
        return new ObservationBatch(ids,observations,before.revisions(),snapshot(tick,before),camps,routes,exposures);
    }
    static MemoryView apply(MemoryView before,Reduction result){
        Map<AggregateKey,AggregateState> states=new HashMap<>();
        before.regionRecords().values().forEach(v->{var s=new RegionAggregate(v);states.put(s.key(),s);});
        before.regions().forEach(v->{var s=new RegionMemoryAggregate(v);states.put(s.key(),s);});
        before.anchors().values().forEach(v->{var s=new AnchorAggregate(v);states.put(s.key(),s);});
        before.operationalPlayers().forEach(v->{var s=new PlayerOperationalAggregate(v);states.put(s.key(),s);});
        before.significantPlayers().forEach(v->{var s=new PlayerSignificantAggregate(v);states.put(s.key(),s);});
        for(var change:result.aggregateChanges()){var old=states.get(change.key());check(change.expectedRevision().equals(Optional.ofNullable(old).map(s->s.header().revision())),"CAS revision");states.put(change.key(),change.next());}
        return MemoryProjection.of(before.scopes(),states.values());
    }
    public static void main(String[] args)throws Exception {
        var raw=((JsonValue.ObjectValue)((JsonValue.ObjectValue)CanonicalJsonV1.decode(Files.readAllBytes(Path.of(args[0])))).values().get("values")).values();
        Map<String,JsonValue.Number> numbers=new HashMap<>();raw.forEach((k,v)->numbers.put(k,(JsonValue.Number)v));
        config=new ConfigView(new Revision(1),new Fingerprint("a0ba94dfec141788b6f3768a585fec5208d9270e10fc0202139b4dae228cd81d"),Calibration.DEFAULT_FOR_TESTING,numbers);settings=new MemorySettings(config,false);
        rejects(()->new MemorySettings(config,true),"test calibration prohibited in production");
        reduction();windows();lineage();buffer();projection();groups();rawCamps();nearThresholdCampWindows();observationRegressions();indexedCampPipeline();budgetAndCadence();CampAbandonmentPolicyTests.run();AuthoredRegionTagProjectionTests.run();MemoryCampRoutineConfirmationTests.run();
        System.out.println("PASS MemoryIntegrationTest assertions="+checks);
    }
    static void reduction(){
        var r=region(1,Set.of(new AnchorId(id(10))),0);var a=anchor(10,r.regionId(),0);var fact=new FactId(id(500));
        var original=memory(r,4,Set.of(fact));
        var stored=new RegionMemory(header(id(9500),1,0),r.regionId(),original.components(),original.lastVisitTick(),original.lastEventTick(),original.persistentStateRefs(),original.durableFactRefs(),original.activity());
        var before=MemoryProjection.of(all,List.of(new RegionAggregate(r),new RegionMemoryAggregate(stored),new AnchorAggregate(a)));
        var reducer=new MemoryReducer(settings);PlayerId p=new PlayerId(id(80));
        var obs=new DirectorObservation(new ObservationId(id(90)),new ResourceId("proutlost_director:activity"),new ScopeRef(ScopeKind.REGION,r.regionId().value()),tick(200),Map.of("visit",1.0,"activity",2.0),new UnitValue(1));
        var presence=new DirectorObservation(new ObservationId(id(91)),new ResourceId("proutlost_director:presence"),new ScopeRef(ScopeKind.PLAYER,p.value()),tick(200),Map.of("eligible_ticks",200.0),new UnitValue(1));
        var input=batch(200,before,List.of(obs,presence),List.of(),List.of(),Map.of());
        var result=reducer.reduce(before,input,new DirectorClock(tick(200),0,Instant.EPOCH));
        check(result.equals(reducer.reduce(before,input,new DirectorClock(tick(200),1000,Instant.MAX))),"pure replay ignores UTC/process time");
        var codec=new ModelCodec<>(Reduction.class);check(result.equals(codec.decode(codec.encode(result))),"durable DTO codec round trip");
        var after=apply(before,result);var m=after.regions().getFirst();
        check(m.header().id().equals(id(9500)),"memory aggregate identity differs from region identity and survives reduction");
        check(m.durableFactRefs().equals(Set.of(fact))&&m.persistentStateRefs().contains(new ResourceId("test:durable_scar")),"durable references preserved");
        near(m.components().get(MemoryComponent.VISIT).value(),MemoryMath.decay(4,tick(0),tick(200),settings.ticks("memory.visit_half_life_ticks"))+1,"regional reduction");
        near(after.operationalPlayers().getFirst().attentionDebt().value(),.002,"eligible online debt");
        check(before.regions().getFirst().header().revision().value()==1,"before immutable");
        rejects(()->reducer.reduce(after,input,new DirectorClock(tick(200),0,Instant.EPOCH)),"stale batch refused");
        var idle=reducer.reduce(after,batch(400,after,List.of(),List.of(),List.of(),Map.of()),new DirectorClock(tick(400),0,Instant.EPOCH));
        var offline=apply(after,idle);near(offline.operationalPlayers().getFirst().attentionDebt().value(),.002,"no offline accrual");
        check(result.proposedFacts().isEmpty()&&after.significantPlayers().isEmpty(),"no fabricated significant memory");
        var bad=new DirectorObservation(new ObservationId(id(93)),new ResourceId("test:unknown"),obs.coarseScope(),tick(201),Map.of("activity",1.0),new UnitValue(1));
        rejects(()->reducer.reduce(after,batch(201,after,List.of(bad),List.of(),List.of(),Map.of()),new DirectorClock(tick(201),0,Instant.EPOCH)),"unknown observation rejected");
    }
    static void windows(){
        var r=region(2,Set.of(new AnchorId(id(11))),0);var a=anchor(11,r.regionId(),0);
        var before=MemoryProjection.of(all,List.of(new RegionAggregate(r),new RegionMemoryAggregate(memory(r,0,Set.of())),new AnchorAggregate(a)));
        var reducer=new MemoryReducer(settings);EpisodeId repeated=new EpisodeId(id(300));
        long now=0;
        for(int i=0;i<8;i++) {
            long start=i*1600L,end=start+200;now=end;
            // First four windows are a single episode; later windows are independent revisits.
            var episode=i<4?repeated:new EpisodeId(id(300+i));
            var observation=new CampEpisodeObservation(new ObservationId(id(400+i)),r.regionId(),a.anchorId(),new EvidenceWindowId(id(600+i)),episode,tick(start),tick(end),new DurationTicks(200),ActivityWindows.emptyHistogram());
            before=apply(before,reducer.reduce(before,batch(end,before,List.of(),List.of(observation),List.of(),Map.of()),new DirectorClock(tick(end),0,Instant.EPOCH)));
            if(i<4)check(before.regions().getFirst().activity().camps().get(a.anchorId()).confidence().value()==0,"repeated episode cannot recognize camp");
        }
        var camp=before.regions().getFirst().activity().camps().get(a.anchorId());check(camp.independentVisits()==5,"only independent episodes count");
        List<CampWindowContribution> doubled=new ArrayList<>(camp.windows());doubled.addAll(camp.windows());
        var merged=ActivityWindows.camp(a.anchorId(),doubled,tick(now),settings);check(merged.occupiedTicks().equals(camp.occupiedTicks()),"merge deduplicates occupied windows");
        check(merged.independentVisits()==camp.independentVisits(),"merge deduplicates episodes");
        var first=camp.windows().getFirst();var conflict=new CampWindowContribution(first.window(),first.independentVisits(),new DurationTicks(1),first.histogram());
        rejects(()->ActivityWindows.camp(a.anchorId(),List.of(first,conflict),tick(20000),settings),"conflicting sealed statistics refused");
        var route=new RouteKey(r.regionId(),new RegionId(id(9)));var window=new RouteWindowContribution(new EvidenceWindow(new EvidenceWindowId(id(700)),tick(0),tick(200),Set.of(repeated)),3,Map.of(Sector.E,3L));
        check(ActivityWindows.route(route,List.of(window,window)).transitions()==3,"route union no double count");
        var remembered=before;long abandoned=now+settings.ticks("pattern.abandonment_enter_ticks");
        var decayed=apply(remembered,reducer.reduce(remembered,batch(abandoned,remembered,List.of(),List.of(),List.of(),Map.of()),new DirectorClock(tick(abandoned),0,Instant.EPOCH)));
        check(decayed.regions().getFirst().persistentStateRefs().contains(new ResourceId("proutlost:camp_abandoned")),"abandonment detected");
    }
    static void lineage(){
        RegionService service=new RegionService(settings);var a=anchor(12,new RegionId(id(3)),0);var b=anchor(13,new RegionId(id(4)),48);
        var r=region(3,Set.of(a.anchorId()),0);var s=region(4,Set.of(b.anchorId()),48);var fact=new FactId(id(501));
        var before=MemoryProjection.of(all,List.of(new RegionAggregate(r),new RegionAggregate(s),new RegionMemoryAggregate(memory(r,3,Set.of(fact))),new RegionMemoryAggregate(memory(s,5,Set.of(fact))),new AnchorAggregate(a),new AnchorAggregate(b)));
        var unique=new UniqueUseRecord(header(id(800),1,0),new DefinitionId("test:once"),UniquenessScope.ONCE_PER_REGION,r.regionId().value(),UniqueStatus.SPENT_POSSIBLE,Optional.empty(),new EventInstanceId(id(801)),Optional.empty(),Set.of(r.regionId().value()));
        long now=settings.ticks("region.merge_persistence_ticks");check(service.mergeReady(.8,.9,tick(0),tick(now)),"merge persistent evidence");check(!service.mergeReady(.8,.9,tick(1),tick(now)),"merge requires entire interval");
        var changes=service.merge(before,Set.of(r.regionId(),s.regionId()),Map.of(r.regionId(),tick(100),s.regionId(),tick(10)),List.of(unique),tick(now));
        var merged=apply(before,new Reduction(changes,List.of(),List.of(),List.of(),List.of()));
        check(service.canonical(r.regionId(),merged.regionRecords()).equals(s.regionId()),"oldest identity survives regardless of lexical ID");
        var active=merged.regions().stream().filter(m->m.regionId().equals(s.regionId())).findFirst().orElseThrow();
        near(active.components().get(MemoryComponent.VISIT).value(),MemoryMath.decay(8,tick(0),tick(now),settings.ticks("memory.visit_half_life_ticks")),"primary counts summed exactly once");
        check(active.durableFactRefs().equals(Set.of(fact)),"facts set-union");
        List<UniqueUseRecord> inherited=changes.stream().map(AggregateChange::next).filter(UniqueAggregate.class::isInstance).map(UniqueAggregate.class::cast).map(UniqueAggregate::value).toList();
        check(inherited.size()==1&&inherited.getFirst().status()==UniqueStatus.SPENT_POSSIBLE,"uncertain uniqueness survives merge");
        RegionId c=new RegionId(id(30)),d=new RegionId(id(31));
        var split=service.split(merged,s.regionId(),Map.of(c,Set.of(a.anchorId()),d,Set.of(b.anchorId())),Map.of(),inherited,tick(now+1));
        var splitView=apply(merged,new Reduction(split,List.of(),List.of(),List.of(),List.of()));
        check(splitView.regionRecords().get(s.regionId()).status()==RegionStatus.ARCHIVED,"historical parent retained");
        check(service.lineage(c,splitView.regionRecords()).contains(r.regionId()),"child inherits merged ancestry");
        check(split.stream().filter(x->x.next() instanceof UniqueAggregate).count()==2,"each child inherits spent uniqueness");
        double total=splitView.regions().stream().filter(m->m.regionId().equals(c)||m.regionId().equals(d)).mapToDouble(m->m.components().get(MemoryComponent.VISIT).value()).sum();
        near(total,MemoryMath.decay(active.components().get(MemoryComponent.VISIT).value(),tick(now),tick(now+1),settings.ticks("memory.visit_half_life_ticks")),"split conserves counter mass");
        var reducer=new MemoryReducer(settings);
        var postSplit=reducer.reduce(splitView,batch(now+2,splitView,List.of(),List.of(),List.of(),Map.of()),new DirectorClock(tick(now+2),0,Instant.EPOCH));
        check(postSplit.aggregateChanges().stream().filter(v->v.next() instanceof RegionMemoryAggregate).count()==2,"historical parents and aliases are not counted again");
        rejects(()->service.split(merged,s.regionId(),Map.of(c,Set.of(a.anchorId()),d,Set.of(a.anchorId())),Map.of(),inherited,tick(now+1)),"no duplicate anchor assignments");
    }
    static void buffer() throws Exception {
        var empty=MemoryProjection.of(all,List.of());var buffer=new ObservationBuffer(world,200,2);ScopeRef player=new ScopeRef(ScopeKind.PLAYER,id(80));ResourceId presence=new ResourceId("proutlost_director:presence");
        check(buffer.add(player,presence,tick(40),"eligible_ticks",40),"add");check(buffer.add(player,presence,tick(80),"eligible_ticks",40),"coalesce");
        check(buffer.pendingScopes()==1,"bounded coalescing");var sealed=buffer.seal(snapshot(200,empty));near(sealed.observations().getFirst().aggregateDelta().get("eligible_ticks"),80,"coalesced duration");
        check(buffer.seal(snapshot(400,empty))==sealed,"retry retains identical in-flight batch");
        rejects(()->buffer.acknowledge(List.of(new ObservationId(id(900)))),"wrong ACK");
        buffer.add(player,presence,tick(240),"eligible_ticks",40);buffer.acknowledge(sealed.inputIds());var second=buffer.seal(snapshot(400,empty));check(!second.inputIds().equals(sealed.inputIds()),"new window new identity");
        AtomicReference<Throwable> error=new AtomicReference<>();Thread worker=new Thread(()->{try{buffer.pendingScopes();}catch(Throwable e){error.set(e);}});worker.start();worker.join();check(error.get() instanceof IllegalStateException,"worker cannot use actor buffer");
        buffer.acknowledge(second.inputIds());
        rejects(()->buffer.add(player,presence,tick(240),"eligible_ticks",1),"cannot reopen sealed bucket");
        var backlog=new ObservationBuffer(world,200,4);backlog.add(player,presence,tick(40),"eligible_ticks",40);backlog.add(player,presence,tick(240),"eligible_ticks",40);
        var firstBacklog=backlog.seal(snapshot(600,empty));check(firstBacklog.observations().size()==1,"backlog seals one window at a time");backlog.acknowledge(firstBacklog.inputIds());
        check(backlog.seal(snapshot(600,empty)).observations().getFirst().tick().value()==240,"next backlog window retained");
        var route=new RouteKey(new RegionId(id(1)),new RegionId(id(2)));
        buffer.transition(route,Sector.E,tick(401));buffer.transition(route,Sector.E,tick(402));var routes=buffer.seal(snapshot(600,empty));check(routes.routeTransitions().getFirst().transitionCount()==2,"routes are aggregate counts only");
    }
    static PlayerSnapshot player(long id,int x,int z) {
        return new PlayerSnapshot(new PlayerId(id(id)),new Revision(1),tick(100),PlayerEligibility.ELIGIBLE,new PositionSnapshot(new BlockPosition(dimension,x,64,z),tick(100)),Set.of(),Set.of(),new DurationTicks(0),Set.of(),new ClientCapabilitySnapshot(1,Set.of(),Map.of(),true),Set.of());
    }
    static void groups() {
        var a=player(1,-1,0);var b=player(2,1,0);var c=player(3,500,0);
        var players=Map.of(a.playerId(),a,b.playerId(),b,c.playerId(),c);
        var result=ProximityGroups.build(players,32,10,tick(100));
        check(result.complete()&&result.groups().size()==1,"near players grouped across negative cell boundary");
        check(!result.memberships().containsKey(c.playerId()),"distant player remains solo");
        check(result.comparisons()==1,"sparse join avoids distant pairs");
        var limited=ProximityGroups.build(players,32,0,tick(100));
        check(!limited.complete()&&limited.groups().isEmpty(),"dense budget exhaustion is explicit unknown");
    }
    static void rawCamps() {
        var region=new RegionId(id(77));var anchor=anchor(77,region,0);var sampler=new CampSampler(32,12,200,1200);
        for(int bin=0;bin<12;bin++) {
            long now=bin*200L+40;
            check(sampler.observe(anchor,region,List.of(new PositionSnapshot(new BlockPosition(dimension,0,64,0),tick(now))),tick(now),bin*2000L,40,32),"raw camp sample");
        }
        var windows=sampler.sealed(tick(2400));check(windows.size()==12,"twelve sealed windows");
        Set<Integer> bins=new HashSet<>();Set<EpisodeId> episodes=new HashSet<>();
        windows.forEach(w->{bins.addAll(w.timeBins().keySet());episodes.addAll(w.window().independentEpisodes());check(w.occupiedTicks().value()==40,"occupancy not player sum");});
        check(bins.size()==12&&episodes.size()==1,"all twelve bins retained without inventing independent episodes");
        check(windows.equals(sampler.sealed(tick(2400))),"camp windows retained until durable ACK");
        sampler.acknowledge(windows.stream().map(w->w.window().id()).toList(),tick(2400));check(sampler.sealed(tick(2400)).isEmpty(),"camp ACK clears only confirmed windows");
        long revisit=4000;sampler.observe(anchor,region,List.of(new PositionSnapshot(anchor.position(),tick(revisit))),tick(revisit),0,40,32);
        var later=sampler.sealed(tick(4000));check(!later.getFirst().window().independentEpisodes().equals(episodes),"spaced revisit starts new episode");
        check(!sampler.observe(anchor,region,List.of(new PositionSnapshot(anchor.position(),tick(revisit))),tick(revisit),0,40,32),"sealed duplicate sample rejected");
        // The identity remains bucket-derived; the evidence bounds do not
        // invent presence before or after the actual observed interval.
        var firstObserved=windows.stream().filter(w->w.window().startTick().value()==0)
            .findFirst().orElseThrow();
        check(firstObserved.window().endTick().value()==40 &&
              firstObserved.occupiedTicks().value()==40,
            "sealed camp window has exact sampled interval, not entire bucket");
        rejects(()->sampler.acknowledge(List.of(new EvidenceWindowId(id(12345))),tick(4000)),"unknown camp ACK rejected");
        var service=new RegionService(settings);List<CampWindowContribution> evidence=new ArrayList<>();
        for(int i=0;i<8;i++)evidence.add(new CampWindowContribution(new EvidenceWindow(new EvidenceWindowId(id(2000+i)),tick(i*1600),tick(i*1600+200),Set.of(new EpisodeId(id(3000+i)))),1,new DurationTicks(200),ActivityWindows.emptyHistogram()));
        rejects(()->service.discoverFromCamp(new RegionId(id(78)),anchor,List.of(evidence.getFirst()),tick(200)),"transient fire cannot create a routine region");
        check(service.discoverFromCamp(new RegionId(id(78)),anchor,evidence,tick(11400)).stream().anyMatch(v->v.next() instanceof RegionAggregate),"persistent camp discovers region");
    }
    /** A genuine 1300-tick revisit clears the 1200-tick threshold.
     * Rounded 200-tick buckets must not silently relabel it as contiguous.
     * No GameTest injection or production tuning is needed to prove this.
     */
    static void nearThresholdCampWindows(){
        var a=anchor(977,new RegionId(id(977)),0);
        var candidate=new AnchorRecord(a.header(),a.anchorId(),Optional.empty(),Set.of(),
            a.dimension(),a.position(),a.radiusProfile(),a.tags(),a.origin(),
            EvidenceStatus.POSSIBLE,true);
        var sampler=new CampSampler(8,12,200,1200);
        for(long now:new long[]{40,1340})
            check(sampler.observe(candidate,new RegionId(a.anchorId().value()),
                List.of(new PositionSnapshot(candidate.position(),tick(now))),
                tick(now),0,40,32),"1300 tick independent camp witness");
        var windows=sampler.sealed(tick(1600));
        check(windows.size()==2 &&
              windows.stream().map(w->w.window().independentEpisodes().iterator().next())
                .distinct().count()==2 &&
              windows.stream().mapToLong(w->w.occupiedTicks().value()).sum()==80,
            "distinct witnessed episodes retain exact occupancy");
        var ordered=windows.stream().sorted(Comparator.comparing(
            w->w.window().startTick())).toList();
        check(ordered.getFirst().window().startTick().value()==0 &&
              ordered.getFirst().window().endTick().value()==40 &&
              ordered.getLast().window().startTick().value()==1300 &&
              ordered.getLast().window().endTick().value()==1340,
            "exact observed evidence bounds survive coarse bucket identities");
        var before=MemoryProjection.of(all,List.of(new AnchorAggregate(candidate)));
        var episodes=ordered.stream().map(CampSampler::observation).toList();
        var after=apply(before,new MemoryReducer(settings).reduce(before,
            batch(1600,before,List.of(),episodes,List.of(),Map.of()),
            new DirectorClock(tick(1600),0,Instant.EPOCH)));
        var stored=after.regions().getFirst().activity().camps().get(candidate.anchorId());
        check(stored.independentVisits()==2 && stored.occupiedTicks().value()==80 &&
              after.regionRecords().isEmpty(),
            "J04 candidate recognizes both independent visits without early birth");
    }
    static void observationRegressions() {
        var tracking=new ObservationContinuity(40);var p=player(501,0,0);var q=player(502,1,0);
        var region=new RegionId(id(700));var original=anchor(700,region,0);
        var unassigned=new AnchorRecord(original.header(),original.anchorId(),Optional.empty(),Set.of(),dimension,original.position(),original.radiusProfile(),original.tags(),original.origin(),original.evidence(),true);
        var sampler=new CampSampler(8,12,200,1200);
        // The adapter's regular sampling path: continuity -> camp sample. No primary region exists.
        for(long start:new long[]{40,1640}) {
            for(long now=start;now<=start+80;now+=40) {
                boolean continuous=tracking.camp(p.playerId(),Optional.of(unassigned.anchorId()),PlayerEligibility.ELIGIBLE,tick(now));
                check(continuous==(now>start),"unassigned camp continuity survives absent region; gap starts a new visit");
                if(continuous)check(sampler.observe(unassigned,region,List.of(new PositionSnapshot(unassigned.position(),tick(now))),tick(now),0,40,32),"unassigned camp reaches raw observation sampler");
            }
        }
        var visits=sampler.sealed(tick(1800));
        check(visits.size()==2&&visits.stream().mapToLong(w->w.occupiedTicks().value()).sum()==160,"repeated unassigned visits produce occupied windows");
        check(!visits.get(0).window().independentEpisodes().equals(visits.get(1).window().independentEpisodes()),"unassigned visits preserve independent episodes");
        check(!tracking.camp(p.playerId(),Optional.of(unassigned.anchorId()),PlayerEligibility.SPECTATOR,tick(1760)),"ineligible camp observation resets continuity");
        check(!tracking.camp(p.playerId(),Optional.of(unassigned.anchorId()),PlayerEligibility.ELIGIBLE,tick(1800)),"eligibility reentry cannot credit previous interval");

        tracking.resetIsolation(p.playerId());
        check(tracking.camp(p.playerId(),Optional.of(unassigned.anchorId()),PlayerEligibility.ELIGIBLE,tick(1840)),"snapshot group reset cannot interrupt camp continuity");
        var solo=Map.of(p.playerId(),p);var together=Map.of(p.playerId(),p,q.playerId(),q);
        tracking.proximity(solo,ProximityGroups.build(solo,32,10,tick(40)),true,tick(40));
        tracking.proximity(solo,ProximityGroups.build(solo,32,10,tick(80)),true,tick(80));
        check(tracking.isolatedTicks(p.playerId(),tick(80))==40,"first requested snapshot sees observed isolation");
        tracking.proximity(together,ProximityGroups.build(together,32,10,tick(120)),true,tick(120));
        tracking.proximity(solo,ProximityGroups.build(solo,32,10,tick(160)),true,tick(160));
        tracking.proximity(solo,ProximityGroups.build(solo,32,10,tick(200)),true,tick(200));
        check(tracking.isolatedTicks(p.playerId(),tick(200))==40,"group entry and exit between requests reset isolation");
        check(tracking.isolatedTicks(p.playerId(),tick(220))==40,"snapshot does not add unobserved time");
        check(tracking.isolatedTicks(p.playerId(),tick(240))==0,"missed observation expires isolation");
        tracking.proximity(solo,ProximityGroups.build(solo,32,10,tick(280)),true,tick(280));
        check(tracking.isolatedTicks(p.playerId(),tick(280))==0,"gap cannot bridge isolated intervals");
        tracking.proximity(solo,ProximityGroups.build(solo,32,10,tick(320)),false,tick(320));
        check(tracking.isolatedTicks(p.playerId(),tick(320))==0,"incomplete census clears isolation");
        var spectator=new PlayerSnapshot(p.playerId(),p.revision(),tick(360),PlayerEligibility.SPECTATOR,p.position(),Set.of(),Set.of(),new DurationTicks(0),Set.of(),p.clientCapabilities(),Set.of());
        var ineligible=Map.of(p.playerId(),spectator);
        tracking.proximity(ineligible,ProximityGroups.build(ineligible,32,10,tick(360)),true,tick(360));
        check(tracking.isolatedTicks(p.playerId(),tick(360))==0,"spectator is never isolated eligible audience");

        var frozen=new CampSampler(8,12,200,1200);
        check(frozen.observe(unassigned,region,List.of(new PositionSnapshot(unassigned.position(),tick(160))),tick(160),0,40,32),"partial camp before seal");
        var sealed=frozen.sealed(tick(200));
        check(!frozen.observe(unassigned,region,List.of(new PositionSnapshot(unassigned.position(),tick(200))),tick(200),2000,40,32),"boundary contribution explicitly rejected after seal");
        check(sealed.equals(frozen.sealed(tick(200))),"same sealed identity retains exact contents");
        check(frozen.observe(unassigned,region,List.of(new PositionSnapshot(unassigned.position(),tick(240))),tick(240),4000,40,32),"next window remains writable before old acknowledgement");
        frozen.acknowledge(sealed.stream().map(w->w.window().id()).toList(),tick(240));
        check(!frozen.observe(unassigned,region,List.of(new PositionSnapshot(unassigned.position(),tick(200))),tick(200),0,40,32),"acknowledgement cannot reopen old bucket");
        var next=frozen.sealed(tick(400));
        check(next.size()==1&&next.getFirst().occupiedTicks().value()==40&&next.getFirst().timeBins().equals(Map.of(2,40L)),"old acknowledgement preserves all accepted later data");
    }
    static void indexedCampPipeline() {
        var region=new RegionId(id(880));var a=anchor(880,region,0);
        var unassigned=new AnchorRecord(a.header(),a.anchorId(),Optional.empty(),Set.of(),a.dimension(),a.position(),a.radiusProfile(),a.tags(),AnchorOrigin.CAMP,EvidenceStatus.POSSIBLE,true);
        var before=MemoryProjection.of(all,List.of(new AnchorAggregate(unassigned)));
        var sampler=new CampSampler(32,12,200,1200);var buffer=new ObservationBuffer(world,200,32);
        var pipeline=new ObservationPipeline(buffer,sampler);var tracker=new ObservationContinuity(40);
        var reducer=new MemoryReducer(settings);var player=new PlayerId(id(881));
        long end=0;
        for(int bin=0;bin<12;bin++) {
            long start=40+bin*1600L;end=200+bin*1600L;
            for(long now=start;now<=start+80;now+=40)if(tracker.camp(player,Optional.of(a.anchorId()),PlayerEligibility.ELIGIBLE,tick(now)))
                check(sampler.observe(unassigned,region,List.of(new PositionSnapshot(a.position(),tick(now))),tick(now),bin*2000L,40,32),"indexed pipeline raw sample");
            var batch=pipeline.seal(snapshot(end,before),before);
            check(batch.campEpisodes().size()==1,"camp contribution included in durable observation batch");
            check(batch.campEpisodes().getFirst().histogram().activityTime().orElseThrow().counts().get(bin)==80,"correct bin survives actor delivery");
            check(pipeline.seal(snapshot(end+40,before),before)==batch,"combined batch stable until ACK");
            var reduced=reducer.reduce(before,batch,new DirectorClock(tick(end),1000,Instant.EPOCH));
            var codec=new ModelCodec<>(Reduction.class);check(reduced.equals(codec.decode(codec.encode(reduced))),"indexed reduction codec roundtrip");
            check(new HashSet<>(reduced.consumedInputs()).equals(new HashSet<>(batch.inputIds())),"camp input included in atomic inbox");
            before=apply(before,codec.decode(codec.encode(reduced)));
            if(bin==0)check(before.regionRecords().isEmpty()&&before.regions().size()==1,"first fire visit persists evidence without inventing active region");
            rejects(()->pipeline.acknowledge(List.of(new ObservationId(id(999999))),tick(200)),"wrong combined ACK rejected");
            pipeline.acknowledge(batch.inputIds(),tick(end));
            check(sampler.sealed(tick(end)).isEmpty(),"durable combined ACK releases exact camp windows");
        }
        check(before.regionRecords().containsKey(region)&&before.anchors().get(a.anchorId()).regionIds().contains(region),"repeated unassigned visits discover region with atomic anchor membership");
        var camp=before.regions().getFirst().activity().camps().get(a.anchorId());
        check(camp.histogram().activityTime().orElseThrow().counts().equals(Collections.nCopies(12,80L)),"all twelve bins preserved through discovery and reduction");
        check(camp.independentVisits()==12,"independent episodes remain distinct");
        var provider=new MemorySnapshotProvider(settings);var regional=provider.regions(before,Map.of(),tick(end)).get(region);
        check(regional.camps().getFirst().activityTime().equals(camp.histogram().activityTime()),"snapshot provider publishes indexed counts losslessly");
        check(regional.risks().isEmpty(),"provider does not fabricate risk measurements");
        var merged=ActivityWindows.merge(before.regions().getFirst().activity(),before.regions().getFirst().activity(),tick(end),settings);
        check(merged.camps().get(a.anchorId()).histogram().equals(camp.histogram()),"duplicate merge windows do not double indexed totals");
        var hist=camp.histogram();var mismatch=new ActivityHistogram(Map.of(),Map.of(),Map.of(),Map.of(),Optional.of(new ActivityTimeCounts(4,List.of(1L,0L,0L,0L))));
        rejects(()->ActivityWindows.sum(hist,mismatch),"incompatible bin counts fail explicitly");
        check(ActivityWindows.sum(hist,ActivityWindows.emptyHistogram()).activityTime().equals(hist.activityTime()),"legacy unknown bins do not fabricate indexed evidence");
        var selected=new ScopeSet(world,Set.of(new ScopeRef(ScopeKind.REGION,region.value())));
        var live=new SnapshotBatch(new RequestId(id(882)),selected,tick(end),Map.of(),new ObservationContext(Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(),Map.of()),Set.of(SensorKind.CAMP_ACTIVITY,SensorKind.REGION_TRAITS,SensorKind.TERRAIN),Set.of(),HealthMode.NORMAL);
        var enriched=provider.enrich(live,before);
        check(!enriched.missingData().contains(SensorKind.CAMP_ACTIVITY)&&enriched.missingData().contains(SensorKind.TERRAIN),"provider resolves only evidenced requested sensors");
        check(enriched.context().regions().get(region).camps().getFirst().activityTime().equals(hist.activityTime()),"enriched context retains twelve bins");
        var worldLive=new SnapshotBatch(live.requestId(),all,live.capturedTick(),live.scopeRevisions(),live.context(),live.missingData(),live.loadedChunks(),live.serverHealth());
        check(provider.enrich(worldLive,before).missingData().contains(SensorKind.CAMP_ACTIVITY),"resident cache cannot assert whole-world completeness");
        var cold=new MemoryView(before.scopes(),before.revisions(),before.regions(),before.operationalPlayers(),before.significantPlayers(),before.regionRecords(),Map.of(),before.locations());
        check(provider.regions(cold,Map.of(),tick(end)).isEmpty(),"missing anchor projection stays unknown");
        var shortGap=new CampSampler(8,12,200,40);
        shortGap.observe(a,region,List.of(new PositionSnapshot(a.position(),tick(40))),tick(40),0,40,32);
        shortGap.observe(a,region,List.of(new PositionSnapshot(a.position(),tick(120))),tick(120),2000,40,32);
        var episodes=shortGap.sealed(tick(200));check(episodes.size()==2,"two episodes inside one bucket retain separate immutable contributions");
        check(episodes.stream().map(CampSampler::observation).mapToLong(o->o.occupiedTicks().value()).sum()==80,"episode conversion never duplicates occupancy");
    }
    static void budgetAndCadence() {
        var nanos=new java.util.concurrent.atomic.AtomicLong();
        var budget=new ObservationBudget(10,100,nanos::get);int lookups=0;
        for(int i=0;i<10000;i++) {
            if(!budget.take())break; // same pre-filter boundary as the adapter
            if(!budget.take())break;
            lookups++; // every player unrequested: filtering cannot evade accounting
        }
        check(lookups==5&&budget.remaining()==0,"unrequested players exhaust operation budget before further lookups");
        var expired=new ObservationBudget(100,10,nanos::get);nanos.set(10);
        check(!expired.take(),"deadline at boundary prevents first lookup");
        var p=player(990,0,0);var q=player(991,1,0);var players=Map.of(p.playerId(),p,q.playerId(),q);
        var stopped=ProximityGroups.build(players,32,100,tick(0),expired);
        check(!stopped.complete()&&stopped.comparisons()==0&&stopped.groups().isEmpty(),"deadline exhaustion never starts grouping comparisons");
        var elapsed=new ObservationBudget(1000,8,()->nanos.getAndIncrement());
        var partial=ProximityGroups.build(players,32,100,tick(0),elapsed);
        check(!partial.complete()&&partial.groups().isEmpty(),"deadline during spatial processing invalidates partial groups");
        var shared=new ObservationBudget(3,1000,()->0L);check(shared.take(),"scope lookup consumes shared budget");
        check(!ProximityGroups.build(players,32,100,tick(0),shared).complete()&&shared.remaining()==0,"group setup consumes remaining operation budget");
        var cadence=new ObservationCadence(400);var tracking=new ObservationContinuity(40,400);
        var region=new RegionId(id(992));var a=anchor(992,region,0);var sampler=new CampSampler(16,12,200,1200);
        int scans=0;long accepted=0;
        for(long now=40;now<=1200;now+=40) {
            if(cadence.due(tick(now))) {
                scans++;
                if(tracking.camp(p.playerId(),Optional.of(a.anchorId()),PlayerEligibility.ELIGIBLE,tick(now))) {
                    check(sampler.observeInterval(a,region,List.of(new PositionSnapshot(a.position(),tick(now))),tick(now),4000,400,32),"camp cadence interval splits across observation buckets");
                    accepted+=400;
                }
            }
            // Frequent player snapshots must not seal buckets still awaiting their camp census.
            sampler.sealed(ObservationPipeline.campCutoff(tick(now),400));
        }
        check(scans==3&&accepted==800,"40-tick player cadence invokes camp work only every 400 ticks");
        var sealed=sampler.sealed(tick(1200));
        check(sealed.size()==4&&sealed.stream().mapToLong(w->w.occupiedTicks().value()).sum()==800,"split windows conserve occupied ticks");
        check(sealed.stream().allMatch(w->w.occupiedTicks().value()==200&&w.window().endTick().value()-w.window().startTick().value()==200),"all contributions respect bucket boundaries");
        check(!tracking.camp(p.playerId(),Optional.of(a.anchorId()),PlayerEligibility.ELIGIBLE,tick(2000)),"missed rotating camp pass cannot credit an observation gap");
        Set<Integer> visited=new HashSet<>();
        for(int pass=0;pass<3;pass++){for(int offset=0;offset<2;offset++)visited.add(cadence.index(offset,5));cadence.advance(2,5);}
        check(visited.size()==5,"bounded rotation eventually considers every player");
        var odd=new CampSampler(16,12,200,1200);
        check(odd.observeInterval(a,region,List.of(new PositionSnapshot(a.position(),tick(750))),tick(750),0,350,32),"non-divisible cadence supported");
        var oddWindows=odd.sealed(tick(800));
        check(oddWindows.size()==2&&oddWindows.stream().mapToLong(w->w.occupiedTicks().value()).sum()==350,"partial boundary windows conserve non-divisible interval");
    }
    static StoreReadResult typedFixture(StoreView original,StoreQuery query,StoreSession session) {
        var stamp=new ReadStamp(session,original.lastDurableSequence(),ReadContracts.queryHash(query));
        if(query.pageCursor().isPresent()) {
            try {
                var cursor=ReadContracts.decodeCursor(query.pageCursor().orElseThrow());
                if(!cursor.stamp().equals(stamp)||!cursor.position().equals("1"))
                    return new StoreReadResult(ReadStatus.STALE,Optional.empty(),Optional.empty(),List.of());
            }catch(IllegalArgumentException e) {
                return new StoreReadResult(ReadStatus.STALE,Optional.empty(),Optional.empty(),List.of());
            }
        }
        var continued=original.hasMore()
            ?Optional.of(ReadContracts.encodeCursor(new StoreReadCursor(stamp,"1")))
            :Optional.<String>empty();
        var page=new StoreView(original.records(),original.aggregates(),
            original.lastDurableSequence(),original.hasMore(),continued);
        return new StoreReadResult(ReadStatus.AVAILABLE,Optional.of(page),Optional.of(stamp),List.of());
    }
    static void projection(){
        PlayerId player=new PlayerId(id(88));var significant=new PlayerSignificantMemory(header(id(900),1,0),new MemoryId(id(900)),player,new EventInstanceId(id(901)),new DefinitionId("test:real_outcome"),"witness",PerceptionStatus.RECEIVED,new BlobRef(new Fingerprint("0".repeat(64)),1,1,new ResourceId("test:outcome")),Optional.empty(),Optional.empty(),Set.of(new FactId(id(902))),Set.of(),tick(0));
        var state=new PlayerSignificantAggregate(significant);var page=new StoreView(List.of(),List.of(new RestoredAggregate(state,new Sequence(1),new Fingerprint("1".repeat(64)))),new Sequence(1),false,Optional.empty());
        var fakeSession=new StoreSession(world,new Epoch(1),new Epoch(1),new SessionId(id(903)));
        DurableStore store=(DurableStore)Proxy.newProxyInstance(DurableStore.class.getClassLoader(),new Class[]{DurableStore.class},(proxy,method,args)->switch(method.getName()){
            case "view" -> typedFixture(page,(StoreQuery)args[0],fakeSession);case "session" -> fakeSession;default -> throw new AssertionError("unexpected IO/append: "+method.getName());
        });
        var port=new DurableMemoryPort(store,settings);port.refresh(new StoreQuery(world,Set.of(state.key()),new Sequence(0),Optional.empty(),10));
        check(port.supportedModes().equals(Set.of(ProviderMode.TEST_ONLY)),"LAB memory retains its actual calibration mode");
        rejects(()->fr.proutlost.director.bootstrap.DirectorBootstrap.compose(ProviderMode.PRODUCTION,
            List.of(new fr.proutlost.director.bootstrap.DirectorBootstrap.Provider(port,ProviderMode.PRODUCTION,List.of(),List.of()))),
            "LAB memory cannot be promoted by a Provider envelope");
        // Approved metadata alone cannot promote the synthetic durable writer.
        var approvedMetadata=new ConfigView(config.revision(),config.provenanceHash(),Calibration.APPROVED,config.values());
        rejects(()->new DurableMemoryPort(store,new MemorySettings(approvedMetadata,true)),
            "production memory rejects nonprovenanced durable dependency");
        check(port.view(new ScopeSet(world,Set.of(new ScopeRef(ScopeKind.PLAYER,player.value())))).significantPlayers().equals(List.of(significant)),"J05 significant archive projected unchanged");
        check(port.view(new ScopeSet(world,Set.of(new ScopeRef(ScopeKind.PLAYER,id(89))))).significantPlayers().isEmpty(),"player archive isolation");
        AtomicReference<StoreView> current=new AtomicReference<>(new StoreView(List.of(),page.aggregates(),page.lastDurableSequence(),true,Optional.of("next")));
        DurableStore paged=(DurableStore)Proxy.newProxyInstance(DurableStore.class.getClassLoader(),new Class[]{DurableStore.class},(proxy,method,args)->switch(method.getName()){
            case "view" -> typedFixture(current.get(),(StoreQuery)args[0],fakeSession);case "session" -> fakeSession;default -> throw new AssertionError("unexpected IO");
        });
        var pagedPort=new DurableMemoryPort(paged,settings);pagedPort.refresh(new StoreQuery(world,Set.of(),new Sequence(0),Optional.empty(),10));
        check(pagedPort.view(all).significantPlayers().isEmpty(),"partial page generation not published");
        current.set(new StoreView(List.of(),List.of(),page.lastDurableSequence(),false,Optional.empty()));
        var requested=new StoreQuery(world,Set.of(),new Sequence(0),Optional.empty(),10);
        var expectedStamp=new ReadStamp(fakeSession,new Sequence(1),ReadContracts.queryHash(requested));
        var nextPageCursor=ReadContracts.encodeCursor(new StoreReadCursor(expectedStamp,"1"));
        pagedPort.refresh(new StoreQuery(world,Set.of(),new Sequence(0),Optional.of(nextPageCursor),10));
        check(pagedPort.view(all).significantPlayers().equals(List.of(significant)),"complete page generation publishes atomically");
        List<RestoredAggregate> archives=new ArrayList<>();
        for(int i=0;i<129;i++) {
            var value=new PlayerSignificantMemory(header(id(10000+i),1,0),new MemoryId(id(10000+i)),new PlayerId(id(20000+i)),new EventInstanceId(id(30000+i)),significant.eventDefinitionId(),significant.role(),significant.perceptionStatus(),significant.outcomeRef(),Optional.empty(),Optional.empty(),Set.of(),Set.of(),tick(0));
            archives.add(new RestoredAggregate(new PlayerSignificantAggregate(value),new Sequence(1),new Fingerprint("1".repeat(64))));
        }
        current.set(new StoreView(List.of(),archives,new Sequence(1),false,Optional.empty()));
        var bounded=new DurableMemoryPort(paged,settings);bounded.refresh(new StoreQuery(world,Set.of(),new Sequence(0),Optional.empty(),256));
        check(bounded.view(all).significantPlayers().size()==128,"resident player limit enforced across archive pages");
        check(bounded.view(new ScopeSet(world,Set.of(new ScopeRef(ScopeKind.PLAYER,id(20000))))).significantPlayers().isEmpty(),"old resident becomes cold");
        check(current.get().aggregates().size()==129,"cache eviction never deletes durable archive");
    }
}
