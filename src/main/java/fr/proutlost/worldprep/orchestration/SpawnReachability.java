package fr.proutlost.worldprep.orchestration;

import java.util.*;

/** Conservative walking graph, never a claim of arbitrary parkour or aquatic accessibility.
 * Native observations must establish stable support and three clear air blocks per node.
 */
public final class SpawnReachability {
    public record Cell(int x,int y,int z) {}
    public record Result(int reachable,Set<String> exits) {public Result {exits=Set.copyOf(exits);}}
    public static Result verify(int radius,int verticalRange,int minimumReachable,Collection<Cell> observations) {
        if(radius<2||radius>4||verticalRange<0||verticalRange>3||minimumReachable<5||minimumReachable>(2*radius+1)*(2*radius+1))
            throw new IllegalArgumentException("Bounded spawn graph required");
        var columns=new HashMap<String,Cell>();
        if(observations.size()>(2*radius+1)*(2*radius+1))throw new IllegalArgumentException("Spawn observation bound");
        for(var p:observations) {
            if(Math.abs((long)p.x())>radius||Math.abs((long)p.z())>radius||Math.abs((long)p.y())>verticalRange
                ||columns.put(p.x()+","+p.z(),p)!=null)throw new IllegalArgumentException("Ambiguous/outside spawn column");
        }
        var start=new Cell(0,0,0);
        if(!start.equals(columns.get("0,0")))throw new IllegalStateException("Spawn support/clearance unsafe");
        int[][] directions={{1,0},{-1,0},{0,1},{0,-1}};
        for(var d:directions) {
            var adjacent=columns.get(d[0]+","+d[1]);
            if(adjacent==null||Math.abs(adjacent.y())>1)throw new IllegalStateException("Immediate spawn fall/water/obstruction hazard");
        }
        var seen=new HashSet<Cell>();var queue=new ArrayDeque<Cell>();seen.add(start);queue.add(start);
        var exits=new TreeSet<String>();
        while(!queue.isEmpty()) {
            var cell=queue.removeFirst();
            if(cell.x()==-radius)exits.add("WEST");if(cell.x()==radius)exits.add("EAST");
            if(cell.z()==-radius)exits.add("NORTH");if(cell.z()==radius)exits.add("SOUTH");
            for(var d:directions) {
                var next=columns.get((cell.x()+d[0])+","+(cell.z()+d[1]));
                if(next!=null&&Math.abs(next.y()-cell.y())<=1&&seen.add(next))queue.addLast(next);
            }
        }
        if(seen.size()<minimumReachable||exits.size()!=4)throw new IllegalStateException("Spawn lacks connected walking exits on all four sides");
        return new Result(seen.size(),exits);
    }
    private SpawnReachability(){}
}
