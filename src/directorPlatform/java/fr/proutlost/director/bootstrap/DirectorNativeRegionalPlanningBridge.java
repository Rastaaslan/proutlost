package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.memory.MemorySettings;
import fr.proutlost.director.memory.MemorySnapshotProvider;
import fr.proutlost.director.memory.AuthoredRegionTagProjection;
import fr.proutlost.director.memory.RegionAnchorCoverage;
import java.util.*;

/**
 * J01/J04 -> J03: actor-captured, bounded, read-only regional context.
 * Neither region memory nor a historical J04 anchor is an effect permission.
 * Incomplete/cold projections stay unknown and any oversize is deferred,
 * never rewritten into a false "no regions" safety conclusion.
 */
public final class DirectorNativeRegionalPlanningBridge {
    private static final int MAX_REGIONS=64;
    private static final int MAX_REGION_ANCHORS=256;
    private static final int MAX_LOCATIONS=256;
    private static final int MAX_SNAPSHOT_BYTES=262144;
    public record Projection(SnapshotBatch snapshot, long regionCount,
            long campCount,long j04Sequence,boolean deferred) {
        public Projection {
            Objects.requireNonNull(snapshot);
            if(regionCount<0||campCount<0||j04Sequence<0)
                throw new IllegalArgumentException("regional planning counters");
            if(deferred&&(regionCount!=0||campCount!=0))
                throw new IllegalArgumentException("deferred is not complete evidence");
        }
    }
    private final MemorySnapshotProvider provider;
    public DirectorNativeRegionalPlanningBridge(ConfigView config) {
        this(config,ProviderMode.TEST_ONLY);
    }
    /** Same bounded J04 projection in both modes; bootstrap supplies explicit
     * provenance and matching calibration. Membership grants no effect rights. */
    public DirectorNativeRegionalPlanningBridge(ConfigView config,ProviderMode mode) {
        Objects.requireNonNull(config);
        Objects.requireNonNull(mode);
        if(config.calibration()!=(mode==ProviderMode.PRODUCTION
                ?Calibration.APPROVED:Calibration.DEFAULT_FOR_TESTING))
            throw new IllegalArgumentException("J03 regional calibration/provider mode mismatch");
        provider=new MemorySnapshotProvider(new MemorySettings(config,mode==ProviderMode.PRODUCTION));
    }
    public Projection project(SnapshotBatch live,MemoryView memory,long durableSequence){
        return project(live,memory,durableSequence,Map.of());
    }
    /** authoredRegionTags must come from the current world coordinator's
     * verified J04 manifest + exact memory projection, never input JSON alone. */
    public Projection project(SnapshotBatch live,MemoryView memory,long durableSequence,
            Map<RegionId,Set<TagId>> authoredRegionTags){
        return project(live,memory,durableSequence,authoredRegionTags,Optional.empty());
    }
    public Projection project(SnapshotBatch live,MemoryView memory,long durableSequence,
            Map<RegionId,Set<TagId>> authoredRegionTags,Optional<RegionAnchorCoverage> coverage){
        Objects.requireNonNull(live);Objects.requireNonNull(memory);
        Objects.requireNonNull(authoredRegionTags);Objects.requireNonNull(coverage);
        if(!live.scopes().worldId().equals(memory.scopes().worldId())||
           durableSequence<0)
            throw new IllegalArgumentException("foreign/invalid J04 projection");
        if(memory.regionRecords().size()>MAX_REGIONS||authoredRegionTags.size()>MAX_REGIONS)
            return deferred(live,durableSequence);
        // A verified complete anchor-tag fold permits regional semantics while
        // distant anchor bodies remain cold. It grants no physical authority.
        // The pure provider performs no chunk loads or fictitious risk sampling.
        var enriched=provider.enrich(live,memory,coverage);
        if(enriched.context().regions().size()>MAX_REGIONS)
            return deferred(live,durableSequence);
        // The pure provider omits a region with missing memory or an unattested
        // incomplete anchor census. Propagate UNKNOWN rather than allowing
        // an omitted active region to masquerade as a complete readback.
        long expected=memory.regionRecords().values().stream()
            .filter(r->r.status()==RegionStatus.ACTIVE ||
                       r.status()==RegionStatus.DORMANT).count();
        if(enriched.context().regions().size()!=expected)
            return deferred(live,durableSequence);
        Map<AnchorId,AnchorRecord> bounded=new TreeMap<>();
        for(var region:enriched.context().regions().values())
            for(var anchorId:region.region().anchorIds()){
                var known=memory.anchors().get(anchorId);
                if(known!=null)bounded.put(anchorId,known);
                if(bounded.size()>MAX_REGION_ANCHORS)
                    return deferred(live,durableSequence);
            }
        // Native CAMP discovery records the real region link on AnchorRecord;
        // an authored RegionRecord need not list that later observed anchor.
        // Retain either durable membership direction without rewriting either.
        for(var known:memory.anchors().values()){
            if(known.regionIds().stream().noneMatch(enriched.context().regions()::containsKey))
                continue;
            bounded.put(known.anchorId(),known);
            if(bounded.size()>MAX_REGION_ANCHORS)
                return deferred(live,durableSequence);
        }
        // Do not dump unrelated candidate/pending anchors into every J03 audit.
        // Absent physical live measurements remain MISSING, not known-absent.
        Set<SensorKind> missing=new HashSet<>(enriched.missingData());
        missing.addAll(Set.of(SensorKind.RISK,SensorKind.TERRAIN,
                              SensorKind.OWNERSHIP,SensorKind.LOCATIONS));
        var c=enriched.context();
        // Retain genuinely recovered location rows. Their positive metadata
        // is useful to native preview; it does not invent a complete physical
        // terrain/risk/location survey or remove the MISSING sensors above.
        if(c.locations().size()>MAX_LOCATIONS)return deferred(live,durableSequence);
        var semanticRegions=AuthoredRegionTagProjection.merge(c.regions(),authoredRegionTags);
        var compact=new SnapshotBatch(enriched.requestId(),enriched.scopes(),
            enriched.capturedTick(),enriched.scopeRevisions(),
            new ObservationContext(c.scopes(),c.players(),c.groups(),
                semanticRegions,bounded,c.locations(),c.terrain(),c.capabilities()),
            missing,enriched.loadedChunks(),enriched.serverHealth());
        if(new ModelCodec<>(SnapshotBatch.class).encode(compact).length>
           MAX_SNAPSHOT_BYTES)
            return deferred(live,durableSequence);
        long camps=compact.context().regions().values().stream()
            .mapToLong(r->r.camps().size()).sum();
        return new Projection(compact,compact.context().regions().size(),
            camps,durableSequence,false);
    }
    private static Projection deferred(SnapshotBatch live,long sequence){
        var missing=new HashSet<>(live.missingData());
        missing.addAll(Set.of(SensorKind.REGION_TAGS,SensorKind.REGION_TRAITS,
            SensorKind.CAMP_ACTIVITY,SensorKind.RISK,SensorKind.TERRAIN,
            SensorKind.OWNERSHIP,SensorKind.ANCHORS,SensorKind.LOCATIONS));
        // No region context is promoted when it exceeds the bounded audit
        // payload; this is NOT evidence that a region is absent in the world.
        var c=live.context();
        var cleared=new ObservationContext(c.scopes(),c.players(),c.groups(),
            Map.of(),Map.of(),Map.of(),c.terrain(),c.capabilities());
        return new Projection(new SnapshotBatch(live.requestId(),live.scopes(),
            live.capturedTick(),live.scopeRevisions(),cleared,missing,
            live.loadedChunks(),live.serverHealth()),0,0,sequence,true);
    }
}
