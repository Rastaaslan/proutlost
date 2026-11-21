package fr.proutlost.integration.contra;

import fr.proutlost.contract.director.*;
import fr.proutlost.contract.director.Vocabulary.*;
import fr.proutlost.director.api.codec.CanonicalJsonV1;
import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.PhysicalCatalogModels;
import fr.proutlost.director.api.model.PhysicalCatalogSourceDescriptor;
import fr.proutlost.director.api.model.PrimitiveId;
import fr.proutlost.director.api.model.DefinitionId;
import fr.proutlost.director.api.model.JsonValue;
import fr.proutlost.director.bootstrap.PhysicalCatalogIntegrationProvider;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.function.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import fr.proutlost.director.api.model.PhysicalCatalogModels.Key;

/** Installed native validation, without a world writer or an independent store.
 * A location becomes usable only while the actually loaded cells still match
 * an explicitly retained physical-state witness from its producer. */
public final class D0NativeAdapters {
    private D0NativeAdapters() {}
    public static final Id STATE_VALIDATOR=new Id("proutlost:native_loaded_block_state_v1");
    private static final Id READ_ONLY=new Id("proutlost:d0_loaded_read_only");
    private static final Id OWNED_ONLY=new Id("proutlost:d0_director_owned_only");
    private static final Id DENY=new Id("proutlost:d0_deny");
    public record Built(D0PreparedSiteResolver resolver){}
    private record Descriptor(Map<String,JsonValue> fields,D0PreparedSiteResolver.PolicySupport policies,
            Map<PrimitiveId,D0PreparedSiteResolver.EffectUse> effects,Map<SourceType,Id> sources){}
    private static void require(boolean v,String message){if(!v)throw new IllegalArgumentException(message);}
    private static Map<String,JsonValue> object(JsonValue v){return ((JsonValue.ObjectValue)v).values();}
    private static String text(JsonValue v){return ((JsonValue.Text)v).value();}
    private static List<JsonValue> array(JsonValue v){return ((JsonValue.Array)v).values();}
    private static Set<Id> ids(JsonValue v){var out=new HashSet<Id>();for(var x:array(v))require(out.add(new Id(text(x))),"duplicate native adapter id");return Set.copyOf(out);}
    private static Descriptor descriptor(byte[] bytes){
        require(bytes.length>0&&bytes.length<=1_048_576,"native physical adapter byte bound");
        var f=object(CanonicalJsonV1.decode(bytes));
        require(f.keySet().equals(Set.of("schema_version","requirements","effects","source_types","policies","bindings"))
            &&f.get("schema_version").equals(new JsonValue.Number(java.math.BigDecimal.ONE)),"native physical adapter schema");
        var ps=new EnumMap<D0PreparedSiteResolver.PolicyDomain,Set<Id>>(D0PreparedSiteResolver.PolicyDomain.class);
        for(var e:object(f.get("policies")).entrySet()) {
            var domain=D0PreparedSiteResolver.PolicyDomain.valueOf(e.getKey());var known=ids(e.getValue());
            require(Set.of(READ_ONLY,OWNED_ONLY,DENY).containsAll(known),"policy has no installed native implementation");
            ps.put(domain,known);
        }
        var effects=new HashMap<PrimitiveId,D0PreparedSiteResolver.EffectUse>();
        for(var value:array(f.get("effects"))) {
            var row=object(value);require(row.keySet().equals(Set.of("primitive","channels","observable_actions","physical")),"native effect descriptor fields");
            var id=new PrimitiveId(text(row.get("primitive")));var channels=new HashSet<Channel>();
            for(var c:array(row.get("channels")))require(channels.add(Channel.valueOf(text(c))),"duplicate native channel");
            boolean physical=((JsonValue.Bool)row.get("physical")).value();
            require(knownEffect(id,channels,physical),"native effect/channel/physical contract differs from installed binding");
            require(effects.put(id,new D0PreparedSiteResolver.EffectUse(channels,ids(row.get("observable_actions")),physical))==null,"duplicate native effect adapter");
        }
        require(!effects.isEmpty()&&effects.size()<=32,"native effect adapter count");
        var sources=new EnumMap<SourceType,Id>(SourceType.class);
        object(f.get("source_types")).forEach((type,id)->sources.put(SourceType.valueOf(type),new Id(text(id))));
        require(!sources.isEmpty(),"explicit native source type mapping required");
        // Binding selectors require an exact durable lookup implementation; no unknown ID becomes empty truth.
        var bindings=object(f.get("bindings"));require(bindings.size()<=128,"native binding lookup bound");
        bindings.forEach((id,event)->{new Id(id);UUID.fromString(text(event));});
        return new Descriptor(f,new D0PreparedSiteResolver.PolicySupport(ps),Map.copyOf(effects),Map.copyOf(sources));
    }
    private static boolean knownEffect(PrimitiveId id,Set<Channel> channels,boolean physical){
        return switch(id.value()) {
            case "proutlost:target_sound"->!physical&&channels.equals(Set.of(Channel.SOUND));
            case "proutlost:target_particles"->!physical&&channels.equals(Set.of(Channel.PARTICLE));
            case "proutlost:local_weather"->!physical&&channels.equals(Set.of(Channel.LIGHT_AMBIENCE));
            case "proutlost:apparition"->!physical&&channels.equals(Set.of(Channel.NONPHYSICAL_APPARITION));
            case "proutlost:warning","proutlost:trace","proutlost:shared_choice"->!physical&&!channels.isEmpty()
                &&Set.of(Channel.SYMBOL,Channel.TEXT_UI,Channel.TEMPORARY_CUE).containsAll(channels);
            case "proutlost:goat_visit","proutlost:fauna_migrate","proutlost:fauna_spawn_owned"->physical&&channels.equals(Set.of(Channel.CONTROLLED_ENTITY));
            case "proutlost:camp_growth","proutlost:landslide"->physical&&channels.isEmpty();
            default->false;
        };
    }
    public static DurablePhysicalCatalogView.InstalledSemantics compilerSemantics(byte[] bytes){
        var d=descriptor(bytes);
        return source->{for(var e:source.entries()){
            require(d.sources().containsKey(e.source_type()),"unsupported prepared source type");
            for(var c:e.capabilities()){CapabilityTypes.requireKnown(c.capability_id());require(c.capability_version().value().equals("1"),"unsupported physical capability version");}
            for(var p:e.protection()){
                require(d.policies().supports(p.access_policy(),D0PreparedSiteResolver.PolicyDomain.ACCESS),"unknown access policy");
                require(d.policies().supports(p.presentation_policy(),D0PreparedSiteResolver.PolicyDomain.PRESENTATION),"unknown presentation policy");
                require(d.policies().supports(p.player_action_policy(),D0PreparedSiteResolver.PolicyDomain.PLAYER_ACTION),"unknown action policy");
            }
            for(var s:e.surfaces())require(d.policies().supports(s.player_action_policy(),D0PreparedSiteResolver.PolicyDomain.PLAYER_ACTION),"unknown surface policy");
        }};
    }
    public static Built create(PhysicalCatalogIntegrationProvider.Context context,DurablePhysicalCatalogView view,byte[] bytes){
        require(context.platform() instanceof MinecraftServer,"native server context required");
        var server=(MinecraftServer)context.platform();var d=descriptor(bytes);
        var needs=D0PhysicalRequirements.decode(CanonicalJsonV1.encode(d.fields().get("requirements")),context.definitions().get());
        ObservationPort observations=new ObservationPort(){
            public List<Observation> after(Id world,long sequence,int limit){context.actor().run();throw new UnsupportedOperationException("Use existing J01/J04 fact projection, not a second observation history");}
            public Optional<LocationObservation> current(Id world,LocationRef location){
                context.actor().run();if(!server.isSameThread()||!world.equals(view.snapshot().world_id()))return Optional.empty();
                var e=view.lookup(location);if(e.isEmpty()||!view.current(location))return Optional.empty();var entry=e.orElseThrow();
                var retained=entry.validation_evidence().stream().filter(v->v.validator_id().equals(STATE_VALIDATOR)).toList();
                if(retained.size()!=1)return Optional.empty();var actual=stateFingerprint(server,entry.geometry());
                if(actual.isEmpty()||!actual.orElseThrow().equals(retained.getFirst().digest()))return Optional.empty();
                var now=Instant.now();var capabilities=entry.capabilities().stream().map(LocationCapability::capability_id).collect(java.util.stream.Collectors.toSet());
                return Optional.of(new LocationObservation(world,SchemaVersion.BASE,location,new Revision(actual.orElseThrow().value()),now,now.plusSeconds(1),
                    true,true,capabilities,Set.of(),new Revision("native_explicit_policy_v1"),List.of(),retained));
            }
        };
        D0PreparedSiteResolver.PolicyAuthority policies=(policy,domain,need,entry,effect,use)->{
            context.actor().run();
            if(policy.equals(DENY))return false;
            if(policy.equals(READ_ONLY))return !use.physical();
            if(!policy.equals(OWNED_ONLY)||!use.physical()||effect.expectedFootprint().cells().isEmpty())return false;
            var owned=context.ownership().get();
            for(var p:effect.expectedFootprint().cells()){
                var matching=owned.records().values().stream().filter(o->o.resourceKey().domain()==fr.proutlost.director.api.model.OwnershipDomain.BLOCK
                    &&o.resourceKey().dimension().equals(p.dimension())&&o.resourceKey().key().equals(p.x()+","+p.y()+","+p.z())).toList();
                if(matching.size()!=1||matching.getFirst().disposition()!=fr.proutlost.director.api.model.OwnershipDisposition.DIRECTOR_ACTIVE
                    ||matching.getFirst().playerOverrideEpoch().value()!=0)return false;
            }
            return true;
        };
        return new Built(new D0PreparedSiteResolver(view,needs,d.effects(),d.sources(),observations,d.policies(),policies,
            binding->{
                context.actor().run();var configured=object(d.fields().get("bindings")).get(binding.value());if(configured==null)return Optional.empty();var event=UUID.fromString(text(configured));
                var matches=context.recordedSites().get().stream().filter(p->p.event().value().equals(event)&&p.catalog().key().sourceWorld().equals(sourceWorld(view))&&p.locations().size()==1).toList();
                if(matches.size()!=1)return Optional.empty();var pin=matches.getFirst();var publication=view.publication(pin.catalog().key());if(publication.isEmpty())return Optional.empty();
                var historical=StrictCodec.decode(new String(publication.orElseThrow().source(),StandardCharsets.UTF_8),CatalogSnapshot.class);
                var mapped=publication.orElseThrow().binding().locations().entrySet().stream().filter(e->pin.locations().contains(e.getValue())).toList();
                if(mapped.size()!=1)return Optional.empty();return historical.entries().stream().filter(e->e.location_id().value().equals(mapped.getFirst().getKey().value()))
                    .map(e->new LocationRef(LocationStore.CATALOG,e.location_id(),e.revision())).findFirst();
            },Instant::now,context.actor()));
    }
    private static fr.proutlost.director.api.model.ResourceId sourceWorld(DurablePhysicalCatalogView view){return view.readback().orElseThrow().key().sourceWorld();}
    /** Hashes actual already-loaded cells only; geometry is inclusive D0 geometry.
     * No chunk load/generation, off-actor access or claimed manifest can substitute. */
    public static Optional<Digest> stateFingerprint(MinecraftServer server,List<Bounds> geometry){return fr.proutlost.integration.physical.NativeLoadedCellWitness.stateFingerprint(server,geometry);}
}