package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.integration.locations.StandaloneLocationImporter;
import fr.proutlost.director.memory.MemoryProjection;
import fr.proutlost.director.memory.AuthoredRegionTagProjection;
import fr.proutlost.director.persistence.JournalStore;
import fr.proutlost.director.runtime.ResultTransaction;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/** Explicit operator-authored J08 metadata -> existing J04 writer.
 * File and registry fsync run on one private IO thread; Minecraft/metadata
 * decisions, WAL submission and ACK readback remain on the server actor.
 * Tags are authored data, never physical survey or event outcome evidence.
 */
public final class DirectorNativeLocationImports implements AutoCloseable {
    public static final FactType APPLIED=new FactType("proutlost_director:location_import_applied");
    private static final ModelCodec<LocationImport> CODEC=new ModelCodec<>(LocationImport.class);
    private static final int MAX_BYTES=StandaloneLocationImporter.NATIVE_MAX_BYTES,MAX_ENTRIES=StandaloneLocationImporter.NATIVE_MAX_ENTRIES,MAX_METADATA=512,MAX_SOURCES=32;
    private static final Set<AggregateKind> METADATA=Set.of(AggregateKind.REGION,
        AggregateKind.REGION_MEMORY,AggregateKind.LOCATION,AggregateKind.ANCHOR);
    private static final Set<String> LEGACY_TAGS=Set.of("proutlost:clearing","proutlost:fauna_corridor",
        "proutlost:camp","proutlost:abandoned_camp","proutlost:camp_abandoned",
        "proutlost:regrown_camp","proutlost:signal_anchor","proutlost:landslide_scar",
        "proutlost:fauna_settled","proutlost:remembered_camp","proutlost:recovering_landscape",
        "proutlost:unfinished_recovery","proutlost:structure","proutlost:dungeon","proutlost:anchor_center");

