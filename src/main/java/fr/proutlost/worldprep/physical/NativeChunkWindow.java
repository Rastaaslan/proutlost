package fr.proutlost.worldprep.physical;

import fr.proutlost.worldprep.runtime.ExistingChunkLoader;
import fr.proutlost.worldprep.world.*;
import net.minecraft.server.level.ServerLevel;
import java.util.*;

/** Qualification-owned leases, never ExistingChunkLoader.ensure()/ACTIVE. */
public final class NativeChunkWindow implements AutoCloseable {
    private static final Map<ServerLevel,Set<NativeChunkWindow>> OWNERS=new IdentityHashMap<>();
    public static void closeAll(ServerLevel level){
        NativePhysicalRuntime.requireThread(level);RuntimeException failure=null;
        for(var owner:List.copyOf(OWNERS.getOrDefault(level,Set.of())))try{owner.close();}catch(RuntimeException e){if(failure==null)failure=e;else failure.addSuppressed(e);}
        if(failure!=null)throw failure;
    }
    private final ServerLevel level;private final VerifiedChunkWindow window;
    public NativeChunkWindow(ServerLevel level){NativePhysicalRuntime.requireThread(level);this.level=level;OWNERS.computeIfAbsent(level,k->Collections.newSetFromMap(new IdentityHashMap<>())).add(this);window=new VerifiedChunkWindow(p->ExistingChunkLoader.openVerifiedLease(level,p.x(),p.z()));}
    public boolean blocks(int minX,int minZ,int maxX,int maxZ){
        NativePhysicalRuntime.requireThread(level);
        if(minX>maxX||minZ>maxZ||(long)(maxX>>4)-(minX>>4)>2||(long)(maxZ>>4)-(minZ>>4)>2){close();throw new IllegalArgumentException("REFUSED: qualification footprint exceeds 3x3 centers");}
        var centers=new ArrayList<ExistingChunkLease.Position>();
        for(int x=minX>>4;x<=maxX>>4;x++)for(int z=minZ>>4;z<=maxZ>>4;z++)centers.add(new ExistingChunkLease.Position(x,z));
        return window.advance(centers);
    }
    public boolean structure(NativeStructurePass.Plan plan){
        int minX=Integer.MAX_VALUE,minZ=minX,maxX=Integer.MIN_VALUE,maxZ=maxX;
        for(var c:plan.cells()){minX=Math.min(minX,c.x());minZ=Math.min(minZ,c.z());maxX=Math.max(maxX,c.x());maxZ=Math.max(maxZ,c.z());}
        for(var c:plan.supports()){minX=Math.min(minX,c.x());minZ=Math.min(minZ,c.z());maxX=Math.max(maxX,c.x());maxZ=Math.max(maxZ,c.z());}
        return blocks(minX,minZ,maxX,maxZ);
    }
    public boolean fauna(NativeFaunaPass.Plan plan){return blocks(plan.area().minX(),plan.area().minZ(),plan.area().maxX(),plan.area().maxZ());}
    public int ownedLeases(){return window.ownedLeases();}
    public void release(){NativePhysicalRuntime.requireThread(level);window.release();}
    public void close(){NativePhysicalRuntime.requireThread(level);try{window.close();}finally{var owners=OWNERS.get(level);if(owners!=null){owners.remove(this);if(owners.isEmpty())OWNERS.remove(level);}}}
}
