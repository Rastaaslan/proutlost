package fr.proutlost.director.api.model;
import java.util.*;
/** Explicitly retire a hidden proposal; retained J04 evidence is never deleted. */
public record DurableMetadataCancellation(LocationTransferManifest transfer) implements DurableRecord {
    public DurableMetadataCancellation{Objects.requireNonNull(transfer);}
}
