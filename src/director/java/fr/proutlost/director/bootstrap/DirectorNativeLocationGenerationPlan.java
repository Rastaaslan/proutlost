package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.integration.locations.LocationTransfer;
import fr.proutlost.director.integration.locations.StandaloneLocationImporter;
import java.util.*;
import java.util.function.Function;

/** Pure proposal builder for J04 metadata generations. A page is never an import
 * revision and has no publication effect. The caller supplies a verified,
 * pinned J04 snapshot and must activate every page together through J04.
 *
 * Only the bounded source DTOs and key index are retained here. Existing native
 * aggregate bodies are supplied one page at a time, with at most 96 keys. A
 * region and its memory prerequisite always occupy the same page. This class
 * deliberately does not retain a catalogue-sized cache of aggregate bodies.
 * Border, tag vocabulary and cross-kind/native-origin admission remain the
 * trusted native admission owner's responsibility; historical owner checks
 * below protect every source-owned head used by this plan.
 */
public final class DirectorNativeLocationGenerationPlan {
    public static final int PAGE_KEYS=96;
    public record Page(List<AggregateState> states,Map<AggregateKey,Revision> beforeRevisions) {
        public Page {
            states=List.copyOf(states);beforeRevisions=Map.copyOf(beforeRevisions);
            require(states.size()<=PAGE_KEYS&&beforeRevisions.size()<=PAGE_KEYS,"generation page capacity");
        }
    }
    private final LocationImport input;
    private final Tick tick;
    private final Function<AggregateKey,Optional<ResourceId>> ownership;
    private final Map<RegionId,ImportedRegion> regions=new LinkedHashMap<>(),previousRegions=new HashMap<>();
    private final Map<LocationId,ImportedLocation> locations=new LinkedHashMap<>(),previousLocations=new HashMap<>();
    private final Map<AnchorId,ImportedAnchor> anchors=new LinkedHashMap<>(),previousAnchors=new HashMap<>();
    private final Map<RegionId,Set<AnchorId>> regionAnchors=new HashMap<>();
    private final Map<LocationId,Set<AnchorId>> locationAnchors=new HashMap<>();
    private final List<List<AggregateKey>> pages;
    private final List<AggregateKey> keys;

