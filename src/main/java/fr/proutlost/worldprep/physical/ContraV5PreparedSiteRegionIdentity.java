package fr.proutlost.worldprep.physical;

import fr.proutlost.contract.director.Id;
import fr.proutlost.contract.director.SemanticRegionId;
import java.util.UUID;

/**
 * A prepared physical site's region is owned by its immutable operation,
 * not just by the parent/sector area. Two placements in the same area
 * have distinct bounds; assigning them the same region ID would cause
 * CatalogValidation to reject the entire final D0 catalogue.
 *
 * Not a Director import or an authorization to start V5.
 */
public final class ContraV5PreparedSiteRegionIdentity {
    private ContraV5PreparedSiteRegionIdentity() {}

    public static SemanticRegionId forOperation(String areaId, UUID operation) {
        if (areaId == null || areaId.isBlank() || operation == null) {
            throw new IllegalArgumentException("Prepared site requires pinned area and operation");
        }
        return new SemanticRegionId(new Id("proutlost:prepared_site/"
            + PhysicalStore.sha("v1|area=" + areaId + "|operation=" + operation)));
    }
}
