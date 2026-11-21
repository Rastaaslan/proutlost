package fr.proutlost.director.client.perception;

import fr.proutlost.director.api.model.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import java.util.*;
import java.util.function.Consumer;

/** Instantiate only in the physical-client bootstrap; all boundaries carry frozen DTOs. */
public final class ClientPerceptionLifecycle implements AutoCloseable {
    private final Minecraft minecraft;
    private final PerceptionManager manager;
    private final MinecraftPerceptionEffects effects;
    private final Consumer<Object> send;
    private ClientLevel observedLevel;
    private boolean dead;
    private IEventBus bus;
    private final Consumer<ClientTickEvent.Post> tickListener=this::tick;
    private final Consumer<ClientPlayerNetworkEvent.LoggingOut> logoutListener=e->logout();
    private final Consumer<ClientPlayerNetworkEvent.Clone> cloneListener=e->invalidate();
    private final Consumer<net.neoforged.neoforge.client.event.RenderLevelStageEvent> renderListener=this::render;
    public ClientPerceptionLifecycle(Minecraft minecraft,PerceptionManager manager,MinecraftPerceptionEffects effects,Consumer<Object> send){this.minecraft=minecraft;this.manager=manager;this.effects=effects;this.send=send;}
    public void register(IEventBus gameBus){
        Checks.require(bus==null,"already registered");bus=gameBus;
        gameBus.addListener(tickListener);gameBus.addListener(logoutListener);
        gameBus.addListener(cloneListener);gameBus.addListener(renderListener);
    }
    private void logout(){manager.disconnect();observedLevel=null;dead=false;}
    private void invalidate(){manager.invalidate();}
    public boolean suspendForAssetReload(){
        Checks.require(minecraft.isSameThread(),"client thread");manager.invalidate();return !manager.rendererFaulted();
    }
    public boolean needsSessionReset(){return manager.needsSessionReset();}
    public boolean rendererFaulted(){return manager.rendererFaulted();}
    private void render(net.neoforged.neoforge.client.event.RenderLevelStageEvent event){effects.render(event);}
    @Override public void close(){logout();if(bus!=null){bus.unregister(tickListener);bus.unregister(logoutListener);bus.unregister(cloneListener);bus.unregister(renderListener);bus=null;}}
    /** A F8 administrative identity must only exist after J06 accepted this
     * exact world/epoch and its capability reply was successfully submitted.
     * Rejected and failed sessions are never promoted to UI identity.
     */
    public boolean acceptSession(SessionReset reset) {
        Checks.require(minecraft.isSameThread(),"client thread");
        Objects.requireNonNull(reset);
        if(minecraft.level==null || minecraft.player==null)return false;
        if(!manager.session(reset,new DimensionId(minecraft.level.dimension().location().toString())))
            return false;
        observedLevel=minecraft.level;dead=!minecraft.player.isAlive();
        try {
            // The registry is intentionally empty until byte-verified assets
            // are approved. An unsuccessful send cannot enable J07 on F8.
            send.accept(VerifiedAssetNegotiation.reply(reset,effects.verifiedKinds()));
        } catch(RuntimeException sendFailure) {
            manager.disconnect();
            System.err.println("DIRECTOR_J06_RESET_NEGOTIATION_FAILED_FAIL_CLOSED");
            return false;
        }
        System.out.println("DIRECTOR_NATIVE_J06_CLIENT_RESET_ACCEPTED conn_epoch="
            +reset.connectionEpoch().value()+" visual_assets="+effects.hashes().size());
        return true;
    }
    public void receive(Object payload){Checks.require(minecraft.isSameThread(),"client thread");
        if(payload instanceof SessionReset reset)acceptSession(reset);
        else if(payload instanceof PerceptionUpsert upsert)manager.upsert(upsert);
        else if(payload instanceof PerceptionStop stop)manager.stop(stop);
        else if(payload instanceof PerceptionReset reset)manager.reset(reset);
        else throw new IllegalArgumentException("Not a perception DTO; route admin DTOs to J07");
    }
    private void tick(ClientTickEvent.Post event){
        if(observedLevel!=minecraft.level){manager.invalidate();observedLevel=minecraft.level;}
        boolean nowDead=minecraft.player==null||!minecraft.player.isAlive();if(nowDead&&!dead)manager.invalidate();dead=nowDead;
        manager.tick();if(minecraft.level!=null)effects.tick();
    }
}
