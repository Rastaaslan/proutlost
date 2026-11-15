package fr.proutlost.director.adapter.neoforge.client;

import fr.proutlost.director.adapter.neoforge.network.DirectorPayloads;
import fr.proutlost.director.adapter.neoforge.network.AdminQueryReplyFence;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.client.perception.*;
import fr.proutlost.director.admin.client.DirectorScreen;
import fr.proutlost.director.admin.client.ClientAdminReplyFence;
import fr.proutlost.director.admin.client.AdminClientReadPayloads;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Physical-client-only J06 entrypoint. No client classes are instantiated from
 * the dedicated server; the server adapter links this class only inside a
 * Dist.CLIENT branch. First SessionReset can arrive during level loading and
 * is retained until a real ClientLevel is available.
 *
 * The shipped asset manifest is verified against effective resource bytes; no effect may be rendered without
 * explicitly approved asset hashes, even if a remote server sends upserts.
 */
public final class DirectorClientPacketGateway {
    private DirectorClientPacketGateway(){}
    private static final int MAX_PENDING_ADMIN_RESULTS=64;
    private record ReloadedAssets(long generation,Map<ResourceId,MinecraftPerceptionEffects.Asset> assets) {}
    private static final AtomicLong resourceGeneration=new AtomicLong();
    private static Map<ResourceId,MinecraftPerceptionEffects.Asset> verifiedAssets=Map.of();
    private static boolean reloadRegistered,assetsReady,resourceReloadPending,awaitingFreshSession,connectionQuarantined;
    private static SessionReset lastAcceptedReset;
    private static long lastRefreshRequest=-1;
    private static final LinkedHashMap<EffectId,PerceptionHeader> recordable=new LinkedHashMap<>();
    private static DirectorPayloads channels;
    private static ClientPerceptionLifecycle lifecycle;
    private static final PendingClientReset resets=new PendingClientReset();
    private static SessionIdentity adminSession;
    private static SessionIdentity requestedUiSession;
    private static boolean priorUiKey;
    private static long clientTicks;
    private static boolean tickRegistered;
    private static AdminSnapshot lastAdmin;
    // Class initialization runs on the mod loading worker. The reply fence must
    // capture the actual Minecraft client actor, after clientThread validates it.
    private static ClientAdminReplyFence adminReplyFence;
    private static final LinkedHashMap<RequestId,AdminCommandResult> adminResults=
        new LinkedHashMap<>();

