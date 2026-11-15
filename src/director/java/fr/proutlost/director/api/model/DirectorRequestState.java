package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record DirectorRequestState(AggregateHeader header, RequestId requestId, ResourceId producerId, String producerKey, RequestKind kind, Selector selector, ScopeRef scope, TargetConstraints constraints, FulfillmentMode fulfillment, RequestStatus status, DurationTicks eligibleWaitTicks, Optional<Tick> expiresTick, Set<FactId> resultFactIds, List<fr.proutlost.director.api.error.ReasonCode> lastReasonCodes, RequestSchedulingPolicy schedulingPolicy) {
 public DirectorRequestState(AggregateHeader header, RequestId requestId, ResourceId producerId, String producerKey, RequestKind kind, Selector selector, ScopeRef scope, TargetConstraints constraints, FulfillmentMode fulfillment, RequestStatus status, DurationTicks eligibleWaitTicks, Optional<Tick> expiresTick, Set<FactId> resultFactIds, List<fr.proutlost.director.api.error.ReasonCode> lastReasonCodes){this(header,requestId,producerId,producerKey,kind,selector,scope,constraints,fulfillment,status,eligibleWaitTicks,expiresTick,resultFactIds,lastReasonCodes,RequestSchedulingPolicy.legacyWire());}
 public DirectorRequestState { Objects.requireNonNull(schedulingPolicy); Objects.requireNonNull(header); Objects.requireNonNull(requestId); Objects.requireNonNull(producerId); text(producerKey); Objects.requireNonNull(kind); Objects.requireNonNull(selector); Objects.requireNonNull(scope); Objects.requireNonNull(constraints); Objects.requireNonNull(fulfillment); Objects.requireNonNull(status); Objects.requireNonNull(eligibleWaitTicks); Objects.requireNonNull(expiresTick); resultFactIds=set(resultFactIds); lastReasonCodes=list(lastReasonCodes);  }
}
