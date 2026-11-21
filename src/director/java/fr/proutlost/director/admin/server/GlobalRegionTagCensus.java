package fr.proutlost.director.admin.server;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Complete current regional tag index over one pinned J04 cut. Metadata
 * bodies are consumed in pages96 and discarded; only identity, provenance and
 * tag memberships are retained. Original G0 sources are read from J04 itself.
 * The caller must retain/revalidate that cut's REGION/LOCATION/ANCHOR tokens. */
public final class GlobalRegionTagCensus {
    private static final int PAGE=96;
    private record Region(DimensionId dimension,boolean active){}
    private record AuthoredLocation(ResourceId source,Revision revision,ImportedLocation value){}
    private GlobalRegionTagCensus(){}
    public static CompletionStage<Map<RegionId,Set<TagId>>> load(MetadataReadPortProvider source,MetadataReadSnapshot snapshot){
        Objects.requireNonNull(source);Objects.requireNonNull(snapshot);
        return new Builder(source.metadataReads(),snapshot).load();
    }
    private static final class Builder {
        final MetadataReadPort store;final MetadataReadSnapshot snapshot;
        final Map<RegionId,Region> regions=new HashMap<>();
        final Map<RegionId,Set<TagId>> tags=new HashMap<>();
        final Map<AnchorId,Set<RegionId>> declared=new HashMap<>();
        final Set<AnchorId> unseen=new HashSet<>();
        final Map<LocationId,AuthoredLocation> authoredLocations=new HashMap<>();
        final Set<LocationId> unseenLocations=new HashSet<>();
        Builder(MetadataReadPort store,MetadataReadSnapshot snapshot){
            this.store=store;this.snapshot=snapshot;
            if(snapshot.sources().size()>32||snapshot.sources().values().stream().mapToLong(LocationTransferManifest::sourceBytes).sum()>32L*1_048_576)
                throw new IllegalStateException("J04 source capacity inconsistent");
        }
        CompletionStage<Map<RegionId,Set<TagId>>> load(){
            return pass(AggregateKind.REGION,rows->{for(var state:rows){
                var value=((RegionAggregate)state).value();boolean active=value.status()==RegionStatus.ACTIVE||value.status()==RegionStatus.DORMANT;
                regions.put(value.regionId(),new Region(value.dimension(),active));
                if(!active)continue;tags.put(value.regionId(),new HashSet<>());
                for(var id:value.anchorIds())declared.computeIfAbsent(id,unused->new HashSet<>()).add(value.regionId());
            }}).thenCompose(v->{
                unseen.addAll(declared.keySet());
                CompletionStage<Void> sources=CompletableFuture.completedFuture(null);
                for(var source:snapshot.sources().keySet().stream().sorted(Comparator.comparing(ResourceId::value)).toList())
                    sources=sources.thenCompose(ignored->source(source));
                return sources;
            }).thenCompose(v->pass(AggregateKind.LOCATION,rows->{for(var state:rows){
                var value=((LocationAggregate)state).value();var authored=authoredLocations.get(value.locationId());if(authored==null)continue;
                unseenLocations.remove(value.locationId());var expected=authored.value();var b=expected.bounds();
                if(!value.sourceNamespace().equals(authored.source())||!value.importRevision().equals(authored.revision())
                    ||!value.externalId().equals(expected.externalId())||!value.dimension().equals(expected.dimension())
                    ||!value.bounds().equals(new Aabb(expected.dimension(),b.minX(),b.minY(),b.minZ(),b.maxXExclusive(),b.maxYExclusive(),b.maxZExclusive()))
                    ||!value.tags().equals(expected.tags()))throw new IllegalStateException("authored location head mismatch");
                if(value.active())for(var id:expected.regionIds()){var target=tags.get(id);if(target!=null)target.addAll(value.tags());}
            }})).thenCompose(v->{
                if(!unseenLocations.isEmpty())throw new IllegalStateException("authored location head missing");
                authoredLocations.clear();
                return pass(AggregateKind.ANCHOR,rows->{for(var state:rows){
                    var value=((AnchorAggregate)state).value();unseen.remove(value.anchorId());if(!value.active())continue;
                    var owners=new HashSet<>(declared.getOrDefault(value.anchorId(),Set.of()));owners.addAll(value.regionIds());
                    for(var id:owners){var target=tags.get(id);if(target==null)continue;
                        if(!regions.get(id).dimension().equals(value.dimension()))throw new IllegalStateException("regional anchor dimension mismatch");target.addAll(value.tags());}
                }});
            }).thenApply(v->{
                if(!unseen.isEmpty())throw new IllegalStateException("declared regional anchor head missing");
                var result=new HashMap<RegionId,Set<TagId>>();tags.forEach((id,value)->result.put(id,Set.copyOf(value)));return Map.copyOf(result);
            });
        }
        CompletionStage<Void> source(ResourceId namespace){
            return store.readMetadataSource(snapshot,namespace).thenAccept(found->{
                var input=found.orElseThrow(()->new IllegalStateException("authored source unavailable"));
                var descriptor=snapshot.sources().get(namespace);
                if(!input.worldId().equals(snapshot.session().worldId())||!input.sourceNamespace().equals(namespace)
                    ||!input.exportRevision().equals(descriptor.exportRevision())||!input.exportDigest().equals(descriptor.exportDigest()))
                    throw new IllegalStateException("authored source identity mismatch");
                for(var value:input.regions()){
                    var actual=regions.get(value.regionId());if(actual==null)throw new IllegalStateException("authored region head missing");
                    if(!actual.dimension().equals(value.dimension())||!store.metadataOwner(snapshot,new AggregateKey(AggregateKind.REGION,value.regionId().value())).equals(Optional.of(namespace)))
                        throw new IllegalStateException("authored region head identity mismatch");
                    var target=tags.get(value.regionId());if(target!=null)target.addAll(value.tags());
                }
                for(var value:input.locations()){
                    if(value.regionIds().stream().noneMatch(tags::containsKey))continue;
                    if(!store.metadataOwner(snapshot,new AggregateKey(AggregateKind.LOCATION,value.locationId().value())).equals(Optional.of(namespace))
                        ||authoredLocations.putIfAbsent(value.locationId(),new AuthoredLocation(namespace,input.exportRevision(),value))!=null)
                        throw new IllegalStateException("authored location ownership collision");
                    unseenLocations.add(value.locationId());
                }
            });
        }
        CompletionStage<Void> pass(AggregateKind kind,Consumer<List<AggregateState>> consume){return page(kind,Optional.empty(),consume);}
        CompletionStage<Void> page(AggregateKind kind,Optional<AggregateKey> after,Consumer<List<AggregateState>> consume){
            var keys=store.metadataKeysPage(snapshot,Set.of(kind),after,PAGE).orElseThrow(()->new IllegalStateException("tag census key index unavailable"));
            if(keys.keys().isEmpty()){if(keys.next().isPresent())throw new IllegalStateException("nonadvancing tag census cursor");return CompletableFuture.completedFuture(null);}
            var query=new StoreQuery(snapshot.session().worldId(),Set.copyOf(keys.keys()),new Sequence(0),Optional.empty(),PAGE);
            return store.readMetadata(snapshot,query).thenCompose(read->{
                ReadContracts.validate(read,query,snapshot.session());
                if(read.status()!=ReadStatus.AVAILABLE)throw new IllegalStateException("tag census metadata unavailable");
                var data=read.page().orElseThrow();if(data.hasMore()||!data.lastDurableSequence().equals(snapshot.cut())||!data.records().isEmpty()
                    ||data.aggregates().size()!=keys.keys().size())throw new IllegalStateException("tag census page cut/completeness");
                var expected=new HashSet<>(keys.keys());var states=new ArrayList<AggregateState>();
                for(var row:data.aggregates()){if(!expected.remove(row.state().key()))throw new IllegalStateException("tag census foreign/duplicate key");states.add(row.state());}
                if(!expected.isEmpty())throw new IllegalStateException("tag census omitted key");consume.accept(List.copyOf(states));
                if(keys.next().isEmpty())return CompletableFuture.completedFuture(null);
                if(keys.next().equals(after))throw new IllegalStateException("nonadvancing tag census cursor");return page(kind,keys.next(),consume);
            });
        }
    }
}
