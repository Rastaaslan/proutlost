package fr.proutlost.director.adapter.neoforge.server.observation;

import fr.proutlost.director.api.model.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Actor-local sufficient statistics with lossless configured time bins.
 * Indexed time evidence crosses the API1.0.1 boundary without phase inference.
 */
public final class CampSampler {
    public record RawWindow(AnchorId anchor,RegionId primaryRegion,EvidenceWindow window,
            DurationTicks occupiedTicks,Map<Sector,Long> sectors,Map<DistanceBand,Long> rings,
            Map<Integer,Long> timeBins,Map<GroupSizeBucket,Long> groups,int binCount) {
        public RawWindow {sectors=Map.copyOf(sectors);rings=Map.copyOf(rings);timeBins=Map.copyOf(timeBins);groups=Map.copyOf(groups);}
    }
    public static CampEpisodeObservation observation(RawWindow raw) {
        if(raw.window().independentEpisodes().size()!=1)throw new IllegalArgumentException("one episode per sealed contribution required");
        List<Long> counts=new ArrayList<>(Collections.nCopies(raw.binCount(),0L));
        raw.timeBins().forEach((index,count)->counts.set(index,count));
        var histogram=new ActivityHistogram(raw.sectors(),raw.rings(),Map.of(),raw.groups(),Optional.of(new ActivityTimeCounts(raw.binCount(),counts)));
        return new CampEpisodeObservation(new ObservationId(raw.window().id().value()),raw.primaryRegion(),raw.anchor(),raw.window().id(),
                raw.window().independentEpisodes().iterator().next(),raw.window().startTick(),raw.window().endTick(),raw.occupiedTicks(),histogram);
    }
    private record Key(AnchorId anchor,long bucket,EpisodeId episode) {}
    private static final class Accumulator {
        final AnchorId anchor;final RegionId region;final long bucket;final Set<EpisodeId> episodes=new HashSet<>();
        final Map<Sector,Long> sectors=new EnumMap<>(Sector.class);final Map<DistanceBand,Long> rings=new EnumMap<>(DistanceBand.class);
        final Map<Integer,Long> bins=new HashMap<>();final Map<GroupSizeBucket,Long> groups=new EnumMap<>(GroupSizeBucket.class);
        long occupied;
        long firstObservedStart=Long.MAX_VALUE,lastObservedEnd;
        Accumulator(AnchorId anchor,RegionId region,long bucket){this.anchor=anchor;this.region=region;this.bucket=bucket;}
    }
    private record Presence(Tick last,EpisodeId episode){}
    private final Map<Key,Accumulator> pending=new LinkedHashMap<>();
    private long closedThrough=-1;
    private final Map<EvidenceWindowId,RawWindow> frozen=new HashMap<>();
    private final Map<AnchorId,Presence> presence=new HashMap<>();
    private final int capacity,bins;
    private final long bucketTicks,gap;
    private final Thread actor=Thread.currentThread();
    public CampSampler(int capacity,int bins,long bucketTicks,long independentGap){
        if(capacity<=0||bins<=0||bins>4096||bucketTicks<=0||independentGap<=0)throw new IllegalArgumentException("camp limits");
        this.capacity=capacity;this.bins=bins;this.bucketTicks=bucketTicks;gap=independentGap;
    }
    private void actor(){if(Thread.currentThread()!=actor)throw new IllegalStateException("server actor required");}
    /** Caller supplies only players assigned to this primary anchor, after loaded-world observation.
     * For an unassigned discovery anchor, region is a reserved candidate identity, not an active region. */
    public boolean observe(AnchorRecord anchor,RegionId region,Collection<PositionSnapshot> players,
                           Tick now,long dayTime,long observedDuration,double radius) {
        actor();if(!anchor.active()||(!anchor.regionIds().isEmpty()&&!anchor.regionIds().contains(region)))throw new IllegalArgumentException("active anchor membership required");
        if(observedDuration<=0||observedDuration>bucketTicks||!Double.isFinite(radius)||radius<=0)throw new IllegalArgumentException("camp sample");
        if(players.isEmpty())return true;
        if(now.value()>0&&(now.value()-1)/bucketTicks<=closedThrough)return false;
        Presence previous=presence.get(anchor.anchorId());
        if(previous!=null&&now.value()-previous.last().value()<observedDuration)throw new IllegalArgumentException("duplicate or reordered camp sample");
        if(now.value()<observedDuration||(now.value()-observedDuration)/bucketTicks!=(now.value()-1)/bucketTicks)
            throw new IllegalArgumentException("split sample at bucket boundary");
        EpisodeId episode=previous!=null&&now.value()-previous.last().value()<gap?previous.episode():
                new EpisodeId(UUID.nameUUIDFromBytes((anchor.anchorId().value()+"/episode/"+now.value()).getBytes(StandardCharsets.UTF_8)));
        Key key=new Key(anchor.anchorId(),(now.value()-1)/bucketTicks,episode);
        if(!pending.containsKey(key)&&pending.size()>=capacity)return false;
        if(previous==null&&presence.size()>=capacity)return false;
        // No player identity or coordinate is retained after this reduction.
        for(var player:players) {
            if(!player.tick().equals(now)||!player.position().dimension().equals(anchor.dimension()))throw new IllegalArgumentException("camp snapshot mismatch");
            if(Math.hypot((double)player.position().x()-anchor.position().x(),(double)player.position().z()-anchor.position().z())>radius)throw new IllegalArgumentException("sample outside known camp");
        }
        Accumulator a=pending.computeIfAbsent(key,k->new Accumulator(anchor.anchorId(),region,k.bucket()));
        if(!a.region.equals(region))throw new IllegalStateException("drain observations before topology change");
        long duration=observedDuration;
        if(a.occupied+duration>bucketTicks)throw new IllegalArgumentException("overlapping camp occupancy");
        a.occupied=Math.addExact(a.occupied,duration);a.episodes.add(episode);
        // Evidence bounds are the observed interval, not the enclosing
        // coarse time bucket. A bucket is only an immutable dedupe key:
        // rounding both endpoints can erase an otherwise independent
        // visit separated by the configured gap (e.g. 1300 > 1200 ticks).
        a.firstObservedStart=Math.min(a.firstObservedStart,now.value()-duration);
        a.lastObservedEnd=Math.max(a.lastObservedEnd,now.value());
        GroupSizeBucket group=players.size()==1?GroupSizeBucket.SOLO:players.size()==2?GroupSizeBucket.PAIR:players.size()<=4?GroupSizeBucket.SMALL:GroupSizeBucket.LARGE;
        a.groups.merge(group,duration,Math::addExact);
        int bin=(int)(Math.floorMod(dayTime,24000L)*bins/24000L);a.bins.merge(bin,duration,Math::addExact);
        for(var player:players) {
            double x=(double)player.position().x()-anchor.position().x(),z=(double)player.position().z()-anchor.position().z();
            Sector sector=Sector.values()[Math.floorMod((int)Math.round(Math.atan2(x,-z)/(Math.PI/4)),8)];
            double fraction=Math.hypot(x,z)/radius;DistanceBand ring=fraction<1.0/3?DistanceBand.INNER:fraction<2.0/3?DistanceBand.MIDDLE:DistanceBand.OUTER;
            a.sectors.merge(sector,duration,Math::addExact);a.rings.merge(ring,duration,Math::addExact);
        }
        presence.put(anchor.anchorId(),new Presence(now,episode));return true;
    }
    /** Approximate occupancy between consecutive camp censuses. Split at bucket boundaries;
     * relative sectors use the current observed position, never a reconstructed player path.
     * Synthetic interval endpoints below are integration boundaries, not extra world observations.
     */
    public boolean observeInterval(AnchorRecord anchor,RegionId region,Collection<PositionSnapshot> players,
            Tick now,long dayTime,long duration,double radius) {
        actor();if(duration<=0||now.value()<duration)throw new IllegalArgumentException("camp interval");
        for(var p:players)if(!p.tick().equals(now))throw new IllegalArgumentException("camp census timestamp");
        long start=now.value()-duration;
        long pieces=(now.value()-1)/bucketTicks-start/bucketTicks+1;
        if(pieces>capacity)return false;
        boolean accepted=true;
        while(start<now.value()) {
            long end=start+Math.min(now.value()-start,bucketTicks-start%bucketTicks);
            Tick boundary=new Tick(end);
            List<PositionSnapshot> integrated=players.stream().map(p->new PositionSnapshot(p.position(),boundary)).toList();
            // Day-time is observed at the census, not extrapolated through sleep/time changes.
            accepted=observe(anchor,region,integrated,boundary,dayTime,end-start,radius)&&accepted;
            start=end;
        }
        return accepted;
    }
    /** Closes all completed buckets to late observations; immutable snapshots retained until acknowledge after conversion and durable commit under API1.0.1. */
    public List<RawWindow> sealed(Tick now) {
        actor();closedThrough=Math.max(closedThrough,now.value()/bucketTicks-1);
        for(var a:pending.values()) {
            if(a.bucket>=now.value()/bucketTicks)continue;
            Tick start=new Tick(a.firstObservedStart),end=new Tick(a.lastObservedEnd);
            if(start.compareTo(end)>=0 ||
               start.value()/bucketTicks!=a.bucket ||
               (end.value()-1)/bucketTicks!=a.bucket ||
               end.value()-start.value()<a.occupied)
                throw new IllegalStateException("camp interval/bucket invariant");
            var id=new EvidenceWindowId(UUID.nameUUIDFromBytes((a.anchor.value()+"/window/"+a.bucket+"/"+a.episodes.iterator().next().value()).getBytes(StandardCharsets.UTF_8)));
            frozen.putIfAbsent(id,new RawWindow(a.anchor,a.region,new EvidenceWindow(id,start,end,a.episodes),new DurationTicks(a.occupied),a.sectors,a.rings,a.bins,a.groups,bins));
        }
        return frozen.values().stream().sorted(Comparator.comparing(w->w.window().id().value().toString())).toList();
    }
    public void acknowledge(Collection<EvidenceWindowId> committed,Tick now){
        actor();Map<EvidenceWindowId,RawWindow> available=frozen;
        if(new HashSet<>(committed).size()!=committed.size()||!available.keySet().containsAll(committed))throw new IllegalArgumentException("unknown camp acknowledgement");
        committed.forEach(id->{var w=available.remove(id);pending.remove(new Key(w.anchor(),w.window().startTick().value()/bucketTicks,w.window().independentEpisodes().iterator().next()));});
        Set<AnchorId> retained=new HashSet<>();pending.keySet().forEach(k->retained.add(k.anchor()));
        presence.entrySet().removeIf(e->now.value()-e.getValue().last().value()>=gap&&!retained.contains(e.getKey()));
    }
}
