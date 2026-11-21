package fr.proutlost.director.testkit;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.AiAdvisorPort;
import fr.proutlost.director.bootstrap.DirectorBootstrap.TestOnlyBackend;
/** TEST_ONLY fixed contract responses; no domain computation or persistence. */
public record TestAiAdvisorPort(AiProposalResult result) implements AiAdvisorPort,TestOnlyBackend {
 public TestAiAdvisorPort { java.util.Objects.requireNonNull(result); }
 public java.util.concurrent.CompletionStage<AiProposalResult> propose(AiInput input){return java.util.concurrent.CompletableFuture.completedFuture(result);}
}
