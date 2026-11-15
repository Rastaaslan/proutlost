package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record CatalogSnapshot(Epoch generation, Fingerprint hash, Map<DefinitionId,EventDefinition> events, Map<DefinitionId,ChainDefinition> chains, Map<PrimitiveId,PrimitiveDescriptor> primitives, Map<DefinitionId,DefinitionScoringProfile> scoringProfiles) {
 public CatalogSnapshot(Epoch generation,Fingerprint hash,Map<DefinitionId,EventDefinition> events,Map<DefinitionId,ChainDefinition> chains,Map<PrimitiveId,PrimitiveDescriptor> primitives){this(generation,hash,events,chains,primitives,Map.of());}
 public CatalogSnapshot { scoringProfiles=map(scoringProfiles); for(var e:scoringProfiles.entrySet()) require(e.getKey().equals(e.getValue().definition().id())&&events.containsKey(e.getKey()),"scoring profile definition"); Objects.requireNonNull(generation); Objects.requireNonNull(hash); events=map(events); chains=map(chains); primitives=map(primitives);  }
}
