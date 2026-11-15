package fr.proutlost.contract.director;

import java.util.*;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Read-only sealed READY catalog. Bounded deterministic indexed queries; no chunk access. */
public interface LocationCatalogView {
    CatalogSnapshot snapshot();
    Optional<StaticLocationCatalogEntry> lookup(LocationRef reference);
    List<LocationRef> query(CatalogQuery query);
}
