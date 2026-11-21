package fr.proutlost.quest.runtime;

import fr.proutlost.quest.interaction.DistressScan;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import java.util.*;

/** Samples actual loaded terrain once, then uses the exact algorithm exercised by JVM tests. */
public final class MinecraftDistressScan {
    private MinecraftDistressScan() {}
    public static DistressScan.Result inspect(ServerLevel level,BlockPos anchor,int minimumFires) {
        int radius=24;var cells=new HashMap<DistressScan.Point,DistressScan.Cell>();
        int surfaceMin=Integer.MAX_VALUE,surfaceMax=Integer.MIN_VALUE; boolean complete=true;
        for(int x=-radius-2;x<=radius+2;x++)for(int z=-radius-2;z<=radius+2;z++) {
            var column=anchor.offset(x,0,z);if(!level.hasChunkAt(column)){complete=false;continue;}
            int y=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,column.getX(),column.getZ())-1;
            var pos=new BlockPos(column.getX(),y,column.getZ());var state=level.getBlockState(pos);
            if(Math.abs(x)<=8&&Math.abs(z)<=8){surfaceMin=Math.min(surfaceMin,y);surfaceMax=Math.max(surfaceMax,y);}
            boolean sea=Math.abs(y-level.getSeaLevel()+1)<=2&&level.getBiome(pos).is(BiomeTags.IS_OCEAN)
                &&level.getFluidState(pos).is(FluidTags.WATER)&&level.getFluidState(pos.below(3)).is(FluidTags.WATER);
            int color=state.getMapColor(level,pos).col;
            double tone=(.2126*((color>>16)&255)+.7152*((color>>8)&255)+.0722*(color&255))/255.0;
            boolean plane=Math.abs(y-anchor.getY())<=2;
            cells.put(new DistressScan.Point(x,z),new DistressScan.Cell(true,level.canSeeSky(pos.above()),
                plane&&state.getBlock() instanceof CampfireBlock&&state.getValue(BlockStateProperties.LIT),plane&&state.is(BlockTags.WOOL),tone,sea));
        }
        boolean high=complete&&surfaceMin>=level.getSeaLevel()+24&&surfaceMax-surfaceMin<=6;
        var unknown=new DistressScan.Cell(false,false,false,false,0,false);
        return DistressScan.inspect((x,z)->cells.getOrDefault(new DistressScan.Point(x,z),unknown),radius,high,minimumFires);
    }
}
