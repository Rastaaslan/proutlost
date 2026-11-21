package fr.proutlost.director.memory;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** Narrow phase over immutable support geometry; chunk membership is never region membership. */
public final class SupportGeometry {
    private SupportGeometry() {}

    public static boolean contains(SupportShape shape, BlockPosition p) {
        for (Aabb b : shape.boxes()) {
            if (b.dimension().equals(p.dimension()) && p.x() >= b.minX() && p.x() < b.maxXExclusive()
                    && p.y() >= b.minY() && p.y() < b.maxYExclusive()
                    && p.z() >= b.minZ() && p.z() < b.maxZExclusive()) return true;
        }
        // Discs describe horizontal supports; vertical bounds, when required, use boxes.
        for (SupportDisc d : shape.discs()) {
            if (!d.center().dimension().equals(p.dimension())) continue;
            double x = (double) p.x() - d.center().x(), z = (double) p.z() - d.center().z();
            if (StrictMath.hypot(x, z) <= d.radius()) return true;
        }
        for (RouteSegment r : shape.routes()) {
            if (!r.from().dimension().equals(p.dimension())) continue;
            double dx = (double) r.to().x() - r.from().x();
            double dy = (double) r.to().y() - r.from().y();
            double dz = (double) r.to().z() - r.from().z();
            double px = (double) p.x() - r.from().x();
            double py = (double) p.y() - r.from().y();
            double pz = (double) p.z() - r.from().z();
            double lengthSquared = dx * dx + dy * dy + dz * dz;
            double t = lengthSquared == 0 ? 0 : Math.clamp((px * dx + py * dy + pz * dz) / lengthSquared, 0, 1);
            if (StrictMath.hypot(StrictMath.hypot(px - t * dx, py - t * dy), pz - t * dz) <= r.radius()) return true;
        }
        return false;
    }

    /** Exactly one primary, selected by activity relevance, importance, then canonical ID. */
    public static Optional<RegionId> primary(Collection<RegionRecord> candidates, BlockPosition p,
                                             Map<RegionId, UnitValue> activityRelevance) {
        return candidates.stream().filter(r -> r.status() == RegionStatus.ACTIVE)
                .filter(r -> r.dimension().equals(p.dimension()) && contains(r.supportShape(), p))
                .sorted(Comparator.<RegionRecord>comparingDouble(r ->
                        activityRelevance.getOrDefault(r.regionId(), new UnitValue(0)).value()).reversed()
                        .thenComparing(Comparator.comparingDouble((RegionRecord r) -> r.importance().value()).reversed())
                        .thenComparing(RegionRecord::regionId))
                .map(RegionRecord::regionId).findFirst();
    }
}
