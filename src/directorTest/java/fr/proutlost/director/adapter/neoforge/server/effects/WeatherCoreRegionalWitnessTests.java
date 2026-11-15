package fr.proutlost.director.adapter.neoforge.server.effects;

import java.nio.ByteBuffer;
import java.util.*;

/** Armed state is part of the actual native post hash, never inferred from metadata. */
public final class WeatherCoreRegionalWitnessTests {
    private static int checks;
    private static void check(boolean v){checks++;if(!v)throw new AssertionError("check "+checks);}
    private static void reject(Runnable r){checks++;try{r.run();}catch(IllegalArgumentException e){return;}throw new AssertionError("invalid witness accepted "+checks);}
    public static void main(String[] args){
        var s=new WeatherCoreRegionalState(WeatherCoreRegionalState.Kind.ACTIVE,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),3,4,5000,new WeatherCoreRegionalState.Region(120,180,32));
        var active=new WeatherCoreRegionalWitness(s,true);var disarmed=new WeatherCoreRegionalWitness(s,false);
        check(!Arrays.equals(active.bytes(),disarmed.bytes()));
        for(var value:List.of(active,disarmed,new WeatherCoreRegionalWitness(s.retired(),false),new WeatherCoreRegionalWitness(WeatherCoreRegionalState.absent(),false))){
            check(WeatherCoreRegionalWitness.read(value.bytes()).equals(value));
            for(int n=0;n<value.bytes().length;n++){var truncated=Arrays.copyOf(value.bytes(),n);reject(()->WeatherCoreRegionalWitness.read(truncated));}
            var trailing=Arrays.copyOf(value.bytes(),value.bytes().length+1);reject(()->WeatherCoreRegionalWitness.read(trailing));
        }
        reject(()->new WeatherCoreRegionalWitness(s.retired(),true));reject(()->new WeatherCoreRegionalWitness(WeatherCoreRegionalState.absent(),true));
        var bad=active.bytes();bad[4]=2;reject(()->WeatherCoreRegionalWitness.read(bad));
        var negative=active.bytes();ByteBuffer.wrap(negative).putInt(5,-1);reject(()->WeatherCoreRegionalWitness.read(negative));
        var version=active.bytes();version[3]=1;reject(()->WeatherCoreRegionalWitness.read(version));
        System.out.println("WEATHERCORE_REGIONAL_WITNESS_TESTS_PASS assertions="+checks);
    }
}
