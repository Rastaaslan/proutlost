package fr.proutlost.integration.contra;

import fr.proutlost.contract.director.*;
import fr.proutlost.director.api.model.PhysicalCatalogFacts;
import fr.proutlost.director.api.model.StoreSession;
import fr.proutlost.director.api.model.ResourceId;
import fr.proutlost.director.api.model.PhysicalCatalogModels.*;
import fr.proutlost.director.bootstrap.PhysicalCatalogJournalAdmission;
import fr.proutlost.director.bootstrap.PhysicalCatalogJournalAdmission.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import static fr.proutlost.contract.director.Vocabulary.*;

/**
 * Full D0 projection derived ONLY from opaque J04 publications. It owns no files,
 * store or actor. It preserves complete records; query returns current pins,
 * historical lookup never makes an old pin eligible for a new selection.
 */
public final class DurablePhysicalCatalogView implements LocationCatalogView,PhysicalCatalogJournalAdmission.Compiler {
    /** Actual installed semantic adapters must validate each complete entry.
     * Unknown capability versions, policies, channels/actions MUST throw here.
     * This does not replace the per-effect current-observation/permission checks.
     */
    @FunctionalInterface public interface InstalledSemantics { void requireSupported(CatalogSnapshot source); }
    private record Index(CatalogSnapshot snapshot,NavigableMap<Id,StaticLocationCatalogEntry> current,
            Map<LocationRef,StaticLocationCatalogEntry> history,Map<Revision,Digest> revisions,
            Map<Id,Set<Id>> capabilities,Map<SemanticRegionId,Set<Id>> regions,Publication publication,Map<Key,Publication> publications) {}
    private final Id world,build;private final Supplier<StoreSession> currentSession;
    private final Map<Id,Bounds> dimensions;private final InstalledSemantics semantics;
    private final AtomicReference<Index> index=new AtomicReference<>();
    public DurablePhysicalCatalogView(Id world,Id build,Supplier<StoreSession> currentSession,
            Map<Id,Bounds> actualDimensionBounds,InstalledSemantics semantics){
        this.world=Objects.requireNonNull(world);this.build=Objects.requireNonNull(build);
        this.currentSession=Objects.requireNonNull(currentSession);this.dimensions=Map.copyOf(actualDimensionBounds);
        this.semantics=Objects.requireNonNull(semantics);
    }
    private static void require(boolean value,String why){if(!value)throw new IllegalArgumentException(why);}
    private static String utf8(byte[] bytes){
        try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException invalid){throw new IllegalArgumentException("physical catalogue must be exact UTF-8",invalid);}
    }
    @Override public Prepared compile(Document document){
        byte[] bytes=document.completeSource().bytes();String json=utf8(bytes);
        var snapshot=StrictCodec.decode(json,CatalogSnapshot.class);CatalogValidation.requireSealed(snapshot);
        var key=document.key();var binding=document.binding();
        require(snapshot.state()==CatalogState.READY&&snapshot.world_id().equals(world)&&snapshot.build_id().equals(build),"physical READY world/build");
        require(snapshot.world_id().value().equals(key.sourceWorld().value())&&snapshot.build_id().value().equals(key.sourceBuild().value())
            &&snapshot.catalog_revision().value().equals(key.sourceRevision())&&snapshot.digest().value().equals(key.sourceContentDigest().value())
            &&StrictCodec.encode(snapshot).equals(json),"physical exact canonical source key");
        require(PhysicalCatalogFacts.bindingDigest(binding).equals(binding.bindingSha()),"physical explicit binding digest");
        require(binding.regions().keySet().equals(snapshot.regions().stream().map(r->new ResourceId(r.region_id().value().value())).collect(java.util.stream.Collectors.toSet())),"physical region binding coverage");
        require(binding.locations().keySet().equals(snapshot.entries().stream().map(e->new ResourceId(e.location_id().value())).collect(java.util.stream.Collectors.toSet())),"physical location binding coverage");
        Set<List<ResourceId>> expectedAnchors=new HashSet<>();
        for(var entry:snapshot.entries())for(var anchor:entry.anchors())expectedAnchors.add(List.of(new ResourceId(entry.location_id().value()),new ResourceId(anchor.anchor_id().value())));
        var actualAnchors=binding.anchors().stream().map(a->List.of(a.sourceLocation(),a.sourceAnchor())).collect(java.util.stream.Collectors.toSet());
        require(expectedAnchors.equals(actualAnchors)&&actualAnchors.size()==binding.anchors().size(),"physical local anchor binding coverage");
        for(var region:snapshot.regions()){
            require(!region.bounds().isEmpty(),"empty physical semantic region");region.bounds().forEach(this::bounds);
        }
        for(var entry:snapshot.entries()){
            entry.geometry().forEach(this::bounds);entry.protection().forEach(p->bounds(p.bounds()));
            require(!entry.provenance().evidence().isEmpty(),"physical provenance evidence required");
        }
        semantics.requireSupported(snapshot);
        Index previous=index.get();var history=new HashMap<LocationRef,StaticLocationCatalogEntry>();
        var revisions=new HashMap<Revision,Digest>();if(previous!=null){history.putAll(previous.history);revisions.putAll(previous.revisions);}
        var priorRevision=revisions.putIfAbsent(snapshot.catalog_revision(),snapshot.digest());
        require(priorRevision==null||priorRevision.equals(snapshot.digest()),"physical catalogue revision changed");
        var current=new TreeMap<Id,StaticLocationCatalogEntry>();var capabilities=new HashMap<Id,Set<Id>>();
        var regions=new HashMap<SemanticRegionId,Set<Id>>();snapshot.regions().forEach(r->regions.put(r.region_id(),new HashSet<>()));
        for(var entry:snapshot.entries()){
            var old=history.putIfAbsent(ref(entry),entry);require(old==null||old.equals(entry),"physical location revision changed");
            current.put(entry.location_id(),entry);entry.semantic_region_ids().forEach(r->regions.get(r).add(entry.location_id()));
            entry.capabilities().forEach(c->capabilities.computeIfAbsent(c.capability_id(),ignored->new HashSet<>()).add(entry.location_id()));
        }
        require(history.size()<=Limits.MAX_ITEMS&&revisions.size()<=Limits.MAX_ITEMS,"physical projection history capacity");
        for(var entry:snapshot.entries()){
            Set<LocationRef> visited=new HashSet<>();var cursor=entry;
            while(cursor!=null){require(visited.add(ref(cursor)),"physical predecessor cycle");var predecessor=cursor.provenance().predecessor();
                if(predecessor==null)break;require(predecessor.store()==LocationStore.CATALOG&&history.containsKey(predecessor),"physical predecessor absent");cursor=history.get(predecessor);}
        }
        if(previous!=null)for(var removed:previous.current.values())require(current.containsKey(removed.location_id())
            ||snapshot.entries().stream().anyMatch(e->Objects.equals(e.provenance().predecessor(),ref(removed))),"physical removal requires provenance migration");
        var frozenCurrent=Collections.unmodifiableNavigableMap(current);var frozenHistory=Map.copyOf(history);var frozenRevisions=Map.copyOf(revisions);
        var frozenCapabilities=freeze(capabilities);var frozenRegions=freeze(regions);
        var bindingBlob=fr.proutlost.director.api.model.BlobContent.capture(PhysicalCatalogFacts.BINDING_CODEC,1,
            new fr.proutlost.director.api.codec.ModelCodec<>(Binding.class).encode(binding));
        var digest=PhysicalCatalogFacts.projectionDigest(key,document.completeSource().reference(),bindingBlob.reference());
        return new Prepared(digest,publication->{
            require(publication.readback().key().equals(key)&&publication.readback().projectionDigest().equals(digest)
                &&publication.binding().equals(binding)&&Arrays.equals(publication.source(),bytes),"actual J04 publication differs from compiled D0");
            require(publication.readback().session().equals(currentSession.get()),"physical publication session no longer current");
            var publications=new HashMap<Key,Publication>();if(previous!=null)publications.putAll(previous.publications);
            publications.put(key,publication);require(publications.size()<=Limits.MAX_ITEMS,"physical publication history capacity");
            var next=new Index(snapshot,frozenCurrent,frozenHistory,frozenRevisions,frozenCapabilities,frozenRegions,publication,Map.copyOf(publications));
            require(index.compareAndSet(previous,next),"physical projection changed while compilation pending");
        });
    }
    private void bounds(Bounds value){
        var allowed=dimensions.get(value.dimension_id());require(allowed!=null,"physical dimension not loaded");
        // D0 bounds are inclusive; the owner supplies inclusive real world/build bounds.
        require(value.min().x()>=allowed.min().x()&&value.min().y()>=allowed.min().y()&&value.min().z()>=allowed.min().z()
            &&value.max().x()<=allowed.max().x()&&value.max().y()<=allowed.max().y()&&value.max().z()<=allowed.max().z(),"physical geometry exceeds actual world/border/build bounds");
    }
    private Index live(){var value=index.get();return value!=null&&value.publication.readback().session().equals(currentSession.get())?value:null;}
    @Override public CatalogSnapshot snapshot(){var value=live();if(value==null)throw new IllegalStateException("physical catalogue not durably published in current session");return value.snapshot;}
    @Override public Optional<StaticLocationCatalogEntry> lookup(LocationRef ref){var value=live();return value==null?Optional.empty():Optional.ofNullable(value.history.get(ref));}
    @Override public List<LocationRef> query(CatalogQuery query){
        Objects.requireNonNull(query);var value=live();if(value==null)return List.of();var ids=new TreeSet<>(value.current.keySet());
        for(var capability:query.capabilities())ids.retainAll(value.capabilities.getOrDefault(CapabilityTypes.canonical(capability),Set.of()));
        if(!query.regions().isEmpty()){Set<Id> regionIds=new HashSet<>();for(var region:query.regions())regionIds.addAll(value.regions.getOrDefault(region,Set.of()));ids.retainAll(regionIds);}
        return (query.after_id()==null?ids:ids.tailSet(query.after_id(),false)).stream().limit(query.limit()).map(id->ref(value.current.get(id))).toList();
    }
    public boolean current(LocationRef ref){var value=live();return value!=null&&ref.store()==LocationStore.CATALOG
        &&Optional.ofNullable(value.current.get(ref.location_id())).map(DurablePhysicalCatalogView::ref).filter(ref::equals).isPresent();}
    public Optional<Readback> readback(){var value=live();return value==null?Optional.empty():Optional.of(value.publication.readback());}
    public Optional<Binding> binding(){var value=live();return value==null?Optional.empty():Optional.of(value.publication.binding());}
    /** Historical opaque publications retain exact source/binding for owned cleanup only.
     * query/current still expose only the newest eligible pins. Cold replay rebuilds this map. */
    public Optional<Publication> publication(Key key){var value=live();return value==null?Optional.empty():Optional.ofNullable(value.publications.get(key));}
    private static LocationRef ref(StaticLocationCatalogEntry entry){return new LocationRef(LocationStore.CATALOG,entry.location_id(),entry.revision());}
    private static <K>Map<K,Set<Id>> freeze(Map<K,Set<Id>> from){Map<K,Set<Id>> out=new HashMap<>();from.forEach((k,v)->out.put(k,Set.copyOf(v)));return Map.copyOf(out);}
}
