package fr.proutlost.director.bootstrap;

import java.util.Objects;
import net.minecraft.server.MinecraftServer;

/**
 * Native heartbeat for automatic Director orchestration. Captures bounded,
 * actor-only facts. A heartbeat is not a decision, reservation or event permit.
 * Publication/activation is intentionally fail-closed until G5 configuration,
 * providers and both real clients have been qualified.
 */
public final class DirectorNativeAutoSupervisor {
    public record Snapshot(long observedTicks, long evaluations,
                           int onlinePlayers, int missingPorts,
                           String lifecyclePhase, boolean gameplayAdmission,
                           boolean needsQualification) {
        public Snapshot {
            if (observedTicks < 0 || evaluations < 0 ||
                onlinePlayers < 0 || missingPorts < 0 ||
                gameplayAdmission || !needsQualification)
                throw new IllegalArgumentException("unqualified automatic Director activation");
            Objects.requireNonNull(lifecyclePhase);
        }
    }

    private static final int CADENCE_TICKS = 200;
    private long lastEvaluated = -CADENCE_TICKS;
    private long evaluations;
    private Snapshot last = new Snapshot(0,0,0,11,"STARTING",false,true);

    public Snapshot sample(MinecraftServer server,
                           DirectorServerLifecycle.View lifecycle) {
        Objects.requireNonNull(server);
        Objects.requireNonNull(lifecycle);
        if (!server.isSameThread())
            throw new IllegalStateException("automatic supervision requires server actor");
        if (lifecycle.observedTicks() < last.observedTicks())
            throw new IllegalArgumentException("server tick reversed");
        long now=lifecycle.observedTicks();
        if (now >= lastEvaluated && now-lastEvaluated < CADENCE_TICKS)
            return last;
        lastEvaluated=now;
        evaluations=Math.addExact(evaluations,1);
        int count=server.getPlayerList().getPlayerCount();
        // No chunks loaded, no mutable world state, no audience inspection.
        last=new Snapshot(now,evaluations,count,lifecycle.missingPortCount(),
            lifecycle.phase().name(),false,true);
        return last;
    }
    public Snapshot snapshot() {return last;}
    public static int cadenceTicks() {return CADENCE_TICKS;}
}
