package fr.proutlost.director.bootstrap;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.*;
import fr.proutlost.director.memory.*;
import java.util.*;
import java.util.concurrent.CompletionStage;
/** Composition boundary. Admin consumes an API read service; the existing
 * memory implementation retains its bounded selection and validation policy. */
public final class DirectorMetadataMemoryReader implements MetadataMemoryReader {
    private final MetadataReadPort store;
    public DirectorMetadataMemoryReader(MetadataReadPortProvider provider){store=Objects.requireNonNull(provider).metadataReads();}
    private final class Selected implements Selection {
        private final DirectorMetadataMemoryReader owner=DirectorMetadataMemoryReader.this;
        private final BoundedMetadataProjection.View view;
        Selected(BoundedMetadataProjection.View view){this.view=view;}
        @Override public MetadataReadSnapshot snapshot(){return view.snapshot();}
        @Override public Map<AggregateKey,AggregateState> states(){return view.states();}
        @Override public ScopeSet scopes(){return view.scopes();}
        @Override public MemoryView memory(){return view.memory();}
        @Override public Set<AnchorId> requestedAnchors(){return view.demand().anchors();}
        @Override public long examinedRows(){return view.examinedRows();}
        @Override public int peakPageRows(){return view.peakPageRows();}
        @Override public boolean currentHeads(MetadataReadSnapshot current){return view.currentHeads(store,current);}
    }
    private final class Captured implements Capture {
        private final DirectorMetadataMemoryReader owner=DirectorMetadataMemoryReader.this;
        private final ScopedAggregateCapture.View view;
        Captured(ScopedAggregateCapture.View view){this.view=view;}
        @Override public MetadataReadSnapshot snapshot(){return view.snapshot();}
        @Override public Map<AggregateKey,AggregateState> states(){return view.states();}
        @Override public boolean sameHeads(MetadataReadSnapshot current){return view.sameHeads(store,current);}
        @Override public boolean sameMemoryHeads(MetadataReadSnapshot current,ScopeSet scopes,Set<AnchorId> anchors){return view.sameMemoryHeads(store,current,scopes,anchors);}
    }
    private void snapshot(MetadataReadSnapshot snapshot,ScopeSet scopes){
        if(!store.owns(snapshot)||!snapshot.session().worldId().equals(scopes.worldId()))throw new IllegalStateException("foreign or revoked memory snapshot");
        if(scopes.scopes().stream().anyMatch(scope->scope.kind()!=ScopeKind.REGION&&scope.kind()!=ScopeKind.PLAYER&&scope.kind()!=ScopeKind.LOCATION))
            throw new IllegalArgumentException("exact memory scope requires region/player/location identities");
    }
    @Override public Selection empty(MetadataReadSnapshot snapshot,ScopeSet scopes){
        snapshot(snapshot,scopes);
        for(var scope:scopes.scopes()){
            var kind=switch(scope.kind()){case REGION->AggregateKind.REGION;case PLAYER->AggregateKind.PLAYER_OPERATIONAL;case LOCATION->AggregateKind.LOCATION;default->throw new IllegalArgumentException("exact scope");};
            if(store.metadataRevision(snapshot,new AggregateKey(kind,scope.id())).value()!=0)throw new IllegalStateException("empty memory scope is not proven absent");
        }
        var demand=new BoundedMetadataProjection.Demand(Set.of(),Set.of(),16,scopes.scopes());
        return new Selected(new BoundedMetadataProjection.View(snapshot,demand,Map.of(),scopes,0,0));
    }
    @Override public CompletionStage<Selection> exact(MetadataReadSnapshot snapshot,ScopeSet scopes,Set<AnchorId> anchors,Configuration configuration){
        snapshot(snapshot,scopes);Objects.requireNonNull(configuration);
        var players=new HashSet<PlayerId>();for(var scope:scopes.scopes())if(scope.kind()==ScopeKind.PLAYER)players.add(new PlayerId(scope.id()));
        var demand=new BoundedMetadataProjection.Demand(players,Set.of(),16,scopes.scopes(),anchors);
        var settings=new MemorySettings(configuration.configuration(),configuration.mode()==ProviderMode.PRODUCTION);
        return BoundedMetadataProjection.loadExact(store,snapshot,demand,settings).thenApply(Selected::new);
    }
    @Override public CompletionStage<Capture> capture(MetadataReadSnapshot snapshot,Set<AggregateKey> keys,int maximumStates){
        return ScopedAggregateCapture.load(store,snapshot,keys,maximumStates).thenApply(Captured::new);
    }
    @Override public Selection expand(Selection base,Capture additional,ScopeSet scopes){
        if(!(base instanceof Selected selected)||selected.owner!=this||!(additional instanceof Captured captured)||captured.owner!=this
            ||selected.snapshot()!=captured.snapshot())throw new IllegalArgumentException("memory expansion requires same service and identical snapshot");
        snapshot(selected.snapshot(),scopes);var states=new HashMap<>(selected.states());
        captured.states().forEach((key,state)->{var old=states.putIfAbsent(key,state);if(old!=null&&!old.equals(state))throw new IllegalArgumentException("conflicting memory expansion state");});
        var prior=selected.view;
        return new Selected(new BoundedMetadataProjection.View(prior.snapshot(),prior.demand(),states,scopes,
            Math.addExact(prior.examinedRows(),captured.states().size()),Math.max(prior.peakPageRows(),Math.min(96,captured.states().size())),prior.anchorCoverage()));
    }
}
