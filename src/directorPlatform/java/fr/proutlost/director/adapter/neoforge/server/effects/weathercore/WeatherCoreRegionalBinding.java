package fr.proutlost.director.adapter.neoforge.server.effects.weathercore;

import fr.proutlost.director.adapter.neoforge.server.effects.*;
import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import io.github.steamautomata.weathercore.api.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** Separate shared-region provider. It never masquerades as J06 local_weather
 * or the dimension-wide global_weather_lease, and never changes global rainfall.
 * New application and cleanup are invoked only behind ServerEffectPort's permit.
 * A constructor is registration, not qualification: the caller must supply its
 * independently qualified capability set when installing ServerEffectPort.
 */
public final class WeatherCoreRegionalBinding implements ServerEffectPort.Binding {
    public static final PrimitiveId PRIMITIVE=new PrimitiveId("proutlost:regional_weather_lease");
    public static final CapabilityId CAPABILITY=new CapabilityId("weather.regional_scope_v1");
    public static final ResourceId PROFILE=new ResourceId("proutlost_director:regional_mist");
    public static final ResourceId REARM=new ResourceId("proutlost_director:regional_weather_rearm");
    public static final ResourceId STOP=new ResourceId("proutlost_director:regional_weather_stop");
    private static final ResourceId STATE=new ResourceId("proutlost_director:weathercore_regional_witness_v2");
    private static final ResourceId OWNER=new ResourceId("proutlost_director:owner_v1");
    private final Function<DimensionId,ServerLevel> levels;
    private final Supplier<OwnershipView> ownership;
    private final Function<ResourceKey,Revision> externalRevision;
    private final int capacity;
    private final Map<OperationToken,BlobContent> stops=new HashMap<>();
    private record Observation(WeatherCoreRegionalState state,boolean renderArmed,Optional<DirectorRegionalMistWeather> live,Set<UUID> identities) {
        WeatherCoreRegionalWitness witness(){return new WeatherCoreRegionalWitness(state,renderArmed);}
    }

