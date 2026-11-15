package fr.proutlost.director.adapter.neoforge.server.observation;

import fr.proutlost.director.api.model.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Actor-owned bounded coalescing buffer. A sealed batch is retained until its durable acknowledgement. */
public final class ObservationBuffer {
    private record Key(ScopeRef scope,ResourceId type,long bucket) {}
    private record RouteBucket(RouteKey route,Sector direction,long bucket) {}
    private final Map<RouteBucket,Long> routes=new LinkedHashMap<>();
    private record Pending(Tick tick,Map<String,Double> values) {}
    private final WorldId world;
    private final String bootNamespace;
    private final long bucketTicks;
    private final int capacity;
    private final Thread actor;
    private final Map<Key,Pending> pending=new LinkedHashMap<>();
    private ObservationBatch inFlight;
    private boolean saturated;
    private long sealedThrough=-1;
    public ObservationBuffer(WorldId world,long bucketTicks,int capacity) {
        this(world,bucketTicks,capacity,new Epoch(0));
    }
    /** Unique boot-fenced J01 capture identity. Epoch 0 preserves original
     * unqualified domain fixtures; native J04 MUST provide verified epoch > 0.
     */
    public ObservationBuffer(WorldId world,long bucketTicks,int capacity,Epoch bootEpoch) {
        if(bucketTicks<=0||capacity<=0)throw new IllegalArgumentException("buffer limits");
        Objects.requireNonNull(bootEpoch);
        this.bootNamespace=bootEpoch.value()==0?"":"/j04-boot/"+bootEpoch.value();
        this.world=Objects.requireNonNull(world);this.bucketTicks=bucketTicks;
        this.capacity=capacity;actor=Thread.currentThread();
    }
    private void actor(){if(Thread.currentThread()!=actor)throw new IllegalStateException("server actor required");}
    public boolean add(ScopeRef scope,ResourceId type,Tick tick,String counter,double amount) {
        actor();if(!Double.isFinite(amount)||amount<0)throw new IllegalArgumentException("counter");
        long bucket=tick.value()/bucketTicks;if(bucket<=sealedThrough)throw new IllegalArgumentException("observation for sealed bucket");
        Key key=new Key(scope,type,bucket);Pending old=pending.get(key);
        if(old==null&&pending.size()+routes.size()+inFlightSize()>=capacity){saturated=true;return false;}
        Map<String,Double> values=new TreeMap<>(old==null?Map.of():old.values());
        values.merge(counter,amount,(a,b)->{double sum=a+b;if(!Double.isFinite(sum))throw new IllegalArgumentException("counter overflow");return sum;});
        pending.put(key,new Pending(old==null||tick.compareTo(old.tick())>0?tick:old.tick(),Map.copyOf(values)));return true;
    }
    public boolean transition(RouteKey route,Sector direction,Tick tick) {
        actor();if(tick.value()/bucketTicks<=sealedThrough)throw new IllegalArgumentException("route for sealed bucket");RouteBucket key=new RouteBucket(route,direction,tick.value()/bucketTicks);
        if(!routes.containsKey(key)&&pending.size()+routes.size()+inFlightSize()>=capacity){saturated=true;return false;}
        routes.merge(key,1L,Math::addExact);return true;
    }
    private int inFlightSize(){return inFlight==null?0:inFlight.inputIds().size();}
    public boolean saturated(){actor();return saturated;}
    public int pendingScopes(){actor();return pending.size()+routes.size();}
    public ObservationBatch seal(SnapshotBatch snapshot) {return seal(snapshot,snapshot.scopeRevisions());}
    public ObservationBatch seal(SnapshotBatch snapshot,Map<ScopeRef,Revision> beforeRevisions) {
        actor();if(!world.equals(snapshot.scopes().worldId()))throw new IllegalArgumentException("wrong world");
        if(inFlight!=null)return inFlight;
        long cutoff=snapshot.capturedTick().value()/bucketTicks;
        long bucket=pending.keySet().stream().mapToLong(Key::bucket).filter(v->v<cutoff).min().orElse(Long.MAX_VALUE);
        bucket=Math.min(bucket,routes.keySet().stream().mapToLong(RouteBucket::bucket).filter(v->v<cutoff).min().orElse(Long.MAX_VALUE));
        List<DirectorObservation> observations=new ArrayList<>();
        Iterator<Map.Entry<Key,Pending>> iterator=pending.entrySet().iterator();
        while(iterator.hasNext()) {
            var entry=iterator.next();Key key=entry.getKey();Pending value=entry.getValue();
            // Only close completed buckets, so no two updates share a sealed input identity.
            if(key.bucket()!=bucket)continue;
            ObservationId id=new ObservationId(UUID.nameUUIDFromBytes((world.value()+bootNamespace+"/"+key.scope().kind()+"/"+key.scope().id()+"/"+key.type().value()+"/"+key.bucket()).getBytes(StandardCharsets.UTF_8)));
            observations.add(new DirectorObservation(id,key.type(),key.scope(),value.tick(),value.values(),new UnitValue(1)));iterator.remove();
        }
        List<RouteTransitionObservation> transitions=new ArrayList<>();
        Iterator<Map.Entry<RouteBucket,Long>> routeIterator=routes.entrySet().iterator();
        while(routeIterator.hasNext()) {
            var entry=routeIterator.next();var key=entry.getKey();
            if(key.bucket()!=bucket)continue;
            String identity=world.value()+bootNamespace+"/route/"+key.route().from().value()+"/"+key.route().to().value()+"/"+key.direction()+"/"+key.bucket();
            UUID id=UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
            transitions.add(new RouteTransitionObservation(new ObservationId(id),key.route(),new EvidenceWindowId(id),new EpisodeId(id),new Tick(Math.multiplyExact(key.bucket(),bucketTicks)),new Tick(Math.multiplyExact(key.bucket()+1,bucketTicks)),key.direction(),entry.getValue()));
            routeIterator.remove();
        }
        observations.sort(Comparator.comparing(DirectorObservation::observationId));
        transitions.sort(Comparator.comparing(RouteTransitionObservation::observationId));
        List<ObservationId> ids=new ArrayList<>(observations.stream().map(DirectorObservation::observationId).toList());
        transitions.forEach(o->ids.add(o.observationId()));ids.sort(ObservationId::compareTo);
        if(bucket!=Long.MAX_VALUE)sealedThrough=bucket;
        inFlight=new ObservationBatch(ids,observations,beforeRevisions,snapshot,List.of(),transitions,Map.of());
        return inFlight;
    }
    /** Only after the actor has proved the previous append was rejected, never after an uncertain timeout. */
    public ObservationBatch rebaseAfterRejection(CommitAck acknowledgement,SnapshotBatch snapshot,Map<ScopeRef,Revision> beforeRevisions) {
        actor();if(inFlight==null||acknowledgement.ackStatus()!=AckStatus.REJECTED)throw new IllegalArgumentException("confirmed rejection required");
        if(!world.equals(snapshot.scopes().worldId()))throw new IllegalArgumentException("wrong world");
        inFlight=new ObservationBatch(inFlight.inputIds(),inFlight.observations(),beforeRevisions,snapshot,inFlight.campEpisodes(),inFlight.routeTransitions(),inFlight.exposures());
        return inFlight;
    }
    /** Invoked by the server actor after a successful durable commit of this exact batch. */
    public void acknowledge(List<ObservationId> committed) {
        actor();if(inFlight==null||committed.size()!=inFlight.inputIds().size()||!new HashSet<>(committed).equals(new HashSet<>(inFlight.inputIds())))throw new IllegalArgumentException("wrong observation acknowledgement");
        inFlight=null;saturated=false;
    }
}
