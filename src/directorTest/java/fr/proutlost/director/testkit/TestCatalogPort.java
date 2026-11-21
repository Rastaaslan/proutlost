package fr.proutlost.director.testkit;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.CatalogPort;
import fr.proutlost.director.bootstrap.DirectorBootstrap.TestOnlyBackend;
/** TEST_ONLY fixed contract responses; no domain computation or persistence. */
public record TestCatalogPort(CatalogSnapshot snapshot) implements CatalogPort,TestOnlyBackend {
 public TestCatalogPort { java.util.Objects.requireNonNull(snapshot); }
 public CatalogSnapshot current(){return snapshot;}
}