    private static void clientThread() {
        if(!Minecraft.getInstance().isSameThread())
            throw new IllegalStateException("J06 client packets must use main thread");
        if(adminReplyFence==null)adminReplyFence=new ClientAdminReplyFence(64);
    }
    /** Revoke a visible privileged page whenever its physical connection,
     * player or dimension authority changes. The Screen's onClose clears its
     * pending requests, rows and command history. */
    private static void closeAdminScreen() {
        var client=Minecraft.getInstance();
        if(client.screen instanceof DirectorScreen) client.setScreen(null);
    }
    /** Registered once on the physical-client mod bus before the first resource reload. */
    public static void register(net.neoforged.bus.api.IEventBus modBus) {
        if(reloadRegistered)throw new IllegalStateException("duplicate J06 resource reload registration");
        reloadRegistered=true;
        modBus.addListener(DirectorClientPacketGateway::registerReloadListeners);
        NeoForge.EVENT_BUS.addListener(DirectorClientPacketGateway::registerClientCommands);
    }
    private static void registerClientCommands(net.neoforged.neoforge.client.event.RegisterClientCommandsEvent event) {
        var root=net.minecraft.commands.Commands.literal("proutlost");
        root.then(net.minecraft.commands.Commands.literal("ui").requires(source->source.hasPermission(4))
            .executes(context->requestAdminScreen(context.getSource())));
        root.then(net.minecraft.commands.Commands.literal("director")
            .then(net.minecraft.commands.Commands.literal("ui").requires(source->source.hasPermission(4))
                .executes(context->requestAdminScreen(context.getSource()))));
        var record=net.minecraft.commands.Commands.literal("record").executes(c->recordPerception(c.getSource(),null,""));
        record.then(net.minecraft.commands.Commands.argument("effect",com.mojang.brigadier.arguments.StringArgumentType.word())
            .suggests((c,builder)->{recordable.keySet().forEach(id->builder.suggest(id.value().toString()));return builder.buildFuture();})
            .executes(c->recordPerception(c.getSource(),com.mojang.brigadier.arguments.StringArgumentType.getString(c,"effect"),""))
            .then(net.minecraft.commands.Commands.argument("text",com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                .executes(c->recordPerception(c.getSource(),com.mojang.brigadier.arguments.StringArgumentType.getString(c,"effect"),com.mojang.brigadier.arguments.StringArgumentType.getString(c,"text")))));
        root.then(net.minecraft.commands.Commands.literal("perception").then(record)
            .then(DirectorPersonalFactClient.command(()->adminSession,()->channels,
                ()->connectionQuarantined||resourceReloadPending||awaitingFreshSession||resets.hasPending())));
        event.getDispatcher().register(root);
        event.getDispatcher().register(net.minecraft.commands.Commands.literal("proutlostdirectorui")
            .requires(source->source.hasPermission(4)).executes(context->requestAdminScreen(context.getSource())));
    }
    private static int recordPerception(net.minecraft.commands.CommandSourceStack source,String effect,String text){
        clientThread();var mc=Minecraft.getInstance();
        if(mc.player==null||adminSession==null||channels==null||connectionQuarantined||resourceReloadPending||awaitingFreshSession||resets.hasPending()){
            source.sendFailure(net.minecraft.network.chat.Component.literal("Relevé indisponible : session actuelle requise."));return 0;
        }
        try{
            var header=effect==null?(recordable.isEmpty()?null:new ArrayList<>(recordable.values()).getLast()):recordable.get(new EffectId(UUID.fromString(effect)));
            if(header==null)throw new IllegalArgumentException();
            channels.sendToServer(new RecordObservation(header,new RecordObservationPayload(Optional.of(header.effectId()),Optional.empty(),text.isBlank()?Optional.empty():Optional.of(text))));
            source.sendSuccess(()->net.minecraft.network.chat.Component.literal("Demande envoyée ; la sauvegarde sera confirmée par le serveur."),false);return 1;
        }catch(RuntimeException unavailable){source.sendFailure(net.minecraft.network.chat.Component.literal("Relevé indisponible : perception reçue et texte de 1 000 caractères maximum requis."));return 0;}
    }
    private static void receipt(PerceptionAck ack){
        channels.sendToServer(ack); // A failed send must never advertise recordability.
        if(ack.status()!=PerceptionAckStatus.RECEIVED)return;
        var id=ack.header().effectId();boolean first=!recordable.containsKey(id);
        if(first&&recordable.size()>=128)return;
        recordable.put(id,ack.header());
        var player=Minecraft.getInstance().player;
        if(first&&player!=null)player.displayClientMessage(net.minecraft.network.chat.Component.literal("Consigner cette perception")
            .withStyle(style->style.withClickEvent(new net.minecraft.network.chat.ClickEvent(net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND,
                "/proutlost perception record "+id.value()))),false);
    }
    private static int requestAdminScreen(net.minecraft.commands.CommandSourceStack source) {
        clientThread();
        if(!canOpenAdminScreen()) {
            source.sendFailure(net.minecraft.network.chat.Component.literal(
                "Interface indisponible : session serveur absente ou accès administrateur insuffisant."));
            return 0;
        }
        // ChatScreen closes after executing a command. Open on the next client
        // tick, preserving and rechecking the exact negotiated session instead.
        requestedUiSession=adminSession;
        return 1;
    }
    private static boolean canOpenAdminScreen() {
        clientThread();var mc=Minecraft.getInstance();var identity=adminSession;
        return identity!=null&&mc.level!=null&&mc.player!=null&&channels!=null&&
            mc.player.hasPermissions(4)&&!connectionQuarantined&&!resourceReloadPending&&
            !awaitingFreshSession&&!resets.hasPending()&&lifecycle!=null&&!lifecycle.rendererFaulted()&&
            identity.playerId().equals(new PlayerId(mc.player.getUUID()))&&
            identity.dimension().equals(new DimensionId(mc.level.dimension().location().toString()));
    }
    private static boolean openAdminScreen() {
        clientThread();var mc=Minecraft.getInstance();
        if(mc.screen!=null||!canOpenAdminScreen())return false;
        var identity=adminSession;
        // New screen must not accept a reply from the previously closed F8 page.
        adminReplyFence.reset(identity);
        var screen=new DirectorScreen(
            DirectorAdminSessionKey.of(identity),identity,
            query->{
                adminReplyFence.track(query);
                try {channels.sendToServer(query);}
                catch(RuntimeException uncertain){adminReplyFence.cancel(query.requestId());throw uncertain;}
            },
            command->{
                adminReplyFence.track(command);
                try {channels.sendToServer(command);}
                catch(RuntimeException uncertain){adminReplyFence.cancel(command.requestId());throw uncertain;}
            },
            AdminClientReadPayloads::parse,
            fixture->{
                // Only a validated ResourceId enters this fixed canonical command.
                // The server resolves its own registered SnapshotBatch and authenticates OP4.
                if(!identity.equals(adminSession)||!canOpenAdminScreen()||mc.getConnection()==null)
                    throw new IllegalArgumentException("Current operator session required");
                mc.getConnection().sendCommand("proutlost director simulate "+fixture.value());
            },
            40,20,25);
        mc.setScreen(screen);
        System.out.println("DIRECTOR_NATIVE_J07_FIVE_TAB_CLIENT_SCREEN_OPEN");
        return true;
    }
    private static void registerReloadListeners(net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new net.minecraft.server.packs.resources.PreparableReloadListener(){
            @Override public CompletableFuture<Void> reload(PreparationBarrier barrier,
                    net.minecraft.server.packs.resources.ResourceManager resources,
                    net.minecraft.util.profiling.ProfilerFiller preparationProfiler,
                    net.minecraft.util.profiling.ProfilerFiller applyProfiler,
                    java.util.concurrent.Executor preparationExecutor,java.util.concurrent.Executor applyExecutor) {
                long generation=resourceGeneration.incrementAndGet();
                // ResourceManager already denotes the incoming pack set. Suspend on the
                // client actor before hashing, not after new resources become observable.
                CompletableFuture<Void> suspended;
                if(Minecraft.getInstance().isSameThread()){
                    beginAssetReload(generation);suspended=CompletableFuture.completedFuture(null);
                }else suspended=CompletableFuture.runAsync(()->beginAssetReload(generation),applyExecutor);
                return suspended.thenCompose(ignored->CompletableFuture.supplyAsync(()->new ReloadedAssets(generation,
                    PerceptionAssetLoader.prepare(resources,
                        fr.proutlost.director.adapter.neoforge.network.DirectorGameplayAssets.specs(),16_777_216)),preparationExecutor))
                    .thenCompose(barrier::wait)
                    .thenAcceptAsync(DirectorClientPacketGateway::finishAssetReload,applyExecutor);
            }
        });
    }
    private static void beginAssetReload(long generation) {
        clientThread();if(generation!=resourceGeneration.get())return;
        resourceReloadPending=true;recordable.clear();closeAdminScreen();adminSession=null;adminReplyFence.revoke();lastAdmin=null;adminResults.clear();
        if(lifecycle!=null&&!lifecycle.suspendForAssetReload()){
            disconnectUncertain("J06 renderer cleanup failed during resource reload");return;
        }
        if(lastAcceptedReset!=null)awaitingFreshSession=true;
        requestFreshSession();
    }
    private static void finishAssetReload(ReloadedAssets prepared) {
        clientThread();if(prepared.generation()!=resourceGeneration.get())return;
        boolean changed=!assetsReady||!prepared.assets().equals(verifiedAssets);
        if(resourceReloadPending&&lifecycle!=null){lifecycle.close();lifecycle=null;}
        verifiedAssets=prepared.assets();assetsReady=true;resourceReloadPending=false;
        System.out.println("DIRECTOR_J06_RESOURCE_RELOAD_VERIFIED generation="+prepared.generation()+" assets="+verifiedAssets.size()+" changed="+changed);
        attach();applyPendingReset();
    }
    private static void disconnectUncertain(String reason) {
        connectionQuarantined=true;recordable.clear();
        closeAdminScreen();adminSession=null;adminReplyFence.revoke();lastAdmin=null;adminResults.clear();
        var connection=Minecraft.getInstance().getConnection();
        if(connection!=null)connection.getConnection().disconnect(net.minecraft.network.chat.Component.literal(reason));
    }
    private static void requestFreshSession() {
        if(connectionQuarantined||!awaitingFreshSession||lastAcceptedReset==null||channels==null||resets.hasPending()
                ||lastRefreshRequest>=0&&clientTicks-lastRefreshRequest<20)return;
        lastRefreshRequest=clientTicks;
        try {channels.sendToServer(new Capabilities(1,lastAcceptedReset.connectionEpoch(),Set.of(),Map.of()));}
        catch(RuntimeException uncertain){disconnectUncertain("J06 resource authority could not be revoked");}
    }
    private static void maintainSessionReset() {
        if(lifecycle!=null&&lifecycle.needsSessionReset()&&!lifecycle.rendererFaulted()&&lastAcceptedReset!=null) {
            if(!awaitingFreshSession&&!lifecycle.suspendForAssetReload()){
                disconnectUncertain("J06 renderer cleanup failed during session renewal");return;
            }
            awaitingFreshSession=true;recordable.clear();closeAdminScreen();adminSession=null;adminReplyFence.revoke();
        }
        requestFreshSession();
    }
    private static void applyPendingReset() {
        if(connectionQuarantined||resourceReloadPending||!assetsReady)return;
        var mc=Minecraft.getInstance();attach();
        if(lifecycle!=null)resets.takeIfReady(mc.level!=null,mc.player!=null).ifPresent(reset->{
            if(lifecycle.acceptSession(reset))adminIdentity(reset);
            else {disconnectUncertain("J06 resource session could not be negotiated");}
        });
    }
    private static void initialize(DirectorPayloads channel) {
        clientThread();
        if(channels==null)channels=Objects.requireNonNull(channel);
        else if(channels!=channel)throw new IllegalStateException("J06 channel identity");
        if(tickRegistered)return;
        tickRegistered=true;
        NeoForge.EVENT_BUS.addListener(DirectorClientPacketGateway::tick);
        NeoForge.EVENT_BUS.addListener(DirectorClientPacketGateway::logout);
    }
    private static void logout(
            net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        clientThread();
        closeAdminScreen();
        // A reset waiting for player readiness belongs to the disconnected
        // socket, not to whichever world the user joins next.
        resets.clear();recordable.clear();
        // Renderer/ACK failures are quarantined for the lifetime of one
        // connection. On logout remove all four event handlers and create
        // fresh isolated client state on the next genuine connection.
        if(lifecycle!=null) {
            lifecycle.close();
            lifecycle=null;
        }
        adminSession=null;
        adminReplyFence.revoke();
        lastAdmin=null;
        adminResults.clear();
        requestedUiSession=null;
        priorUiKey=false;lastAcceptedReset=null;awaitingFreshSession=false;lastRefreshRequest=-1;connectionQuarantined=false;
    }
    private static void attach() {
        clientThread();
        var mc=Minecraft.getInstance();
        if(connectionQuarantined||mc.level==null||channels==null||lifecycle!=null)return;
        if(!assetsReady||resourceReloadPending)return;
        var effects=new MinecraftPerceptionEffects(mc,verifiedAssets,
            ()->clientTicks,256);
        var manager=new PerceptionManager(effects,
            new PerceptionManager.Limits(32,2,128,1200),
            ()->clientTicks,DirectorClientPacketGateway::receipt);
        lifecycle=new ClientPerceptionLifecycle(mc,manager,effects,
            dto->channels.sendToServer(dto));
        lifecycle.register(NeoForge.EVENT_BUS);
    }
    private static void tick(ClientTickEvent.Post event) {
        clientThread();
        clientTicks=Math.addExact(clientTicks,1);
        var mc=Minecraft.getInstance();
        if(mc.level==null) {
            closeAdminScreen();
            lastAdmin=null;adminSession=null;requestedUiSession=null;adminReplyFence.revoke();priorUiKey=false;
            return;
        }
        attach();
        applyPendingReset();
        maintainSessionReset();
        if(adminSession!=null && mc.player!=null &&
           !adminSession.dimension().equals(new DimensionId(
               mc.level.dimension().location().toString()))) {
            closeAdminScreen();
            adminSession=null;
            adminReplyFence.revoke();
        }
        boolean keyDown=InputConstants.isKeyDown(mc.getWindow().getWindow(),
            GLFW.GLFW_KEY_F8);
        var requested=requestedUiSession;requestedUiSession=null;
        if(requested!=null&&requested.equals(adminSession))openAdminScreen();
        if(keyDown&&!priorUiKey)openAdminScreen();
        priorUiKey=keyDown;
    }
    private static void adminIdentity(SessionReset reset){
        var mc=Minecraft.getInstance();
        if(mc.level==null || mc.player==null){
            adminSession=null;return;
        }
        recordable.clear();lastAcceptedReset=reset;awaitingFreshSession=false;lastRefreshRequest=-1;
        adminSession=new SessionIdentity(reset.worldId(),
            reset.bootEpoch(),reset.connectionEpoch(),
            new DimensionId(mc.level.dimension().location().toString()),
            new PlayerId(mc.player.getUUID()));
        adminReplyFence.reset(adminSession);
    }
    public static void receive(Object dto,IPayloadContext context,
                               DirectorPayloads registered) {
        clientThread();
        Objects.requireNonNull(context);
        initialize(registered);
        if(connectionQuarantined)return;
        if(dto instanceof SessionReset reset) {
            if(!resets.offer(reset))return;
            closeAdminScreen();
            adminSession=null;
            adminReplyFence.revoke();
            lastAdmin=null;
            adminResults.clear();
            // Do NOT consume just because ClientLevel exists. Minecraft may
            // not have created LocalPlayer yet, leaving F8 permanently
            // unbound for the session. Keep it until both are available.
            var minecraft=Minecraft.getInstance();
            if(minecraft.level!=null && minecraft.player!=null&&!resourceReloadPending)applyPendingReset();
        } else if(dto instanceof PerceptionUpsert ||
                  dto instanceof PerceptionStop ||
                  dto instanceof PerceptionReset) {
            // A late packet cannot create a phantom session or renderer.
            if(lifecycle!=null && Minecraft.getInstance().level!=null &&
                !resets.hasPending()&&!resourceReloadPending) {
                lifecycle.receive(dto);
                maintainSessionReset();
            }
        } else if(dto instanceof AdminSnapshot snapshot) {
            // J07 authenticated snapshots and score-only laboratory rows
            // are read-only; receipt never authorizes a new client command.
            // The DTO itself has no world/session envelope. Only a request
            // actually sent in the current negotiated connection can supply UI data.
            if(!adminReplyFence.accept(snapshot))return;
            if(adminSession!=null &&
                Minecraft.getInstance().screen instanceof DirectorScreen screen) {
                lastAdmin=snapshot;
                screen.receive(adminSession,snapshot);
            }
        } else if(dto instanceof AdminDelta delta) {
            // Out-of-order deltas must be dropped, not applied to stale UI.
            if(adminSession==null ||
               !(Minecraft.getInstance().screen instanceof DirectorScreen))return;
            if(lastAdmin!=null &&
               lastAdmin.snapshotRevision().equals(delta.fromRevision()))
                lastAdmin=null; // force full refresh rather than invent a projection
            if(adminSession!=null &&
               Minecraft.getInstance().screen instanceof DirectorScreen screen)
                screen.invalidate(adminSession,delta);
        } else if(dto instanceof AdminCommandResult result) {
            if(!adminReplyFence.accept(result))return;
            if(!adminResults.containsKey(result.commandId()) &&
               adminResults.size()>=MAX_PENDING_ADMIN_RESULTS)
                adminResults.remove(adminResults.keySet().iterator().next());
            adminResults.put(result.commandId(),result);
            if(adminSession!=null &&
                Minecraft.getInstance().screen instanceof DirectorScreen screen) {
                // A correlated query must leave PENDING on every typed
                // refusal, including a J04 projection failure or deferral.
                AdminQueryReplyFence.readFailure(result).ifPresent(reason ->
                    screen.receiveQueryFailure(adminSession,result.commandId(),reason));
                screen.receive(adminSession,result);
            }
        } else throw new IllegalArgumentException("unknown J06 client DTO");
    }
}
