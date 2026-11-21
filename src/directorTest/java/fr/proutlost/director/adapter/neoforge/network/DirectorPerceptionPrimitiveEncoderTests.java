package fr.proutlost.director.adapter.neoforge.network;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** Pure J02 -> J06 encoder tests; never assert real delivery, WAL or rendering. */
final class DirectorPerceptionPrimitiveEncoderTests {
    private DirectorPerceptionPrimitiveEncoderTests(){}
    private static final PlayerId PLAYER=new PlayerId(new UUID(31,32));
    private static final SessionIdentity SESSION=new SessionIdentity(
        NetworkTests.WORLD,new Epoch(1),new Epoch(1),NetworkTests.DIM,PLAYER);
    private static final PerceptionTransform TRANSFORM=
        new PerceptionTransform(4,64,5,0,0,1);
    private static final ResourceId SOUND=new ResourceId("proutlost:test_sound");
    private static final ResourceId PARTICLE=new ResourceId("proutlost:test_particle");
    private static final ResourceId WEATHER=new ResourceId("proutlost:test_weather");
    private static final ResourceId APPARITION=new ResourceId("proutlost:test_apparition");
    private static final ResourceId WARN=new ResourceId("proutlost:test_warning");
    private static final Map<String,PlayerId> ROLES=Map.of("witness",PLAYER);
    private static final Map<ResourceId,DirectorPerceptionPrimitiveEncoder.Asset> ASSETS=Map.of(
        SOUND, asset(PerceptionKind.SOUND),
        PARTICLE,asset(PerceptionKind.PARTICLES),
        WEATHER,asset(PerceptionKind.LOCAL_WEATHER),
        APPARITION,asset(PerceptionKind.APPARITION),
        WARN,asset(PerceptionKind.SYMBOL));
    private static DirectorPerceptionPrimitiveEncoder.Asset asset(PerceptionKind kind){
        return new DirectorPerceptionPrimitiveEncoder.Asset(kind,NetworkTests.HASH,
            ProviderMode.PRODUCTION);
    }
    private static EffectIntent intent(String id,PrimitiveArgs args) {
        return new EffectIntent(new OperationToken(NetworkTests.EFFECT.value()),
            new PrimitiveId(id),1,args,
            new Footprint(NetworkTests.DIM,
                Set.of(new ChunkRef(NetworkTests.DIM,0,0)),
                Set.of(new BlockPosition(NetworkTests.DIM,4,64,5)),List.of()),
            NetworkTests.INSTANCE);
    }
    private static PerceptionIntent encode(EffectIntent intent) {
        return DirectorPerceptionPrimitiveEncoder.encode(intent,ROLES,SESSION,
            NetworkTests.header(1,1,80),TRANSFORM,ASSETS);
    }
    static void run(){
        var sound=intent("proutlost:target_sound",new TargetSound("witness",SOUND,.4,24,1));
        var particle=intent("proutlost:target_particles",new TargetParticles("witness",PARTICLE,12,16));
        var local=intent("proutlost:local_weather",new LocalWeather("witness",WEATHER));
        var apparition=intent("proutlost:apparition",new Apparition("witness",APPARITION,
            new DurationTicks(40),32));
        var warning=intent("proutlost:warning",new Warning("witness",WARN,
            new DurationTicks(30)));
        for(var testcase:List.of(
            Map.entry(sound,PerceptionKind.SOUND),
            Map.entry(particle,PerceptionKind.PARTICLES),
            Map.entry(local,PerceptionKind.LOCAL_WEATHER),
            Map.entry(apparition,PerceptionKind.APPARITION),
            Map.entry(warning,PerceptionKind.SYMBOL))){
            var out=encode(testcase.getKey());
            NetworkTests.check(out.target().equals(PLAYER),"J06 target role");
            NetworkTests.check(out.effect().kind()==testcase.getValue(),"J02 primitive kind");
            NetworkTests.check(out.effect().header().effectId().value().equals(
                testcase.getKey().operationToken().value()),"J04 operation identity");
            NetworkTests.check(out.effect().assetHash().equals(NetworkTests.HASH),
                "asset SHA from production manifest");
        }
        NetworkTests.check(encode(local).effect().parameters() instanceof WeatherParameters,
            "weather must be local-client only, not global weather mutation");
        NetworkTests.check(encode(warning).effect().parameters() instanceof SymbolParameters,
            "warning requires bounded symbolic J06 presentation");
        // J04's sealed spatial footprint must constrain where a native J06
        // effect can appear, independently of an authenticated client role.
        var wrongCell=new PerceptionTransform(8,64,8,0,0,1);
        NetworkTests.reject(()->DirectorPerceptionPrimitiveEncoder.encode(local,
            ROLES,SESSION,NetworkTests.header(1,1,80),wrongCell,ASSETS),
            "J06 in-chunk substitution outside sealed cell");
        var wrongChunk=new PerceptionTransform(20,64,5,0,0,1);
        NetworkTests.reject(()->DirectorPerceptionPrimitiveEncoder.encode(local,
            ROLES,SESSION,NetworkTests.header(1,1,80),wrongChunk,ASSETS),
            "J06 off-chunk substitution");
        var noSpatialAuthority=new EffectIntent(local.operationToken(),
            local.primitive(),1,local.typedArgs(),new Footprint(
                NetworkTests.DIM,Set.of(),Set.of(),List.of()),local.instanceId());
        NetworkTests.reject(()->encode(noSpatialAuthority),
            "J06 empty footprint must not authorize arbitrary coordinates");
        var bounded=new EffectIntent(local.operationToken(),local.primitive(),1,
            local.typedArgs(),new Footprint(NetworkTests.DIM,
                Set.of(new ChunkRef(NetworkTests.DIM,0,0)),Set.of(),
                List.of(new Aabb(NetworkTests.DIM,3,60,4,6,68,7))),local.instanceId());
        NetworkTests.check(encode(bounded).effect().transform().equals(TRANSFORM),
            "J06 sealed AABB contains origin");
        NetworkTests.reject(()->DirectorPerceptionPrimitiveEncoder.encode(bounded,
            ROLES,SESSION,NetworkTests.header(1,1,80),wrongCell,ASSETS),
            "J06 origin outside sealed AABB");
        NetworkTests.reject(()->encode(intent("proutlost:target_sound",
            new TargetSound("witness",SOUND,.2,8,2))),"repeat without multiple durable steps");
        NetworkTests.reject(()->encode(intent("proutlost:apparition",
            new Apparition("witness",APPARITION,new DurationTicks(120),12))),
            "apparition longer than TTL");
        NetworkTests.reject(()->encode(intent("proutlost:warning",
            new Warning("witness",WARN,new DurationTicks(100)))),
            "warning lead exceeds visible TTL");
        NetworkTests.reject(()->encode(intent("proutlost:warning",
            new Warning("witness",WARN,new DurationTicks(0)))),
            "zero-warning lead");
        NetworkTests.reject(()->encode(intent("proutlost:local_weather",
            new TargetSound("witness",SOUND,.2,8,1))),"type substitution");
        NetworkTests.reject(()->encode(intent("proutlost:global_weather_lease",
            new LocalWeather("witness",WEATHER))),"global weather confused with local weather");
        NetworkTests.reject(()->encode(intent("proutlost:trace",
            new LocalWeather("witness",WEATHER))),"anchor trace falsely encoded as perception");
        NetworkTests.reject(()->encode(intent("proutlost:target_sound",
            new TargetSound("other",SOUND,.2,8,1))),"unresolved target role");
        NetworkTests.reject(()->DirectorPerceptionPrimitiveEncoder.encode(local,
            Map.of("witness",new PlayerId(new UUID(45,46))),SESSION,
            NetworkTests.header(1,1,80),TRANSFORM,ASSETS),"role sent to another player");
        NetworkTests.reject(()->DirectorPerceptionPrimitiveEncoder.encode(local,
            ROLES,SESSION,NetworkTests.header(2,1,80),TRANSFORM,ASSETS),
            "stale connection epoch");
        NetworkTests.reject(()->DirectorPerceptionPrimitiveEncoder.encode(local,
            ROLES,SESSION,NetworkTests.header(1,1,1201),TRANSFORM,ASSETS),
            "unbounded payload TTL");
        NetworkTests.reject(()->DirectorPerceptionPrimitiveEncoder.encode(local,
            ROLES,SESSION,new PerceptionHeader(1,NetworkTests.WORLD,new Epoch(1),
                new Epoch(1),NetworkTests.DIM,new EventInstanceId(new UUID(13,14)),
                NetworkTests.EFFECT,new Sequence(1),new DurationTicks(80)),
            TRANSFORM,ASSETS),"foreign event instance");
        NetworkTests.reject(()->DirectorPerceptionPrimitiveEncoder.encode(local,
            ROLES,SESSION,new PerceptionHeader(1,NetworkTests.WORLD,new Epoch(1),
                new Epoch(1),NetworkTests.DIM,NetworkTests.INSTANCE,
                new EffectId(new UUID(13,14)),new Sequence(1),new DurationTicks(80)),
            TRANSFORM,ASSETS),"foreign WAL operation");
        NetworkTests.reject(()->DirectorPerceptionPrimitiveEncoder.encode(local,
            ROLES,SESSION,NetworkTests.header(1,1,80),TRANSFORM,
            Map.of(WEATHER,new DirectorPerceptionPrimitiveEncoder.Asset(
                PerceptionKind.LOCAL_WEATHER,NetworkTests.HASH,ProviderMode.TEST_ONLY))),
            "test asset promoted to production");
        NetworkTests.reject(()->DirectorPerceptionPrimitiveEncoder.encode(local,
            ROLES,SESSION,NetworkTests.header(1,1,80),TRANSFORM,
            Map.of(WEATHER,asset(PerceptionKind.SOUND))),"asset kind mismatch");
        NetworkTests.reject(()->DirectorPerceptionPrimitiveEncoder.encode(local,
            ROLES,SESSION,NetworkTests.header(1,1,80),TRANSFORM,Map.of()),
            "asset absent from qualified production manifest");
        NetworkTests.reject(()->DirectorPerceptionPrimitiveEncoder.encode(
            new EffectIntent(local.operationToken(),local.primitive(),2,local.typedArgs(),
                local.expectedFootprint(),local.instanceId()),
            ROLES,SESSION,NetworkTests.header(1,1,80),TRANSFORM,ASSETS),
            "primitive revision not approved");
        NetworkTests.reject(()->DirectorPerceptionPrimitiveEncoder.encode(
            new EffectIntent(local.operationToken(),local.primitive(),1,local.typedArgs(),
                new Footprint(new DimensionId("minecraft:the_nether"),Set.of(),Set.of(),List.of()),
                local.instanceId()),
            ROLES,SESSION,NetworkTests.header(1,1,80),TRANSFORM,ASSETS),
            "foreign dimension footprint");
    }
}
