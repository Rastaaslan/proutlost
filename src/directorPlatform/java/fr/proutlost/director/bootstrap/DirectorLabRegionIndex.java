package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.memory.SparseRegionIndex;
import java.util.*;
import net.minecraft.server.MinecraftServer;

/**
 * Actor-owned read-only index for ACTUAL J04 RegionAggregate projections.
 * Missing/ambiguous geometry never authorizes a synthetic region.
 * Uses existing bounded SpatialRegionIndex/SupportGeometry; no chunk loading.
 *
 * Mode is explicit for production; the legacy constructor is TEST_ONLY.
 * An index supplies membership data, never gameplay admission authority.
 */
public final class DirectorLabRegionIndex {
    public record Status(int records,int occupiedCells,long durableSequence,
                         boolean testOnly,boolean permitsGameplay) {
        public Status {
            if(records<0 || occupiedCells<0 || durableSequence<0 ||
               permitsGameplay)
                throw new IllegalArgumentException("unqualified region index");
        }
    }

    private final MinecraftServer server;
    private final WorldId world;
    private final int cellBlocks;
    private final int maximumEntries;
    private final ProviderMode mode;
    private SparseRegionIndex index;
    private long lastSequence;
    private int regionCount;
    private Map<RegionId,RegionRecord> publishedRegions=Map.of();

    public DirectorLabRegionIndex(MinecraftServer server,WorldId world,
            ConfigView testConfig,MemoryView recovered,long durableSequence) {
        this(server,world,testConfig,recovered,durableSequence,ProviderMode.TEST_ONLY);
    }
    public DirectorLabRegionIndex(MinecraftServer server,WorldId world,
            ConfigView configuration,MemoryView recovered,long durableSequence,
            ProviderMode mode) {
        this.server=Objects.requireNonNull(server);
        this.world=Objects.requireNonNull(world);
        Objects.requireNonNull(configuration);
        this.mode=Objects.requireNonNull(mode);
        actor();
        if(configuration.calibration()!=(mode==ProviderMode.PRODUCTION
                ?Calibration.APPROVED:Calibration.DEFAULT_FOR_TESTING))
            throw new IllegalArgumentException("J01 region calibration/provider mode mismatch");
        cellBlocks=configuration.values().get("spatial.region_cell_blocks")
            .value().intValueExact();
        maximumEntries=8192;
        if(cellBlocks<=0)throw new IllegalArgumentException("region cell size");
        refresh(recovered,durableSequence);
    }
    private void actor() {
        if(!server.isSameThread())
            throw new IllegalStateException("region index Minecraft actor");
    }
    public void refresh(MemoryView view,long sequence) {
        actor();
        Objects.requireNonNull(view);
        if(!view.scopes().worldId().equals(world) ||
           sequence<lastSequence)
            throw new IllegalArgumentException("foreign/stale J04 region projection");
        if(sequence==lastSequence && index!=null&&publishedRegions.equals(view.regionRecords()))return;
        // Use only complete, immutable recovered J04 records. Unknown, cold
        // or absent regions are not guessed from world chunks or campfires.
        Collection<RegionRecord> data=view.regionRecords().values();
        var candidate=new SparseRegionIndex(data,cellBlocks,maximumEntries);
        index=candidate;publishedRegions=Map.copyOf(view.regionRecords());
        regionCount=data.size();
        lastSequence=sequence;
    }
    public Optional<RegionId> primary(BlockPosition point) {
        actor();
        return index==null?Optional.empty():index.primary(point,Map.of());
    }
    public Status status() {
        actor();
        return new Status(regionCount,index==null?0:index.occupiedCells(),
            lastSequence,mode==ProviderMode.TEST_ONLY,false);
    }
}
