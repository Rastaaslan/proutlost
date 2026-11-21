package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.integration.locations.StandaloneLocationImporter;
import java.util.*;
import java.util.function.Function;

/** Declared synthetic, worldless planner checks. No native/cold qualification claim. */
public final class DirectorNativeLocationGenerationPlanTests {
    private static final WorldId WORLD=new WorldId(new UUID(0,1));
    private static final ResourceId SOURCE=new ResourceId("qa:planned_source"),OTHER=new ResourceId("qa:other");
    private static final DimensionId DIM=new DimensionId("minecraft:overworld");
    private static final ImportBounds BOX=new ImportBounds(-10,50,-10,11,80,11);
    private static int assertions;
    private static UUID id(long n){return new UUID(0,n);}
    private static AggregateKey key(AggregateKind kind,long n){return new AggregateKey(kind,id(n));}
    private static AggregateHeader head(long n,long revision){return new AggregateHeader(1,id(n),new Revision(revision),new Sequence(12),new Tick(4));}
    private static void check(boolean ok,String why){assertions++;if(!ok)throw new AssertionError(why);}
    private static void refuses(Runnable action,String why){assertions++;try{action.run();throw new AssertionError("accepted "+why);}catch(IllegalArgumentException expected){}}
    private static LocationImport signed(long revision,List<ImportedRegion> regions,List<ImportedLocation> locations,List<ImportedAnchor> anchors){
        var value=new LocationImport(1,WORLD,SOURCE,new Revision(revision),new Fingerprint("0".repeat(64)),regions,locations,anchors);
        return new LocationImport(1,WORLD,SOURCE,value.exportRevision(),StandaloneLocationImporter.digest(value),regions,locations,anchors);
    }
    private static ImportedLocation location(long n,Set<RegionId> regions){return new ImportedLocation(new LocationId(id(n)),"qa-location-"+n,regions,DIM,BOX,Set.of());}
    private static ImportedAnchor anchor(long n,long location,String tag){return new ImportedAnchor(new AnchorId(id(n)),new LocationId(id(location)),DIM,new ImportPosition(0,64,0),Set.of(new TagId(tag)));}
    private static Function<AggregateKey,Optional<ResourceId>> owners(Map<AggregateKey,AggregateState> existing){
        return key->Optional.ofNullable(existing.containsKey(key)?SOURCE:null);
    }
    private static Map<AggregateKey,AggregateState> apply(DirectorNativeLocationGenerationPlan planner,Map<AggregateKey,AggregateState> old){
        Map<AggregateKey,AggregateState> proposed=new LinkedHashMap<>();
        for(int n=0;n<planner.pageCount();n++){
            var keys=planner.pageKeys(n);check(keys.size()<=96,"bounded native page");
            Map<AggregateKey,AggregateState> resident=new HashMap<>();
            for(var k:keys)if(old.containsKey(k))resident.put(k,old.get(k));
            var page=planner.planPage(keys,resident);
            check(page.beforeRevisions().keySet().equals(new HashSet<>(keys)),"all requested heads CAS guarded");
            for(var state:page.states()){
                check(proposed.putIfAbsent(state.key(),state)==null,"head occurs once across generation pages");
                check(state.header().revision().equals(page.beforeRevisions().get(state.key()).plus(1)),"next head revision exact");
            }
        }
        return Map.copyOf(proposed);
    }
    private static void beyondResidentCapacity(){
        List<ImportedLocation> locations=new ArrayList<>();
        for(int n=0;n<700;n++)locations.add(location(1000+n,Set.of()));
        var input=signed(1,List.of(),locations,List.of());
        var planner=new DirectorNativeLocationGenerationPlan(WORLD,input,null,new Tick(9),k->Optional.empty());
        check(planner.pageCount()==8,"700 location proposal spans eight native pages");
        var states=apply(planner,Map.of());check(states.size()==700,"no truncation above512 metadata cache");
        check(planner.keys().size()==700,"all keys retained");
        check(states.values().stream().allMatch(s->((LocationAggregate)s).value().importRevision().equals(new Revision(1))),"one source revision across pages");
    }
    private static void replacementPreservesRuntimeAndRetiresMissing(){
        var rid=new RegionId(id(10));
        var previous=signed(1,List.of(new ImportedRegion(rid,DIM,BOX,Set.of())),
            List.of(location(20,Set.of(rid)),location(21,Set.of(rid))),
            List.of(anchor(30,20,"proutlost:camp"),anchor(31,21,"proutlost:camp")));
        var first=new DirectorNativeLocationGenerationPlan(WORLD,previous,null,new Tick(4),k->Optional.empty());
        Map<AggregateKey,AggregateState> old=new HashMap<>(apply(first,Map.of()));
        var record=((RegionAggregate)old.get(key(AggregateKind.REGION,10))).value();
        var foreignBox=new Aabb(DIM,100,50,100,105,70,105);
        var disc=new SupportDisc(new BlockPosition(DIM,0,64,0),3);
        var route=new RouteSegment(new BlockPosition(DIM,0,64,0),new BlockPosition(DIM,1,64,0),1);
        var personality=new RegionPersonality(Map.of(RegionTrait.values()[0],new UnitValue(.7)));
        var foreignAnchor=new AnchorId(id(900));
        old.put(key(AggregateKind.REGION,10),new RegionAggregate(new RegionRecord(head(10,5),rid,DIM,RegionStatus.ACTIVE,
            new SupportShape(List.of(record.supportShape().boxes().getFirst(),foreignBox),List.of(disc),List.of(route)),
            Set.of(new AnchorId(id(30)),new AnchorId(id(31)),foreignAnchor),new UnitValue(.6),Set.of(new RegionId(id(800))),
            Set.of(new RegionId(id(801))),new Revision(8),personality,Set.of(new MemoryId(id(10))))));
        var memory=new RegionMemoryAggregate(new RegionMemory(head(10,7),rid,
            Map.of(MemoryComponent.VISIT,new DecayValue(.8,new Tick(3),"qa.half_life",4,new UnitValue(.9))),
            new Tick(3),new Tick(4),Set.of(new ResourceId("qa:runtime_state")),Set.of(new FactId(id(500))),new RegionActivityState(Map.of(),Map.of())));
        old.put(key(AggregateKind.REGION_MEMORY,10),memory);
        var beforeAnchor=((AnchorAggregate)old.get(key(AggregateKind.ANCHOR,30))).value();
        old.put(key(AggregateKind.ANCHOR,30),new AnchorAggregate(new AnchorRecord(head(30,9),beforeAnchor.anchorId(),beforeAnchor.locationId(),
            beforeAnchor.regionIds(),DIM,beforeAnchor.position(),"qa.runtime_radius",Set.of(new TagId("proutlost:camp"),new TagId("qa:runtime_tag")),
            AnchorOrigin.IMPORTED,EvidenceStatus.CONFIRMED,true)));
        var nextBox=new ImportBounds(-8,50,-8,9,80,9);
        var input=signed(2,List.of(new ImportedRegion(rid,DIM,nextBox,Set.of())),
            List.of(location(20,Set.of(rid)),location(22,Set.of(rid))),
            List.of(anchor(30,20,"proutlost:regrown_camp"),anchor(32,22,"proutlost:camp")));
        var planner=new DirectorNativeLocationGenerationPlan(WORLD,input,previous,new Tick(9),
            k->k.equals(key(AggregateKind.ANCHOR,900))?Optional.of(OTHER):owners(old).apply(k));
        var next=apply(planner,old);
        check(!next.containsKey(key(AggregateKind.REGION_MEMORY,10)),"existing region memory unchanged, no new revision");
        var page=planner.planPage(planner.pageKeys(0),old);
        check(page.beforeRevisions().get(key(AggregateKind.REGION_MEMORY,10)).equals(new Revision(7)),"unchanged memory remains guarded");
        var region=((RegionAggregate)next.get(key(AggregateKind.REGION,10))).value();
        check(region.personality().equals(personality)&&region.importance().equals(new UnitValue(.6)),"runtime region personality/importance retained");
        check(region.parentIds().equals(Set.of(new RegionId(id(800))))&&region.aliases().equals(Set.of(new RegionId(id(801)))),"runtime region lineage retained");
        check(region.supportShape().boxes().contains(foreignBox)&&region.supportShape().boxes().size()==2,"prior authored box replaced, foreign box retained");
        check(region.supportShape().discs().equals(List.of(disc))&&region.supportShape().routes().equals(List.of(route)),"runtime support discs/routes retained");
        check(region.anchorIds().equals(Set.of(new AnchorId(id(30)),new AnchorId(id(32)),foreignAnchor)),"source membership replaced, foreign anchor retained");
        check(region.membershipRevision().equals(new Revision(9))&&region.header().revision().equals(new Revision(6)),"independent membership/head revisions increment correctly");
        var updated=((AnchorAggregate)next.get(key(AggregateKind.ANCHOR,30))).value();
        check(updated.tags().equals(Set.of(new TagId("proutlost:regrown_camp"),new TagId("qa:runtime_tag"))),"authored tag diff preserves runtime tag");
        check(updated.radiusProfile().equals("qa.runtime_radius")&&updated.header().revision().equals(new Revision(10)),"runtime anchor profile and exact revision retained");
        check(!((AnchorAggregate)next.get(key(AggregateKind.ANCHOR,31))).value().active(),"missing source anchor inactive");
        check(!((LocationAggregate)next.get(key(AggregateKind.LOCATION,21))).value().active(),"missing source location inactive");
        check(((LocationAggregate)next.get(key(AggregateKind.LOCATION,21))).value().importRevision().equals(new Revision(2)),"retired location belongs to new source revision");
        check(old.get(key(AggregateKind.REGION_MEMORY,10))==memory,"input memory unchanged by proposal");
        var removed=signed(3,List.of(),List.of(),List.of());
        var allCurrent=new HashMap<>(old);allCurrent.putAll(next);
        var retirement=new DirectorNativeLocationGenerationPlan(WORLD,removed,input,new Tick(10),owners(allCurrent));
        var retired=apply(retirement,allCurrent);
        check(((RegionAggregate)retired.get(key(AggregateKind.REGION,10))).value().status()==RegionStatus.ARCHIVED,"missing region archived");
        check(!retired.containsKey(key(AggregateKind.REGION_MEMORY,10)),"retired region memory preserved");
    }
    private static void collisionAndMalformedGuards(){
        var input=signed(1,List.of(),List.of(location(20,Set.of())),List.of());
        var planner=new DirectorNativeLocationGenerationPlan(WORLD,input,null,new Tick(9),k->Optional.of(OTHER));
        refuses(()->planner.planPage(planner.pageKeys(0),Map.of()),"historical foreign identity even absent head");
        var unsigned=new LocationImport(1,WORLD,SOURCE,new Revision(1),new Fingerprint("a".repeat(64)),input.regions(),input.locations(),input.anchors());
        refuses(()->new DirectorNativeLocationGenerationPlan(WORLD,unsigned,null,new Tick(9),k->Optional.empty()),"corrupt source digest");
        var first=new DirectorNativeLocationGenerationPlan(WORLD,input,null,new Tick(9),k->Optional.empty());
        refuses(()->first.planPage(List.of(key(AggregateKind.LOCATION,999)),Map.of()),"unplanned key");
        var rid=new RegionId(id(10));
        var regionInput=signed(1,List.of(new ImportedRegion(rid,DIM,BOX,Set.of())),List.of(),List.of());
        var fresh=new DirectorNativeLocationGenerationPlan(WORLD,regionInput,null,new Tick(9),k->Optional.of(SOURCE));
        var orphan=new RegionMemoryAggregate(new RegionMemory(head(10,1),rid,Map.of(),new Tick(0),new Tick(0),Set.of(),Set.of(),new RegionActivityState(Map.of(),Map.of())));
        refuses(()->fresh.planPage(fresh.pageKeys(0),Map.of(orphan.key(),orphan)),"orphan existing memory");
        var repeated=signed(1,List.of(),List.of(location(20,Set.of()),location(20,Set.of())),List.of());
        refuses(()->new DirectorNativeLocationGenerationPlan(WORLD,repeated,null,new Tick(9),k->Optional.empty()),"duplicate source identity");
    }
    public static void main(String[] args){
        beyondResidentCapacity();replacementPreservesRuntimeAndRetiresMissing();collisionAndMalformedGuards();
        System.out.println("P008 native generation planner: "+assertions+" assertions PASS; synthetic worldless scope only");
    }
}
