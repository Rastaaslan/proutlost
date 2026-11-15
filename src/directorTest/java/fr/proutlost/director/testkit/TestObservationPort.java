package fr.proutlost.director.testkit;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.ObservationPort;
import fr.proutlost.director.bootstrap.DirectorBootstrap.TestOnlyBackend;
/** TEST_ONLY fixed immutable snapshot; does not sample a world. */
public final class TestObservationPort implements ObservationPort,TestOnlyBackend {
 private final SnapshotBatch snapshot;
 public TestObservationPort(SnapshotBatch snapshot){this.snapshot=java.util.Objects.requireNonNull(snapshot);}
 public SnapshotBatch sample(SampleRequest request){Checks.require(request.scopes().equals(snapshot.scopes()),"fixture scope mismatch");return snapshot;}
}
