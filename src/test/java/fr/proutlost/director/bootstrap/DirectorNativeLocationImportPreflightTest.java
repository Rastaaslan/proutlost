package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.integration.locations.StandaloneLocationImporter;
import fr.proutlost.director.memory.MemoryProjection;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Pure admission bounds/ownership tests, not native or cold-boot qualification. */
final class DirectorNativeLocationImportPreflightTest {
    private static final WorldId WORLD=new WorldId(new UUID(0,1));
    private static final DimensionId DIMENSION=new DimensionId("minecraft:overworld");
    private static final ResourceId SOURCE=new ResourceId("test:import_source");
    private static final ImportBounds BOUNDS=new ImportBounds(-4,60,-4,5,70,5);
    private static final Tick TICK=new Tick(3);
    private static UUID id(long n){return new UUID(0,n);}
    private static AggregateKey key(long n){return new AggregateKey(AggregateKind.LOCATION,id(n));}
    private static LocationImport input(ResourceId source,long revision,long location){
        var imported=new ImportedLocation(new LocationId(id(location)),"location-"+location,
            Set.of(),DIMENSION,BOUNDS,Set.of());
        var unsigned=new LocationImport(1,WORLD,source,new Revision(revision),new Fingerprint("0".repeat(64)),
            List.of(),List.of(imported),List.of());
        return new LocationImport(1,WORLD,source,unsigned.exportRevision(),StandaloneLocationImporter.digest(unsigned),
            unsigned.regions(),unsigned.locations(),unsigned.anchors());
    }
    private static LocationAggregate location(long n){
        var header=new AggregateHeader(1,id(n),new Revision(1),new Sequence(1),new Tick(1));
        return new LocationAggregate(new LocationRecord(header,new LocationId(id(n)),SOURCE,"location-"+n,
            DIMENSION,new Aabb(DIMENSION,-4,60,-4,5,70,5),Set.of(),Set.of(),new Revision(1),true));
    }
    private static DirectorNativeLocationImports.Context context(int count){
        Map<AggregateKey,AggregateState> states=new HashMap<>();
        for(int n=0;n<count;n++)states.put(key(100+n),location(100+n));
        var scopes=new ScopeSet(WORLD,Set.of(new ScopeRef(ScopeKind.WORLD,WORLD.value())));
        return new DirectorNativeLocationImports.Context(new Sequence(1),TICK,
            MemoryProjection.of(scopes,states.values()),Map.copyOf(states),Set.copyOf(states.keySet()));
    }
    private static Set<ResourceId> sources(int count){
        Set<ResourceId> result=new HashSet<>();
        for(int n=0;n<count;n++)result.add(new ResourceId("test:source_"+n));
        return Set.copyOf(result);
    }
    @Test void acceptsTheLastResidentMetadataSlot(){
        var result=DirectorNativeLocationImports.preflight(WORLD,input(SOURCE,1,2000),null,context(511),Map.of(),Set.of());
        assertEquals(Set.of(key(2000)),result.states().keySet());
    }
    private static LocationImport graph(List<ImportedLocation> locations,List<ImportedAnchor> anchors){
        var value=new LocationImport(1,WORLD,SOURCE,new Revision(1),new Fingerprint("0".repeat(64)),
            List.of(),locations,anchors);
        return new LocationImport(1,WORLD,SOURCE,value.exportRevision(),StandaloneLocationImporter.digest(value),
            value.regions(),value.locations(),value.anchors());
    }
    @Test void missingAnchorLocationIsARecoverablePreDurableRefusal(){
        var anchor=new ImportedAnchor(new AnchorId(id(3000)),new LocationId(id(2000)),DIMENSION,
            new ImportPosition(0,64,0),Set.of());
        var value=graph(List.of(),List.of(anchor));
        var error=assertThrows(IllegalArgumentException.class,()->DirectorNativeLocationImports.preflight(
            WORLD,value,null,context(0),Map.of(),Set.of()));
        assertEquals("import anchor location",error.getMessage());
    }
    @Test void missingAnchorRegionIsARecoverablePreDurableRefusal(){
        var location=new ImportedLocation(new LocationId(id(2000)),"location-2000",Set.of(new RegionId(id(4000))),
            DIMENSION,BOUNDS,Set.of());
        var anchor=new ImportedAnchor(new AnchorId(id(3000)),location.locationId(),DIMENSION,
            new ImportPosition(0,64,0),Set.of());
        var value=graph(List.of(location),List.of(anchor));
        var error=assertThrows(IllegalArgumentException.class,()->DirectorNativeLocationImports.preflight(
            WORLD,value,null,context(0),Map.of(),Set.of()));
        assertEquals("import anchor region",error.getMessage());
    }
    @Test void refusesProjectedOverflowBeforeAnyMutableAdmission(){
        var context=context(512);var before=context.states();
        var refused=assertThrows(IllegalArgumentException.class,()->DirectorNativeLocationImports.preflight(
            WORLD,input(SOURCE,1,2000),null,context,Map.of(),Set.of()));
        assertEquals("import projected metadata capacity",refused.getMessage());
        assertSame(before,context.states());assertEquals(512,before.size());
    }
    @Test void fullResidentCapacityStillAllowsAnExistingOwnedLocationUpdate(){
        var context=context(512);var ownership=new HashMap<AggregateKey,ResourceId>();
        context.states().keySet().forEach(k->ownership.put(k,SOURCE));
        var result=DirectorNativeLocationImports.preflight(WORLD,input(SOURCE,2,100),input(SOURCE,1,100),
            context,ownership,Set.of(SOURCE));
        assertEquals(Set.of(key(100)),result.states().keySet());
        assertEquals(new Revision(2),result.states().get(key(100)).header().revision());
    }
    @Test void refusesSourceThirtyThreeAndAcceptsSourceThirtyTwo(){
        assertDoesNotThrow(()->DirectorNativeLocationImports.preflight(WORLD,input(SOURCE,1,2000),null,
            context(0),Map.of(),sources(31)));
        var refused=assertThrows(IllegalArgumentException.class,()->DirectorNativeLocationImports.preflight(
            WORLD,input(SOURCE,1,2000),null,context(0),Map.of(),sources(32)));
        assertEquals("import source capacity",refused.getMessage());
    }
    @Test void permitsAnExistingSourceAtTheSourceLimit(){
        var sources=new HashSet<>(sources(31));sources.add(SOURCE);
        assertDoesNotThrow(()->DirectorNativeLocationImports.preflight(WORLD,input(SOURCE,1,2000),null,
            context(0),Map.of(),sources));
    }
    @Test void ownershipCollisionRefusesWithoutChangingTheOwner(){
        var context=context(1);var other=new ResourceId("test:other_origin");
        var ownership=new HashMap<>(Map.of(key(100),other));
        var refused=assertThrows(IllegalArgumentException.class,()->DirectorNativeLocationImports.preflight(
            WORLD,input(SOURCE,1,100),null,context,ownership,Set.of()));
        assertTrue(refused.getMessage().startsWith("identity collision with another origin/source"));
        assertEquals(Map.of(key(100),other),ownership);
    }
    @Test void historicalOwnershipCapacityIncludesNewIdentities(){
        var ownership=new HashMap<AggregateKey,ResourceId>();
        for(int n=0;n<4096;n++)ownership.put(new AggregateKey(AggregateKind.ANCHOR,id(10000+n)),SOURCE);
        var refused=assertThrows(IllegalArgumentException.class,()->DirectorNativeLocationImports.preflight(
            WORLD,input(SOURCE,1,2000),null,context(0),ownership,Set.of(SOURCE)));
        assertEquals("import historical identity capacity",refused.getMessage());
        assertEquals(4096,ownership.size());
    }
    @Test void existingContextOverflowIsAnExplicitFailureRatherThanAWait(){
        assertDoesNotThrow(()->DirectorNativeLocationImports.requireCompleteContextCapacity(512));
        var refused=assertThrows(IllegalStateException.class,
            ()->DirectorNativeLocationImports.requireCompleteContextCapacity(513));
        assertEquals("location import metadata context capacity 513>512",refused.getMessage());
    }
    @Test void alteredDigestCannotReachThePreparedPlan(){
        var input=input(SOURCE,1,2000);
        var altered=new LocationImport(1,WORLD,SOURCE,input.exportRevision(),new Fingerprint("a".repeat(64)),
            input.regions(),input.locations(),input.anchors());
        assertEquals("input world/digest changed",assertThrows(IllegalArgumentException.class,
            ()->DirectorNativeLocationImports.preflight(WORLD,altered,null,context(0),Map.of(),Set.of())).getMessage());
    }

