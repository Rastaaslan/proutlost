package fr.proutlost.director.persistence;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** Persistence-facing alias for the single canonical J04 reference traversal. */
final class BlobReferences {
    private BlobReferences() {}
    static Set<BlobRef> collect(Object value) {
        return DurableBlobReferences.collect(value);
    }
}
