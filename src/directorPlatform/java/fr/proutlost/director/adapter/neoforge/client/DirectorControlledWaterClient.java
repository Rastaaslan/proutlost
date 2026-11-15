package fr.proutlost.director.adapter.neoforge.client;

import fr.proutlost.director.adapter.neoforge.server.effects.DirectorControlledWater;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

/** DRAFT physical-client-only hook; call register only inside the existing Dist.CLIENT branch. */
public final class DirectorControlledWaterClient {
 private DirectorControlledWaterClient(){}
 public static void register(IEventBus modBus){
  modBus.addListener(DirectorControlledWaterClient::extensions);
  modBus.addListener(DirectorControlledWaterClient::setup);
 }
 private static void extensions(RegisterClientExtensionsEvent event){
  event.registerFluidType(new IClientFluidTypeExtensions(){
   @Override public ResourceLocation getStillTexture(){return ResourceLocation.parse("minecraft:block/water_still");}
   @Override public ResourceLocation getFlowingTexture(){return ResourceLocation.parse("minecraft:block/water_flow");}
   @Override public ResourceLocation getOverlayTexture(){return ResourceLocation.parse("minecraft:block/water_overlay");}
   @Override public ResourceLocation getRenderOverlayTexture(Minecraft minecraft){return ResourceLocation.parse("minecraft:textures/misc/underwater.png");}
   @Override public int getTintColor(){return 0xFF3F76E4;}
   @Override public int getTintColor(FluidState state,BlockAndTintGetter level,BlockPos pos){
    return 0xFF000000|BiomeColors.getAverageWaterColor(level,pos);
   }
  },DirectorControlledWater.type());
 }
 private static void setup(FMLClientSetupEvent event){
  event.enqueueWork(()->{
   ItemBlockRenderTypes.setRenderLayer(DirectorControlledWater.source(),RenderType.translucent());
   ItemBlockRenderTypes.setRenderLayer(DirectorControlledWater.flowing(),RenderType.translucent());
  });
 }
}
