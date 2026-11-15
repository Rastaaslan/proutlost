package fr.proutlost.director.testkit;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.AdminPort;
import fr.proutlost.director.bootstrap.DirectorBootstrap.TestOnlyBackend;
/** TEST_ONLY fixed contract responses; no domain computation or persistence. */
public record TestAdminPort(AdminReadResult read, AdminResult result) implements AdminPort,TestOnlyBackend {
 public TestAdminPort { java.util.Objects.requireNonNull(read); java.util.Objects.requireNonNull(result); }
 public AdminReadResult query(AdminQuery query,ActorIdentity actor){return read;} public java.util.concurrent.CompletionStage<AdminResult> submit(AdminCommand command,ActorIdentity actor){Checks.require(AdminContracts.authorized(command,actor),"fixture denied");return java.util.concurrent.CompletableFuture.completedFuture(result);}
}
