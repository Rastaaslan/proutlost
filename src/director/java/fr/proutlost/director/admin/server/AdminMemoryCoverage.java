package fr.proutlost.director.admin.server;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** Coverage, not absence: a scoped resident cache cannot answer a whole-world
 * listing or a cold exact scope as an AVAILABLE empty administrative result. */
public final class AdminMemoryCoverage {
    private AdminMemoryCoverage(){}
    public static boolean available(AdminFilter filter,MemoryView view) {
        Objects.requireNonNull(filter);Objects.requireNonNull(view);
        boolean whole=view.scopes().scopes().contains(new ScopeRef(ScopeKind.WORLD,view.scopes().worldId().value()));
        if(filter instanceof RegionFilter query) {
            if(query.regionId().isEmpty())return whole;
            var id=query.regionId().orElseThrow();var scope=new ScopeRef(ScopeKind.REGION,id.value());
            var region=view.regionRecords().get(id);
            var memory=view.regions().stream().filter(r->r.regionId().equals(id)).findFirst();
            if(region==null&&memory.isEmpty())return whole;
            if(region==null||memory.isEmpty()||!whole&&!view.scopes().scopes().contains(scope))return false;
            if(!view.anchors().keySet().containsAll(region.anchorIds()))return false;
            for(var anchor:view.anchors().values())if((region.anchorIds().contains(anchor.anchorId())||anchor.regionIds().contains(id))
                &&anchor.locationId().isPresent()&&!view.locations().containsKey(anchor.locationId().orElseThrow()))return false;
            return true;
        }
        if(filter instanceof PlayerFilter query) {
            var scope=new ScopeRef(ScopeKind.PLAYER,query.playerId().value());
            return whole||view.scopes().scopes().contains(scope)&&view.operationalPlayers().stream().anyMatch(p->p.playerId().equals(query.playerId()));
        }
        return false;
    }
}