    public DirectorNativeLocationGenerationPlan(WorldId world,LocationImport input,
            LocationImport previous,Tick tick,Function<AggregateKey,Optional<ResourceId>> ownership) {
        this.input=Objects.requireNonNull(input);this.tick=Objects.requireNonNull(tick);
        this.ownership=Objects.requireNonNull(ownership);
        require(input.worldId().equals(world)&&input.exportDigest().equals(StandaloneLocationImporter.digest(input)),
            "input world/digest changed");
        require(new ModelCodec<>(LocationImport.class).encode(input).length<=LocationTransfer.SOURCE_BYTES,
            "generation complete source byte bound");
        require(previous==null||previous.worldId().equals(world)
            &&previous.sourceNamespace().equals(input.sourceNamespace())
            &&input.exportRevision().compareTo(previous.exportRevision())>0,"import revision/source not newer");
        if(previous!=null){
            require(new ModelCodec<>(LocationImport.class).encode(previous).length<=LocationTransfer.SOURCE_BYTES
                &&previous.exportDigest().equals(StandaloneLocationImporter.digest(previous)),"previous source bytes/digest");
            previous.regions().forEach(r->previousRegions.put(r.regionId(),r));
            previous.locations().forEach(l->previousLocations.put(l.locationId(),l));
            previous.anchors().forEach(a->previousAnchors.put(a.anchorId(),a));
        }
        Set<UUID> ids=new HashSet<>();
        for(var r:input.regions()){
            require(ids.add(r.regionId().value()),"duplicate import id");regions.put(r.regionId(),r);
        }
        Set<String> external=new HashSet<>();
        for(var l:input.locations()){
            require(ids.add(l.locationId().value()),"duplicate import id");
            require(external.add(l.externalId()),"duplicate external id");
            locations.put(l.locationId(),l);
            for(var region:l.regionIds())require(regions.containsKey(region)
                &&regions.get(region).dimension().equals(l.dimension()),"import location region");
        }
        for(var a:input.anchors()){
            require(ids.add(a.anchorId().value()),"duplicate import id");anchors.put(a.anchorId(),a);
            var location=locations.get(a.locationId());
            require(location!=null&&location.dimension().equals(a.dimension()),"import anchor location");
            locationAnchors.computeIfAbsent(a.locationId(),k->new HashSet<>()).add(a.anchorId());
            for(var id:location.regionIds()){
                var region=regions.get(id);
                require(region!=null,"import anchor region");
                require(region.dimension().equals(a.dimension())&&contains(region.bounds(),a.position()),
                    "import anchor outside declared region");
                regionAnchors.computeIfAbsent(id,k->new HashSet<>()).add(a.anchorId());
            }
        }
        // Stable groups keep a region and its existing-memory CAS prerequisite
        // together; callers need no unbounded neighbouring-state read.
        List<List<AggregateKey>> groups=new ArrayList<>();
        for(var r:input.regions())groups.add(List.of(key(AggregateKind.REGION,r.regionId().value()),
            key(AggregateKind.REGION_MEMORY,r.regionId().value())));
        for(var l:input.locations())groups.add(List.of(key(AggregateKind.LOCATION,l.locationId().value())));
        for(var a:input.anchors())groups.add(List.of(key(AggregateKind.ANCHOR,a.anchorId().value())));
        if(previous!=null){
            for(var a:previous.anchors())if(!anchors.containsKey(a.anchorId()))
                groups.add(List.of(key(AggregateKind.ANCHOR,a.anchorId().value())));
            for(var l:previous.locations())if(!locations.containsKey(l.locationId()))
                groups.add(List.of(key(AggregateKind.LOCATION,l.locationId().value())));
            for(var r:previous.regions())if(!regions.containsKey(r.regionId()))
                groups.add(List.of(key(AggregateKind.REGION,r.regionId().value())));
        }
        List<List<AggregateKey>> built=new ArrayList<>();List<AggregateKey> page=new ArrayList<>();
        Set<AggregateKey> seen=new HashSet<>();
        for(var group:groups){
            if(page.size()+group.size()>PAGE_KEYS){built.add(List.copyOf(page));page.clear();}
            for(var k:group){require(seen.add(k),"duplicate generation key");page.add(k);}
        }
        if(!page.isEmpty())built.add(List.copyOf(page));
        pages=List.copyOf(built);keys=pages.stream().flatMap(Collection::stream).toList();
    }
    public List<AggregateKey> keys(){return keys;}
    public int pageCount(){return pages.size();}
    public List<AggregateKey> pageKeys(int pageIndex){return pages.get(pageIndex);}

