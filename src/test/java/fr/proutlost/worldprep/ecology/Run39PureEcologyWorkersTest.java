package fr.proutlost.worldprep.ecology;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class Run39PureEcologyWorkersTest {
    private static final List<String> PASSES=List.of("SOILS","FLORA","AQUATIC");
    private static List<BoundedEcologyCandidatePool.Column> sample(int minX,int minZ) {
        var result=new ArrayList<BoundedEcologyCandidatePool.Column>();
        for(int z=minZ;z<minZ+16;z++)for(int x=minX;x<minX+16;x++) {
            int base=50+(int)Math.floorMod((long)x*31+(long)z*7,80);
            int depth=(int)Math.floorMod((long)x*13+(long)z*19,19);
            result.add(new BoundedEcologyCandidatePool.Column(x,z,base+depth,base,
                    Math.floorMod(x*17L+z*11L,23)==0));
        }
        return List.copyOf(result);
    }
    private static List<BoundedEcologyCandidatePool.Candidate> oracle(
            long seed,String pass,List<BoundedEcologyCandidatePool.Column> cols) {
        var expected=new ArrayList<BoundedEcologyCandidatePool.Candidate>();
        for(var c:cols) {
            if(c.protectedColumn())continue;
            for(int y=-64;y<320;y++) {
                boolean chosen=switch(pass) {
                    case "SOILS" -> y==c.floorY() && c.surfaceY()==c.floorY();
                    case "FLORA" -> y==c.surfaceY()+1 && c.surfaceY()==c.floorY()
                            && VegetationPlanner.flora(seed,c.x(),c.z(),.28,1);
                    case "AQUATIC" -> y==c.floorY()+1
                            && c.surfaceY()-c.floorY()>=2 && c.surfaceY()-c.floorY()<=16
                            && VegetationPlanner.unit(seed,c.x(),c.z(),123)<.30;
                    default -> throw new AssertionError("Bad pass");
                };
                if(chosen)expected.add(new BoundedEcologyCandidatePool.Candidate(c.x(),y,c.z()));
            }
        }
        return List.copyOf(expected);
    }
    private static List<BoundedEcologyCandidatePool.Candidate> actual(
            long seed,String pass,List<BoundedEcologyCandidatePool.Column> columns,int workers) {
        var ticket=BoundedEcologyCandidatePool.submit(seed,pass,columns,workers);
        long cutoff=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(!BoundedEcologyCandidatePool.ready(ticket)&&System.nanoTime()<cutoff)Thread.yield();
        assertTrue(BoundedEcologyCandidatePool.ready(ticket),"Worker queue failed to drain");
        return BoundedEcologyCandidatePool.collect(ticket);
    }
    private static String sha(List<BoundedEcologyCandidatePool.Candidate> candidates)throws Exception {
        var md=MessageDigest.getInstance("SHA-256");
        for(var c:candidates)md.update((c.x()+","+c.y()+","+c.z()+"\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(md.digest());
    }
    @Test @Timeout(30) void workers1248MatchIndependentSerialVoxelOracleAndSHA()throws Exception {
        for(var cols:List.of(sample(-8,-8),sample(1600,3200),sample(-4096,255)))
            for(long seed:List.of(0L,1L,-2323L,98765432101L))
                for(String pass:PASSES) {
                    var expected=oracle(seed,pass,cols);
                    assertEquals(expected,BoundedEcologyCandidatePool.sequential(seed,pass,cols));
                    var digest=sha(expected);
                    for(int n:List.of(1,2,4,8)) {
                        var got=actual(seed,pass,cols,n);
                        assertEquals(expected,got,"Worker candidate identity for "+pass+" "+n);
                        assertEquals(digest,sha(got),"Content-addressed hash differs");
                        assertTrue(got.size()<=256);
                    }
                }
    }
    @Test @Timeout(20) void zeroCandidatesRemainExactlyEmptyEvenWithEightWorkers()throws Exception {
        var cols=new ArrayList<BoundedEcologyCandidatePool.Column>();
        for(int z=0;z<16;z++)for(int x=0;x<16;x++)
            cols.add(new BoundedEcologyCandidatePool.Column(x,z,80,75,true));
        for(String pass:PASSES)for(int workers:List.of(1,2,4,8)) {
            assertEquals(List.of(),actual(99,pass,cols,workers));
            assertEquals(sha(List.of()),sha(actual(99,pass,cols,workers)));
        }
    }
    @Test void rejectsInvalidWorkerCountDuplicateOutOfOrderAndOversizedSnapshots() {
        var c=sample(0,0);
        assertThrows(IllegalArgumentException.class,()->BoundedEcologyCandidatePool.submit(0,"TREES",c,4));
        assertThrows(IllegalArgumentException.class,()->BoundedEcologyCandidatePool.submit(0,"SOILS",c,0));
        assertThrows(IllegalArgumentException.class,()->BoundedEcologyCandidatePool.submit(0,"SOILS",c,9));
        var overflow=new ArrayList<>(c);overflow.add(c.getFirst());
        assertThrows(IllegalArgumentException.class,()->BoundedEcologyCandidatePool.submit(0,"SOILS",overflow,4));
        assertThrows(IllegalArgumentException.class,()->BoundedEcologyCandidatePool.submit(0,"SOILS",List.of(c.get(1),c.get(0)),4));
        assertThrows(IllegalArgumentException.class,()->BoundedEcologyCandidatePool.submit(0,"SOILS",List.of(c.get(0),c.get(0)),4));
        assertThrows(IllegalArgumentException.class,()->BoundedEcologyCandidatePool.submit(0,"SOILS",null,4));
    }
    @Test @Timeout(20) void twoSimultaneousDifferentWorldInputsDoNotMixOutputs() {
        var a=sample(-8,-8);var b=sample(400,650);
        var first=BoundedEcologyCandidatePool.submit(-12,"AQUATIC",a,4);
        var second=BoundedEcologyCandidatePool.submit(20261028L,"FLORA",b,8);
        long cutoff=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while((!BoundedEcologyCandidatePool.ready(first)||!BoundedEcologyCandidatePool.ready(second))
                &&System.nanoTime()<cutoff)Thread.yield();
        assertEquals(oracle(-12,"AQUATIC",a),BoundedEcologyCandidatePool.collect(first));
        assertEquals(oracle(20261028L,"FLORA",b),BoundedEcologyCandidatePool.collect(second));
    }
}
