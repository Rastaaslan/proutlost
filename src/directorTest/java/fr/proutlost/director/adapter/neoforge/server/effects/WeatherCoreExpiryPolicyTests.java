package fr.proutlost.director.adapter.neoforge.server.effects;

import java.util.*;
import static fr.proutlost.director.adapter.neoforge.server.effects.WeatherCoreExpiryPolicy.Decision.*;

/** Boundary and foreign-control properties, not a substitute for real Core tests. */
public final class WeatherCoreExpiryPolicyTests {
    private static int checks;
    private static void check(boolean b){checks++;if(!b)throw new AssertionError("expiry fence "+checks);}
    public static void main(String[] ignored){
        var id=UUID.randomUUID();var foreign=UUID.randomUUID();long born=2000,duration=400;
        for(long tick=born;tick<born+duration;tick++){
            long counter=born+duration-tick+1;
            for(long timer:List.of(counter+1,counter,counter-1)){
                check(WeatherCoreExpiryPolicy.decide(id,id,"minecraft:rain","minecraft:rain",born,duration,tick,timer)==KEEP);
                check(WeatherCoreExpiryPolicy.decide(id,foreign,"minecraft:rain","minecraft:rain",born,duration,tick,timer)==YIELD);
                check(WeatherCoreExpiryPolicy.decide(id,id,"minecraft:rain","minecraft:thunder",born,duration,tick,timer)==YIELD);
            }
            check(WeatherCoreExpiryPolicy.decide(id,id,"minecraft:rain","minecraft:rain",born,duration,tick,counter+5)==YIELD);
        }
        check(WeatherCoreExpiryPolicy.decide(id,id,"minecraft:rain","minecraft:rain",born,duration,born+duration,1)==EXPIRE_OWN);
        check(WeatherCoreExpiryPolicy.decide(id,id,"minecraft:rain","minecraft:rain",born,duration,born+duration,0)==EXPIRE_OWN);
        check(WeatherCoreExpiryPolicy.decide(id,foreign,"minecraft:rain","minecraft:rain",born,duration,born+duration,1)==YIELD);
        check(WeatherCoreExpiryPolicy.decide(id,id,"minecraft:rain","minecraft:rain",born,duration,born+duration,100)==YIELD);
        check(WeatherCoreExpiryPolicy.decide(id,id,"minecraft:rain","minecraft:rain",born,duration,born-1,400)==YIELD);
        check(WeatherCoreExpiryPolicy.decide(id,id,"minecraft:rain","minecraft:rain",born,duration,born+duration+1,1)==YIELD);
        check(WeatherCoreExpiryPolicy.decide(id,id,"minecraft:rain","minecraft:rain",Long.MAX_VALUE-1,400,Long.MAX_VALUE-1,400)==YIELD);
        System.out.println("WEATHERCORE_EXPIRY_FENCE_TESTS_PASS assertions="+checks);
    }
}
