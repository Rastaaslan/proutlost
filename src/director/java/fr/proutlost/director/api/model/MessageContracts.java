package fr.proutlost.director.api.model;
public final class MessageContracts {
 private MessageContracts() {}
 public static DirectorRequest request(DirectorEnvelope e){Checks.require(e.payload() instanceof RequestMessage,"request envelope");return new DirectorRequest(e.messageId(),e.worldId(),e.producerId(),e.producerKey(),e.scope(),e.correlationId(),e.causationId(),e.producerSequence(),e.createdTick(),((RequestMessage)e.payload()).value());}
 public static boolean matches(PayloadType type,EnvelopePayload payload){return switch(type){case DirectorRequest -> payload instanceof RequestMessage;case DirectorFact -> payload instanceof FactPayload;case DirectorEvent -> payload instanceof EventPayload;case DirectorObservation -> payload instanceof ObservationPayload;case DirectorWorldState -> payload instanceof WorldStatePayload;};}
}
