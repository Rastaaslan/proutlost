package fr.proutlost.director.api.model;
import java.util.*;
/** The sole visibility pivot. J04 verifies every staged page and each current key revision. */
public record DurableMetadataActivation(LocationTransferManifest transfer,Optional<Fingerprint> previousGeneration,int pageCount) implements DurableRecord {
    public DurableMetadataActivation {Objects.requireNonNull(transfer);Objects.requireNonNull(previousGeneration);
        Checks.require(pageCount>=0&&pageCount<=1024,"metadata activation page count");}
}
