package fr.proutlost.integration.physical;
import fr.proutlost.contract.director.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
/** Shared physical observation only: no Director, writer, capability or narrative authority. */
public final class NativeLoadedCellWitness {
 private NativeLoadedCellWitness(){}
 public static final Id VALIDATOR=new Id("proutlost:native_loaded_block_state_v1");
    public static Optional<Digest> stateFingerprint(MinecraftServer server,List<Bounds> geometry){
        if(!server.isSameThread())throw new IllegalStateException("native D0 observation actor");
        try{
            long cells=0;var md=MessageDigest.getInstance("SHA-256");
            for(var b:geometry){
                cells=Math.addExact(cells,Math.multiplyExact(Math.multiplyExact((long)b.max().x()-b.min().x()+1,(long)b.max().y()-b.min().y()+1),(long)b.max().z()-b.min().z()+1));
                if(cells>32768)return Optional.empty();ServerLevel level=null;
                for(var l:server.getAllLevels())if(l.dimension().location().toString().equals(b.dimension_id().value()))level=l;
                if(level==null)return Optional.empty();
                for(int x=b.min().x();x<=b.max().x();x++)for(int z=b.min().z();z<=b.max().z();z++){
                    if(!level.hasChunk(x>>4,z>>4))return Optional.empty();
                    for(int y=b.min().y();y<=b.max().y();y++){
                        var pos=new BlockPos(x,y,z);
                        if(!level.isInWorldBounds(pos)||level.getBlockEntity(pos)!=null)return Optional.empty();
                        String row=b.dimension_id().value()+"/"+x+"/"+y+"/"+z+"/"+level.getBlockState(pos)+"\n";
                        md.update(row.getBytes(StandardCharsets.UTF_8));
                    }
                }
            }
            return cells==0?Optional.empty():Optional.of(new Digest(HexFormat.of().formatHex(md.digest())));
        }catch(ArithmeticException overflow){return Optional.empty();}
        catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
}
