package fr.proutlost.director.admin.server;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.MemoryPort;
import java.util.*;

/** Exact-scope reads through the frozen memory port; caller enforces authorization. */
public final class AdminMemoryQueries {
    private final MemoryPort memory;
    private final WorldId world;
    public AdminMemoryQueries(MemoryPort memory,WorldId world) {this.memory=Objects.requireNonNull(memory);this.world=Objects.requireNonNull(world);}
    public Optional<AdminRow> region(RegionId id) {
        var scope=new ScopeRef(ScopeKind.REGION,id.value());
        var view=memory.view(new ScopeSet(world,Set.of(scope)));
        if(!AdminMemoryCoverage.available(new RegionFilter(Optional.of(id),Set.of()),view))
            throw new IllegalStateException("memory scope not resident; hydrate exact region before querying");
        var record=view.regionRecords().get(id);
        var state=view.regions().stream().filter(r->r.regionId().equals(id)).findFirst();
        if(record==null||state.isEmpty()) return Optional.empty();
        var anchors=view.anchors().values().stream().filter(a->a.regionIds().contains(id)).sorted(Comparator.comparing(AnchorRecord::anchorId)).toList();
        var locations=view.locations().values().stream().filter(l->anchors.stream().anyMatch(a->a.locationId().filter(l.locationId()::equals).isPresent())).sorted(Comparator.comparing(LocationRecord::locationId)).toList();
        Set<TagId> tags=new HashSet<>();anchors.forEach(a->tags.addAll(a.tags()));locations.forEach(l->tags.addAll(l.tags()));
        return Optional.of(new AdminRow(scope,Objects.requireNonNull(view.revisions().get(scope),"Missing scope revision"),
                record.header().updatedTick(),new RegionAdminData(record,state.get(),tags,anchors,locations)));
    }
    public Optional<AdminRow> player(PlayerId id) {
        var scope=new ScopeRef(ScopeKind.PLAYER,id.value());var view=memory.view(new ScopeSet(world,Set.of(scope)));
        if(!AdminMemoryCoverage.available(new PlayerFilter(id),view))
            throw new IllegalStateException("memory scope not resident; hydrate exact player before querying");
        return view.operationalPlayers().stream().filter(p->p.playerId().equals(id)).findFirst().map(p->new AdminRow(scope,
                Objects.requireNonNull(view.revisions().get(scope),"Missing scope revision"),p.header().updatedTick(),
                new PlayerAdminData(p,view.significantPlayers().stream().filter(s->s.playerId().equals(id)).toList())));
    }
}
