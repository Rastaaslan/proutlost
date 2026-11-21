package fr.proutlost.worldprep.physical;

import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Reordering is finite, reproducible, complete and retains the primary/fallback phases.
 * Every primary candidate in one chunk uses an identical native verified lease.
 */
class NativeTemplateCandidateChunkLocalityTest {
    private static final String TEMPLATE="proutlost:worldprep/stone_platform_v1";
    private static final WorldPrepSavedData.Area GROUP=new WorldPrepSavedData.Area(
        "test/s2_group_0_3","minecraft:overworld",4496,-1296,4623,-1169);

    @Test void sameSeedAndConfigAlwaysUseSameChunkOrder()throws Exception {
        try(var first=new NativeTemplateSitePlanner(GROUP,TEMPLATE,"frozen-s2-group",42L);
            var second=new NativeTemplateSitePlanner(GROUP,TEMPLATE,"frozen-s2-group",42L)){
            assertEquals(first.auditCandidates(),second.auditCandidates());
        }
    }

    @Test void theEntireLegacyPositionSetIsPreservedWithoutAnyExtraCandidate()throws Exception {
        var before=new HashSet<BlockPos>();
        for(int x=GROUP.minX()>>4;x<=GROUP.maxX()>>4;x++)
            for(int z=GROUP.minZ()>>4;z<=GROUP.maxZ()>>4;z++)
                for(int dx:new int[]{2,4,6,8,10,12})
                    for(int dz:new int[]{2,4,6,8,10,12}){
                        int bx=x*16+dx,bz=z*16+dz;
                        if(bx<GROUP.minX()||bx>GROUP.maxX()||bz<GROUP.minZ()||bz>GROUP.maxZ())continue;
                        if(bx<(long)GROUP.minX()+16||bx>(long)GROUP.maxX()-18
                          ||bz<(long)GROUP.minZ()+16||bz>(long)GROUP.maxZ()-18)continue;
                        before.add(new BlockPos(bx,0,bz));
                    }
        try(var p=new NativeTemplateSitePlanner(GROUP,TEMPLATE,"frozen-s2-group",42L)){
            var actual=p.auditCandidates();
            assertEquals(1296,actual.size());
            assertEquals(before,new HashSet<>(actual));
        }
    }

    @Test void eachSearchPhaseVisitsEachChunkExactlyOnce()throws Exception {
        try(var p=new NativeTemplateSitePlanner(GROUP,TEMPLATE,"frozen-s2-group",42L)){
            var candidates=p.auditCandidates();
            assertEquals(1296,candidates.size());
            assertPhaseLocality(candidates.subList(0,324),9,36,true);
            assertPhaseLocality(candidates.subList(324,1296),27,36,false);
        }
    }

    private static void assertPhaseLocality(List<BlockPos> phase,int perChunk,int chunks,
                                            boolean primary){
        var visited=new HashSet<String>();
        String current=null;
        int run=0,transitions=0;
        for(var pos:phase) {
            boolean first=(pos.getX()&15)%4==0&&(pos.getZ()&15)%4==0;
            assertEquals(primary,first,"Primary positions must precede fallback");
            String key=(pos.getX()>>4)+","+(pos.getZ()>>4);
            if(!key.equals(current)){
                if(current!=null)assertEquals(perChunk,run,"Old chunk halo would be reloaded");
                assertTrue(visited.add(key),"Each chunk should be visited exactly once per tier");
                current=key;run=0;transitions++;
            }
            run++;
        }
        assertEquals(perChunk,run);
        assertEquals(chunks,transitions);
        assertEquals(chunks,visited.size());
    }

    @Test void everyPrimaryPositionSharesSingleChunkFootprintForThreeBlockTemplateRadius()throws Exception {
        try(var p=new NativeTemplateSitePlanner(GROUP,TEMPLATE,"frozen-s2-group",42L)){
            for(var pos:p.auditCandidates().subList(0,324)){
                assertEquals((pos.getX()-3)>>4,(pos.getX()+3)>>4);
                assertEquals((pos.getZ()-3)>>4,(pos.getZ()+3)>>4);
            }
        }
    }

    @Test void differentFrozenSeedStillRemainsDeterministicButChangesOrder()throws Exception {
        try(var a=new NativeTemplateSitePlanner(GROUP,TEMPLATE,"frozen-s2-group",42L);
            var b=new NativeTemplateSitePlanner(GROUP,TEMPLATE,"frozen-s2-group",43L)){
            assertEquals(new HashSet<>(a.auditCandidates()),new HashSet<>(b.auditCandidates()));
            assertNotEquals(a.auditCandidates(),b.auditCandidates());
        }
    }
}
