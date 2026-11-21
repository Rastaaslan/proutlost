package fr.proutlost.contract.director;

import java.util.*;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Separate runtime-site authority. Append immutable validated revisions; relocation gets a NEW ID. */
public interface RuntimeSiteRegistry {
    Optional<RuntimeSite> lookup(Id world, LocationRef location);
    void append(RuntimeSite site, Revision expectedRevision);
    void retire(RuntimeSite retiredRevision, Revision expectedRevision);
    void relocate(LocationRef predecessor, RuntimeSite successor);
}
