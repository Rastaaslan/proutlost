package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.adapter.neoforge.server.observation.CampAnchorLookupSaturatedException;
import java.util.*;

/**
 * Immutable J01 TEST_ONLY spatial read index of J04-published camp anchors.
 *
 * Each projection is rebuilt atomically on the Minecraft actor. Player
 * censuses never sweep every persisted anchor, load chunks or infer missing
 * camps. Candidate overflow is an error, not a false "complete" census.
 */
public final class DirectorLabCampAnchorIndex {
    private record Cell(DimensionId dimension,long x,long z) {}
    private final Map<Cell,List<AnchorRecord>> cells;
    private final double radiusSquared;
    private final int cellSize,maxNearby,recordCount,activeCount;

    public DirectorLabCampAnchorIndex(Map<AnchorId,AnchorRecord> durable,
            double radius,int cellSize,int maxEntries,int maxNearby) {
        Objects.requireNonNull(durable);
        if(!Double.isFinite(radius)||radius<=0||radius>256
                ||cellSize<1||cellSize>1024||maxEntries<1
                ||maxNearby<1||maxNearby>maxEntries)
            throw new IllegalArgumentException("camp spatial index bounds");
        if(durable.size()>maxEntries)
            throw new IllegalStateException("J04 camp index record limit");
        this.radiusSquared=radius*radius;
        this.cellSize=cellSize;
        this.maxNearby=maxNearby;
        this.recordCount=durable.size();
        int count=0;
        Map<Cell,List<AnchorRecord>> building=new HashMap<>();
        for(var entry:durable.entrySet()) {
            var anchor=Objects.requireNonNull(entry.getValue());
            if(!entry.getKey().equals(anchor.anchorId()) ||
                    !anchor.dimension().equals(anchor.position().dimension()))
                throw new IllegalArgumentException("unverified J04 anchor geometry");
            if(!anchor.active())continue;
            if(anchor.origin()!=AnchorOrigin.CAMP &&
                    !anchor.tags().contains(new TagId("proutlost:camp")))
                continue;
            var position=anchor.position();
            Cell key=new Cell(position.dimension(),
                Math.floorDiv(position.x(),cellSize),
                Math.floorDiv(position.z(),cellSize));
            building.computeIfAbsent(key,unused->new ArrayList<>()).add(anchor);
            count++;
        }
        var published=new HashMap<Cell,List<AnchorRecord>>();
        building.forEach((key,value)->{
            value.sort(Comparator.comparing(AnchorRecord::anchorId));
            published.put(key,List.copyOf(value));
        });
        cells=Map.copyOf(published);
        activeCount=count;
    }
    /**
     * Exact horizontal radius; vertical direction remains intentionally
     * unfiltered, matching existing NeoForgeObservation camp geometry.
     * An overcrowded search fails closed rather than silently taking 64.
     */
    public List<AnchorRecord> nearby(BlockPosition point) {
        Objects.requireNonNull(point);
        double radius=Math.sqrt(radiusSquared);
        long minX=Math.floorDiv((long)Math.floor(point.x()-radius),cellSize);
        long maxX=Math.floorDiv((long)Math.floor(point.x()+radius),cellSize);
        long minZ=Math.floorDiv((long)Math.floor(point.z()-radius),cellSize);
        long maxZ=Math.floorDiv((long)Math.floor(point.z()+radius),cellSize);
        if((maxX-minX+1)*(maxZ-minZ+1)>4096)
            throw new IllegalStateException("camp search cell bound");
        List<AnchorRecord> results=new ArrayList<>();
        for(long x=minX;x<=maxX;x++)for(long z=minZ;z<=maxZ;z++){
            for(var anchor:cells.getOrDefault(
                    new Cell(point.dimension(),x,z),List.of())){
                double dx=(double)anchor.position().x()-point.x();
                double dz=(double)anchor.position().z()-point.z();
                if(dx*dx+dz*dz<=radiusSquared) {
                    if(results.size()>=maxNearby)
                        throw new CampAnchorLookupSaturatedException();
                    results.add(anchor);
                }
            }
        }
        results.sort(Comparator.comparing(AnchorRecord::anchorId));
        return List.copyOf(results);
    }
    public int durableRecords(){return recordCount;}
    public int activeCampAnchors(){return activeCount;}
    public int occupiedCells(){return cells.size();}
}
