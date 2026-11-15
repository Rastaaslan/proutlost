package fr.proutlost.director.testkit;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.time.ClockSource;
import fr.proutlost.director.bootstrap.DirectorBootstrap.TestOnlyBackend;
import java.time.Instant;
/** TEST_ONLY: manually advances clock domains independently. */
public final class TestClock implements ClockSource,TestOnlyBackend {
 private Tick tick=new Tick(0);private long nanos;private Instant utc=Instant.EPOCH;
 public void tick(){tick=tick.plus(1);}
 public void elapsed(long ns){nanos=Math.addExact(nanos,ns);utc=utc.plusNanos(ns);}
 public DirectorClock snapshot(){return new DirectorClock(tick,nanos,utc);}
}
