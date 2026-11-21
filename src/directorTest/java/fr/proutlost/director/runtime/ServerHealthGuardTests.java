package fr.proutlost.director.runtime;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.config.TestingConfig;
import java.nio.file.*;
import java.math.BigDecimal;
import java.util.*;

/** Synthetic completed-tick durations qualify the policy only, never a server performance result. */
public final class ServerHealthGuardTests {
    private static int assertions;
    private static void check(boolean good,String label){assertions++;if(!good)throw new AssertionError(label);}
    private static ConfigView config(long enter,long recover,long metrics){
        var values=new HashMap<String,JsonValue.Number>();
        values.put("health.degraded_mspt",number(45));values.put("health.critical_mspt",number(70));values.put("health.recover_mspt",number(38));
        values.put("health.enter_window_ticks",number(enter));values.put("health.recover_window_ticks",number(recover));values.put("health.metrics_window_ticks",number(metrics));
        return new ConfigView(new Revision(1),new Fingerprint("0".repeat(64)),Calibration.DEFAULT_FOR_TESTING,values);
    }
    private static JsonValue.Number number(long value){return new JsonValue.Number(BigDecimal.valueOf(value));}
    private static ServerHealthGuard guard(){return new ServerHealthGuard(config(3,5,8));}
    private static long nanos(double ms){return Math.round(ms*1_000_000);}
    private static void accept(ServerHealthGuard guard,long tick,double ms,boolean backlog,boolean durable){guard.sample(tick,nanos(ms),backlog,durable);}
    private static void warm(ServerHealthGuard guard){for(long tick=1;tick<=5;tick++)accept(guard,tick,20,false,true);check(guard.mode()==HealthMode.NORMAL,"warmup requires actual good tick samples");}
    private static void hysteresis(){
        var g=guard();check(g.mode()==HealthMode.READ_ONLY_SAFE&&g.snapshot().sampleCount()==0,"unmeasured startup is unavailable");
        check(Double.isNaN(g.snapshot().ewmaMs())&&Double.isNaN(g.snapshot().p95Ms()),"no fabricated startup timings");
        for(long tick=1;tick<5;tick++){accept(g,tick,20,false,true);check(g.mode()==HealthMode.READ_ONLY_SAFE,"startup cannot clear immediately");}
        accept(g,5,20,false,true);check(g.mode()==HealthMode.NORMAL,"complete startup recovery window opens measured health");
        accept(g,6,45,false,true);accept(g,7,45,false,true);check(g.mode()==HealthMode.NORMAL,"overload below entry duration deferred");
        accept(g,8,45,false,true);check(g.mode()==HealthMode.DEGRADED,"persistent threshold equality degrades");
        for(long tick=9;tick<=30;tick++)accept(g,tick,38,false,true);
        check(g.mode()==HealthMode.DEGRADED,"recovery equality is not below lower threshold");
        long tick=31;while(g.mode()!=HealthMode.NORMAL&&tick<60)accept(g,tick++,20,false,true);
        check(g.mode()==HealthMode.NORMAL,"sustained below-threshold recovery returns normal");
        accept(g,tick,70,false,true);check(g.mode()==HealthMode.CRITICAL,"critical threshold equality closes immediately");
        accept(g,tick+1,20,false,true);check(g.mode()==HealthMode.CRITICAL,"single good tick cannot clear critical");
        for(long at=tick+2;at<tick+25;at++)accept(g,at,20,false,true);
        check(g.mode()==HealthMode.NORMAL,"critical recovery also needs complete lower-threshold window");
    }
    private static void backlogAndDurability(){
        var g=guard();warm(g);for(long tick=6;tick<=7;tick++)accept(g,tick,1,true,true);
        check(g.mode()==HealthMode.NORMAL,"brief mailbox/IO pressure does not forge degraded duration");
        accept(g,8,1,true,true);check(g.mode()==HealthMode.DEGRADED&&g.snapshot().cause()==ServerHealthGuard.Cause.BACKLOG_PERSISTENT,"sustained pressure degrades despite cheap ticks");
        for(long tick=9;tick<14;tick++)accept(g,tick,1,false,true);check(g.mode()==HealthMode.NORMAL,"backlog recovery requires lower-threshold window");
        long count=g.snapshot().sampleCount();accept(g,13,1,false,false);
        check(g.mode()==HealthMode.READ_ONLY_SAFE&&g.snapshot().persistenceLost(),"persistence loss closes even duplicate tick");
        check(g.snapshot().sampleCount()==count,"duplicate never adds a statistical sample");
        for(long tick=14;tick<80;tick++)accept(g,tick,1,false,true);
        check(g.mode()==HealthMode.READ_ONLY_SAFE,"lost authority cannot autoheal in same monitor/session");
        var fresh=guard();warm(fresh);check(!fresh.snapshot().persistenceLost(),"new session monitor must collect its own recovery");
    }
    private static void continuity(){
        var g=guard();accept(g,1,20,false,true);accept(g,2,20,false,true);accept(g,100,20,false,true);
        check(g.snapshot().recoverySamples()==1,"unsampled time cannot satisfy recovery");
        for(long tick=101;tick<=103;tick++)accept(g,tick,20,false,true);
        check(g.mode()==HealthMode.READ_ONLY_SAFE,"only contiguous observed good samples count");
        accept(g,104,20,false,true);check(g.mode()==HealthMode.NORMAL,"fresh complete sequence recovers");
        var old=g.snapshot();accept(g,104,80,true,true);check(g.snapshot().equals(old),"duplicate timing cannot inject overload or advance state");
        accept(g,110,20,false,true);check(g.mode()==HealthMode.READ_ONLY_SAFE&&g.snapshot().cause()==ServerHealthGuard.Cause.MEASUREMENT_GAP,"unobserved gap revokes normal freshness");
        for(long tick=111;tick<115;tick++)accept(g,tick,20,false,true);check(g.mode()==HealthMode.NORMAL,"fresh observations recover after a gap");
        try{accept(g,113,20,false,true);throw new AssertionError("retrograde accepted");}catch(IllegalArgumentException expected){assertions++;}
        try{g.sample(115,0,false,true);throw new AssertionError("zero duration accepted");}catch(IllegalArgumentException expected){assertions++;}
        try{g.sample(-1,1,false,true);throw new AssertionError("negative tick accepted");}catch(IllegalArgumentException expected){assertions++;}
    }
    private static void statisticsAndBounds(){
        var g=new ServerHealthGuard(config(3,5,5));double expected=10;
        for(int tick=1;tick<=8;tick++){
            double ms=tick*10;if(tick>1)expected+=.5*(ms-expected);accept(g,tick,ms,false,true);
            check(g.snapshot().windowSamples()<=5,"sample ring remains bounded");
        }
        check(Math.abs(g.snapshot().ewmaMs()-expected)<1e-9,"EWMA uses configured entry window");
        check(g.snapshot().windowSamples()==5&&g.snapshot().p95Ms()==80&&g.snapshot().p99Ms()==80,"nearest-rank percentiles use rolling complete durations");
        for(int tick=9;tick<=50;tick++)accept(g,tick,1,false,true);
        check(g.snapshot().p95Ms()==1&&g.snapshot().p99Ms()==1,"old outliers actually evict from percentile distribution");
        check(g.snapshot().sampleCount()==50&&g.snapshot().windowSamples()==5&&g.snapshot().windowCapacity()==5,"lifetime count distinct from resident metric window");
        var frozen=g.snapshot();accept(g,51,2,false,true);check(frozen.sampleCount()==50&&frozen.p95Ms()==1,"published stats DTO remains immutable");
        int peakDistinct=0;
        for(long tick=52;tick<10_000;tick++){g.sample(tick,1_000_000+tick,false,true);peakDistinct=Math.max(peakDistinct,g.snapshot().distinctDurations());}
        check(g.snapshot().windowSamples()==5&&peakDistinct<=5,"ring and ordered percentile index both remain bounded under unique durations");
        try{new ServerHealthGuard(config(0,5,8));throw new AssertionError("zero window accepted");}catch(IllegalArgumentException expectedFailure){assertions++;}
        try{new ServerHealthGuard(config(3,5,65_537));throw new AssertionError("unbounded ring accepted");}catch(IllegalArgumentException expectedFailure){assertions++;}
    }
    private static void rollingPercentileOracle(){
        for(int capacity:new int[]{1,2,5,99,100,1200,ServerHealthGuard.MAX_METRIC_SAMPLES}){
            var g=new ServerHealthGuard(config(3,5,capacity));var random=new Random(0x150008L+capacity);
            long[] resident=new long[capacity];int steps=capacity==ServerHealthGuard.MAX_METRIC_SAMPLES?capacity+129:3*capacity+101;
            for(int tick=1;tick<=steps;tick++){
                long value=switch(tick%5){case 0->20_000_000L;case 1->38_000_000L;case 2->45_000_000L;case 3->70_000_000L;default->1L+random.nextInt(80_000_000);};
                resident[(tick-1)%capacity]=value;var snapshot=g.sample(tick,value,tick%127==0,true);
                if(capacity<2000||tick%503==0||tick>=capacity-2&&tick<=capacity+2||tick==steps){
                    int count=Math.min(tick,capacity);long[] oracle=Arrays.copyOf(resident,count);Arrays.sort(oracle);
                    int distinct=1;for(int i=1;i<count;i++)if(oracle[i]!=oracle[i-1])distinct++;
                    check(snapshot.p95Ms()==oracle[(95*count+99)/100-1]/1_000_000.0,"exact rolling p95 capacity="+capacity+" tick="+tick);
                    check(snapshot.p99Ms()==oracle[(99*count+99)/100-1]/1_000_000.0,"exact rolling p99 capacity="+capacity+" tick="+tick);
                    check(snapshot.distinctDurations()==distinct&&snapshot.windowSamples()==count&&snapshot.sampleCount()==tick,
                        "exact duplicate cardinality and bounded resident count capacity="+capacity+" tick="+tick);
                }
                if(tick%37==0)check(g.sample(tick,1,true,true).equals(snapshot),"duplicate cannot replace resident percentile sample");
            }
        }
    }
    private static void actorOwnership()throws Exception{
        var g=guard();warm(g);var before=g.snapshot();
        var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var read=new java.util.concurrent.atomic.AtomicReference<ServerHealthGuard.Snapshot>();
        var other=new Thread(()->{read.set(g.snapshot());try{g.sample(6,nanos(20),false,true);}catch(Throwable error){failure.set(error);}},"health-foreign-test-actor");
        other.setDaemon(true);other.start();other.join(5000);
        check(!other.isAlive()&&failure.get() instanceof IllegalArgumentException,"foreign writer cannot alter health");
        check(g.snapshot().equals(before)&&read.get().equals(before),"read-only metrics can be observed without granting sample authority");
    }
    private static void recoveredBootstrap(Path profile)throws Exception{
        ConfigView config=TestingConfig.load(Files.readAllBytes(profile)).view();
        var normal=new ServerHealthGuard(config,HealthMode.NORMAL);
        check(normal.mode()==HealthMode.READ_ONLY_SAFE,"even known-normal boot has a technical measurement fence");
        for(long tick=1;tick<100;tick++)accept(normal,tick,40,false,true);
        check(normal.mode()==HealthMode.READ_ONLY_SAFE,"known-normal40ms boot cannot skip100 measured ticks");
        accept(normal,100,40,false,true);check(normal.mode()==HealthMode.NORMAL,"known-normal boot40ms uses existing45 entry threshold and100 window");
        var degraded=new ServerHealthGuard(config,HealthMode.DEGRADED);
        for(long tick=1;tick<2500;tick++)accept(degraded,tick,40,false,true);
        check(degraded.mode()==HealthMode.DEGRADED,"recovered degraded40ms cannot use bootstrap exemption");
        var critical=new ServerHealthGuard(config,HealthMode.CRITICAL);
        for(long tick=1;tick<2500;tick++)accept(critical,tick,40,false,true);
        check(critical.mode()==HealthMode.CRITICAL,"recovered critical40ms needs strict lower-threshold recovery");
        var overloaded=new ServerHealthGuard(config,HealthMode.NORMAL);
        for(long tick=1;tick<100;tick++)accept(overloaded,tick,50,false,true);
        check(overloaded.mode()==HealthMode.READ_ONLY_SAFE,"initial overloaded bootstrap still needs entry duration");
        accept(overloaded,100,50,false,true);check(overloaded.mode()==HealthMode.DEGRADED,"sustained bootstrap pressure becomes measured degraded");
        var gap=new ServerHealthGuard(config(3,5,8),HealthMode.NORMAL);
        accept(gap,1,40,false,true);accept(gap,2,40,false,true);accept(gap,10,40,false,true);accept(gap,11,40,false,true);
        check(gap.mode()==HealthMode.READ_ONLY_SAFE,"bootstrap gap resets known-normal measurement streak");
        accept(gap,12,40,false,true);check(gap.mode()==HealthMode.NORMAL,"three fresh contiguous bootstrap samples qualify");
        var grave=new ServerHealthGuard(config(3,5,8),HealthMode.NORMAL);accept(grave,1,70,false,true);
        check(grave.mode()==HealthMode.CRITICAL,"critical tick during bootstrap closes immediately");
        for(long tick=2;tick<30;tick++)accept(grave,tick,40,false,true);
        check(grave.mode()==HealthMode.CRITICAL,"critical bootstrap disables normal-bootstrap recovery shortcut");
        var lost=new ServerHealthGuard(config(3,5,8),HealthMode.NORMAL);accept(lost,1,20,false,false);
        for(long tick=2;tick<30;tick++)accept(lost,tick,20,false,true);
        check(lost.mode()==HealthMode.READ_ONLY_SAFE&&lost.snapshot().persistenceLost(),"bootstrap never clears lost persistence authority");
    }
    private static void actualDefaultProfile(Path profile)throws Exception{
        ConfigView config=TestingConfig.load(Files.readAllBytes(profile)).view();var g=new ServerHealthGuard(config);
        check(g.snapshot().windowCapacity()==1200,"central metrics window is consumed");
        for(long tick=1;tick<=1199;tick++)accept(g,tick,20,false,true);
        check(g.mode()==HealthMode.READ_ONLY_SAFE,"central1200 recovery is not bypassed");
        accept(g,1200,20,false,true);check(g.mode()==HealthMode.NORMAL,"central recovery window completes on measured ticks");
        for(long tick=1201;tick<=1299;tick++)accept(g,tick,45,false,true);
        check(g.mode()==HealthMode.NORMAL,"central100 entry is not shortened");
        accept(g,1300,45,false,true);check(g.mode()==HealthMode.DEGRADED,"central45 degraded threshold and100 window are consumed");
        accept(g,1301,70,false,true);check(g.mode()==HealthMode.CRITICAL,"central70 threshold is consumed");
        for(long tick=1302;tick<4000;tick++)accept(g,tick,38,false,true);
        check(g.mode()==HealthMode.CRITICAL,"central38 lower threshold is strictly enforced");
    }
    public static void main(String[] args)throws Exception{
        hysteresis();backlogAndDurability();continuity();statisticsAndBounds();rollingPercentileOracle();actorOwnership();
        Path profile=args.length==1?Path.of(args[0]):Path.of("../p008-source/docs/worlddirector-v1/spec-1.0.0/DEFAULT_FOR_TESTING.json");
        actualDefaultProfile(profile);recoveredBootstrap(profile);
        System.out.println("PASS ServerHealthGuardTests assertions="+assertions+" scope=SYNTHETIC_COMPLETED_TICK_DURATIONS_POLICY_NOT_NATIVE_MSPT_OR_FULLPACK");
    }
}
