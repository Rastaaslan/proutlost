package fr.proutlost.quest.interaction;

import java.util.*;

/** Bounded server scan. Unknown/unloaded cells are ineligible; no world generation. */
public final class DistressScan {
    public record Point(int x,int z) {}
    public record Cell(boolean loaded, boolean sky, boolean litFire, boolean marker,
                       double luminance, boolean coastalWater) {}
    public interface Grid { Cell cell(int x,int z); }
    public record Result(boolean readable, boolean coastal, boolean highPoint) {
        public boolean valid(){return readable&&(coastal||highPoint);}
    }
    private static final List<String> SOS=List.of("11101110111","10001010100","11101010111","00101010001","11101110111");
    private DistressScan() {}
    public static Result inspect(Grid grid,int radius,boolean highPoint,int minimumFires) {
        if(radius<8||radius>64||minimumFires<3)throw new IllegalArgumentException();
        var fires=new ArrayList<Point>(); var water=new HashSet<Point>();
        for(int x=-radius;x<=radius;x++)for(int z=-radius;z<=radius;z++) {
            var c=grid.cell(x,z); if(!c.loaded())continue;
            if(c.sky()&&c.litFire())fires.add(new Point(x,z));
            if(c.coastalWater())water.add(new Point(x,z));
        }
        boolean coast=false;
        while(!water.isEmpty()) {
            var queue=new ArrayDeque<Point>();var first=water.iterator().next();water.remove(first);queue.add(first);
            int count=0,minX=first.x(),maxX=first.x(),minZ=first.z(),maxZ=first.z();boolean boundary=false;
            while(!queue.isEmpty()) {
                var p=queue.removeFirst();count++;minX=Math.min(minX,p.x());maxX=Math.max(maxX,p.x());minZ=Math.min(minZ,p.z());maxZ=Math.max(maxZ,p.z());
                boundary|=Math.abs(p.x())==radius||Math.abs(p.z())==radius;
                for(var next:List.of(new Point(p.x()+1,p.z()),new Point(p.x()-1,p.z()),new Point(p.x(),p.z()+1),new Point(p.x(),p.z()-1)))
                    if(water.remove(next))queue.add(next);
            }
            coast|=boundary&&count>=64&&Math.max(maxX-minX,maxZ-minZ)>=16;
        }
        boolean readable=firePattern(fires,minimumFires);
        if(!readable)outer:for(int x=-radius;x<=radius-10;x++)for(int z=-radius;z<=radius-10;z++)for(int rotation=0;rotation<4;rotation++)
            if(sos(grid,x,z,rotation)){readable=true;break outer;}
        return new Result(readable,coast,highPoint);
    }
    private static boolean firePattern(List<Point> fires,int minimum) {
        if(fires.size()<minimum)return false;
        // Separate smoke columns in an intentional evenly spaced line (horizontal or diagonal).
        var points=new HashSet<>(fires);
        for(var start:fires)for(int dx=-8;dx<=8;dx++)for(int dz=-8;dz<=8;dz++) {
            int distance=dx*dx+dz*dz;if(distance<9||distance>64)continue;
            boolean match=true;
            for(int i=1;i<minimum;i++)if(!points.contains(new Point(start.x()+i*dx,start.z()+i*dz))){match=false;break;}
            if(match)return true;
        }
        return false;
    }
    private static boolean sos(Grid grid,int ox,int oz,int rotation) {
        double min=1,max=0;Double markerTone=null;
        // Border and spaces must be known, clear background; letters must contrast with it.
        for(int x=-1;x<=11;x++)for(int z=-1;z<=5;z++) {
            int rx=switch(rotation){case 0->x;case 1->z;case 2->10-x;default->4-z;};
            int rz=switch(rotation){case 0->z;case 1->10-x;case 2->4-z;default->x;};
            var c=grid.cell(ox+rx,oz+rz);if(!c.loaded()||!c.sky())return false;
            boolean ink=x>=0&&x<11&&z>=0&&z<5&&SOS.get(z).charAt(x)=='1';
            if(ink){if(!c.marker())return false;if(markerTone==null)markerTone=c.luminance();if(Math.abs(markerTone-c.luminance())>.1)return false;}
            else {if(c.marker()||c.litFire())return false;min=Math.min(min,c.luminance());max=Math.max(max,c.luminance());}
        }
        return markerTone!=null&&(markerTone-max>=.35||min-markerTone>=.35);
    }
}
