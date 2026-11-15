package fr.proutlost.director.platform;

import com.mojang.serialization.Codec;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;

/** TEST_ONLY compile probe against MC 1.21.1 / NeoForge 21.1.248. Never registered or executed. */
public final class PinnedApiProbe {
 private PinnedApiProbe() {}
 record ProbePayload(long sequence) implements CustomPacketPayload {
  static final Type<ProbePayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("director_test","probe"));
  static final StreamCodec<RegistryFriendlyByteBuf,ProbePayload> CODEC=StreamCodec.of((buffer,value)->buffer.writeVarLong(value.sequence()),buffer->new ProbePayload(buffer.readVarLong()));
  public Type<ProbePayload> type(){return TYPE;}
 }
 static void networking(RegisterPayloadHandlersEvent event,ServerPlayer target){
  event.registrar("1").optional().executesOn(HandlerThread.MAIN).playToClient(ProbePayload.TYPE,ProbePayload.CODEC,(payload,context)->context.enqueueWork(()->{}));
  PacketDistributor.sendToPlayer(target,new ProbePayload(1));
 }
 static void attachment(LevelChunk chunk){AttachmentType<Long> type=AttachmentType.builder(()->0L).serialize(Codec.LONG).build();chunk.setData(type,1L);chunk.setUnsaved(true);}
 static final class Projection extends SavedData {
  @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries){tag.putLong("generation",1);return tag;}
 }
 static Projection storage(MinecraftServer server){Projection value=server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(Projection::new,(tag,registries)->new Projection()),"director_test");value.setDirty();return value;}
 static boolean tick(ServerTickEvent.Post event){return event.getServer().isSameThread();}
 static boolean rendering(RenderLevelStageEvent event){return event.getStage()==RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS&&event.getPoseStack()!=null&&event.getCamera()!=null;}
}
