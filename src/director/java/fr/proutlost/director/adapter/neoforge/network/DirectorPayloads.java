package fr.proutlost.director.adapter.neoforge.network;

import fr.proutlost.director.api.model.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.HandlerThread;
import java.util.*;
import java.util.function.BiConsumer;

/** Composition calls register on the mod bus, supplying handlers for frozen DTOs.
 * Contains no client class references; no handler is silently installed as a no-op. */
public final class DirectorPayloads {
    public record Packet<T>(CustomPacketPayload.Type<Packet<T>> type, T value) implements CustomPacketPayload {}
    public record Channel<T>(Class<T> dto, boolean clientbound, CustomPacketPayload.Type<Packet<T>> type, WireCodec<T> wire) {
        public StreamCodec<RegistryFriendlyByteBuf,Packet<T>> codec() { return new StreamCodec<>() {
            public Packet<T> decode(RegistryFriendlyByteBuf buf) {
                // Fixed length prefix avoids unbounded transport allocation and VarInt overflow aliases.
                int length=buf.readInt(); Checks.require(length>=0 && length<=wire.maximum() && length==buf.readableBytes(),"payload frame");
                byte[] bytes=new byte[length]; buf.readBytes(bytes); return new Packet<>(type,wire.decode(bytes));
            }
            public void encode(RegistryFriendlyByteBuf buf,Packet<T> packet) {
                Checks.require(packet.type().equals(type),"channel mismatch"); byte[] bytes=wire.encode(packet.value()); buf.writeInt(bytes.length); buf.writeBytes(bytes);
            }
        }; }
    }
    private final Map<Class<?>,Channel<?>> channels;
    public DirectorPayloads(int s2cBytes,int c2sBytes) {
        Checks.require(s2cBytes<=24576 && c2sBytes<=8192,"transport ceilings");
        Map<Class<?>,Channel<?>> m=new LinkedHashMap<>();
        add(m,SessionReset.class,true,"session_reset",s2cBytes); add(m,Capabilities.class,false,"capabilities",c2sBytes);
        add(m,PerceptionUpsert.class,true,"perception_upsert",s2cBytes); add(m,PerceptionStop.class,true,"perception_stop",s2cBytes);
        add(m,PerceptionReset.class,true,"perception_reset",s2cBytes); add(m,PerceptionAck.class,false,"perception_ack",c2sBytes);
        add(m,RecordObservation.class,false,"record_observation",c2sBytes); add(m,AnchorInteraction.class,false,"anchor_interaction",c2sBytes);
        add(m,AdminQuery.class,false,"admin_query",c2sBytes); add(m,AdminCommand.class,false,"admin_command",c2sBytes);
        add(m,AdminSnapshot.class,true,"admin_snapshot",s2cBytes); add(m,AdminCommandResult.class,true,"admin_result",s2cBytes); add(m,AdminDelta.class,true,"admin_delta",s2cBytes);
        channels=Collections.unmodifiableMap(m);
    }
    private static <T> void add(Map<Class<?>,Channel<?>> m,Class<T> dto,boolean client,String id,int max) {
        m.put(dto,new Channel<>(dto,client,new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("proutlost_director",id)),new WireCodec<>(dto,max-4)));
    }
    public void register(RegisterPayloadHandlersEvent event,BiConsumer<Object,IPayloadContext> client,BiConsumer<Object,IPayloadContext> server) {
        Objects.requireNonNull(client); Objects.requireNonNull(server);
        var registrar=event.registrar("1.0.0").executesOn(HandlerThread.MAIN);
        for(var c:channels.values()) registerOne(registrar,c,client,server);
    }
    private static <T> void registerOne(net.neoforged.neoforge.network.registration.PayloadRegistrar r,Channel<T> c,BiConsumer<Object,IPayloadContext> client,BiConsumer<Object,IPayloadContext> server) {
        if(c.clientbound())r.playToClient(c.type(),c.codec(),(p,ctx)->client.accept(p.value(),ctx));
        else r.playToServer(c.type(),c.codec(),(p,ctx)->server.accept(p.value(),ctx));
    }
    @SuppressWarnings("unchecked") public <T> Channel<T> channel(Class<T> dto) {
        Channel<?> c=channels.get(dto); Checks.require(c!=null,"unknown payload"); return (Channel<T>)c;
    }
    private <T> Packet<T> packet(T dto,boolean client) {
        @SuppressWarnings("unchecked") Channel<T> c=(Channel<T>)channel(dto.getClass());
        Checks.require(c.clientbound()==client,"payload direction"); return new Packet<>(c.type(),dto);
    }
    public void send(ServerPlayer player,Object dto) { Checks.require(player.server.isSameThread(),"server thread"); PacketDistributor.sendToPlayer(player,packet(dto,true)); }
    public void sendToServer(Object dto) { PacketDistributor.sendToServer(packet(dto,false)); }
}