    @Test void laterCutAndTickDoNotInvalidateAnOtherwiseExactPreparedPlan(){
        var prepared=context(1);var owners=Map.of(key(100),SOURCE);
        var input=input(SOURCE,2,100);var previous=input(SOURCE,1,100);
        var plan=DirectorNativeLocationImports.preflight(WORLD,input,previous,prepared,owners,Set.of(SOURCE));
        var later=new DirectorNativeLocationImports.Context(new Sequence(10),new Tick(99),prepared.memory(),
            prepared.states(),prepared.allKeys());
        assertDoesNotThrow(()->DirectorNativeLocationImports.verifyPreparedPlan(plan,WORLD,input,previous,
            prepared,later,owners,Set.of(SOURCE)));
    }
    @Test void aChangedOwnedRevisionAfterRegistryIoCannotBorrowTheOldPreparedPlan(){
        var prepared=context(1);var owners=Map.of(key(100),SOURCE);
        var input=input(SOURCE,2,100);var previous=input(SOURCE,1,100);
        var plan=DirectorNativeLocationImports.preflight(WORLD,input,previous,prepared,owners,Set.of(SOURCE));
        var old=((LocationAggregate)prepared.states().get(key(100))).value();
        var changed=new LocationAggregate(new LocationRecord(
            new AggregateHeader(1,old.header().id(),new Revision(2),new Sequence(2),new Tick(2)),
            old.locationId(),old.sourceNamespace(),old.externalId(),old.dimension(),old.bounds(),old.tags(),
            old.anchorIds(),old.importRevision(),old.active()));
        var scopes=new ScopeSet(WORLD,Set.of(new ScopeRef(ScopeKind.WORLD,WORLD.value())));
        var later=new DirectorNativeLocationImports.Context(new Sequence(2),new Tick(4),
            MemoryProjection.of(scopes,List.of(changed)),Map.of(key(100),changed),Set.of(key(100)));
        assertEquals("import actor preflight changed after registry commit",assertThrows(IllegalArgumentException.class,
            ()->DirectorNativeLocationImports.verifyPreparedPlan(plan,WORLD,input,previous,prepared,later,
                owners,Set.of(SOURCE))).getMessage());
    }
    @Test void newRuntimeIdentityAfterRegistryIoIsStillRejected(){
        var prepared=context(0);var input=input(SOURCE,1,2000);
        var plan=DirectorNativeLocationImports.preflight(WORLD,input,null,prepared,Map.of(),Set.of());
        var colliding=new AggregateKey(AggregateKind.WORLD,id(2000));
        var later=new DirectorNativeLocationImports.Context(new Sequence(2),new Tick(4),prepared.memory(),
            prepared.states(),Set.of(colliding));
        assertTrue(assertThrows(IllegalArgumentException.class,
            ()->DirectorNativeLocationImports.verifyPreparedPlan(plan,WORLD,input,null,prepared,later,
                Map.of(),Set.of(SOURCE))).getMessage().startsWith("identity collision with another origin/source"));
    }
}
