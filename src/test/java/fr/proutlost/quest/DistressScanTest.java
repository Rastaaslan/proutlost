package fr.proutlost.quest;

import fr.proutlost.quest.interaction.DistressScan;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DistressScanTest {
    static class Grid implements DistressScan.Grid {
        final Map<DistressScan.Point,DistressScan.Cell> cells=new HashMap<>();
        public DistressScan.Cell cell(int x,int z){return cells.getOrDefault(new DistressScan.Point(x,z),new DistressScan.Cell(true,true,false,false,.1,false));}
        void fire(int x,int z){cells.put(new DistressScan.Point(x,z),new DistressScan.Cell(true,true,true,false,.1,false));}
        void water(int min,int max){for(int x=min;x<=max;x++)for(int z=-24;z<=24;z++)cells.put(new DistressScan.Point(x,z),new DistressScan.Cell(true,true,false,false,.1,true));}
    }
    @Test void nearbyPoolAndUnreadableWoolPileNeverValidate() {
        var g=new Grid();for(int x=0;x<4;x++)for(int z=0;z<4;z++)g.cells.put(new DistressScan.Point(x,z),new DistressScan.Cell(true,true,false,true,(x+z)%2==0?.9:.1,false));
        g.cells.put(new DistressScan.Point(5,5),new DistressScan.Cell(true,true,false,false,.1,true));
        assertFalse(DistressScan.inspect(g,24,false,3).valid());
        g.fire(-8,0);g.fire(-4,0);g.fire(0,0);assertFalse(DistressScan.inspect(g,24,false,3).valid());
    }
    @Test void spacedVisibleFiresAtVerifiedCoastSucceedButClusterFails() {
        var g=new Grid();g.water(16,24);g.fire(0,0);g.fire(1,0);g.fire(2,0);
        assertFalse(DistressScan.inspect(g,24,false,3).valid());
        g.fire(4,0);g.fire(8,0);assertTrue(DistressScan.inspect(g,24,false,3).valid());
        g.cells.put(new DistressScan.Point(4,0),new DistressScan.Cell(true,false,true,false,.1,false));
        assertFalse(DistressScan.inspect(g,24,false,3).valid());
    }
    @Test void readableContrastingSosWorksAtHighPointWithoutWater() {
        var g=new Grid();var rows=List.of("11101110111","10001010100","11101010111","00101010001","11101110111");
        for(int z=0;z<5;z++)for(int x=0;x<11;x++)if(rows.get(z).charAt(x)=='1')g.cells.put(new DistressScan.Point(x,z),new DistressScan.Cell(true,true,false,true,.9,false));
        assertTrue(DistressScan.inspect(g,24,true,3).valid());assertFalse(DistressScan.inspect(g,24,false,3).valid());
        g.cells.put(new DistressScan.Point(0,0),new DistressScan.Cell(false,false,false,false,0,false));
        assertFalse(DistressScan.inspect(g,24,true,3).valid());
    }
}
