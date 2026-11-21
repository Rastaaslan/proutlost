package fr.proutlost.director.memory;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.*;
import java.util.*;

/** Read-side attestation of a complete pinned anchor scan. It retains tags and
 * head identities, never cold AnchorRecord bodies or permission to mutate them.
 * A partial spatial working set cannot by itself attest a region's tag census. */
public final class RegionAnchorCoverage {
    public record Entry(RegionRecord region,Set<TagId> tags,Set<AnchorId> anchorIds,boolean declaredComplete) {
        public Entry {Objects.requireNonNull(region);tags=Set.copyOf(tags);anchorIds=Set.copyOf(anchorIds);}
    }
    private final MetadataReadSnapshot snapshot;
    private final Map<RegionId,Entry> regions;
    private final Map<AggregateKind,Sequence> anchorHeads;
    private final Map<PlayerId,Sequence> significantHeads;
    private final Map<AggregateKind,Sequence> selectionHeads;
    private final ConfigView selectionProfile;
    private final ProviderMode selectionMode;
    public RegionAnchorCoverage(MetadataReadSnapshot snapshot,Map<RegionId,Entry> regions,Map<AggregateKind,Sequence> anchorHeads,Map<PlayerId,Sequence> significantHeads,Map<AggregateKind,Sequence> selectionHeads,MemorySettings settings) {
        this.snapshot=Objects.requireNonNull(snapshot);this.regions=Map.copyOf(regions);this.anchorHeads=Map.copyOf(anchorHeads);this.significantHeads=Map.copyOf(significantHeads);this.selectionHeads=Map.copyOf(selectionHeads);
        selectionProfile=settings.configuration();selectionMode=settings.mode();
    }
    public Optional<Set<TagId>> tags(RegionRecord region) {
        var entry=regions.get(region.regionId());
        return entry!=null&&entry.declaredComplete()&&entry.region().equals(region)?Optional.of(entry.tags()):Optional.empty();
    }
    public boolean allBodies(RegionRecord region,Map<AnchorId,AnchorRecord> bodies){
        var entry=regions.get(region.regionId());
        return tags(region).isPresent()&&bodies.keySet().containsAll(entry.anchorIds());
    }
    public boolean current(MetadataReadPortProvider source,MetadataReadSnapshot current) {
        var store=source.metadataReads();if(!sameSelection(store,current,false))return false;
        for(var entry:regions.values())if(!store.metadataRevision(current,new AggregateKey(AggregateKind.REGION,entry.region().regionId().value()))
            .equals(entry.region().header().revision()))return false;
        return true;
    }
    /** Selection proof only: bodies may have changed and must be read again at
     * the requested pinned cut before publishing or obtaining CAS authority. */
    public boolean sameSelection(MetadataReadPortProvider source,MetadataReadSnapshot current) {
        return sameSelection(source,current,true);
    }
    private boolean sameSelection(MetadataReadPortProvider source,MetadataReadSnapshot current,boolean includePlayerCacheKeys) {
        var store=source.metadataReads();if(!store.owns(snapshot)||!store.owns(current))return false;
        if(!snapshot.session().equals(current.session())||!snapshot.generations().equals(current.generations())
            ||!snapshot.sources().equals(current.sources()))return false;
        var currentSelection=selectionHeads.isEmpty()?Map.<AggregateKind,Sequence>of():selectionHeads(store,current);
        // A new unrequested player is outside this scoped view, never a claim
        // of absence: WORLD is not covered. It does not change its current
        // bodies/tags. Exact key-set reuse for refresh remains stricter and
        // must rescan to include the newly known operational cache head.
        boolean selectionCurrent=selectionHeads.entrySet().stream().allMatch(entry->
            !includePlayerCacheKeys&&entry.getKey()==AggregateKind.PLAYER_OPERATIONAL
                ||entry.getValue().equals(currentSelection.get(entry.getKey())));
        return (anchorHeads.isEmpty()||anchorHeads.equals(anchorHeads(store,current)))
            &&selectionCurrent
            &&store.metadataSignificantVersions(current,significantHeads.keySet()).filter(significantHeads::equals).isPresent();
    }
    public boolean sameSettings(MemorySettings settings){return selectionMode==settings.mode()&&selectionProfile.equals(settings.configuration());}
    RegionAnchorCoverage rebound(MetadataReadSnapshot current,Map<AggregateKey,AggregateState> states,MemorySettings settings) {
        var entries=new HashMap<RegionId,Entry>();
        for(var prior:regions.entrySet()) {
            var row=states.get(new AggregateKey(AggregateKind.REGION,prior.getKey().value()));
            if(!(row instanceof RegionAggregate region))throw new IllegalStateException("refreshed selected region is missing");
            var old=prior.getValue();entries.put(prior.getKey(),new Entry(region.value(),old.tags(),old.anchorIds(),old.declaredComplete()));
        }
        return new RegionAnchorCoverage(current,entries,anchorHeads,significantHeads,selectionHeads,settings);
    }
    /** V2 semantic spatial/timed-reference census. It never substitutes for
     * selected body or scoped absence validation. */
    public static Map<AggregateKind,Sequence> selectionHeads(MetadataReadPortProvider source,MetadataReadSnapshot snapshot) {
        var store=source.metadataReads();
        if(!store.owns(snapshot)||!(store instanceof MetadataSelectionReadPort selection))throw new IllegalStateException("semantic metadata census unavailable");
        return selection.metadataSelectionVersions(snapshot,Set.of(AggregateKind.REGION,AggregateKind.REGION_MEMORY,AggregateKind.PLAYER_OPERATIONAL,AggregateKind.EVENT,AggregateKind.CHAIN))
            .orElseThrow(()->new IllegalStateException("semantic metadata census unavailable"));
    }
    /** Constant-size spatial/reference census token; unrelated WAL writes preserve it. */
    public static Map<AggregateKind,Sequence> anchorHeads(MetadataReadPortProvider source,MetadataReadSnapshot snapshot) {
        var store=source.metadataReads();if(!store.owns(snapshot))throw new IllegalStateException("foreign or revoked metadata snapshot");
        return store.metadataHeadVersions(snapshot,Set.of(AggregateKind.LOCATION,AggregateKind.ANCHOR))
            .orElseThrow(()->new IllegalStateException("anchor head index unavailable"));
    }
}
