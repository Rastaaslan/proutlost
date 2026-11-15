package fr.proutlost.director.adapter.neoforge.server.effects;

import java.util.*;

public final class WeatherCoreRegionalStateTests {
    private static int checks;
    private static void check(boolean condition){checks++;if(!condition)throw new AssertionError("check "+checks);}
    private static void reject(Runnable r){checks++;try{r.run();}catch(IllegalArgumentException|IllegalStateException expected){return;}throw new AssertionError("accepted invalid state "+checks);}
    public static void main(String[] args){
        var region=new WeatherCoreRegionalState.Region(100,200,64);
        check(region.intensityAt(100,200)==1);check(region.intensityAt(132,200)==1);check(region.intensityAt(148,200)==.5f);
        check(region.intensityAt(164,200)==0);check(region.intensityAt(165,200)==0);
        var tiny=new WeatherCoreRegionalState.Region(0,0,.5f);check(tiny.intensityAt(.25,0)==.5f);
        reject(()->new WeatherCoreRegionalState.Region(Double.NaN,0,5));reject(()->new WeatherCoreRegionalState.Region(0,Double.POSITIVE_INFINITY,5));
        reject(()->new WeatherCoreRegionalState.Region(0,0,Float.NaN));reject(()->new WeatherCoreRegionalState.Region(0,0,0));reject(()->new WeatherCoreRegionalState.Region(0,0,513));
        var active=new WeatherCoreRegionalState(WeatherCoreRegionalState.Kind.ACTIVE,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),3,0,2000,region);
        check(WeatherCoreRegionalState.read(active.bytes()).equals(active));check(WeatherCoreRegionalState.read(active.retired().bytes()).equals(active.retired()));
        check(WeatherCoreRegionalState.read(WeatherCoreRegionalState.absent().bytes()).equals(WeatherCoreRegionalState.absent()));
        check(active.remainingAt(1000)==1000);check(active.remainingAt(1999)==1);reject(()->active.remainingAt(2000));reject(()->active.retired().remainingAt(1000));
        for(int n=0;n<active.bytes().length;n++){var truncated=Arrays.copyOf(active.bytes(),n);reject(()->WeatherCoreRegionalState.read(truncated));}
        var trailing=Arrays.copyOf(active.bytes(),active.bytes().length+1);reject(()->WeatherCoreRegionalState.read(trailing));
        var badKind=active.bytes().clone();badKind[4]=3;reject(()->WeatherCoreRegionalState.read(badKind));
        var version=active.bytes().clone();version[3]=2;reject(()->WeatherCoreRegionalState.read(version));
        reject(()->new WeatherCoreRegionalState(WeatherCoreRegionalState.Kind.ABSENT,UUID.randomUUID(),null,null,null,null,0,0,0,null));
        System.out.println("WEATHERCORE_REGIONAL_STATE_TESTS_PASS assertions="+checks);
    }
}
