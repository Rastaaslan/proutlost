package fr.proutlost.director.api.port;
import fr.proutlost.director.api.model.*;
import java.util.*;
/** Optional V2 read index. These tokens attest selection semantics rather than
 * aggregate headers. REGION covers shape/status/membership and REGION_MEMORY
 * covers the set of camp references. PLAYER_OPERATIONAL covers identities and
 * last-region references; EVENT/CHAIN cover the region sets which pin memory.
 * Exact selected bodies/absences must still
 * be checked separately. This capability grants no writes or fresh-read claim. */
public interface MetadataSelectionReadPort extends MetadataReadPort {
    Optional<Map<AggregateKind,Sequence>> metadataSelectionVersions(MetadataReadSnapshot snapshot,Set<AggregateKind> kinds);
}
