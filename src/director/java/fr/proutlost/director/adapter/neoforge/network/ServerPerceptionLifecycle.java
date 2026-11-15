package fr.proutlost.director.adapter.neoforge.network;

import fr.proutlost.director.api.model.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** One instance per server, closed on shutdown. Durable boot epoch is supplied by J04/bootstrap. */
public final class ServerPerceptionLifecycle implements AutoCloseable {
    private final MinecraftServer server;
    private final PerceptionTransport transport;
    private IEventBus bus;
    private final DeferredDeathCleanup deathCleanup;
    private final java.util.function.Consumer<PlayerEvent.PlayerLoggedInEvent> login=e->open(e.getEntity());
    private final java.util.function.Consumer<PlayerEvent.PlayerLoggedOutEvent> logout=e->closePlayer(e.getEntity());
    private final java.util.function.Consumer<PlayerEvent.PlayerRespawnEvent> respawn=e->open(e.getEntity());
    private final java.util.function.Consumer<PlayerEvent.PlayerChangedDimensionEvent> dimension=e->open(e.getEntity());
    private final java.util.function.Consumer<LivingDeathEvent> death=this::observeDeath;
    private final java.util.function.Consumer<net.neoforged.neoforge.event.tick.ServerTickEvent.Post> postTick=this::afterTick;
    private final java.util.function.Consumer<ServerStoppedEvent> shutdown=this::stopped;
    public ServerPerceptionLifecycle(MinecraftServer server,PerceptionTransport transport){this.server=server;this.transport=transport;this.deathCleanup=new DeferredDeathCleanup(transport);}
    public void register(IEventBus gameBus){Checks.require(bus==null,"already registered");bus=gameBus;bus.addListener(login);bus.addListener(logout);bus.addListener(respawn);bus.addListener(dimension);bus.addListener(true,death);bus.addListener(postTick);bus.addListener(shutdown);}
    private void open(net.minecraft.world.entity.Entity entity){if(entity instanceof ServerPlayer p&&p.server==server)transport.open(new PlayerId(p.getUUID()),new DimensionId(p.level().dimension().location().toString()));}
    private void observeDeath(LivingDeathEvent event){
        if(event.getEntity() instanceof ServerPlayer player&&player.server==server)
            deathCleanup.observe(new PlayerId(player.getUUID()),()->!event.isCanceled()&&!player.isAlive());
    }
    private void afterTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event){
        if(event.getServer()!=server)return;deathCleanup.drain();
        for(var player:server.getPlayerList().getPlayers())
            if(transport.readyForSessionReset(new PlayerId(player.getUUID())))try{open(player);}
            catch(RuntimeException uncertain){System.out.println("DIRECTOR_J06_RESET_SEND_FAILED_SAFE");}
    }
    private void closePlayer(net.minecraft.world.entity.Entity entity){if(entity instanceof ServerPlayer p&&p.server==server){var id=new PlayerId(p.getUUID());deathCleanup.forget(id);transport.close(id);}}
    private void stopped(ServerStoppedEvent event){if(event.getServer()==server)close();}
    @Override public void close(){if(bus==null)return;for(var p:server.getPlayerList().getPlayers())transport.close(new PlayerId(p.getUUID()));bus.unregister(login);bus.unregister(logout);bus.unregister(respawn);bus.unregister(dimension);bus.unregister(death);bus.unregister(postTick);bus.unregister(shutdown);deathCleanup.clear();bus=null;}
}
