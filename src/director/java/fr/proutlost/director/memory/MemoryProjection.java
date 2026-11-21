package fr.proutlost.director.memory;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** Immutable projection of durable aggregates. Significant archives are authored by J05. */
public final class MemoryProjection {
    private MemoryProjection() {}
    public static MemoryView of(ScopeSet scopes, Collection<AggregateState> states) {
        Map<ScopeRef,Revision> revisions = new HashMap<>();
        Map<RegionId,RegionRecord> records = new TreeMap<>();
        Map<AnchorId,AnchorRecord> anchors = new HashMap<>();
        Map<LocationId,LocationRecord> locations = new HashMap<>();
        List<RegionMemory> regions = new ArrayList<>();
        List<PlayerOperationalMemory> players = new ArrayList<>();
        List<PlayerSignificantMemory> significant = new ArrayList<>();
        Set<AggregateKey> seen = new HashSet<>();
        for (AggregateState state : states) {
            if (!seen.add(state.key())) throw new IllegalArgumentException("duplicate projection key");
            switch (state) {
                case RegionAggregate a -> { if (includes(scopes,ScopeKind.REGION,a.value().regionId().value())) {
                    records.put(a.value().regionId(),a.value()); revision(revisions,ScopeKind.REGION,a.value().regionId().value(),a.header());
                } }
                case RegionMemoryAggregate a -> { if (includes(scopes,ScopeKind.REGION,a.value().regionId().value())) {
                    regions.add(a.value()); revision(revisions,ScopeKind.REGION,a.value().regionId().value(),a.header());
                } }
                case PlayerOperationalAggregate a -> { if (includes(scopes,ScopeKind.PLAYER,a.value().playerId().value())) {
                    players.add(a.value()); revision(revisions,ScopeKind.PLAYER,a.value().playerId().value(),a.header());
                } }
                case PlayerSignificantAggregate a -> { if (includes(scopes,ScopeKind.PLAYER,a.value().playerId().value())) significant.add(a.value()); }
                case AnchorAggregate a -> { if (includesWorld(scopes) || a.value().regionIds().stream().anyMatch(id -> includes(scopes,ScopeKind.REGION,id.value()))
                    || a.value().origin()==AnchorOrigin.CAMP && a.value().regionIds().isEmpty()
                        && scopes.scopes().contains(new ScopeRef(ScopeKind.REGION,a.value().anchorId().value()))) anchors.put(a.value().anchorId(),a.value()); }
                case LocationAggregate a -> { if (includes(scopes,ScopeKind.LOCATION,a.value().locationId().value())) locations.put(a.value().locationId(),a.value()); }
                default -> { /* Other owners' states are not memory projections. */ }
            }
        }
        Set<LocationId> referencedLocations=new HashSet<>();anchors.values().forEach(a->a.locationId().ifPresent(referencedLocations::add));
        for(var state:states)if(state instanceof LocationAggregate a&&referencedLocations.contains(a.value().locationId()))locations.put(a.value().locationId(),a.value());
        regions.sort(Comparator.comparing(RegionMemory::regionId));
        players.sort(Comparator.comparing(PlayerOperationalMemory::playerId));
        significant.sort(Comparator.comparing(PlayerSignificantMemory::recordedTick).thenComparing(PlayerSignificantMemory::memoryId));
        return new MemoryView(scopes,revisions,regions,players,significant,records,anchors,locations);
    }
    private static void revision(Map<ScopeRef,Revision> map, ScopeKind kind, UUID id, AggregateHeader h) {
        map.merge(new ScopeRef(kind,id),h.revision(),(a,b)->a.compareTo(b)>=0?a:b);
    }
    private static boolean includesWorld(ScopeSet scopes) { return scopes.scopes().contains(new ScopeRef(ScopeKind.WORLD,scopes.worldId().value())); }
    private static boolean includes(ScopeSet scopes, ScopeKind kind, UUID id) { return includesWorld(scopes)||scopes.scopes().contains(new ScopeRef(kind,id)); }
}