    /** Actual contains only heads returned for this exact requested page from
     * the pinned J04 snapshot. A missing requested key is proven absent by that
     * successful read, never inferred from an unrelated resident cache miss.
     * Unchanged region memories appear only as CAS guards, not new revisions.
     */
    public Page planPage(List<AggregateKey> requested,Map<AggregateKey,AggregateState> actual) {
        Objects.requireNonNull(requested);Objects.requireNonNull(actual);
        require(requested.size()<=PAGE_KEYS&&new HashSet<>(requested).size()==requested.size()
            &&pages.contains(requested),"generation page keys");
        require(new HashSet<>(requested).containsAll(actual.keySet()),"unrequested generation head");
        Map<AggregateKey,Revision> before=new LinkedHashMap<>();List<AggregateState> proposed=new ArrayList<>();
        for(var k:requested){
            var old=actual.get(k);var owner=Objects.requireNonNull(ownership.apply(k));
            require(owner.isEmpty()||owner.orElseThrow().equals(input.sourceNamespace()),"historical source collision");
            if(old!=null){
                require(old.key().equals(k),"generation head identity");
                require(owner.filter(input.sourceNamespace()::equals).isPresent(),"identity collision with another origin/source");
                require(old.header().updatedTick().compareTo(tick)<=0,"generation context ahead of logical clock");
            }
            before.put(k,old==null?new Revision(0):old.header().revision());
            AggregateState next=switch(k.kind()){
                case REGION->region(k,old,actual);
                case REGION_MEMORY->regionMemory(k,old,actual);
                case LOCATION->location(k,old);
                case ANCHOR->anchor(k,old);
                default->throw new IllegalArgumentException("non-metadata generation key");
            };
            if(next!=null)proposed.add(next);
        }
        return new Page(proposed,before);
    }
    private AggregateState region(AggregateKey k,AggregateState old,Map<AggregateKey,AggregateState> actual){
        require(old==null||old instanceof RegionAggregate,"region type collision");
        var before=old==null?null:((RegionAggregate)old).value();
        require(before==null||before.status()!=RegionStatus.ALIAS,"cannot overwrite region topology alias");
        var imported=regions.get(new RegionId(k.id()));
        if(imported==null){
            require(previousRegions.containsKey(new RegionId(k.id()))&&before!=null,"removed region missing");
            return new RegionAggregate(new RegionRecord(next(old,k.id()),before.regionId(),before.dimension(),
                RegionStatus.ARCHIVED,before.supportShape(),before.anchorIds(),before.importance(),
                before.parentIds(),before.aliases(),before.membershipRevision().plus(1),before.personality(),before.memoryRefs()));
        }
        var memoryKey=key(AggregateKind.REGION_MEMORY,k.id());
        require(actual.containsKey(memoryKey)==(before!=null),before==null?"orphan region memory collision":"existing region memory absent");
        if(before!=null)require(actual.get(memoryKey) instanceof RegionMemoryAggregate memory
            &&memory.value().regionId().equals(imported.regionId()),"existing region memory identity/type");
        Set<AnchorId> selected=new HashSet<>();
        if(before!=null)for(var a:before.anchorIds())
            if(!ownership.apply(key(AggregateKind.ANCHOR,a.value())).filter(input.sourceNamespace()::equals).isPresent())selected.add(a);
        selected.addAll(regionAnchors.getOrDefault(imported.regionId(),Set.of()));
        List<Aabb> boxes=new ArrayList<>(before==null?List.of():before.supportShape().boxes());
        var prior=previousRegions.get(imported.regionId());if(prior!=null)boxes.remove(bounds(prior.dimension(),prior.bounds()));
        boxes.add(bounds(imported.dimension(),imported.bounds()));
        var shape=new SupportShape(boxes.stream().distinct().toList(),before==null?List.of():before.supportShape().discs(),
            before==null?List.of():before.supportShape().routes());
        return new RegionAggregate(new RegionRecord(next(old,k.id()),imported.regionId(),imported.dimension(),
            RegionStatus.ACTIVE,shape,selected,before==null?new UnitValue(0):before.importance(),
            before==null?Set.of():before.parentIds(),before==null?Set.of():before.aliases(),
            before==null?new Revision(1):before.membershipRevision().plus(1),
            before==null?new RegionPersonality(Map.of()):before.personality(),
            before==null?Set.of(new MemoryId(k.id())):before.memoryRefs()));
    }
    private AggregateState regionMemory(AggregateKey k,AggregateState old,Map<AggregateKey,AggregateState> actual){
        var region=actual.get(key(AggregateKind.REGION,k.id()));
        if(region!=null){
            require(region instanceof RegionAggregate&&old instanceof RegionMemoryAggregate
                &&((RegionMemoryAggregate)old).value().regionId().value().equals(k.id()),"existing region memory absent/ambiguous");
            return null;
        }
        require(old==null,"orphan region memory collision");
        return new RegionMemoryAggregate(new RegionMemory(next(null,k.id()),new RegionId(k.id()),Map.of(),
            new Tick(0),new Tick(0),Set.of(),Set.of(),new RegionActivityState(Map.of(),Map.of())));
    }
    private AggregateState location(AggregateKey k,AggregateState old){
        require(old==null||old instanceof LocationAggregate,"location type collision");
        var before=old==null?null:((LocationAggregate)old).value();
        require(before==null||before.sourceNamespace().equals(input.sourceNamespace()),"location source collision");
        var imported=locations.get(new LocationId(k.id()));
        if(imported==null){
            require(previousLocations.containsKey(new LocationId(k.id()))&&before!=null,"removed location missing");
            return new LocationAggregate(new LocationRecord(next(old,k.id()),before.locationId(),input.sourceNamespace(),
                before.externalId(),before.dimension(),before.bounds(),before.tags(),before.anchorIds(),input.exportRevision(),false));
        }
        return new LocationAggregate(new LocationRecord(next(old,k.id()),imported.locationId(),input.sourceNamespace(),
            imported.externalId(),imported.dimension(),bounds(imported.dimension(),imported.bounds()),imported.tags(),
            locationAnchors.getOrDefault(imported.locationId(),Set.of()),input.exportRevision(),true));
    }
    private AggregateState anchor(AggregateKey k,AggregateState old){
        require(old==null||old instanceof AnchorAggregate,"anchor type collision");
        var before=old==null?null:((AnchorAggregate)old).value();
        require(before==null||before.origin()==AnchorOrigin.IMPORTED,"anchor origin collision");
        var imported=anchors.get(new AnchorId(k.id()));
        if(imported==null){
            require(previousAnchors.containsKey(new AnchorId(k.id()))&&before!=null,"removed anchor missing");
            return new AnchorAggregate(new AnchorRecord(next(old,k.id()),before.anchorId(),before.locationId(),before.regionIds(),
                before.dimension(),before.position(),before.radiusProfile(),before.tags(),before.origin(),before.evidence(),false));
        }
        Set<TagId> tags=new HashSet<>(before==null?Set.of():before.tags());
        var prior=previousAnchors.get(imported.anchorId());if(prior!=null)tags.removeAll(prior.tags());tags.addAll(imported.tags());
        return new AnchorAggregate(new AnchorRecord(next(old,k.id()),imported.anchorId(),Optional.of(imported.locationId()),
            locations.get(imported.locationId()).regionIds(),imported.dimension(),
            new BlockPosition(imported.dimension(),imported.position().x(),imported.position().y(),imported.position().z()),
            before==null?"region.camp_radius_blocks":before.radiusProfile(),tags,AnchorOrigin.IMPORTED,EvidenceStatus.CONFIRMED,true));
    }
    private AggregateHeader next(AggregateState old,UUID id){
        return old==null?new AggregateHeader(1,id,new Revision(1),new Sequence(0),tick)
            :new AggregateHeader(1,id,old.header().revision().plus(1),old.header().lastSequence(),tick);
    }
    private static AggregateKey key(AggregateKind kind,UUID id){return new AggregateKey(kind,id);}
    private static Aabb bounds(DimensionId dimension,ImportBounds b){return new Aabb(dimension,
        b.minX(),b.minY(),b.minZ(),b.maxXExclusive(),b.maxYExclusive(),b.maxZExclusive());}
    private static boolean contains(ImportBounds b,ImportPosition p){return p.x()>=b.minX()&&p.x()<b.maxXExclusive()
        &&p.y()>=b.minY()&&p.y()<b.maxYExclusive()&&p.z()>=b.minZ()&&p.z()<b.maxZExclusive();}
    private static void require(boolean ok,String reason){if(!ok)throw new IllegalArgumentException(reason);}
}
