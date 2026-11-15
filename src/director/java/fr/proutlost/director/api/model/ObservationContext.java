package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record ObservationContext(Map<ScopeRef,ScopeSnapshot> scopes, Map<PlayerId,PlayerSnapshot> players, Map<UUID,GroupSnapshot> groups, Map<RegionId,RegionSnapshot> regions, Map<AnchorId,AnchorRecord> anchors, Map<LocationId,LocationRecord> locations, List<TerrainSnapshot> terrain, Map<CapabilityId,CapabilityDescriptor> capabilities) {
 public ObservationContext { scopes=map(scopes); players=map(players); groups=map(groups); regions=map(regions); anchors=map(anchors); locations=map(locations); terrain=list(terrain); capabilities=map(capabilities); require(scopes.entrySet().stream().allMatch(e->e.getKey().equals(e.getValue().scope())),"scope index"); require(players.entrySet().stream().allMatch(e->e.getKey().equals(e.getValue().playerId())),"player index"); require(regions.entrySet().stream().allMatch(e->e.getKey().equals(e.getValue().region().regionId())),"region index"); }
}
