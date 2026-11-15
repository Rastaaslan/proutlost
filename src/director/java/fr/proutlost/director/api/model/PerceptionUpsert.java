package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record PerceptionUpsert(PerceptionHeader header, PerceptionKind kind, PerceptionTransform transform, ResourceId assetId, Fingerprint assetHash, PerceptionParameters parameters) {
 public PerceptionUpsert { Objects.requireNonNull(header); Objects.requireNonNull(kind); Objects.requireNonNull(transform); Objects.requireNonNull(assetId); Objects.requireNonNull(assetHash); Objects.requireNonNull(parameters); require(header.remainingTtlTicks().value()>0,"finite live TTL"); require(NetworkContracts.matches(kind,parameters),"perception parameters"); }
}
