package fr.proutlost.director.persistence;

import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.model.*;
import java.util.*;

/** Writer-derived bounded index. It retains hashes, never region/memory bodies.
 * Replay applies the same visible-head transitions; staged pages do not enter
 * this index until activation. Unrelated activity counters do not alter the
 * spatial or timed-camp selection of a pinned reader. */
final class MetadataSelectionCensus {
    private static final ModelCodec<RegionRecord> REGION=new ModelCodec<>(RegionRecord.class);
    private MetadataSelectionCensus(){}
    static void update(StoreProjection projection,AggregateState state,long sequence) {
        Fingerprint fingerprint;boolean absentEquivalent=false;
        if(state instanceof RegionAggregate aggregate) {
            var region=aggregate.value();
            // Keep every spatial/membership field. Only header, personality
            // and importance are irrelevant to selection of OTHER regions.
            var normalized=new RegionRecord(new AggregateHeader(1,region.regionId().value(),new Revision(0),new Sequence(0),new Tick(0)),
                region.regionId(),region.dimension(),region.status(),region.supportShape(),region.anchorIds(),new UnitValue(0),
                region.parentIds(),region.aliases(),region.membershipRevision(),new RegionPersonality(Map.of()),region.memoryRefs());
            fingerprint=BlobContent.hash(REGION.encode(normalized));
        } else if(state instanceof RegionMemoryAggregate aggregate) {
            var camps=aggregate.value().activity().camps().keySet();absentEquivalent=camps.isEmpty();
            List<JsonValue> identities=camps.stream().sorted().map(id->(JsonValue)new JsonValue.Text(id.value().toString())).toList();
            fingerprint=BlobContent.hash(CanonicalJsonV1.encode(new JsonValue.Array(identities)));
        } else if(state instanceof PlayerOperationalAggregate aggregate) {
            var player=aggregate.value();
            fingerprint=BlobContent.hash(CanonicalJsonV1.encode(new JsonValue.Array(List.of(
                new JsonValue.Text(player.playerId().value().toString()),
                player.lastRegionId().<JsonValue>map(id->new JsonValue.Text(id.value().toString())).orElse(JsonValue.Nil.INSTANCE)))));
        } else if(state instanceof EventAggregate aggregate) {
            var event=aggregate.value();
            boolean active=switch(event.state()){case SUCCEEDED,PARTIAL,ABORTED_SAFE,FAILED_SAFE->false;default->true;};
            var regions=active?event.regionIds():Set.<RegionId>of();absentEquivalent=regions.isEmpty();
            fingerprint=regions(regions);
        } else if(state instanceof ChainAggregate aggregate) {
            var chain=aggregate.value();
            boolean active=chain.state()!=ChainState.COMPLETED&&chain.state()!=ChainState.ABORTED_SAFE
                &&(chain.state()!=ChainState.SETTLED_AS_WORLD_STATE||chain.regionalStateRef().isPresent());
            var regions=active?chain.lineageRefs():Set.<RegionId>of();absentEquivalent=regions.isEmpty();
            fingerprint=regions(regions);
        } else return;
        var previous=projection.metadataSelectionFingerprints.get(state.key());
        if(fingerprint.equals(previous))return;
        projection.metadataSelectionFingerprints.put(state.key(),fingerprint);
        // A new empty off-scope memory adds no timed dependency. Scoped absence
        // proofs independently fence its appearance inside an actual demand.
        if(!fingerprint.equals(previous)&&!(previous==null&&absentEquivalent))
            projection.metadataSelectionVersions.put(state.key().kind(),sequence);
    }
    private static Fingerprint regions(Set<RegionId> regions) {
        List<JsonValue> identities=regions.stream().sorted().map(id->(JsonValue)new JsonValue.Text(id.value().toString())).toList();
        return BlobContent.hash(CanonicalJsonV1.encode(new JsonValue.Array(identities)));
    }
}
