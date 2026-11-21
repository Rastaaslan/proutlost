package fr.proutlost.director.api.model;

import java.util.*;

/** Closed-model WAL payload for a physically bounded owned-animal route.
 * The native binding validates geometry again after hydrating this record.
 * Never deserialize arbitrary native binding classes from durable input.
 */
public record FaunaNavigationRoute(EntityOwnershipRecord owner,
                                   List<BlockPosition> cells, double speed) {
    public FaunaNavigationRoute {
        Objects.requireNonNull(owner);
        cells=List.copyOf(cells);
        if(cells.size()<2 || cells.size()>4096 || !Double.isFinite(speed) ||
           speed<=0 || speed>1)
            throw new IllegalArgumentException("durable animal route bounds");
    }
}
