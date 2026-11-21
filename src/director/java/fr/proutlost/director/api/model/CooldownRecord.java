package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record CooldownRecord(AggregateHeader header, ResourceId key, String kind, String reason, Tick notBeforeTick, EventInstanceId sourceInstanceId, Risk safetyClass, Optional<RetryCondition> releaseCondition, Optional<CooldownBinding> binding) {
 public CooldownRecord(AggregateHeader header,ResourceId key,String kind,String reason,Tick notBeforeTick,EventInstanceId sourceInstanceId,Risk safetyClass,Optional<RetryCondition> releaseCondition){this(header,key,kind,reason,notBeforeTick,sourceInstanceId,safetyClass,releaseCondition,Optional.empty());}
 public CooldownRecord { Objects.requireNonNull(binding); Objects.requireNonNull(header); Objects.requireNonNull(key); text(kind); text(reason); Objects.requireNonNull(notBeforeTick); Objects.requireNonNull(sourceInstanceId); Objects.requireNonNull(safetyClass); Objects.requireNonNull(releaseCondition);  }
}
