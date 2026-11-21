package fr.proutlost.director.runtime;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** Measured server-tick health; no TPS inference, IO, world mutation or execution authority.
 * One actor feeds completed tick durations in nanoseconds. The EWMA uses the
 * configured entry window, alpha=2/(enterWindow+1), while exact nearest-rank
 * percentiles cover the configured bounded metrics window. A missing tick is
 * never counted as a continuous good or overloaded sample.
 */
public final class ServerHealthGuard {
    public static final int MAX_METRIC_SAMPLES=65_536;
    public enum Cause { STARTUP, NORMAL, MSPT_PERSISTENT, BACKLOG_PERSISTENT, CRITICAL_MSPT,
        PERSISTENCE_LOST, MEASUREMENT_GAP, RECOVERY_COMPLETE }
    /** Diagnostic DTO only; sampleCount0/NaN metrics mean unmeasured, not healthy. */
    public record Snapshot(HealthMode mode,long sampleCount,int windowSamples,int windowCapacity,int distinctDurations,
            long lastTick,double latestMs,double ewmaMs,double p95Ms,double p99Ms,
            long degradedSamples,long recoverySamples,boolean persistenceLost,Cause cause) {}
    private final Thread actor=Thread.currentThread();
    private final double degradedMs,criticalMs,recoverMs,alpha;
    private final long enterWindow,recoverWindow;
    private final long[] samples,orderedSamples;
    private int position,size,distinctDurations;
    private long count,lastTick=-1,degradedSamples,recoverySamples;
    private double latest=Double.NaN,ewma=Double.NaN,p95=Double.NaN,p99=Double.NaN;
    private boolean persistenceLost,bootstrapNormal;
    private long bootstrapSamples;
    private HealthMode mode=HealthMode.READ_ONLY_SAFE;
    private Cause cause=Cause.STARTUP;
    private volatile Snapshot published;
    /** Unknown or previously unsafe startup retains strict recovery semantics. */
    public ServerHealthGuard(ConfigView config){this(config,HealthMode.READ_ONLY_SAFE);}
    /** NORMAL is a technical bootstrap fence, not a recovered health fault.
     * It needs the configured entry window of measured sub-degraded ticks.
     * Persisted DEGRADED/CRITICAL/READ_ONLY_SAFE retain lower-threshold recovery.
     */
    public ServerHealthGuard(ConfigView config,HealthMode recoveredMode){
        Objects.requireNonNull(config);Objects.requireNonNull(recoveredMode);
        bootstrapNormal=recoveredMode==HealthMode.NORMAL;
        mode=bootstrapNormal?HealthMode.READ_ONLY_SAFE:recoveredMode;
        degradedMs=positive(config,"health.degraded_mspt");criticalMs=positive(config,"health.critical_mspt");
        recoverMs=positive(config,"health.recover_mspt");
        Checks.require(recoverMs<degradedMs&&degradedMs<criticalMs,"health threshold order");
        enterWindow=window(config,"health.enter_window_ticks");recoverWindow=window(config,"health.recover_window_ticks");
        long capacity=window(config,"health.metrics_window_ticks");
        Checks.require(capacity<=MAX_METRIC_SAMPLES,"health metrics window capacity");
        samples=new long[(int)capacity];orderedSamples=new long[(int)capacity];alpha=2.0/((double)enterWindow+1.0);publish();
    }
    private static double positive(ConfigView config,String key){
        var value=Objects.requireNonNull(config.values().get(key),key).value().doubleValue();
        Checks.require(Double.isFinite(value)&&value>0,"health positive threshold "+key);return value;
    }
    private static long window(ConfigView config,String key){
        long value=Objects.requireNonNull(config.values().get(key),key).value().longValueExact();
        Checks.require(value>0,"health positive window "+key);return value;
    }
    private static long advance(long value,long maximum){return value<maximum?value+1:value;}
    private void publish(){published=new Snapshot(mode,count,size,samples.length,distinctDurations,lastTick,latest,ewma,p95,p99,
        degradedSamples,recoverySamples,persistenceLost,cause);}
    public HealthMode mode(){return published.mode();}
    public Snapshot snapshot(){return published;}
    public Snapshot sample(long completedServerTick,long tickNanos,boolean backlog,boolean persistenceAvailable){
        Checks.require(Thread.currentThread()==actor,"health single actor");
        Checks.require(completedServerTick>=0&&tickNanos>0,"health completed tick/duration");
        Checks.require(lastTick<0||completedServerTick>=lastTick,"health tick regression");
        if(!persistenceAvailable){persistenceLost=true;mode=HealthMode.READ_ONLY_SAFE;cause=Cause.PERSISTENCE_LOST;
            degradedSamples=0;recoverySamples=0;bootstrapSamples=0;bootstrapNormal=false;}
        if(completedServerTick==lastTick){publish();return published;}
        if(lastTick>=0&&completedServerTick-lastTick!=1){
            degradedSamples=0;recoverySamples=0;bootstrapSamples=0;
            if(mode==HealthMode.NORMAL){mode=HealthMode.READ_ONLY_SAFE;cause=Cause.MEASUREMENT_GAP;}
        }
        lastTick=completedServerTick;count=Math.incrementExact(count);latest=tickNanos/1_000_000.0;
        ewma=Double.isNaN(ewma)?latest:ewma+alpha*(latest-ewma);
        // Exact nearest-rank metrics on the same bounded rolling window.
        // Contiguous primitive copies replace a boxed tree walk on every actor tick;
        // no sample, threshold, recovery interval or freshness rule is skipped.
        if(size==samples.length){
            long previous=samples[position];int index=Arrays.binarySearch(orderedSamples,0,size,previous);
            Checks.require(index>=0,"health rolling percentile member");
            if((index==0||orderedSamples[index-1]!=previous)
                    &&(index+1==size||orderedSamples[index+1]!=previous))distinctDurations--;
            System.arraycopy(orderedSamples,index+1,orderedSamples,index,size-index-1);
        }else size++;
        int retained=size-1,index=Arrays.binarySearch(orderedSamples,0,retained,tickNanos);
        if(index<0){index=-index-1;distinctDurations++;}
        System.arraycopy(orderedSamples,index,orderedSamples,index+1,retained-index);
        orderedSamples[index]=tickNanos;
        samples[position]=tickNanos;position=(position+1)%samples.length;
        int rank95=(95*size+99)/100,rank99=(99*size+99)/100;
        p95=orderedSamples[rank95-1]/1_000_000.0;p99=orderedSamples[rank99-1]/1_000_000.0;
        if(persistenceLost){mode=HealthMode.READ_ONLY_SAFE;cause=Cause.PERSISTENCE_LOST;publish();return published;}
        if(latest>=criticalMs||ewma>=criticalMs||p95>=criticalMs){
            mode=HealthMode.CRITICAL;cause=Cause.CRITICAL_MSPT;degradedSamples=0;recoverySamples=0;bootstrapSamples=0;bootstrapNormal=false;publish();return published;
        }
        boolean pressured=backlog||latest>=degradedMs||ewma>=degradedMs||p95>=degradedMs;
        boolean recovered=!backlog&&latest<recoverMs&&ewma<recoverMs&&p95<recoverMs;
        if(bootstrapNormal){
            recoverySamples=0;
            if(pressured){bootstrapSamples=0;degradedSamples=advance(degradedSamples,enterWindow);
                if(degradedSamples>=enterWindow){mode=HealthMode.DEGRADED;cause=backlog?Cause.BACKLOG_PERSISTENT:Cause.MSPT_PERSISTENT;bootstrapNormal=false;}}
            else {degradedSamples=0;bootstrapSamples=advance(bootstrapSamples,enterWindow);
                if(bootstrapSamples>=enterWindow){mode=HealthMode.NORMAL;cause=Cause.RECOVERY_COMPLETE;bootstrapNormal=false;}}
        }else if(mode==HealthMode.NORMAL){
            recoverySamples=0;
            if(pressured){degradedSamples=advance(degradedSamples,enterWindow);
                if(degradedSamples>=enterWindow){mode=HealthMode.DEGRADED;cause=backlog?Cause.BACKLOG_PERSISTENT:Cause.MSPT_PERSISTENT;}}
            else {degradedSamples=0;cause=Cause.NORMAL;}
        }else{
            if(pressured){degradedSamples=advance(degradedSamples,enterWindow);recoverySamples=0;}
            else {degradedSamples=0;recoverySamples=recovered?advance(recoverySamples,recoverWindow):0;}
            if(recoverySamples>=recoverWindow){mode=HealthMode.NORMAL;cause=Cause.RECOVERY_COMPLETE;degradedSamples=0;}
        }
        publish();return published;
    }
}
