package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record MemoryView(ScopeSet scopes, Map<ScopeRef,Revision> revisions, List<RegionMemory> regions, List<PlayerOperationalMemory> operationalPlayers, List<PlayerSignificantMemory> significantPlayers, Map<RegionId,RegionRecord> regionRecords, Map<AnchorId,AnchorRecord> anchors, Map<LocationId,LocationRecord> locations) {
 public MemoryView { Objects.requireNonNull(scopes); revisions=map(revisions); regions=list(regions); operationalPlayers=list(operationalPlayers); significantPlayers=list(significantPlayers); regionRecords=map(regionRecords); anchors=map(anchors); locations=map(locations);  }
}
