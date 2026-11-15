package fr.proutlost.director.adapter.neoforge.server.effects.weathercore;

import fr.proutlost.director.adapter.neoforge.server.effects.*;
import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import io.github.steamautomata.weathercore.WeatherCore;
import io.github.steamautomata.weathercore.api.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.phys.Vec3;
import java.util.*;
import java.util.function.*;

/** J04 binding for the real WeatherCore manager. No command execution, own WAL,
 * registry replacement, raw native timer restoration or blanket centered cleanup.
 * ServerEffectPort consumes CommitPermit before calling apply, exactly as before.
 */
public final class WeatherCoreGlobalBinding implements RestorableWeatherBinding {
    private static final ResourceId STATE=new ResourceId("proutlost_director:weathercore_global_state_v2");
    private static final ResourceId OWNER=new ResourceId("proutlost_director:owner_v1");
    private static final CapabilityId CAP=new CapabilityId("weather.global_lease_v1");
    private final Function<DimensionId,ServerLevel> levels;
    private final Supplier<OwnershipView> ownership;
    private final Function<DimensionId,Revision> externalRevision;
    private final Map<OperationToken,BlobContent> restores=new HashMap<>();
    private final int capacity;
    public WeatherCoreGlobalBinding(Function<DimensionId,ServerLevel> levels,Supplier<OwnershipView> ownership,
            Function<DimensionId,Revision> externalRevision,int capacity) {
        this.levels=Objects.requireNonNull(levels);this.ownership=Objects.requireNonNull(ownership);
        this.externalRevision=Objects.requireNonNull(externalRevision);
        if(capacity<1)throw new IllegalArgumentException("weather capacity");this.capacity=capacity;
    }
    public ProviderMode mode(){return ProviderMode.PRODUCTION;}
    public Set<PrimitiveId> supportedPrimitives(){return Set.of(new PrimitiveId("proutlost:global_weather_lease"));}
    public Set<CapabilityId> capabilities(){return Set.of(CAP);}
    private ServerLevel level(DimensionId d) {
        var l=Objects.requireNonNull(levels.apply(d),"weather dimension unavailable");
        if(!l.getServer().isSameThread())throw new IllegalStateException("weather server actor required");
        if(!l.dimension().location().toString().equals(d.value())||!l.dimensionType().hasSkyLight())
            throw new IllegalArgumentException("weather dimension");
        if(WeatherManager.get(l)==null)throw new IllegalStateException("WeatherCore manager not loaded");return l;
    }
    private static ResourceKey key(DimensionId d){return new ResourceKey(OwnershipDomain.WEATHER,d,"weather");}
    private static WeatherCoreLeaseState read(ServerLevel level) {
        long tick=level.getGameTime();
        var actual=WeatherManager.get(level).weathersAt(Vec3.ZERO).filter(w->w.scope() instanceof Scope.Global).toList();
        String physical="minecraft:clear";
        for(var weather:actual){var id=Objects.requireNonNull(WeatherCore.WEATHER_REGISTRY.getKey(weather.type())).toString();
            if(id.equals("minecraft:rain")||id.equals("minecraft:thunder"))physical=id;}
        var nativeData=(ServerLevelData)level.getLevelData();
        if(nativeData.isRaining()!=!physical.equals("minecraft:clear")||nativeData.isThundering()!=physical.equals("minecraft:thunder"))
            throw new IllegalStateException("Core manager/native physical flags diverged");
        var marker=WeatherCoreGlobalLeaseMarker.active(level);
        if(marker.isPresent()){
            if(!marker.orElseThrow().liveMatches(level,actual))throw new IllegalStateException("Core owned global changed or expired");
            return WeatherCoreLeaseState.owned(marker.orElseThrow().lease());
        }
        var globals=actual.stream().map(w->new WeatherCoreLeaseState.Global(
            Objects.requireNonNull(WeatherCore.WEATHER_REGISTRY.getKey(w.type())).toString(),w.uuid(),Math.addExact(tick,w.getTimeLeft()))).toList();
        var state=new WeatherCoreLeaseState(globals);state.requireProjectable(tick);return state;
    }
    private static BlobContent blob(WeatherCoreLeaseState state){return BlobContent.capture(STATE,1,state.bytes());}
    private static WeatherCoreLeaseState decode(BlobContent b) {
        if(!b.reference().codecId().equals(STATE)||b.reference().codecVersion()!=1)
            throw new IllegalArgumentException("WeatherCore blob codec/version");
        return WeatherCoreLeaseState.read(b.bytes());
    }
    private static BlobContent find(BlobBundle b,BlobRef r){return b.contents().stream().filter(v->v.reference().equals(r)).findFirst().orElseThrow(()->new IllegalArgumentException("missing sealed weather blob"));}
    private static GlobalWeatherLease args(EffectIntent i) {
        if(!(i.typedArgs() instanceof GlobalWeatherLease a)||i.version()!=1||!a.dimension().equals(i.expectedFootprint().dimension()))
            throw new IllegalArgumentException("WeatherCore intent");
        if(!i.expectedFootprint().cells().isEmpty()||!i.expectedFootprint().bounds().isEmpty())
            throw new IllegalArgumentException("global weather is dimension-wide, not regional");return a;
    }
    private static boolean inverse(GlobalWeatherLease a){return a.weather().value().equals("proutlost_director:restore");}
    private static OwnershipRecord capturedOwner(PreparedEffect p,BlobBundle b,ResourceKey k) {
        try {
            var refs=p.durableBlobs().snapshots().stream().filter(r->r.codecId().equals(OWNER)&&r.codecVersion()==1).toList();
            if(refs.size()!=1)return null;
            var owner=new ModelCodec<>(OwnershipRecord.class).decode(find(b,refs.getFirst()).bytes());
            return owner.resourceKey().equals(k)?owner:null;
        }catch(RuntimeException invalid){return null;}
    }
    public Fingerprint observedFingerprint(DimensionId d){return blob(read(level(d))).reference().hash();}
    public void registerRestore(EffectIntent i,BlobContent stop) {
        var a=args(i);if(!inverse(a))throw new IllegalArgumentException("inverse intent required");
        var state=decode(stop);if(state.lease().isPresent())throw new IllegalArgumentException("cannot restore/replay an owned Core impact");
        state.requireProjectable(level(a.dimension()).getGameTime());
        if(restores.size()>=capacity||restores.putIfAbsent(i.operationToken(),stop)!=null)
            throw new IllegalStateException("weather restore capacity/duplicate");
    }
    /** Read-only J03 survey. It contains no owner capsule and cannot be applied.
     * Real J04 ownership and an exact native recapture remain mandatory. */
    public EffectPreparation surveyBirth(EffectIntent i,PlanningSnapshot context){
        var a=args(i);var nativeLevel=level(a.dimension());var k=key(a.dimension());
        if(inverse(a)||a.duration().value()<=0||a.duration().value()>72000
                ||!a.externalRevision().equals(externalRevision.apply(a.dimension())))
            throw new IllegalArgumentException("global survey lease/revision");
        var before=read(nativeLevel);if(before.lease().isPresent())throw new IllegalStateException("global native lease already owned");
        var old=context.ownership().records().get(k);
        if(old!=null&&old.disposition()!=OwnershipDisposition.RELEASED)
            throw new IllegalStateException("global weather owner requires recovery or external consent");
        var epoch=old==null?new Epoch(0):old.epoch();var pre=blob(before);
        var survey=BlobContent.capture(new ResourceId("proutlost_director:global_birth_survey_v1"),1,new ModelCodec<>(EffectIntent.class).encode(i));
        var cost=new BudgetVector(0,0,0,0,0,1,0,0,0,0);
        var read=new ReadSet(context.snapshot().scopeRevisions(),Map.of(k,epoch),Map.of(k,pre.reference().hash()));
        var plan=new PreparedEffect(i,context.snapshot(),List.of(new ResourceClaim(k,ClaimPolicy.EXCLUSIVE,cost,Optional.empty(),epoch)),read,Map.of(),cost,List.of(),capabilities(),
            new EffectBlobs(Set.of(pre.reference()),survey.reference(),survey.reference(),Map.of(k,pre.reference()),Map.of()));
        return new EffectPreparation(i,new ValidationResult(true,List.of(),read),Optional.of(plan),new BlobBundle(List.of(pre,survey)));
    }
    public EffectPreparation capture(EffectIntent i,PlanningSnapshot context) {
        var a=args(i);var level=level(a.dimension());var k=key(a.dimension());
        var owner=Objects.requireNonNull(context.ownership().records().get(k),"weather admitted owner required");
        if(owner.disposition()!=OwnershipDisposition.DIRECTOR_ACTIVE||owner.ownerTokens().size()!=1
                ||!a.externalRevision().equals(externalRevision.apply(a.dimension())))
            throw new IllegalArgumentException("weather owner/revision");
        long tick=level.getGameTime();var before=read(level);WeatherCoreLeaseState after;
        if(inverse(a)){
            var nativeLease=before.lease().orElseThrow(()->new IllegalStateException("counterfactual weather entropy boundary (Core lease no longer owned)"));
            if(!nativeLease.event().equals(i.instanceId().value())||!sameOwner(nativeLease,owner))
                throw new IllegalStateException("counterfactual weather entropy boundary (Core lease owner changed)");
            after=decode(Objects.requireNonNull(restores.remove(i.operationToken()),"sealed restore missing"));
        }
        else {
            if(a.duration().value()<=0||a.duration().value()>72000)throw new IllegalArgumentException("weather TTL");
            if(before.lease().isPresent())throw new IllegalStateException("Core global lease already active");
            if(WeatherCoreGlobalLeaseMarker.markers(level).size()>=capacity)throw new IllegalStateException("Core lease receipt capacity");
            var uuid=UUID.nameUUIDFromBytes(("proutlost-weathercore-v1/"+i.operationToken().value()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            after=WeatherCoreLeaseState.owned(new WeatherCoreLeaseState.Lease(i.instanceId().value(),i.operationToken().value(),
                owner.ownerTokens().iterator().next().value(),owner.epoch().value(),owner.creationMutationId().value(),a.weather().value(),uuid,a.duration().value()));
        }
        after.requireProjectable(tick);
        var pre=blob(before);var post=blob(after);
        var ownerBlob=BlobContent.capture(OWNER,1,new ModelCodec<>(OwnershipRecord.class).encode(owner));
        var snapshots=Set.of(pre.reference(),ownerBlob.reference());
        var refs=new EffectBlobs(snapshots,post.reference(),pre.reference(),Map.of(k,pre.reference()),Map.of(k,post.reference()));
        var readSet=new ReadSet(context.snapshot().scopeRevisions(),Map.of(k,owner.epoch()),Map.of(k,pre.reference().hash()));
        var cost=new BudgetVector(0,0,0,0,0,1,0,0,0,0);
        var claim=new ResourceClaim(k,ClaimPolicy.EXCLUSIVE,cost,Optional.empty(),owner.epoch());
        var p=new PreparedEffect(i,context.snapshot(),List.of(claim),readSet,Map.of(k,post.reference().hash()),cost,List.of(),capabilities(),refs);
        var all=new LinkedHashMap<BlobRef,BlobContent>();for(var b:List.of(pre,post,ownerBlob))all.put(b.reference(),b);
        return new EffectPreparation(i,new ValidationResult(true,List.of(),readSet),Optional.of(p),new BlobBundle(List.copyOf(all.values())));
    }
    public boolean revalidate(PreparedEffect p,BlobBundle blobs) {
        try {
            var a=args(p.intent());var level=level(a.dimension());var k=key(a.dimension());
            var owner=ownership.get().records().get(k);
            if(!WeatherOwnershipFence.sameOwner(capturedOwner(p,blobs,k),owner)
                ||!owner.epoch().equals(p.preconditions().epochs().get(k))
                ||!a.externalRevision().equals(externalRevision.apply(a.dimension())))return false;
            var pre=find(blobs,p.durableBlobs().preStates().get(k));decode(pre).requireProjectable(level.getGameTime());
            if(!pre.reference().hash().equals(p.preconditions().fingerprints().get(k))||!read(level).equals(decode(pre)))return false;
            var post=find(blobs,p.durableBlobs().expectedPostStates().get(k));decode(post).requireProjectable(level.getGameTime());
            return post.reference().hash().equals(p.expectedPost().get(k));
        }catch(RuntimeException unavailable){return false;}
    }
    public SliceResult apply(PreparedEffect p,BlobBundle blobs) {
        if(!revalidate(p,blobs))throw new IllegalStateException("WeatherCore changed before permitted write");
        var a=args(p.intent());var level=level(a.dimension());var k=key(a.dimension());
        var desired=decode(find(blobs,p.durableBlobs().expectedPostStates().get(k)));
        var manager=WeatherManager.get(level);
        if(desired.lease().isPresent()){
            var lease=desired.lease().orElseThrow();
            // Metadata is initialized in the factory BEFORE Core's first packet.
            // Inserting this finite cursor before the global ensures its expiry
            // guard runs first; it never permits a random thunder replacement.
            var marker=WeatherCoreGlobalLeaseMarker.duringCreation(lease,level.getGameTime(),
                ()->manager.addCenteredWeather(WeatherCoreRegionalTypes.GLOBAL_LEASE.get(),new Scope.Centered(Vec3.ZERO,1)));
            marker.setTimeLeft(Math.toIntExact(lease.duration()+1));
            var type=Objects.requireNonNull(WeatherCore.WEATHER_REGISTRY.get(ResourceLocation.parse(lease.type())));
            var weather=manager.overrideWeather(type);weather.setSyncUuid(lease.global());weather.setTimeLeft(Math.toIntExact(lease.duration()+1));
        }else{
            // Only the matching active marker was captured and revalidated.
            // Retire its authority before restoring the sealed, aged baseline.
            WeatherCoreGlobalLeaseMarker.active(level).ifPresent(marker->marker.retire(level));
            boolean first=true;
            for(var g:desired.globals()){
                var type=Objects.requireNonNull(WeatherCore.WEATHER_REGISTRY.get(ResourceLocation.parse(g.type())));
                var weather=first?manager.overrideWeather(type):manager.addWeather(type);first=false;
                weather.setSyncUuid(g.uuid());weather.setTimeLeft(g.remainingAt(level.getGameTime()));
            }
        }
        manager.setDirty();
        var observed=blob(read(level)).reference().hash();
        return new SliceResult(observed.equals(p.expectedPost().get(k))?Outcome.SUCCEEDED:Outcome.PARTIAL,
            Set.of(p.intent().operationToken()),Map.of(k,observed),0,Set.of(),Set.of(),List.of());
    }
    private boolean sameOwner(WeatherCoreLeaseState.Lease lease,OwnershipRecord owner){
        return owner!=null&&owner.ownerTokens().size()==1&&owner.ownerTokens().iterator().next().value().equals(lease.owner())
            &&owner.epoch().value()==lease.epoch()&&owner.creationMutationId().value().equals(lease.mutation());
    }
    @Override public boolean expiredByBackend(PreparedEffect p){
        var a=args(p.intent());var level=level(a.dimension());var k=key(a.dimension());
        var owner=ownership.get().records().get(k);
        for(var marker:WeatherCoreGlobalLeaseMarker.markers(level)){
            var lease=marker.lease();
            if(lease.operation().equals(p.intent().operationToken().value())&&lease.event().equals(p.intent().instanceId().value())
                &&sameOwner(lease,owner)&&blob(WeatherCoreLeaseState.owned(lease)).reference().hash().equals(p.expectedPost().get(k))
                &&marker.hasClearReceipt(level,lease))return true;
        }return false;
    }
    @Override public void releaseReceipt(PreparedEffect p){
        var a=args(p.intent());var level=level(a.dimension());var owner=ownership.get().records().get(key(a.dimension()));
        if(owner==null||!Set.of(OwnershipDisposition.RELEASED,OwnershipDisposition.YIELDED_TO_PLAYER).contains(owner.disposition()))
            throw new IllegalStateException("Core receipt retirement requires durable released owner");
        for(var marker:WeatherCoreGlobalLeaseMarker.markers(level)){
            var lease=marker.lease();
            if(lease.operation().equals(p.intent().operationToken().value())&&lease.event().equals(p.intent().instanceId().value())
                &&owner.ownerTokens().stream().anyMatch(t->t.value().equals(lease.owner()))&&owner.creationMutationId().value().equals(lease.mutation())
                &&blob(WeatherCoreLeaseState.owned(lease)).reference().hash().equals(p.expectedPost().get(key(a.dimension())))
                &&marker.expired())marker.retire(level);
        }
    }
    public ReconcileResult reconcile(RecoveryProbe probe) {
        var p=probe.durableIntent();var a=args(p.intent());var k=key(a.dimension());
        var proofs=probe.loadedProofs().stream().filter(v->v.resource().equals(k)).toList();
        if(proofs.isEmpty())return new ReconcileResult(ReconcileStatus.UNAVAILABLE,List.of(),List.of());
        if(proofs.size()!=1)return new ReconcileResult(ReconcileStatus.AMBIGUOUS,proofs,List.of());
        var proof=proofs.getFirst();var owner=ownership.get().records().get(k);
        if(owner==null)return new ReconcileResult(ReconcileStatus.UNAVAILABLE,proofs,List.of());
        if(owner.disposition()==OwnershipDisposition.YIELDED_TO_PLAYER||!owner.playerOverrideEpoch().equals(proof.playerOverrideEpoch())||proof.playerOverrideEpoch().value()>0)
            return new ReconcileResult(ReconcileStatus.PLAYER_OVERRIDE,proofs,List.of());
        if(!WeatherOwnershipFence.sameOwner(capturedOwner(p,probe.blobs(),k),owner)
            ||!owner.epoch().equals(p.preconditions().epochs().get(k))||!proof.ownerEpoch().equals(owner.epoch())
            ||proof.ownerToken().filter(owner.ownerTokens()::contains).isEmpty()
            ||!a.externalRevision().equals(externalRevision.apply(a.dimension())))
            return new ReconcileResult(ReconcileStatus.AMBIGUOUS,proofs,List.of());
        Fingerprint actual;
        try{actual=observedFingerprint(a.dimension());}catch(RuntimeException unavailable){return new ReconcileResult(ReconcileStatus.UNAVAILABLE,proofs,List.of());}
        var status=ReconcileStatus.AMBIGUOUS;
        if(proof.status()==EvidenceStatus.CONFIRMED&&proof.observedHash().equals(Optional.of(actual))) {
            if(actual.equals(p.expectedPost().get(k)))status=ReconcileStatus.SAME_POST;
            else if(actual.equals(p.preconditions().fingerprints().get(k))&&proof.localAbsenceProven())status=ReconcileStatus.SAME_PRE_PROVEN;
        }
        return new ReconcileResult(status,proofs,List.of());
    }
}
