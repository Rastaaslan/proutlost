package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record UniqueUseRecord(AggregateHeader header, DefinitionId definitionId, UniquenessScope scopeKind, UUID canonicalScopeId, UniqueStatus status, Optional<ReservationToken> reservationToken, EventInstanceId instanceId, Optional<BlobRef> consumptionEvidence, Set<UUID> originScopeIds) {
 public UniqueUseRecord { Objects.requireNonNull(header); Objects.requireNonNull(definitionId); Objects.requireNonNull(scopeKind); Objects.requireNonNull(canonicalScopeId); Objects.requireNonNull(status); Objects.requireNonNull(reservationToken); Objects.requireNonNull(instanceId); Objects.requireNonNull(consumptionEvidence); originScopeIds=set(originScopeIds);  }
}