    // Exact metadata classifications and anchor roles emitted by the retained
    // Contra revision3 producer. These labels grant no physical capability.
    private static final Set<String> LOCATION_CLASSIFICATIONS=Set.of(
        "proutlost:bridge","proutlost:explorable","proutlost:gathering_candidate",
        "proutlost:mineshaft","proutlost:minor_poi","proutlost:perception_candidate",
        "proutlost:pillar","proutlost:route_candidate","proutlost:shelter_candidate",
        "proutlost:signal_candidate","proutlost:witch_circle","proutlost:yungs");
    private static final Set<String> ANCHOR_ROLES=Set.of(
        "proutlost:anchor_apparition","proutlost:anchor_entrance","proutlost:anchor_gathering",
        "proutlost:anchor_interaction","proutlost:anchor_observation",
        "proutlost:anchor_route_end","proutlost:anchor_route_start");
    private static final Set<String> TAGS;
    static {
        var known=new HashSet<>(LEGACY_TAGS);known.addAll(LOCATION_CLASSIFICATIONS);known.addAll(ANCHOR_ROLES);
        TAGS=Set.copyOf(known);
    }
    private final MinecraftServer server;
    private final WorldId world;
    private final DirectorRuntimeContent runtimeContent;
    private final DirectorNativeLocationGenerations generations;
    private final Map<ResourceId,LocationImport> applied=new TreeMap<>();
    private final Map<AggregateKey,ResourceId> owners=new HashMap<>();
    private boolean recovered,closed;
    public DirectorNativeLocationImports(MinecraftServer server,WorldId world,JournalStore store,
            Supplier<Optional<MemoryView>> memory,BooleanSupplier quiescent,
            Predicate<Object> acquireLane,Consumer<Object> releaseLane){
        this(server,world,store,memory,quiescent,acquireLane,releaseLane,null);
    }
    public DirectorNativeLocationImports(MinecraftServer server,WorldId world,JournalStore store,
            Supplier<Optional<MemoryView>> memory,BooleanSupplier quiescent,
            Predicate<Object> acquireLane,Consumer<Object> releaseLane,DirectorRuntimeContent content){
        this.server=Objects.requireNonNull(server);this.world=Objects.requireNonNull(world);runtimeContent=content;
        if(content!=null&&!content.production())throw new IllegalArgumentException("approved native content required");
        actor();Map<DimensionId,ImportBounds> dimensions=new HashMap<>();
        for(var level:server.getAllLevels()){
            var border=level.getWorldBorder();
            dimensions.put(new DimensionId(level.dimension().location().toString()),new ImportBounds(
                (int)Math.ceil(Math.max(-29999984,border.getMinX())),level.getMinBuildHeight(),
                (int)Math.ceil(Math.max(-29999984,border.getMinZ())),
                (int)Math.floor(Math.min(29999984,border.getMaxX())),level.getMaxBuildHeight(),
                (int)Math.floor(Math.min(29999984,border.getMaxZ()))));
        }
        generations=new DirectorNativeLocationGenerations(server,world,store,memory,quiescent,
            acquireLane,releaseLane,dimensions,()->Map.copyOf(applied),k->Optional.ofNullable(owners.get(k)));
    }
    private void actor(){if(closed||!server.isSameThread())throw new IllegalStateException("location imports server actor");}
    static boolean acceptsAuthoredTag(TagId tag){return TAGS.contains(tag.value());}
    public void resetRecoveryScan(){actor();require(!generations.busy(),"cannot reset live import");applied.clear();owners.clear();recovered=false;}
    public void acceptRows(List<DurableRecord> records){
        actor();require(!recovered,"import recovery already finished");
        for(var row:records)if(row instanceof DurableFact fact&&fact.value().factType().equals(APPLIED)){
            var f=fact.value();require(f.durable()&&f.evidenceStatus()==EvidenceStatus.CONFIRMED
                &&f.eventId().isEmpty()&&f.subjectRefs().contains(new ScopeRef(ScopeKind.WORLD,world.value())),"invalid legacy import manifest");
            byte[] bytes=Base64.getDecoder().decode(text(f,"location_import_base64"));
            require(bytes.length<=MAX_BYTES,"legacy import manifest bound");
            var value=CODEC.decode(bytes);requireAuthoredScopes(value);
            require(value.worldId().equals(world)&&value.exportDigest().equals(StandaloneLocationImporter.digest(value))
                &&value.sourceNamespace().value().equals(text(f,"source_namespace"))
                &&Long.toString(value.exportRevision().value()).equals(text(f,"export_revision"))
                &&value.exportDigest().value().equals(text(f,"export_digest"))&&f.factId().equals(factId(world,value)),"legacy import manifest identity");
            var previous=applied.get(value.sourceNamespace());
            require(previous==null||value.exportRevision().compareTo(previous.exportRevision())>=0,"legacy revision regression");
            if(previous!=null&&previous.exportRevision().equals(value.exportRevision()))require(previous.exportDigest().equals(value.exportDigest()),"legacy revision conflict");
            require(applied.containsKey(value.sourceNamespace())||applied.size()<MAX_SOURCES,"legacy source capacity");
            for(var r:value.regions()){own(new AggregateKey(AggregateKind.REGION,r.regionId().value()),value.sourceNamespace());own(new AggregateKey(AggregateKind.REGION_MEMORY,r.regionId().value()),value.sourceNamespace());}
            for(var l:value.locations())own(new AggregateKey(AggregateKind.LOCATION,l.locationId().value()),value.sourceNamespace());
            for(var a:value.anchors())own(new AggregateKey(AggregateKind.ANCHOR,a.anchorId().value()),value.sourceNamespace());
            applied.put(value.sourceNamespace(),value);
        }
    }
    private void own(AggregateKey key,ResourceId source){var old=owners.putIfAbsent(key,source);require(old==null||old.equals(source),"legacy historical source collision");}
    public void finishRecovery(Sequence cut){actor();require(!recovered,"import recovery already finished");recovered=true;}
    public void acceptRecoveryRows(List<DurableRecord> rows,boolean last,Sequence cut){acceptRows(rows);if(last)finishRecovery(cut);}
    public String submit(ServerPlayer player,String name){actor();if(player==null||player.getServer()!=server||!player.hasPermissions(4))return "OP4 requis pour importer les lieux.";return submit(name);}
    public String cancel(ServerPlayer player,String name){actor();if(player==null||player.getServer()!=server||!player.hasPermissions(4))return "OP4 requis pour annuler un transfert.";if(!recovered)return "Import en recuperation.";return generations.cancel(name);}
    public String submit(String name){
        actor();if(runtimeContent==null&&!Boolean.getBoolean("proutlost.director.nativeLab"))return "Import LAB desactive.";
        if(!recovered)return "Import en recuperation.";return generations.submit(name);
    }
    public boolean submitApproved(String name,DirectorRuntimeContent expected){actor();require(runtimeContent!=null&&runtimeContent==expected&&expected.production(),"production import owner mismatch");submit(name);return generations.busy();}
    public boolean approvedSubmissionComplete(DirectorRuntimeContent expected){actor();require(runtimeContent==expected,"production import owner mismatch");return generations.complete();}
    public boolean approvedSubmissionFailed(DirectorRuntimeContent expected){actor();require(runtimeContent==expected,"production import owner mismatch");return generations.failed();}
    public boolean busy(){actor();return generations.busy();}
    public boolean metadataLaneHeld(){actor();return generations.laneHeld();}
    public boolean regionTagsReady(){actor();return generations.tagsReady();}
    public Map<RegionId,Set<TagId>> regionTags(){actor();return generations.tags();}
    public Sequence regionTagsCut(){actor();return generations.tagsCut();}
    public String status(){actor();return generations.status();}
    public void poll(){actor();if(recovered)generations.poll();}
    @Override public void close(){actor();generations.close();closed=true;}
    static void requireAuthoredScopes(LocationImport input){
        for(var row:input.regions())for(var tag:row.tags())
            require(LEGACY_TAGS.contains(tag.value()),"unsupported import region tag scope");
        for(var row:input.locations())for(var tag:row.tags())
            require(LEGACY_TAGS.contains(tag.value())||LOCATION_CLASSIFICATIONS.contains(tag.value()),
                "unsupported import location tag scope");
        for(var row:input.anchors())for(var tag:row.tags())
            require(LEGACY_TAGS.contains(tag.value())||ANCHOR_ROLES.contains(tag.value()),
                "unsupported import anchor tag scope");
    }

