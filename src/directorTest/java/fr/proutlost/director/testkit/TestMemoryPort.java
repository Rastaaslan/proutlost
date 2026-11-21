package fr.proutlost.director.testkit;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.MemoryPort;
import fr.proutlost.director.bootstrap.DirectorBootstrap.TestOnlyBackend;
/** TEST_ONLY fixed contract responses; no domain computation or persistence. */
public record TestMemoryPort(MemoryView snapshot, Reduction reduction) implements MemoryPort,TestOnlyBackend {
 public TestMemoryPort { java.util.Objects.requireNonNull(snapshot); java.util.Objects.requireNonNull(reduction); }
 public MemoryView view(ScopeSet scopes){Checks.require(snapshot.scopes().equals(scopes),"fixture scopes");return snapshot;} public Reduction reduce(MemoryView before,ObservationBatch input,DirectorClock clock){Checks.require(before.equals(snapshot),"fixture memory");return reduction;}
}
