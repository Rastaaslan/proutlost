package fr.proutlost.director.adapter.neoforge.server.effects;

import java.util.*;

/** Pure canonicalization/entropy fences. Not a provider or fullpack PASS. */
public final class WeatherCoreLeaseStateTests {
    private static int checks;
    private static void check(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}
    private static void reject(Runnable r){checks++;try{r.run();}catch(IllegalArgumentException|IllegalStateException expected){return;}throw new AssertionError("accepted unsafe weather state "+checks);}
    public static void main(String[] ignored) {
        var rain=new WeatherCoreLeaseState.Global("minecraft:rain",UUID.fromString("11111111-1111-1111-1111-111111111111"),1000);
        var clear=new WeatherCoreLeaseState.Global("minecraft:clear",UUID.fromString("22222222-2222-2222-2222-222222222222"),2000);
        var a=new WeatherCoreLeaseState(List.of(rain,clear));var b=new WeatherCoreLeaseState(List.of(clear,rain));
        check(!Arrays.equals(a.bytes(),b.bytes()));check(a.equals(WeatherCoreLeaseState.read(a.bytes())));
        check(b.equals(WeatherCoreLeaseState.read(b.bytes())));
        check(rain.remainingAt(600)==400);check(rain.remainingAt(999)==1);
        reject(()->rain.remainingAt(1000));reject(()->rain.remainingAt(1001));reject(()->rain.remainingAt(-1));
        reject(()->new WeatherCoreLeaseState.Global("addon:unknown",UUID.randomUUID(),100));
        reject(()->new WeatherCoreLeaseState(List.of()));reject(()->new WeatherCoreLeaseState(List.of(rain,rain)));
        var duplicateUuid=new WeatherCoreLeaseState.Global("minecraft:thunder",rain.uuid(),2000);
        reject(()->new WeatherCoreLeaseState(List.of(rain,duplicateUuid)));
        var duplicateType=new WeatherCoreLeaseState.Global("minecraft:rain",UUID.randomUUID(),2000);
        reject(()->new WeatherCoreLeaseState(List.of(rain,duplicateType)));
        for(int n=0;n<a.bytes().length;n++){var truncated=Arrays.copyOf(a.bytes(),n);reject(()->WeatherCoreLeaseState.read(truncated));}
        var trailing=Arrays.copyOf(a.bytes(),a.bytes().length+1);reject(()->WeatherCoreLeaseState.read(trailing));
        var badVersion=a.bytes().clone();badVersion[3]=3;reject(()->WeatherCoreLeaseState.read(badVersion));
        var huge=new WeatherCoreLeaseState.Global("minecraft:clear",UUID.randomUUID(),(long)Integer.MAX_VALUE+1);
        reject(()->huge.remainingAt(0));
        check(new WeatherCoreLeaseState(List.of(new WeatherCoreLeaseState.Global("minecraft:rain",rain.uuid(),1000))).equals(
            new WeatherCoreLeaseState(List.of(new WeatherCoreLeaseState.Global("minecraft:rain",rain.uuid(),600+400)))));
        var lease=new WeatherCoreLeaseState.Lease(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,UUID.randomUUID(),"minecraft:rain",UUID.randomUUID(),400);
        var owned=WeatherCoreLeaseState.owned(lease);
        check(owned.equals(WeatherCoreLeaseState.read(owned.bytes())));
        check(owned.lease().orElseThrow().duration()==400);
        reject(()->new WeatherCoreLeaseState(owned.globals(),Optional.of(new WeatherCoreLeaseState.Lease(lease.event(),UUID.randomUUID(),lease.owner(),lease.epoch(),lease.mutation(),lease.type(),UUID.randomUUID(),400))));
        reject(()->new WeatherCoreLeaseState.Lease(lease.event(),lease.operation(),lease.owner(),-1,lease.mutation(),lease.type(),lease.global(),400));
        reject(()->new WeatherCoreLeaseState.Lease(lease.event(),lease.operation(),lease.owner(),1,lease.mutation(),lease.type(),lease.global(),0));
        reject(()->new WeatherCoreLeaseState.Lease(lease.event(),lease.operation(),lease.owner(),1,lease.mutation(),lease.type(),lease.global(),72001));
        for(int n=0;n<owned.bytes().length;n++){var truncated=Arrays.copyOf(owned.bytes(),n);reject(()->WeatherCoreLeaseState.read(truncated));}
        System.out.println("WEATHERCORE_CANONICAL_STATE_TESTS_PASS assertions="+checks);
    }
}
