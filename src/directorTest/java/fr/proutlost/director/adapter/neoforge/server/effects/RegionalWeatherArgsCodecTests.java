package fr.proutlost.director.adapter.neoforge.server.effects;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Actual closed ModelCodec/PrimitiveArgs compatibility, no Minecraft fakes. */
public final class RegionalWeatherArgsCodecTests {
    private static int checks;
    private static void check(boolean b){checks++;if(!b)throw new AssertionError("regional args "+checks);}
    private static void reject(Runnable run){checks++;try{run.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("unsafe regional args accepted "+checks);}
    public static void main(String[] ignored){
        var dimension=new DimensionId("minecraft:overworld");var duration=new DurationTicks(400);var revision=new Revision(1);
        var regional=new RegionalWeatherLease(dimension,new ResourceId("proutlost_director:regional_mist"),duration,revision);
        var global=new GlobalWeatherLease(dimension,new ResourceId("minecraft:rain"),duration,revision);
        var local=new LocalWeather("witness",new ResourceId("proutlost:test_mist"));
        var codec=new ModelCodec<>(PrimitiveArgs.class);
        check(codec.decode(codec.encode(regional)).equals(regional));
        check(codec.decode(codec.encode(global)).equals(global));
        check(codec.decode(codec.encode(local)).equals(local));
        var encoded=new String(codec.encode(regional),StandardCharsets.UTF_8);
        check(encoded.contains("\"type\":\"regional_weather_lease\""));
        reject(()->codec.decode(encoded.replace("regional_weather_lease","global_weather_lease").getBytes(StandardCharsets.UTF_8)));
        reject(()->codec.decode(encoded.replace("regional_weather_lease","local_weather").getBytes(StandardCharsets.UTF_8)));
        reject(()->new RegionalWeatherLease(dimension,regional.profile(),new DurationTicks(0),revision));
        reject(()->new RegionalWeatherLease(dimension,regional.profile(),new DurationTicks(72001),revision));
        var footprint=new Footprint(dimension,Set.of(new ChunkRef(dimension,0,0)),Set.of(),List.of(new Aabb(dimension,0,-64,0,16,320,16)));
        var intent=new EffectIntent(new OperationToken(UUID.randomUUID()),new PrimitiveId("proutlost:regional_weather_lease"),1,regional,footprint,new EventInstanceId(UUID.randomUUID()));
        var intents=new ModelCodec<>(EffectIntent.class);
        check(intents.decode(intents.encode(intent)).equals(intent));
        check(intent.typedArgs() instanceof RegionalWeatherLease);
        check(!(intent.typedArgs() instanceof GlobalWeatherLease));
        check(!(intent.typedArgs() instanceof LocalWeather));
        System.out.println("WEATHERCORE_REGIONAL_TYPED_ARGS_TESTS_PASS assertions="+checks);
    }
}
