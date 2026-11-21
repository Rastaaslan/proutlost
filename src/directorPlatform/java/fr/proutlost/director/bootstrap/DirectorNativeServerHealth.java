package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.persistence.JournalStore;
import fr.proutlost.director.runtime.ServerHealthGuard;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.server.MinecraftServer;

/** Vanilla completed-tick durations -> bounded health policy. No TPS or50ms idle-time inference.
 * Disk-floor checks stay on J04's worker; server actor only reads immutable telemetry.
 */
public final class DirectorNativeServerHealth {
    private static final List<String> KEYS=List.of("health.degraded_mspt","health.critical_mspt","health.recover_mspt",
        "health.enter_window_ticks","health.recover_window_ticks","health.metrics_window_ticks","io.max_ack_ms");
    private final MinecraftServer server;private final JournalStore store;private final StoreSession session;
    private ServerHealthGuard guard;private List<java.math.BigDecimal> signature;
    private long enterWindow,maxAckNanos,lastCompleted=-1,lastDiskRequested=Long.MIN_VALUE;
    private CompletableFuture<Optional<JournalStore.HealthPressure>> disk;
    private boolean diskVerified,persistenceLost;
    private HealthMode reported;private long lastReport=-1;
    public DirectorNativeServerHealth(MinecraftServer server,JournalStore store,ConfigView config,HealthMode recovered){
        this.server=Objects.requireNonNull(server);this.store=Objects.requireNonNull(store);session=store.session();
        configure(config,Objects.requireNonNull(recovered));
    }
    private void actor(){if(!server.isSameThread())throw new IllegalStateException("native health server actor");}
    private void configure(ConfigView config,HealthMode recovered){
        signature=KEYS.stream().map(key->Objects.requireNonNull(config.values().get(key),key).value()).toList();
        enterWindow=config.values().get("health.enter_window_ticks").value().longValueExact();
        maxAckNanos=TimeUnit.MILLISECONDS.toNanos(config.values().get("io.max_ack_ms").value().longValueExact());
        Checks.require(maxAckNanos>0,"positive health ACK deadline");guard=new ServerHealthGuard(config,recovered);
    }
    public void poll(ConfigView config,boolean mailboxBacklog){
        actor();var next=KEYS.stream().map(key->Objects.requireNonNull(config.values().get(key),key).value()).toList();
        if(!signature.equals(next)&&!persistenceLost)configure(config,guard.mode());
        if(!session.equals(store.session())||store.readOnlySafe()){persistenceLost=true;return;}
        long current=Integer.toUnsignedLong(server.getTickCount());if(current==0)return;
        long completed=current-1; // Previous tick is final even if this Post callback precedes vanilla's write.
        if(disk!=null&&disk.isDone()){
            var checked=disk.join();disk=null;
            if(checked.isPresent()&&checked.orElseThrow().session().equals(session)&&!checked.orElseThrow().readOnlySafe())diskVerified=true;
        }
        if(disk==null&&(lastDiskRequested==Long.MIN_VALUE||completed-lastDiskRequested>=enterWindow)){
            disk=store.sampleHealthPressure().toCompletableFuture();lastDiskRequested=completed;
        }
        if(!diskVerified||completed<=lastCompleted)return;
        var times=server.getTickTimesNanos();if(times.length==0)return;
        long elapsed=times[(int)(completed%times.length)];if(elapsed<=0)return;
        var pressure=store.healthPressure();
        boolean available=pressure.session().equals(session)&&!pressure.readOnlySafe()&&pressure.oldestAppendAgeNanos()<=maxAckNanos;
        boolean backlog=mailboxBacklog||pressure.oldestTaskAgeNanos()>=maxAckNanos;
        var measured=guard.sample(completed,elapsed,backlog,available);lastCompleted=completed;persistenceLost|=measured.persistenceLost();
        HealthMode effective=mode();
        if(effective!=reported||lastReport<0||completed-lastReport>=enterWindow){
            System.out.println("DIRECTOR_SERVER_HEALTH measured="+effective+" samples="+measured.sampleCount()+" window="+measured.windowSamples()
                +" mspt="+measured.latestMs()+" ewma="+measured.ewmaMs()+" p95="+measured.p95Ms()+" p99="+measured.p99Ms()
                +" io_age_ms="+pressure.oldestTaskAgeNanos()/1_000_000.0+" ack_age_ms="+pressure.oldestAppendAgeNanos()/1_000_000.0
                +" reason="+measured.cause()+" source=VANILLA_COMPLETED_TICK_NANOS");reported=effective;lastReport=completed;
        }
    }
    public HealthMode mode(){
        if(persistenceLost||store.readOnlySafe()||!session.equals(store.session()))return HealthMode.READ_ONLY_SAFE;
        var measured=guard.snapshot();
        if(measured.mode()==HealthMode.CRITICAL)return HealthMode.CRITICAL;
        return diskVerified&&measured.sampleCount()>=enterWindow?measured.mode():HealthMode.READ_ONLY_SAFE;
    }
    public boolean measured(){return diskVerified&&(guard.snapshot().sampleCount()>=enterWindow||critical());}
    public boolean critical(){return persistenceLost||guard.snapshot().mode()==HealthMode.CRITICAL||guard.snapshot().persistenceLost();}
    public ServerHealthGuard.Snapshot snapshot(){return guard.snapshot();}
}
