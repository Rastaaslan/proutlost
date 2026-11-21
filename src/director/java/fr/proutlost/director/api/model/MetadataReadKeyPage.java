package fr.proutlost.director.api.model;
import java.util.*;
/** Lightweight discovery page at exactly the supplied pinned cut. */
public record MetadataReadKeyPage(MetadataReadSnapshot snapshot,List<AggregateKey> keys,Optional<AggregateKey> next){
    public MetadataReadKeyPage {Objects.requireNonNull(snapshot);keys=List.copyOf(keys);Objects.requireNonNull(next);}
}
