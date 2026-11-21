package fr.proutlost.director.testkit;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.AdmissionPort;
import fr.proutlost.director.bootstrap.DirectorBootstrap.TestOnlyBackend;
/** TEST_ONLY fixed contract responses; no domain computation or persistence. */
public record TestAdmissionPort(AdmissionResult result) implements AdmissionPort,TestOnlyBackend {
 public TestAdmissionPort { java.util.Objects.requireNonNull(result); }
 public AdmissionResult admit(AdmissionRequest request){return result;}
}
