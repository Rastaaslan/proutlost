package fr.proutlost.director.adapter.neoforge.client;

import fr.proutlost.director.adapter.neoforge.server.effects.DirectorControlledFireBlocks;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/** Client-only bootstrap. Vanilla animated fire textures; server behavior stays in the journal actor. */
public final class DirectorControlledFireClient {
 private DirectorControlledFireClient(){}
 public static void register(net.neoforged.bus.api.IEventBus bus){bus.addListener(DirectorControlledFireClient::setup);}
 private static void setup(FMLClientSetupEvent event){
  event.enqueueWork(()->ItemBlockRenderTypes.setRenderLayer(
   DirectorControlledFireBlocks.CONTROLLED_FIRE.get(),RenderType.cutout()));
 }
}
