package fr.proutlost.director.adapter.neoforge.server;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.bootstrap.DirectorLabCampAnchorIndex;
import fr.proutlost.director.adapter.neoforge.server.observation.CampAnchorLookupSaturatedException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTest;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Pure spatial-fail-closed contract, run inside the real standalone server.
 * Existing J01 native GameTests separately prove actual J04 publication.
 */
@GameTestHolder("proutlost_director")
@PrefixGameTestTemplate(false)
public final class DirectorLabCampAnchorIndexGameTests {
    private static final DimensionId OVERWORLD=new DimensionId("minecraft:overworld");
    private static final DimensionId NETHER=new DimensionId("minecraft:the_nether");
    private DirectorLabCampAnchorIndexGameTests(){}

    private static AnchorRecord anchor(String name,DimensionId dim,int x,int z,
            boolean active,AnchorOrigin origin){
        var id=new AnchorId(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)));
        var position=new BlockPosition(dim,x,65,z);
        return new AnchorRecord(new AggregateHeader(1,id.value(),new Revision(1),
                new Sequence(0),new Tick(200)),
            id,Optional.empty(),Set.of(),dim,position,
            "region.camp_radius_blocks",
            origin==AnchorOrigin.CAMP?Set.of(new TagId("proutlost:camp")):Set.of(),
            origin,EvidenceStatus.POSSIBLE,active);
    }
    private static Map<AnchorId,AnchorRecord> records(AnchorRecord... anchors){
        var map=new HashMap<AnchorId,AnchorRecord>();
        for(var a:anchors)if(map.put(a.anchorId(),a)!=null)
            throw new IllegalArgumentException("duplicate fixture anchor");
        return map;
    }
    private static boolean rejects(Runnable code){
        try{code.run();return false;}
        catch(IllegalArgumentException|IllegalStateException expected){return true;}
    }

    @GameTest(templateNamespace="proutlost_director",template="smoke_empty",
              timeoutTicks=150)
    public static void nativeJ01CampIndexCrossesCellEdgesAndDimensions(
            GameTestHelper helper){
        helper.succeedWhen(()->{
            var west=anchor("west",OVERWORLD,31,0,true,AnchorOrigin.CAMP);
            var east=anchor("east",OVERWORLD,95,0,true,AnchorOrigin.CAMP);
            var north=anchor("north",OVERWORLD,63,31,true,AnchorOrigin.CAMP);
            var tooFar=anchor("far",OVERWORLD,96,0,true,AnchorOrigin.CAMP);
            var sleeping=anchor("inactive",OVERWORLD,63,0,false,AnchorOrigin.CAMP);
            var foreign=anchor("nether",NETHER,63,0,true,AnchorOrigin.CAMP);
            var imported=anchor("imported",OVERWORLD,63,0,true,AnchorOrigin.IMPORTED);
            var negative=anchor("negative",OVERWORLD,-33,0,true,AnchorOrigin.CAMP);
            var all=records(west,east,north,tooFar,sleeping,foreign,imported,negative);
            var index=new DirectorLabCampAnchorIndex(all,32,64,100,64);
            var query=new BlockPosition(OVERWORLD,63,70,0);
            var found=index.nearby(query).stream().map(AnchorRecord::anchorId).toList();
            helper.assertTrue(found.size()==3 && found.containsAll(
                List.of(west.anchorId(),east.anchorId(),north.anchorId())),
                "cell edge or radius-32 mismatch");
            helper.assertTrue(found.equals(found.stream().sorted().toList()),
                "anchor enumeration not deterministic");
            helper.assertTrue(index.durableRecords()==8 &&
                index.activeCampAnchors()==6 && index.occupiedCells()>=2,
                "wrong source filter or sparse cells");
            var crossing=index.nearby(new BlockPosition(OVERWORLD,-1,140,0))
                .stream().map(AnchorRecord::anchorId).toList();
            helper.assertTrue(crossing.size()==2 &&
                crossing.containsAll(List.of(negative.anchorId(),west.anchorId())),
                "negative-coordinate grid boundary not scanned");
            helper.assertTrue(index.nearby(new BlockPosition(NETHER,63,65,0))
                .equals(List.of(foreign)), "dimension leakage");
            System.out.println("DIRECTOR_NATIVE_J01_BOUNDED_SPATIAL_ANCHOR_INDEX_PASS");
        });
    }

    @GameTest(templateNamespace="proutlost_director",template="smoke_empty",
              timeoutTicks=150)
    public static void nativeJ01CampIndexFailsClosedOnSaturation(
            GameTestHelper helper){
        helper.succeedWhen(()->{
            var one=anchor("one",OVERWORLD,0,0,true,AnchorOrigin.CAMP);
            var two=anchor("two",OVERWORLD,1,0,true,AnchorOrigin.CAMP);
            var three=anchor("three",OVERWORLD,-1,0,true,AnchorOrigin.CAMP);
            var map=records(one,two,three);
            var index=new DirectorLabCampAnchorIndex(map,32,64,3,2);
            boolean overCapacity=false;
            try {
                index.nearby(new BlockPosition(OVERWORLD,0,65,0));
            } catch(CampAnchorLookupSaturatedException safe) {
                overCapacity=true;
            }
            helper.assertTrue(overCapacity,
                "overflow must signal recoverable census saturation");
            helper.assertTrue(rejects(()->new DirectorLabCampAnchorIndex(
                map,32,64,2,2)),"too many stored anchors accepted");
            var invalid=new HashMap<>(map);
            invalid.remove(one.anchorId());
            invalid.put(new AnchorId(UUID.randomUUID()),one);
            helper.assertTrue(rejects(()->new DirectorLabCampAnchorIndex(
                invalid,32,64,8,4)),
                "foreign J04 anchor id accepted");
            var quarantined=records(one,two);
            var healthy=new DirectorLabCampAnchorIndex(quarantined,32,64,3,3);
            helper.assertTrue(healthy.nearby(new BlockPosition(OVERWORLD,0,65,0))
                .size()==2,"saturated index poisoned independent publication");
            System.out.println("DIRECTOR_NATIVE_J01_CAMP_INDEX_OVERFLOW_FAIL_CLOSED_PASS");
        });
    }
}
