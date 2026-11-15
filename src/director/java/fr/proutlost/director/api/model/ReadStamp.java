package fr.proutlost.director.api.model;
import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;
/** Stable last-commit cut; never an outbox message sequence or authority token. */
public record ReadStamp(StoreSession session,Sequence snapshotSequence,Fingerprint queryHash) {
 public ReadStamp {Objects.requireNonNull(session);Objects.requireNonNull(snapshotSequence);Objects.requireNonNull(queryHash);}
}
