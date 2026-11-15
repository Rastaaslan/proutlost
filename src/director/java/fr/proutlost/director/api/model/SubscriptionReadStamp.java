package fr.proutlost.director.api.model;
import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;
/** expectedCursor/HighWater/scannedThrough are MESSAGE worldSequence; store cut is COMMIT sequence.
 * Scan interval is [expectedCursor,scannedThrough], empty when scannedThrough=expectedCursor-1.
 */
public record SubscriptionReadStamp(ReadStamp store,Sequence expectedCursor,Sequence outboxHighWater,Sequence scannedThrough) {
 public SubscriptionReadStamp {Objects.requireNonNull(store);Objects.requireNonNull(expectedCursor);Objects.requireNonNull(outboxHighWater);Objects.requireNonNull(scannedThrough);require(scannedThrough.value()>=Math.max(0,expectedCursor.value()-1)&&scannedThrough.compareTo(outboxHighWater)<=0,"outbox scan interval");require(scannedThrough.value()<Long.MAX_VALUE,"next message sequence overflow");}
}