    private static void require(boolean value,String why){if(!value)throw new IllegalArgumentException(why);}
    private static JsonValue.Text text(Object value){return new JsonValue.Text(value.toString());}
    private static String text(DirectorFact fact,String key){
        return ((JsonValue.Text)Objects.requireNonNull(fact.payload().get(key),key)).value();
    }
    private static UUID identity(String value){
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }
    record Context(Sequence cut,Tick tick,MemoryView memory,Map<AggregateKey,AggregateState> states,Set<AggregateKey> allKeys){}
    static Tick importTick(DirectorWorldState worldState,Collection<AggregateState> states,long logicalNow){
        long floor=worldState.simTick().value();
        for(var state:states)floor=Math.max(floor,state.header().updatedTick().value());
        // A future durable aggregate requires recovery; never forge a new clock
        // or mutate the input timestamps to hide an unverified projection.
        require(logicalNow>=floor,"import context ahead of Director logical clock");
        return new Tick(logicalNow);
    }
    /** A fixed resident capacity is not a transient projection wait. */
    static void requireCompleteContextCapacity(int count){
        if(count>MAX_METADATA)throw new IllegalStateException("location import metadata context capacity "+count+">"+MAX_METADATA);
    }
    /** Pure bounded preflight: no registry, filesystem or J04 mutation. */
    static Plan preflight(WorldId world,LocationImport input,LocationImport previous,Context context,
            Map<AggregateKey,ResourceId> ownership,Set<ResourceId> currentSources){
        require(currentSources.contains(input.sourceNamespace())||currentSources.size()<MAX_SOURCES,
            "import source capacity");
        var result=plan(world,input,previous,context,ownership);
        Set<AggregateKey> projected=new HashSet<>();
        for(var key:context.states.keySet())if(METADATA.contains(key.kind()))projected.add(key);
        projected.addAll(result.states.keySet());
        require(projected.size()<=MAX_METADATA,"import projected metadata capacity");
        Set<AggregateKey> historical=new HashSet<>(ownership.keySet());
        historical.addAll(result.states.keySet());
        require(historical.size()<=4096,"import historical identity capacity");
        return result;
    }
    /** Recheck after registry IO without inventing a fresh plan for an accepted input. */
    static void verifyPreparedPlan(Plan preparedPlan,WorldId world,LocationImport input,LocationImport previous,
            Context preparedContext,Context current,Map<AggregateKey,ResourceId> ownership,
            Set<ResourceId> currentSources){
        require(preparedContext!=null&&preparedPlan!=null,"missing import actor preflight");
        require(current.cut.compareTo(preparedContext.cut)>=0,"import context cut regressed");
        var atPreparedTick=new Context(current.cut,preparedContext.tick,current.memory,current.states,current.allKeys);
        var checked=preflight(world,input,previous,atPreparedTick,ownership,currentSources);
        require(preparedPlan.equals(checked),"import actor preflight changed after registry commit");
    }
    private static FactId factId(WorldId world,LocationImport value){
        return new FactId(identity("director-location-import-v1/"+world.value()+"/"
            +value.sourceNamespace().value()+"/"+value.exportRevision().value()+"/"+value.exportDigest().value()));
    }
    private DirectorFact manifest(LocationImport value,Tick tick){
        return new DirectorFact(factId(world,value),APPLIED,List.of(new ScopeRef(ScopeKind.WORLD,world.value())),
            Optional.empty(),EvidenceStatus.CONFIRMED,Map.of(
                "source_namespace",text(value.sourceNamespace().value()),
                "export_revision",text(value.exportRevision().value()),
                "export_digest",text(value.exportDigest().value()),
                "location_import_base64",text(Base64.getEncoder().encodeToString(CODEC.encode(value))),
                "tick",text(tick.value()),"operator_authored",new JsonValue.Bool(true)),
            true,Optional.empty());
    }
    record Plan(Map<AggregateKey,AggregateState> states,Map<ScopeRef,Revision> revisions){}
    private static Aabb bounds(DimensionId d,ImportBounds b){
        return new Aabb(d,b.minX(),b.minY(),b.minZ(),b.maxXExclusive(),b.maxYExclusive(),b.maxZExclusive());
    }
    private static boolean contains(ImportBounds b,ImportPosition p){
        return p.x()>=b.minX()&&p.x()<b.maxXExclusive()&&p.y()>=b.minY()&&p.y()<b.maxYExclusive()
            &&p.z()>=b.minZ()&&p.z()<b.maxZExclusive();
    }
    private static Map<AnchorId,Set<TagId>> authored(LocationImport input){
        Map<AnchorId,Set<TagId>> result=new HashMap<>();if(input==null)return result;
        Map<LocationId,ImportedLocation> locations=new HashMap<>();
        input.locations().forEach(l->locations.put(l.locationId(),l));
        Map<RegionId,ImportedRegion> regions=new HashMap<>();input.regions().forEach(r->regions.put(r.regionId(),r));
        for(var a:input.anchors()){
            var location=locations.get(a.locationId());
            require(location!=null,"import anchor location");
            // Anchor granularity is exact. Region/location classifications
            // are projected separately into RegionSnapshot after J04 ACK.
            Set<TagId> tags=new HashSet<>(a.tags());
            for(var id:location.regionIds()){
                var region=regions.get(id);
                require(region!=null,"import anchor region");
                require(region.dimension().equals(a.dimension())&&contains(region.bounds(),a.position()),
                    "import anchor outside declared region");
            }
            result.put(a.anchorId(),Set.copyOf(tags));
        }
        return result;
    }
    private static AggregateHeader next(AggregateState before,UUID id,Tick tick){
        return before==null?new AggregateHeader(1,id,new Revision(1),new Sequence(0),tick)
            :new AggregateHeader(1,id,before.header().revision().plus(1),before.header().lastSequence(),tick);
    }
    /** Pure plan, reusable by native qualification without filesystem or Minecraft writes. */
    static Plan plan(WorldId world,LocationImport input,LocationImport previous,Context context,
            Map<AggregateKey,ResourceId> ownership){
        require(input.worldId().equals(world)&&input.exportDigest().equals(StandaloneLocationImporter.digest(input)),
            "input world/digest changed");
        requireAuthoredScopes(input);
        require((long)input.regions().size()+input.locations().size()+input.anchors().size()<=MAX_ENTRIES,
            "import entry bound");
        require(previous==null||previous.sourceNamespace().equals(input.sourceNamespace())
            &&previous.worldId().equals(world)
            &&input.exportRevision().compareTo(previous.exportRevision())>0,"import revision/source not newer");
        Map<AggregateKey,AggregateState> changes=new LinkedHashMap<>();
        Map<ScopeRef,Revision> cas=new HashMap<>();
        var source=input.sourceNamespace();var old=context.states;var tick=context.tick;
        Set<AggregateKey> desired=new HashSet<>();
        for(var r:input.regions()){
            desired.add(new AggregateKey(AggregateKind.REGION,r.regionId().value()));
            desired.add(new AggregateKey(AggregateKind.REGION_MEMORY,r.regionId().value()));
        }
        input.locations().forEach(l->desired.add(new AggregateKey(AggregateKind.LOCATION,l.locationId().value())));
        input.anchors().forEach(a->desired.add(new AggregateKey(AggregateKind.ANCHOR,a.anchorId().value())));
        for(var key:desired){
            for(var existing:context.allKeys)if(existing.id().equals(key.id())){
                boolean expectedMemoryPair=existing.kind()==AggregateKind.REGION&&key.kind()==AggregateKind.REGION_MEMORY || existing.kind()==AggregateKind.REGION_MEMORY&&key.kind()==AggregateKind.REGION;
                require((existing.kind()==key.kind()||expectedMemoryPair)&&source.equals(ownership.get(existing)),
                    "identity collision with another origin/source: "+existing);
            }
        }
        var beforeTags=authored(previous);var afterTags=authored(input);
        Map<LocationId,ImportedLocation> locations=new HashMap<>();input.locations().forEach(l->locations.put(l.locationId(),l));
        Map<RegionId,ImportedRegion> priorRegions=new HashMap<>();
        if(previous!=null)previous.regions().forEach(r->priorRegions.put(r.regionId(),r));
        for(var r:input.regions()){
            var key=new AggregateKey(AggregateKind.REGION,r.regionId().value());var existing=old.get(key);
            RegionRecord before=existing==null?null:((RegionAggregate)existing).value();
            require(before==null||before.status()!=RegionStatus.ALIAS,"cannot overwrite region topology alias");
            Set<AnchorId> anchors=new HashSet<>();
            if(before!=null)for(var a:before.anchorIds())
                if(!source.equals(ownership.get(new AggregateKey(AggregateKind.ANCHOR,a.value()))))anchors.add(a);
            for(var a:input.anchors())if(locations.get(a.locationId()).regionIds().contains(r.regionId()))anchors.add(a.anchorId());
            List<Aabb> boxes=new ArrayList<>();
            if(before!=null)boxes.addAll(before.supportShape().boxes());
            var prior=priorRegions.get(r.regionId());if(prior!=null)boxes.remove(bounds(prior.dimension(),prior.bounds()));
            boxes.add(bounds(r.dimension(),r.bounds()));
            var shape=new SupportShape(boxes.stream().distinct().toList(),
                before==null?List.of():before.supportShape().discs(),before==null?List.of():before.supportShape().routes());
            var next=new RegionRecord(next(existing,r.regionId().value(),tick),r.regionId(),r.dimension(),
                RegionStatus.ACTIVE,shape,anchors,before==null?new UnitValue(0):before.importance(),
                before==null?Set.of():before.parentIds(),before==null?Set.of():before.aliases(),
                before==null?new Revision(1):before.membershipRevision().plus(1),
                before==null?new RegionPersonality(Map.of()):before.personality(),
                before==null?Set.of(new MemoryId(r.regionId().value())):before.memoryRefs());
            changes.put(key,new RegionAggregate(next));
            cas.put(new ScopeRef(ScopeKind.REGION,r.regionId().value()),existing==null?new Revision(0):existing.header().revision());
            if(before==null){
                var memoryKey=new AggregateKey(AggregateKind.REGION_MEMORY,r.regionId().value());
                require(!old.containsKey(memoryKey),"orphan region memory collision");
                changes.put(memoryKey,new RegionMemoryAggregate(new RegionMemory(
                    next(null,r.regionId().value(),tick),r.regionId(),Map.of(),new Tick(0),new Tick(0),
                    Set.of(),Set.of(),new RegionActivityState(Map.of(),Map.of()))));
            }else require(context.memory.regions().stream().filter(m->m.regionId().equals(r.regionId())).count()==1,
                "existing region memory absent/ambiguous");
        }
        for(var l:input.locations()){
            var key=new AggregateKey(AggregateKind.LOCATION,l.locationId().value());var existing=old.get(key);
            if(existing!=null)require(((LocationAggregate)existing).value().sourceNamespace().equals(source),"location source collision");
            Set<AnchorId> anchors=new HashSet<>();
            input.anchors().stream().filter(a->a.locationId().equals(l.locationId())).forEach(a->anchors.add(a.anchorId()));
            var value=new LocationRecord(next(existing,l.locationId().value(),tick),l.locationId(),source,l.externalId(),
                l.dimension(),bounds(l.dimension(),l.bounds()),l.tags(),anchors,input.exportRevision(),true);
            changes.put(key,new LocationAggregate(value));
            cas.put(new ScopeRef(ScopeKind.LOCATION,l.locationId().value()),existing==null?new Revision(0):existing.header().revision());
        }
        for(var a:input.anchors()){
            var key=new AggregateKey(AggregateKind.ANCHOR,a.anchorId().value());var existing=old.get(key);
            AnchorRecord before=existing==null?null:((AnchorAggregate)existing).value();
            require(before==null||before.origin()==AnchorOrigin.IMPORTED,"anchor origin collision");
            Set<TagId> tags=new HashSet<>(before==null?Set.of():before.tags());
            tags.removeAll(beforeTags.getOrDefault(a.anchorId(),Set.of()));tags.addAll(afterTags.get(a.anchorId()));
            var value=new AnchorRecord(next(existing,a.anchorId().value(),tick),a.anchorId(),Optional.of(a.locationId()),
                locations.get(a.locationId()).regionIds(),a.dimension(),
                new BlockPosition(a.dimension(),a.position().x(),a.position().y(),a.position().z()),
                before==null?"region.camp_radius_blocks":before.radiusProfile(),tags,AnchorOrigin.IMPORTED,
                EvidenceStatus.CONFIRMED,true);
            changes.put(key,new AnchorAggregate(value));
        }
        // Missing entries retire only identities from the immediately previous
        // authored source. Runtime memory/personality and extra anchor tags stay.
        if(previous!=null){
            for(var a:previous.anchors()){
                var key=new AggregateKey(AggregateKind.ANCHOR,a.anchorId().value());
                if(desired.contains(key))continue;
                var existing=Objects.requireNonNull(old.get(key),"removed anchor missing");var before=((AnchorAggregate)existing).value();
                require(source.equals(ownership.get(key))&&before.origin()==AnchorOrigin.IMPORTED,"removed anchor ownership");
                changes.put(key,new AnchorAggregate(new AnchorRecord(next(existing,key.id(),tick),before.anchorId(),
                    before.locationId(),before.regionIds(),before.dimension(),before.position(),before.radiusProfile(),
                    before.tags(),before.origin(),before.evidence(),false)));
            }
            for(var l:previous.locations()){
                var key=new AggregateKey(AggregateKind.LOCATION,l.locationId().value());if(desired.contains(key))continue;
                var existing=Objects.requireNonNull(old.get(key),"removed location missing");var before=((LocationAggregate)existing).value();
                require(source.equals(ownership.get(key))&&before.sourceNamespace().equals(source),"removed location ownership");
                changes.put(key,new LocationAggregate(new LocationRecord(next(existing,key.id(),tick),before.locationId(),
                    source,before.externalId(),before.dimension(),before.bounds(),before.tags(),before.anchorIds(),
                    input.exportRevision(),false)));
                cas.put(new ScopeRef(ScopeKind.LOCATION,key.id()),existing.header().revision());
            }
            for(var r:previous.regions()){
                var key=new AggregateKey(AggregateKind.REGION,r.regionId().value());if(desired.contains(key))continue;
                var existing=Objects.requireNonNull(old.get(key),"removed region missing");var before=((RegionAggregate)existing).value();
                require(source.equals(ownership.get(key))&&before.status()!=RegionStatus.ALIAS,"removed region ownership/topology");
                changes.put(key,new RegionAggregate(new RegionRecord(next(existing,key.id(),tick),before.regionId(),
                    before.dimension(),RegionStatus.ARCHIVED,before.supportShape(),before.anchorIds(),before.importance(),
                    before.parentIds(),before.aliases(),before.membershipRevision().plus(1),before.personality(),before.memoryRefs())));
                cas.put(new ScopeRef(ScopeKind.REGION,key.id()),existing.header().revision());
            }
        }
        require(changes.size()<=MAX_ENTRIES*2,"import batch aggregate bound");
        return new Plan(Map.copyOf(changes),Map.copyOf(cas));
    }
}
