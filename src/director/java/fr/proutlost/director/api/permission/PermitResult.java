package fr.proutlost.director.api.permission;
import fr.proutlost.director.api.error.ReasonCode;
import java.util.*;
/** Polling never waits; rejection is data, never successful execution. */
public record PermitResult(Status status,Optional<CommitPermit> permit,Optional<ReasonCode> reason) {
 public enum Status { PENDING, GRANTED, REJECTED }
 public PermitResult {Objects.requireNonNull(status);Objects.requireNonNull(permit);Objects.requireNonNull(reason);if((status==Status.GRANTED)!=permit.isPresent()||(status==Status.REJECTED)!=reason.isPresent())throw new IllegalArgumentException("permit result");}
 public static PermitResult pending(){return new PermitResult(Status.PENDING,Optional.empty(),Optional.empty());}
 public static PermitResult granted(CommitPermit permit){return new PermitResult(Status.GRANTED,Optional.of(permit),Optional.empty());}
 public static PermitResult rejected(ReasonCode reason){return new PermitResult(Status.REJECTED,Optional.empty(),Optional.of(reason));}
}