    public WeatherCoreRegionalBinding(Function<DimensionId,ServerLevel> levels,Supplier<OwnershipView> ownership,
            Function<ResourceKey,Revision> externalRevision,int capacity){
        this.levels=Objects.requireNonNull(levels);this.ownership=Objects.requireNonNull(ownership);this.externalRevision=Objects.requireNonNull(externalRevision);
        if(capacity<1||capacity>64)throw new IllegalArgumentException("regional provider capacity");this.capacity=capacity;
    }
    public ProviderMode mode(){return ProviderMode.PRODUCTION;}
    public Set<PrimitiveId> supportedPrimitives(){return Set.of(PRIMITIVE);}
    public Set<CapabilityId> capabilities(){return Set.of(CAPABILITY);}
    public static ResourceKey resource(DimensionId dimension,EventInstanceId event){
        return new ResourceKey(OwnershipDomain.WEATHER,dimension,"region:"+event.value());
    }
    /** Typed internal API. The shape and all chunk observations still undergo
     * capture and revalidation on the server actor; this creates no world effect.
     */
    public static EffectIntent request(OperationToken operation,EventInstanceId event,Footprint footprint,
            DurationTicks duration,Revision regionalRevision){
        Objects.requireNonNull(footprint);
        return new EffectIntent(operation,PRIMITIVE,1,new RegionalWeatherLease(footprint.dimension(),PROFILE,duration,regionalRevision),footprint,event);
    }
    public static EffectIntent cleanupRequest(OperationToken operation,EventInstanceId event,Footprint footprint,Revision regionalRevision){
        return new EffectIntent(operation,PRIMITIVE,1,new RegionalWeatherLease(footprint.dimension(),STOP,new DurationTicks(1),regionalRevision),footprint,event);
    }
    public static EffectIntent resumeRequest(OperationToken operation,EventInstanceId event,Footprint footprint,Revision regionalRevision){
        return new EffectIntent(operation,PRIMITIVE,1,new RegionalWeatherLease(footprint.dimension(),REARM,new DurationTicks(1),regionalRevision),footprint,event);
    }
    private ServerLevel level(DimensionId dimension){
        var level=Objects.requireNonNull(levels.apply(dimension),"regional dimension unavailable");
        if(!level.getServer().isSameThread())throw new IllegalStateException("regional server actor required");
        if(!level.dimension().location().toString().equals(dimension.value())||WeatherManager.get(level)==null)
            throw new IllegalArgumentException("regional level/provider mismatch");
        if(!WeatherCoreRegionalTypes.MIST.get().canSummonIn(level))throw new IllegalArgumentException("regional weather dimension unsupported");return level;
    }
    private RegionalWeatherLease args(EffectIntent intent){
        if(!intent.primitive().equals(PRIMITIVE)||intent.version()!=1||!(intent.typedArgs() instanceof RegionalWeatherLease args)
            ||!args.dimension().equals(intent.expectedFootprint().dimension())||!(args.profile().equals(PROFILE)||args.profile().equals(STOP)||args.profile().equals(REARM)))
            throw new IllegalArgumentException("regional weather intent identity/profile");
        if(!args.profile().equals(STOP)&&(args.duration().value()<=0||args.duration().value()>72000))throw new IllegalArgumentException("regional weather TTL");
        return args;
    }
    private static WeatherCoreRegionalState.Region shape(Footprint footprint,ServerLevel level,boolean stop){
        if(!footprint.cells().isEmpty()||footprint.bounds().size()!=1||footprint.loadedChunks().size()>4225)
            throw new IllegalArgumentException("one bounded regional weather shape required");
        var box=footprint.bounds().getFirst();long width=(long)box.maxXExclusive()-box.minX(),depth=(long)box.maxZExclusive()-box.minZ();
        if(width!=depth||width<1||width>1024||box.minY()!=level.getMinBuildHeight()||box.maxYExclusive()!=level.getMaxBuildHeight())
            throw new IllegalArgumentException("regional circle requires its full-height square footprint");
        var region=new WeatherCoreRegionalState.Region(box.minX()+width/2.0,box.minZ()+depth/2.0,width/2.0f);
        var exact=new HashSet<ChunkRef>();
        for(int x=box.minX()>>4;x<=((box.maxXExclusive()-1)>>4);x++)for(int z=box.minZ()>>4;z<=((box.maxZExclusive()-1)>>4);z++)
            exact.add(new ChunkRef(footprint.dimension(),x,z));
        if(!(stop&&footprint.loadedChunks().isEmpty())&&!exact.equals(footprint.loadedChunks()))throw new IllegalArgumentException("regional footprint has missing/extra chunk observations");
        return region;
    }
    private static boolean loaded(ServerLevel level,Footprint footprint){
        return footprint.loadedChunks().stream().allMatch(c->level.getChunkSource().getChunkNow(c.x(),c.z())!=null);
    }
    private static UUID weatherId(OperationToken operation){
        return UUID.nameUUIDFromBytes(("proutlost-regional-weather-v1/"+operation.value()).getBytes(StandardCharsets.UTF_8));
    }
    private static BlobContent blob(WeatherCoreRegionalState state){return blob(new WeatherCoreRegionalWitness(state,state.kind()==WeatherCoreRegionalState.Kind.ACTIVE));}
    private static BlobContent blob(WeatherCoreRegionalWitness witness){return BlobContent.capture(STATE,2,witness.bytes());}
    private static WeatherCoreRegionalWitness witness(BlobContent blob){
        if(!blob.reference().codecId().equals(STATE)||blob.reference().codecVersion()!=2)throw new IllegalArgumentException("regional witness codec/version");
        return WeatherCoreRegionalWitness.read(blob.bytes());
    }
    private static WeatherCoreRegionalState state(BlobContent blob){
        return witness(blob).state();
    }
    private static BlobContent find(BlobBundle bundle,BlobRef ref){return bundle.contents().stream().filter(b->b.reference().equals(ref)).findFirst().orElseThrow(()->new IllegalArgumentException("regional sealed blob missing"));}
    private static OwnershipRecord owner(PreparedEffect p,BlobBundle blobs,ResourceKey key){
        try{
            var refs=p.durableBlobs().snapshots().stream().filter(r->r.codecId().equals(OWNER)&&r.codecVersion()==1).toList();
            if(refs.size()!=1)return null;var owner=new ModelCodec<>(OwnershipRecord.class).decode(find(blobs,refs.getFirst()).bytes());
            return owner.resourceKey().equals(key)?owner:null;
        }catch(RuntimeException malformed){return null;}
    }
    private static void sameNativeScope(WeatherCoreRegionalState state,CompoundTag scope){
        if(!scope.getString("scope_type").equals("centered")||!scope.contains("x",Tag.TAG_DOUBLE)||!scope.contains("z",Tag.TAG_DOUBLE)
            ||!scope.contains("radius",Tag.TAG_FLOAT)||Double.compare(scope.getDouble("x"),state.region().centerX())!=0
            ||Double.compare(scope.getDouble("z"),state.region().centerZ())!=0||Float.compare(scope.getFloat("radius"),state.region().radius())!=0)
            throw new IllegalStateException("regional provider geometry differs from sealed owner state");
    }
    /** Public provider serialization is an actor-only in-memory observation,
     * not a second store or a load/replace of unrelated weather.
     */
    private Observation observe(ServerLevel level,EventInstanceId event){
        var nbt=WeatherManager.get(level).save(new CompoundTag(),level.registryAccess());
        var all=nbt.getList("weathers",Tag.TAG_COMPOUND);if(all.size()>64)throw new IllegalStateException("weather provider enumeration bound exceeded");
        WeatherCoreRegionalState match=null;boolean armed=false;var identities=new HashSet<UUID>();
        for(int n=0;n<all.size();n++){
            var row=all.getCompound(n);
            if(!row.hasUUID("uuid")||!identities.add(row.getUUID("uuid")))throw new IllegalStateException("weather provider UUID absent/duplicate");
            if(!row.getString("id").equals(PROFILE.value()))continue;
            var data=row.getCompound("data");
            if(data.getInt("schema")!=1||!data.contains("sealed_lease",Tag.TAG_BYTE_ARRAY))throw new IllegalStateException("unknown regional provider data");
            var s=WeatherCoreRegionalState.read(data.getByteArray("sealed_lease"));
            if(s.kind()==WeatherCoreRegionalState.Kind.ABSENT)continue;
            if(!row.hasUUID("uuid")||!row.getUUID("uuid").equals(s.weather()))throw new IllegalStateException("regional UUID mismatch");
            sameNativeScope(s,row.getCompound("scope"));
            if(!s.event().equals(event.value()))continue;
            if(match!=null)throw new IllegalStateException("duplicate regional event/UUID");match=s;armed=data.getBoolean("render_armed");
        }
        if(match==null)return new Observation(WeatherCoreRegionalState.absent(),false,Optional.empty(),Set.copyOf(identities));
        final var expected=match;
        var instances=WeatherManager.get(level).weathersAt(new Vec3(match.region().centerX(),0,match.region().centerZ()))
            .filter(w->w.uuid().equals(expected.weather())).toList();
        if(instances.size()!=1||!(instances.getFirst() instanceof DirectorRegionalMistWeather nativeWeather)
            ||!nativeWeather.lease().equals(match)||nativeWeather.renderArmed()!=armed)throw new IllegalStateException("regional native instance missing or ambiguous");
        return new Observation(match,armed,Optional.of(nativeWeather),Set.copyOf(identities));
    }
    public Fingerprint observedFingerprint(DimensionId dimension,EventInstanceId event){return blob(observe(level(dimension),event).witness()).reference().hash();}
    /** Current native state is read-only evidence, never permission to write. */
    public WeatherCoreRegionalWitness observedState(DimensionId dimension,EventInstanceId event){return observe(level(dimension),event).witness();}
    /** Hydration only. It cannot create, retire or rearm any weather. */
    public void registerCleanup(EffectIntent inverse,BlobContent stop){
        var args=args(inverse);if(!args.profile().equals(STOP))throw new IllegalArgumentException("regional cleanup intent required");
        level(args.dimension());var state=state(stop);
        if(state.kind()!=WeatherCoreRegionalState.Kind.RETIRED||!state.event().equals(inverse.instanceId().value()))
            throw new IllegalArgumentException("regional cleanup stop identity");
        if(stops.size()>=capacity||stops.putIfAbsent(inverse.operationToken(),stop)!=null)throw new IllegalStateException("regional cleanup hydration duplicate/capacity");
    }
    private static boolean owns(WeatherCoreRegionalState state,OwnershipRecord owner,EventInstanceId event,WeatherCoreRegionalState.Region shape){
        return state.kind()!=WeatherCoreRegionalState.Kind.ABSENT&&state.event().equals(event.value())&&state.region().equals(shape)
            &&owner.ownerTokens().size()==1&&owner.ownerTokens().iterator().next().value().equals(state.owner())
            &&owner.epoch().value()==state.ownerEpoch()&&owner.playerOverrideEpoch().value()==state.overrideEpoch()
            &&owner.creationMutationId().value().equals(state.mutation());
    }
    /** Read-only production planning before the actual owner row exists. This
     * seals observed absence and required claims; it is never an apply capsule.
     * The coordinator must force a genuine owner and recapture before PREPARE. */
    public EffectPreparation surveyBirth(EffectIntent i,PlanningSnapshot context){
        var args=args(i);var level=level(args.dimension());shape(i.expectedFootprint(),level,false);
        if(!args.profile().equals(PROFILE)||!loaded(level,i.expectedFootprint())
            ||!context.snapshot().loadedChunks().containsAll(i.expectedFootprint().loadedChunks()))throw new IllegalArgumentException("regional survey actual loaded birth required");
        var observed=observe(level,i.instanceId());if(observed.state().kind()!=WeatherCoreRegionalState.Kind.ABSENT)
            throw new IllegalArgumentException("regional survey would replace existing native weather");
        var key=resource(args.dimension(),i.instanceId());if(context.ownership().records().containsKey(key))
            throw new IllegalArgumentException("regional survey existing owner requires recovery, never new birth");
        var pre=blob(observed.witness());var survey=BlobContent.capture(new ResourceId("proutlost_director:regional_birth_survey_v1"),1,new ModelCodec<>(EffectIntent.class).encode(i));
        var budget=new BudgetVector(0,0,0,0,0,1,0,0,0,0);var claim=new ResourceClaim(key,ClaimPolicy.EXCLUSIVE,budget,Optional.empty(),new Epoch(0));
        var read=new ReadSet(context.snapshot().scopeRevisions(),Map.of(key,new Epoch(0)),Map.of(key,pre.reference().hash()));
        var refs=new EffectBlobs(Set.of(pre.reference()),survey.reference(),survey.reference(),Map.of(key,pre.reference()),Map.of());
        var plan=new PreparedEffect(i,context.snapshot(),List.of(claim),read,Map.of(),budget,List.of(),capabilities(),refs);
        return new EffectPreparation(i,new ValidationResult(true,List.of(),read),Optional.of(plan),new BlobBundle(List.of(pre,survey)));
    }
    public EffectPreparation capture(EffectIntent i,PlanningSnapshot context){
        var args=args(i);var level=level(args.dimension());var key=resource(args.dimension(),i.instanceId());var shape=shape(i.expectedFootprint(),level,args.profile().equals(STOP));
        if(!args.profile().equals(STOP)&&(!loaded(level,i.expectedFootprint())||!context.snapshot().loadedChunks().containsAll(i.expectedFootprint().loadedChunks())))
            throw new IllegalArgumentException("regional chunks not actually loaded/observed");
        var owner=Objects.requireNonNull(context.ownership().records().get(key),"regional admitted owner required");
        if(owner.disposition()!=OwnershipDisposition.DIRECTOR_ACTIVE||owner.ownerTokens().size()!=1
            ||!owner.resourceKey().equals(key)||!args.externalRevision().equals(externalRevision.apply(key)))throw new IllegalArgumentException("regional owner/revision");
        var observed=observe(level,i.instanceId());var before=observed.state();WeatherCoreRegionalState after;BlobContent stop;
        if(args.profile().equals(STOP)){
            stop=Objects.requireNonNull(stops.remove(i.operationToken()),"regional sealed stop required");var target=state(stop);
            if(!owns(target,owner,i.instanceId(),shape))throw new IllegalArgumentException("regional stop owner mismatch");
            if(before.kind()!=WeatherCoreRegionalState.Kind.ABSENT&&!before.retired().equals(target))throw new IllegalArgumentException("regional stop does not own live instance");
            after=before.kind()==WeatherCoreRegionalState.Kind.ABSENT?before:target;
        }else if(args.profile().equals(REARM)){
            if(!owns(before,owner,i.instanceId(),shape)||before.kind()!=WeatherCoreRegionalState.Kind.ACTIVE||observed.renderArmed())
                throw new IllegalArgumentException("regional REARM needs exact disarmed existing active native owner");
            before.remainingAt(level.getGameTime());after=before;stop=blob(after.retired());
        }else{
            if(before.kind()!=WeatherCoreRegionalState.Kind.ABSENT){
                if(!owns(before,owner,i.instanceId(),shape)||before.kind()!=WeatherCoreRegionalState.Kind.ACTIVE
                    ||!observed.renderArmed()||!before.operation().equals(i.operationToken().value())||!before.weather().equals(weatherId(i.operationToken())))
                    throw new IllegalArgumentException("regional event already has another operation/owner");
                before.remainingAt(level.getGameTime());after=before; // Idempotent receipt, never a second creation or renewed TTL.
            }else{
                if(observed.identities().size()>=64)throw new IllegalArgumentException("regional weather provider capacity exhausted");
                if(observed.identities().contains(weatherId(i.operationToken())))throw new IllegalArgumentException("regional UUID already belongs to another weather");
                after=new WeatherCoreRegionalState(WeatherCoreRegionalState.Kind.ACTIVE,weatherId(i.operationToken()),i.instanceId().value(),i.operationToken().value(),
                    owner.ownerTokens().iterator().next().value(),owner.creationMutationId().value(),owner.epoch().value(),owner.playerOverrideEpoch().value(),Math.addExact(level.getGameTime(),args.duration().value()),shape);
            }
            stop=blob(after.retired());
        }
        var pre=blob(observed.witness());var post=blob(after);var ownerBlob=BlobContent.capture(OWNER,1,new ModelCodec<>(OwnershipRecord.class).encode(owner));
        var read=new ReadSet(context.snapshot().scopeRevisions(),Map.of(key,owner.epoch()),Map.of(key,pre.reference().hash()));
        var budget=new BudgetVector(0,0,0,0,0,1,0,0,0,0);var claim=new ResourceClaim(key,ClaimPolicy.EXCLUSIVE,budget,Optional.empty(),owner.epoch());
        var refs=new EffectBlobs(Set.of(pre.reference(),ownerBlob.reference()),post.reference(),stop.reference(),Map.of(key,pre.reference()),Map.of(key,post.reference()));
        var plan=new PreparedEffect(i,context.snapshot(),List.of(claim),read,Map.of(key,post.reference().hash()),budget,List.of(),capabilities(),refs);
        var all=new LinkedHashMap<BlobRef,BlobContent>();for(var b:List.of(pre,post,stop,ownerBlob))all.put(b.reference(),b);
        return new EffectPreparation(i,new ValidationResult(true,List.of(),read),Optional.of(plan),new BlobBundle(List.copyOf(all.values())));
    }
    public boolean revalidate(PreparedEffect p,BlobBundle blobs){
        try{
            var args=args(p.intent());var level=level(args.dimension());var key=resource(args.dimension(),p.intent().instanceId());var shape=shape(p.intent().expectedFootprint(),level,args.profile().equals(STOP));
            var captured=owner(p,blobs,key);var live=ownership.get().records().get(key);
            if(!WeatherOwnershipFence.sameOwner(captured,live)||!live.epoch().equals(p.preconditions().epochs().get(key))
                ||!args.externalRevision().equals(externalRevision.apply(key))||!loaded(level,p.intent().expectedFootprint()))return false;
            var pre=find(blobs,p.durableBlobs().preStates().get(key));var post=find(blobs,p.durableBlobs().expectedPostStates().get(key));
            if(!pre.reference().hash().equals(p.preconditions().fingerprints().get(key))||!post.reference().hash().equals(p.expectedPost().get(key)))return false;
            var observed=observe(level,p.intent().instanceId());if(!observed.witness().equals(witness(pre)))return false;
            var after=state(post);
            if(after.kind()!=WeatherCoreRegionalState.Kind.ABSENT&&!owns(after,live,p.intent().instanceId(),shape))return false;
            if(after.kind()==WeatherCoreRegionalState.Kind.ACTIVE){
                if(observed.state().kind()==WeatherCoreRegionalState.Kind.ABSENT
                    &&(observed.identities().size()>=64||observed.identities().contains(after.weather())))return false;
                after.remainingAt(level.getGameTime());
            }
            return true;
        }catch(RuntimeException unavailable){return false;}
    }
    public SliceResult apply(PreparedEffect p,BlobBundle blobs){
        if(!revalidate(p,blobs))throw new IllegalStateException("regional weather changed before permitted write");
        var args=args(p.intent());var level=level(args.dimension());var key=resource(args.dimension(),p.intent().instanceId());
        var before=observe(level,p.intent().instanceId());var desired=state(find(blobs,p.durableBlobs().expectedPostStates().get(key)));
        if(!before.witness().equals(witness(find(blobs,p.durableBlobs().expectedPostStates().get(key))))){
            if(args.profile().equals(REARM)&&desired.equals(before.state())&&!before.renderArmed()){
                before.live().orElseThrow().rearm(desired);
            }else
            if(desired.kind()==WeatherCoreRegionalState.Kind.ACTIVE&&before.state().kind()==WeatherCoreRegionalState.Kind.ABSENT){
                var scope=new Scope.Centered(new Vec3(desired.region().centerX(),0,desired.region().centerZ()),desired.region().radius());
                var weather=DirectorRegionalMistWeather.creating(desired,()->WeatherManager.get(level).addCenteredWeather(WeatherCoreRegionalTypes.MIST.get(),scope));
                weather.setTimeLeft(desired.remainingAt(level.getGameTime()));
            }else if(desired.kind()==WeatherCoreRegionalState.Kind.RETIRED&&before.state().kind()==WeatherCoreRegionalState.Kind.ACTIVE){
                before.live().orElseThrow().retire(before.state());
            }else throw new IllegalStateException("regional transition not captured");
            WeatherManager.get(level).setDirty();
        }
        var observed=blob(observe(level,p.intent().instanceId()).witness()).reference().hash();
        return new SliceResult(observed.equals(p.expectedPost().get(key))?Outcome.SUCCEEDED:Outcome.PARTIAL,Set.of(p.intent().operationToken()),Map.of(key,observed),0,Set.of(),Set.of(),List.of());
    }
    public ReconcileResult reconcile(RecoveryProbe probe){
        var p=probe.durableIntent();var args=args(p.intent());var key=resource(args.dimension(),p.intent().instanceId());
        var proofs=probe.loadedProofs().stream().filter(v->v.resource().equals(key)).toList();
        if(proofs.isEmpty())return new ReconcileResult(ReconcileStatus.UNAVAILABLE,List.of(),List.of());
        if(proofs.size()!=1)return new ReconcileResult(ReconcileStatus.AMBIGUOUS,proofs,List.of());
        var proof=proofs.getFirst();var live=ownership.get().records().get(key);var captured=owner(p,probe.blobs(),key);
        if(live==null)return new ReconcileResult(ReconcileStatus.UNAVAILABLE,proofs,List.of());
        if(live.disposition()==OwnershipDisposition.YIELDED_TO_PLAYER||!live.playerOverrideEpoch().equals(proof.playerOverrideEpoch()))return new ReconcileResult(ReconcileStatus.PLAYER_OVERRIDE,proofs,List.of());
        WeatherCoreRegionalWitness actual;
        try{actual=observe(level(args.dimension()),p.intent().instanceId()).witness();}catch(RuntimeException unavailable){return new ReconcileResult(ReconcileStatus.UNAVAILABLE,proofs,List.of());}
        var hash=blob(actual).reference().hash();
        if(!WeatherOwnershipFence.confirmedAtCurrentOwner(proof,captured,live,hash)||!args.externalRevision().equals(externalRevision.apply(key)))
            return new ReconcileResult(ReconcileStatus.AMBIGUOUS,proofs,List.of());
        var post=witness(find(probe.blobs(),p.durableBlobs().expectedPostStates().get(key)));var status=ReconcileStatus.AMBIGUOUS;
        if(actual.equals(post))status=ReconcileStatus.SAME_POST;
        else if(hash.equals(p.preconditions().fingerprints().get(key))&&proof.localAbsenceProven())status=ReconcileStatus.SAME_PRE_PROVEN;
        return new ReconcileResult(status,proofs,List.of());
    }
}
