package fr.proutlost.director.memory;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** Immutable sparse broad phase, built on a worker from a bounded resident snapshot. */
public final class SparseRegionIndex {
    private record Cell(DimensionId dimension, long x, long z) {}
    private final int cellBlocks;
    private final Map<Cell, Set<RegionId>> cells;
    private final Map<RegionId, RegionRecord> regions;

    public SparseRegionIndex(Collection<RegionRecord> input, int cellBlocks, int maxEntries) {
        if (cellBlocks <= 0 || maxEntries <= 0) throw new IllegalArgumentException("index budget");
        this.cellBlocks = cellBlocks;
        Map<RegionId, RegionRecord> records = new HashMap<>();
        Map<Cell, Set<RegionId>> builder = new HashMap<>();
        int entries = 0;
        for (RegionRecord r : input) {
            if (records.putIfAbsent(r.regionId(), r) != null) throw new IllegalArgumentException("duplicate region");
            if (r.status() != RegionStatus.ACTIVE) continue;
            for (Aabb b : r.supportShape().boxes()) {
                dimension(r, b.dimension());
                entries = add(builder, r.regionId(), b.dimension(), b.minX(), b.minZ(),
                        (double) b.maxXExclusive() - 1, (double) b.maxZExclusive() - 1, entries, maxEntries);
            }
            for (SupportDisc d : r.supportShape().discs()) {
                dimension(r, d.center().dimension());
                entries = add(builder, r.regionId(), d.center().dimension(),
                        d.center().x() - d.radius(), d.center().z() - d.radius(),
                        d.center().x() + d.radius(), d.center().z() + d.radius(), entries, maxEntries);
            }
            for (RouteSegment s : r.supportShape().routes()) {
                dimension(r, s.from().dimension());
                entries = add(builder, r.regionId(), s.from().dimension(),
                        Math.min(s.from().x(), s.to().x()) - s.radius(), Math.min(s.from().z(), s.to().z()) - s.radius(),
                        Math.max(s.from().x(), s.to().x()) + s.radius(), Math.max(s.from().z(), s.to().z()) + s.radius(), entries, maxEntries);
            }
        }
        Map<Cell, Set<RegionId>> frozen = new HashMap<>();
        builder.forEach((key, value) -> frozen.put(key, Set.copyOf(value)));
        cells = Map.copyOf(frozen);
        regions = Map.copyOf(records);
    }

    private static void dimension(RegionRecord region, DimensionId dimension) {
        if (!region.dimension().equals(dimension)) throw new IllegalArgumentException("cross dimension support");
    }

    private int add(Map<Cell, Set<RegionId>> builder, RegionId id, DimensionId dimension,
                    double minX, double minZ, double maxX, double maxZ, int entries, int limit) {
        long x0 = coordinate(minX), z0 = coordinate(minZ), x1 = coordinate(maxX), z1 = coordinate(maxZ);
        // Bound iteration before allocating. A larger support requires explicit repartitioning.
        long width = Math.addExact(Math.subtractExact(x1, x0), 1);
        long depth = Math.addExact(Math.subtractExact(z1, z0), 1);
        if (width > limit || depth > limit || width * depth > limit)
            throw new IllegalArgumentException("support exceeds index budget");
        for (long x = x0; x <= x1; x++) for (long z = z0; z <= z1; z++) {
            if (builder.computeIfAbsent(new Cell(dimension, x, z), k -> new HashSet<>()).add(id)) {
                if (++entries > limit) throw new IllegalArgumentException("index entry budget exhausted");
            }
        }
        return entries;
    }

    private long coordinate(double value) {
        if (!Double.isFinite(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)
            throw new IllegalArgumentException("support outside integer coordinate domain");
        return (long) StrictMath.floor(value / cellBlocks);
    }

    public List<RegionRecord> containing(BlockPosition point) {
        Cell cell = new Cell(point.dimension(), Math.floorDiv(point.x(), cellBlocks), Math.floorDiv(point.z(), cellBlocks));
        return cells.getOrDefault(cell, Set.of()).stream().sorted().map(regions::get)
                .filter(r -> SupportGeometry.contains(r.supportShape(), point)).toList();
    }

    public Optional<RegionId> primary(BlockPosition point, Map<RegionId, UnitValue> relevance) {
        return SupportGeometry.primary(containing(point), point, relevance);
    }

    public int occupiedCells() { return cells.size(); }
}
