package fr.proutlost.worldprep.validation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Local, conservative walking evidence only; unknown terrain is never traversable. */
public final class SpawnAccess {
    public record Position(int x,int y,int z) {}
    public record Column(int x,int z,int floorY,boolean safe) {}
    public record Result(String status,List<Position> reachable) {
        public Result {reachable=List.copyOf(reachable);}
    }
    public static final Set<String> SAFE_FLOORS=Set.of("minecraft:grass_block","minecraft:dirt","minecraft:coarse_dirt",
            "minecraft:podzol","minecraft:stone","minecraft:cobblestone","minecraft:deepslate","minecraft:sand","minecraft:gravel");
    public static Result validate(Position spawn,List<Column> input) {
        if(input.size()>256)throw new IllegalArgumentException("Spawn check exceeds tile budget");
        var columns=new HashMap<Long,Column>();
        for(var c:input)if(columns.put(key(c.x(),c.z()),c)!=null)throw new IllegalArgumentException("Duplicate column");
        var start=columns.get(key(spawn.x(),spawn.z()));
        if(start==null)return new Result("OUTSIDE_TILE_UNCHECKED",List.of());
        if(!start.safe()||(long)start.floorY()+1!=spawn.y())return new Result("UNSAFE_OR_UNKNOWN",List.of());
        var queue=new ArrayDeque<Column>();var seen=new HashSet<Long>();var reached=new ArrayList<Position>();
        queue.add(start);seen.add(key(start.x(),start.z()));
        int[][] steps={{0,-1},{-1,0},{1,0},{0,1}};
        while(!queue.isEmpty()){
            var c=queue.remove();reached.add(new Position(c.x(),c.floorY()+1,c.z()));
            for(var step:steps){
                var n=columns.get(key(c.x()+step[0],c.z()+step[1]));
                if(n!=null&&n.safe()&&Math.abs((long)n.floorY()-c.floorY())<=1&&seen.add(key(n.x(),n.z())))queue.add(n);
            }
        }
        return new Result(reached.size()>=16?"LOCAL_ACCESS_VERIFIED_GLOBAL_ROUTE_UNCHECKED":"INSUFFICIENT_LOCAL_ACCESS",reached);
    }
    private static long key(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}
    private SpawnAccess() {}
}
