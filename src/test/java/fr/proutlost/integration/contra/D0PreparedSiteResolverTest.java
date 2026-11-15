package fr.proutlost.integration.contra;

import fr.proutlost.contract.director.*;
import fr.proutlost.director.api.model.Aabb;
import fr.proutlost.director.api.model.BlockPosition;
import fr.proutlost.director.api.model.DimensionId;
import fr.proutlost.director.api.model.Footprint;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static fr.proutlost.contract.director.Vocabulary.Comparison;

/** Pure semantic guards only; these are not Minecraft/J04/effect qualification. */
class D0PreparedSiteResolverTest {
    private static final Id DIM=new Id("minecraft:overworld");
    private static final DimensionId MC_DIM=new DimensionId(DIM.value());
    private static Bounds box(int x,int max){return new Bounds(DIM,new BlockPos(x,64,0),new BlockPos(max,70,7));}
    @Test void multiGeometryDoesNotGrantAuthorityOverTheHoleBetweenBoxes(){
        var geometry=List.of(box(0,7),box(12,19));
        var inside=new Footprint(MC_DIM,Set.of(),Set.of(new BlockPosition(MC_DIM,2,65,2),new BlockPosition(MC_DIM,14,65,2)),List.of());
        var hole=new Footprint(MC_DIM,Set.of(),Set.of(new BlockPosition(MC_DIM,9,65,2)),List.of());
        assertTrue(D0PreparedSiteResolver.contained(geometry,inside));assertFalse(D0PreparedSiteResolver.contained(geometry,hole));
    }
    @Test void inclusiveD0BoundsBecomeExactExclusiveBlockExtentWithoutAnExtraBlock(){
        var geometry=List.of(box(0,7));
        assertTrue(D0PreparedSiteResolver.contained(geometry,new Footprint(MC_DIM,Set.of(),Set.of(),List.of(new Aabb(MC_DIM,0,64,0,8,71,8)))));
        assertFalse(D0PreparedSiteResolver.contained(geometry,new Footprint(MC_DIM,Set.of(),Set.of(),List.of(new Aabb(MC_DIM,0,64,0,9,71,8)))));
    }
    @Test void anUnboundedGlobalEffectDoesNotBorrowALocalPreparedSitesAuthority(){
        assertFalse(D0PreparedSiteResolver.contained(List.of(box(0,7)),new Footprint(MC_DIM,Set.of(),Set.of(),List.of())));
    }
    private static TypedValue capacity(long n){return new TypedValue(new Id("proutlost:group_arrival"),new Revision("1"),Map.of("capacity",new TypedValue.Number(n)));}
    @Test void typedCapacityPredicateHasAnActualInclusiveNumericRange(){
        var range=new TypedValue(new Id("proutlost:range"),new Revision("1"),Map.of("min",new TypedValue.Number(2),"max",new TypedValue.Number(4)));
        var predicate=new Predicate(new Id("proutlost:capacity"),Comparison.RANGE,range);
        assertTrue(D0PreparedSiteResolver.matches(capacity(2),predicate));assertTrue(D0PreparedSiteResolver.matches(capacity(4),predicate));
        assertFalse(D0PreparedSiteResolver.matches(capacity(1),predicate));assertFalse(D0PreparedSiteResolver.matches(capacity(5),predicate));
    }
    @Test void unknownPredicateFieldOrWrongTypeNeverDisappearsFromEligibility(){
        var number=new TypedValue(new Id("proutlost:integer"),new Revision("1"),Map.of("value",new TypedValue.Number(2)));
        assertFalse(D0PreparedSiteResolver.matches(capacity(2),new Predicate(new Id("proutlost:missing"),Comparison.EQ,number)));
        var text=new TypedValue(new Id("proutlost:text"),new Revision("1"),Map.of("value",new TypedValue.Text("2")));
        assertFalse(D0PreparedSiteResolver.matches(capacity(2),new Predicate(new Id("proutlost:capacity"),Comparison.EQ,text)));
        assertFalse(D0PreparedSiteResolver.matches(capacity(2),new Predicate(new Id("foreign:capacity"),Comparison.EQ,number)));
    }
    @Test void presentationChannelPredicateConsumesTheWholeTypedNamesSet(){
        var current=new TypedValue(new Id("proutlost:routine_memory"),new Revision("1"),Map.of("channels",new TypedValue.Names(List.of("SOUND","SYMBOL"))));
        var sound=new TypedValue(new Id("proutlost:routine_memory"),new Revision("1"),Map.of("channels",new TypedValue.Names(List.of("SOUND"))));
        var apparition=new TypedValue(new Id("proutlost:routine_memory"),new Revision("1"),Map.of("channels",new TypedValue.Names(List.of("NONPHYSICAL_APPARITION"))));
        assertTrue(D0PreparedSiteResolver.matches(current,new Predicate(new Id("proutlost:channels"),Comparison.CONTAINS_ALL,sound)));
        assertFalse(D0PreparedSiteResolver.matches(current,new Predicate(new Id("proutlost:channels"),Comparison.CONTAINS_ALL,apparition)));
    }
    @Test void immutablePolicyDescriptorsCanCompileOffActorAndUnknownPoliciesStayUnknown(){
        var ids=new HashSet<>(Set.of(new Id("test:access")));var map=new EnumMap<D0PreparedSiteResolver.PolicyDomain,Set<Id>>(D0PreparedSiteResolver.PolicyDomain.class);
        map.put(D0PreparedSiteResolver.PolicyDomain.ACCESS,ids);var support=new D0PreparedSiteResolver.PolicySupport(map);ids.add(new Id("test:late"));map.clear();
        assertTrue(support.supports(new Id("test:access"),D0PreparedSiteResolver.PolicyDomain.ACCESS));
        assertFalse(support.supports(new Id("test:late"),D0PreparedSiteResolver.PolicyDomain.ACCESS));
        assertFalse(support.supports(new Id("test:access"),D0PreparedSiteResolver.PolicyDomain.PRESENTATION));
    }
}
