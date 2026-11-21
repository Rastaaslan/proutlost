package fr.proutlost.director.testkit;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.FactBusPort;
import fr.proutlost.director.bootstrap.DirectorBootstrap.TestOnlyBackend;
/** TEST_ONLY fixed explicitly stamped responses; no persistence. */
public record TestFactBusPort(SubscriptionReadResult subscription,ConsumeAck result) implements FactBusPort,TestOnlyBackend {
 public TestFactBusPort {java.util.Objects.requireNonNull(subscription);java.util.Objects.requireNonNull(result);}
 public SubscriptionReadResult subscribe(ConsumerSpec consumer){subscription.page().ifPresent(p->Checks.require(consumer.equals(p.consumer()),"fixture consumer"));return subscription;}
 public java.util.concurrent.CompletionStage<SubscriptionReadResult> readSubscription(ConsumerSpec consumer){return java.util.concurrent.CompletableFuture.completedFuture(subscribe(consumer));}
 public java.util.concurrent.CompletionStage<ConsumeAck> acknowledge(ConsumerAck ack){return java.util.concurrent.CompletableFuture.completedFuture(result);}
}
