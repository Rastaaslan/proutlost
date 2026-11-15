package fr.proutlost.director.adapter.neoforge.network;

import fr.proutlost.director.neoforge.DirectorStandaloneMod;
import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.adapter.neoforge.client.DirectorClientPacketGateway;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Native J06 handshake adapter. Registers the frozen 13 DTO channel family
 * on the actual NeoForge mod bus. Boot epoch comes ONLY from recovered J04,
 * never the wall clock or a hand-assigned epoch. Client content remains
 * unapproved/empty; no visual effect or gameplay is admitted.
 */
public final class DirectorNativeNetworkBootstrap {
    private DirectorNativeNetworkBootstrap(){}
    private static final DirectorPayloads CHANNELS = new DirectorPayloads(24576,8192);
    private static final int MAX_CLIENTS=64;
    private record PersonalJournal(fr.proutlost.director.persistence.JournalStore.RetainedFactSnapshot snapshot,
                                   SessionIdentity identity,FactId after,String cursor,long lastReadTick) {}


    private static final class ServerSession {
        final WorldId world;
        final Epoch boot;
        final PerceptionTransport transport;
        final Map<PlayerId,DimensionId> dimensions=new HashMap<>();
        final Map<PlayerId,FactId> pendingRecords=new HashMap<>();
        final DirectorPersonalFactReceipts factReceipts=new DirectorPersonalFactReceipts(MAX_CLIENTS,128);
        final Map<PlayerId,SessionIdentity> journalReads=new HashMap<>();
        final Map<PlayerId,PersonalJournal> personalJournals=new HashMap<>();
        final Map<PlayerId,Long> recordLast=new HashMap<>(),recordReplyLast=new HashMap<>();
        final Map<PlayerId,Long> journalLastRead=new HashMap<>();
        long effectSequence;
        long negotiations;
        long rejected;
        ServerSession(MinecraftServer server,WorldId world,Epoch boot) {
            this.world=world;
            this.boot=boot;
            this.transport=new PerceptionTransport(world,boot,DirectorGameplayAssets.kinds(),DirectorGameplayAssets.hashes(),
                new PerceptionTransport.Limits(MAX_CLIENTS,32,10,1200,128,2,40),
                server::getTickCount,
                (player,payload)->{
                    var actual=server.getPlayerList().getPlayer(player.value());
                    if(actual==null)throw new IllegalStateException(
                        "J06 cannot send to disconnected player");
                    CHANNELS.send(actual,payload);
                });
        }
    }

    public record Status(boolean payloadsRegistered,boolean j04Bound,
                         int clientSessions,long negotiatedPackets,long rejectedPackets,
                         boolean effectsAuthorized) {
        public Status {
            if(clientSessions<0||negotiatedPackets<0||rejectedPackets<0||
                effectsAuthorized)throw new IllegalArgumentException("unqualified J06");
        }
    }
    private static final Map<MinecraftServer,ServerSession> SESSIONS=
        new IdentityHashMap<>();
    private static boolean payloadsRegistered;

    /** A negotiated client does not retain J06 authority if the underlying
     * J04 WAL session becomes unavailable between ticks. Never trust only the
     * cached boot epoch or a transport handshake when serving F8/perception.
     */
    private static boolean hasLiveWal(MinecraftServer server,ServerSession session) {
        if(session==null)return false;
        try {
            return DirectorStandaloneMod.directorLifecycle().verifiedBootEpoch(server)
                .filter(session.boot::equals).isPresent();
        } catch(RuntimeException writerFault) {
            return false;
        }
    }
    private static void revoke(MinecraftServer server) {
        var stale=SESSIONS.remove(server);
        if(stale==null)return;
        for(var player:List.copyOf(stale.dimensions.keySet()))
            stale.transport.close(player);
        stale.dimensions.clear();stale.personalJournals.clear();stale.factReceipts.clear();
        System.out.println("DIRECTOR_NATIVE_J06_WAL_AUTHORITY_REVOKED_FAILED_SAFE");
    }

    public static void install(IEventBus modBus,IEventBus gameBus) {
        Objects.requireNonNull(modBus);
        Objects.requireNonNull(gameBus);
        modBus.addListener(DirectorNativeNetworkBootstrap::registerPayloads);
        gameBus.addListener(DirectorNativeNetworkBootstrap::tick);
        gameBus.addListener(DirectorNativeNetworkBootstrap::logout);
        gameBus.addListener(DirectorNativeNetworkBootstrap::dimension);
        gameBus.addListener(DirectorNativeNetworkBootstrap::respawn);
        gameBus.addListener(DirectorNativeNetworkBootstrap::stopped);
        gameBus.addListener(DirectorNativeNetworkBootstrap::registerPerceptionCommands);
    }

    /** All J07 replies, including denials, traverse the same ambiguous-send
     * quarantine as perception packets. A dropped player must negotiate fresh
     * F8/J06 authority; the Minecraft actor must not unwind on a socket fault.
     */
    private static boolean deliverAdmin(ServerSession session, ServerPlayer player, Object reply) {
        if(!hasLiveWal(player.server, session)) {
            revoke(player.server);
            return false;
        }
        var id=new PlayerId(player.getUUID());
        try {
            boolean sent=DirectorAdminReplyDispatch.send(
                ()->CHANNELS.send(player,reply),
                ()->{session.dimensions.remove(id);session.personalJournals.remove(id);session.factReceipts.clear(id);session.transport.close(id);});
            if(!sent) {
                session.rejected++;
                System.out.println("DIRECTOR_NATIVE_J07_REPLY_DELIVERY_QUARANTINED");
            }
            return sent;
        } catch(RuntimeException revocationFault) {
            // If even per-player revocation fails, invalidate EVERY server
            // session rather than retaining uncertain F8/read authority.
            revoke(player.server);
            System.out.println("DIRECTOR_NATIVE_J07_REPLY_REVOKE_FAILED_SAFE");
            return false;
        }
    }

    /** Live server-derived J07 authority; a deop/reconnect cannot retain the old session. */
    public static Optional<ActorIdentity> adminActor(MinecraftServer server,SessionId wanted){
        if(!server.isSameThread())throw new IllegalStateException("J07 authority actor");
        var session=SESSIONS.get(server);if(!hasLiveWal(server,session))return Optional.empty();
        for(var player:server.getPlayerList().getPlayers()){
            var id=new PlayerId(player.getUUID());var identity=session.transport.identity(id);
            if(identity.isPresent()&&PerceptionTransport.adminSession(identity.get()).equals(wanted))
                return Optional.of(new ActorIdentity(wanted,ActorKind.PLAYER,Optional.of(id),Optional.empty(),
                    effectivePermission(player),true));
        }
        return Optional.empty();
    }
    private static int effectivePermission(ServerPlayer player){for(int level=4;level>0;level--)if(player.hasPermissions(level))return level;return 0;}
    public static Optional<ActorIdentity> adminActorForPlayer(MinecraftServer server,PlayerId player){
        if(!server.isSameThread())throw new IllegalStateException("J07 authority actor");
        var state=SESSIONS.get(server);if(!hasLiveWal(server,state))return Optional.empty();
        return state.transport.identity(player).flatMap(identity->adminActor(server,PerceptionTransport.adminSession(identity)));
    }
    private static void controlReply(MinecraftServer server,PlayerId player,AdminCommand command,AdminResult result){
        var session=SESSIONS.get(server);var actual=server.getPlayerList().getPlayer(player.value());
        if(actual==null||!hasLiveWal(server,session))return;
        var identity=session.transport.identity(player);
        if(identity.isEmpty()||!PerceptionTransport.adminSession(identity.get()).equals(command.actorSession()))return;
        if(!actual.hasPermissions(4))result=new AdminResult(AdminStatus.REJECTED,command.requestId(),
            command.expectedRevision(),Optional.empty(),Optional.of(new SystemError(ReasonCode.AUTH_DENIED,
            Severity.WARNING,false,Optional.empty(),Set.of(),new CorrelationId(command.requestId().value()),SafeState.UNCHANGED)));
        deliverAdmin(session,actual,fr.proutlost.director.admin.server.AdminFacade.commandResult(result));
    }
    private static AdminSnapshot controlCapabilities(MinecraftServer server,AdminSnapshot snapshot){
        if(!DirectorStandaloneMod.directorLifecycle().labGameplay(server)
            .map(g->g.adminControlAvailable()).orElse(false))return snapshot;
        var capabilities=new HashSet<>(snapshot.capabilities());capabilities.add(AdminOperation.PAUSE);capabilities.add(AdminOperation.RESUME);
        return new AdminSnapshot(snapshot.requestId(),snapshot.snapshotRevision(),snapshot.capturedTick(),
            snapshot.lastDurableSequence(),snapshot.rows(),snapshot.nextCursor(),capabilities);
    }
    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        CHANNELS.register(event,DirectorNativeNetworkBootstrap::clientbound,
            DirectorNativeNetworkBootstrap::serverbound);
        payloadsRegistered=true;
        System.out.println("DIRECTOR_NATIVE_J06_REGISTERED_13_DTO_CHANNELS");
    }
    private static void clientbound(Object dto,IPayloadContext context) {
        // Separate client-only class resolves only on the physical client.
        if(!FMLEnvironment.dist.isClient())
            throw new IllegalStateException("J06 client packet on dedicated server");
        DirectorClientPacketGateway.receive(dto,context,CHANNELS);
    }
    private static void serverbound(Object dto,IPayloadContext context) {
        if(!(context.player() instanceof ServerPlayer player))
            throw new IllegalArgumentException("J06 unauthenticated sender");
        MinecraftServer server=player.server;
        if(!server.isSameThread())throw new IllegalStateException("J06 actor");
        var session=SESSIONS.get(server);
        if(!hasLiveWal(server,session)) {
            revoke(server);
            return; // No stale J07/F8 read or handshake after J04 writer loss.
        }
        var id=new PlayerId(player.getUUID());
        if(session.transport.identity(id).isEmpty()){
            session.rejected++;
            return;
        }
        if(dto instanceof Capabilities capability) {
            if(session.transport.capabilities(id,capability)) {
                session.negotiations++;
                System.out.println("DIRECTOR_NATIVE_J06_CAPABILITIES_ACCEPTED accepted="
                    +session.negotiations+" active_clients="+session.dimensions.size()
                    +" approved_effects=0");
            } else {
                session.rejected++;
                if(session.transport.identity(id).isEmpty())session.factReceipts.clear(id);
            }
        } else if(dto instanceof PerceptionAck ack) {
            if(!session.transport.acknowledge(id,ack))session.rejected++;
        } else if(dto instanceof RecordObservation record) {
            recordPerception(session,player,record);
        } else if(dto instanceof AnchorInteraction interaction) {
            var gameplay=DirectorStandaloneMod.directorLifecycle().runtimeGameplay(server);
            if(gameplay.isEmpty()||!session.transport.received(id,interaction.header())){
                session.rejected++;recordReply(session,player,"Interaction refusée : perception actuelle reçue requise.");return;
            }
            try{recordReply(session,player,gameplay.orElseThrow().selectChoicePacket(player,interaction));}
            catch(RuntimeException stale){session.rejected++;recordReply(session,player,"Interaction refusée : offre, portée ou rôle indisponible.");}
        } else if(dto instanceof AdminCommand command) {
            // Never process a network-supplied permission or claimed session.
            // Existing PerceptionTransport admin quotas apply even while all
            // mutations are disabled; otherwise a privileged client can spam
            // indefinitely many J07 receipts on the server actor.
            var identity=session.transport.identity(id).orElseThrow();
            if(!player.hasPermissions(4)) {
                // The F8 client must receive a correlated denial instead of
                // retaining an unresolvable PENDING command. Rate-limit this
                // public rejection without evaluating any privileged action.
                if(session.transport.admin(id,PerceptionTransport.adminSession(identity)))
                    deliverAdmin(session,player,new AdminCommandResult(command.requestId(),
                        AdminStatus.REJECTED,List.of(ReasonCode.AUTH_DENIED),
                        Optional.empty(),Optional.empty()));
                session.rejected++;
                return;
            }
            var reason=!PerceptionTransport.adminSession(identity)
                    .equals(command.actorSession())
                ? ReasonCode.AUTH_DENIED
                : !session.transport.admin(id,command.actorSession())
                    ? ReasonCode.RATE_LIMITED : null;
            if(reason!=null) {
                session.rejected++;
                deliverAdmin(session,player,new AdminCommandResult(command.requestId(),
                    AdminStatus.REJECTED,List.of(reason),
                    Optional.empty(),Optional.empty()));
                return;
            }
            var production=DirectorStandaloneMod.directorLifecycle().productionRuntime(server);
            if(production.isPresent()){
                var actual=production.get().identity(command.actorSession());
                if(actual.isEmpty()){deliverAdmin(session,player,new AdminCommandResult(command.requestId(),AdminStatus.REJECTED,List.of(ReasonCode.AUTH_DENIED),Optional.empty(),Optional.empty()));return;}
                final java.util.concurrent.CompletionStage<AdminResult> future;
                try{future=production.get().admin().submit(command,actual.get());}
                catch(RuntimeException unavailable){deliverAdmin(session,player,new AdminCommandResult(command.requestId(),AdminStatus.DEFERRED,List.of(ReasonCode.STALE_CONTEXT),Optional.empty(),Optional.empty()));return;}
                if(!future.toCompletableFuture().isDone())deliverAdmin(session,player,new AdminCommandResult(command.requestId(),AdminStatus.PENDING,List.of(),Optional.of(command.expectedRevision()),Optional.empty()));
                future.whenComplete((result,error)->{
                    Runnable publish=()->controlReply(server,id,command,error==null&&result!=null?result:
                        fr.proutlost.director.admin.server.AdminFacade.failure(command,AdminStatus.DEFERRED,ReasonCode.DELIVERY_UNCERTAIN));
                    if(server.isSameThread())publish.run();else server.execute(publish);
                });return;
            }
            if(command.operation()==AdminOperation.PAUSE||command.operation()==AdminOperation.RESUME){
                var gameplay=DirectorStandaloneMod.directorLifecycle().labGameplay(server);
                if(gameplay.isEmpty()){
                    deliverAdmin(session,player,new AdminCommandResult(command.requestId(),AdminStatus.DEFERRED,
                        List.of(ReasonCode.STALE_CONTEXT),Optional.empty(),Optional.empty()));return;
                }
                var future=gameplay.get().submitAdminControl(command);
                if(!future.toCompletableFuture().isDone())deliverAdmin(session,player,new AdminCommandResult(
                    command.requestId(),AdminStatus.PENDING,List.of(),Optional.of(command.expectedRevision()),Optional.empty()));
                future.whenComplete((result,error)->{
                    Runnable publish=()->{
                        var reply=result;
                        if(error!=null||reply==null)reply=new AdminResult(AdminStatus.DEFERRED,command.requestId(),
                            command.expectedRevision(),Optional.empty(),Optional.of(new SystemError(
                            ReasonCode.DELIVERY_UNCERTAIN,Severity.WARNING,true,Optional.empty(),Set.of(),
                            new CorrelationId(command.requestId().value()),SafeState.DEFERRED)));
                        controlReply(server,id,command,reply);
                    };
                    if(server.isSameThread())publish.run();else server.execute(publish);
                });
                return;
            }
            // Only bounded J04-backed read commands are reachable from the
            // real F8 client. All unqualified mutating commands are refused,
            // even for operator 4, without constructing an effect permit.
            AdminCommandResult reply;
            try {
                var read=fr.proutlost.director.admin.server.NativeReadCommandFence
                    .query(command);
                var snapshot=read.flatMap(q->
                    fr.proutlost.director.bootstrap.DirectorNativeAdminReadBridge
                        .query(server,identity,true,q));
                reply=fr.proutlost.director.admin.server.NativeReadCommandFence
                    .answer(command,snapshot);
            } catch(RuntimeException failedSafe) {
                reply=fr.proutlost.director.admin.server.NativeReadCommandFence
                    .rejected(command,AdminStatus.FAILED_SAFE,
                        ReasonCode.INTERNAL_FAULT);
            }
            if(reply.status()!=AdminStatus.SUCCEEDED) session.rejected++;
            deliverAdmin(session,player,reply);
        } else if(dto instanceof AdminQuery query) {
            var identity=session.transport.identity(id).orElseThrow();
            if(!player.hasPermissions(4)) {
                // Non-operators may see F8 before permission discovery; do
                // not leave its initial read PENDING forever. Send at most
                // one small correlated denial per authenticated 40-tick
                // query window. No J04 projection or privileged data read.
                if(session.transport.adminQuery(id,PerceptionTransport.adminSession(identity)))
                    deliverAdmin(session,player,new AdminCommandResult(query.requestId(),
                        AdminStatus.REJECTED,List.of(ReasonCode.AUTH_DENIED),
                        Optional.empty(),Optional.empty()));
                session.rejected++;
                return;
            }
            // J07 WAL reads are not cheap: impose the existing authenticated
            // 40-tick refresh fence BEFORE any J04 resident page is touched.
            // A rejected query is correlated to the actual request ID so the
            // physical client's five-tab UI never hangs on a dropped packet.
            var denial=!PerceptionTransport.adminSession(identity)
                    .equals(query.actorSession())
                ? ReasonCode.AUTH_DENIED
                : !session.transport.adminQuery(id,query.actorSession())
                    ? ReasonCode.RATE_LIMITED : null;
            if(denial!=null) {
                session.rejected++;
                deliverAdmin(session,player,new AdminCommandResult(query.requestId(),
                    AdminStatus.REJECTED,List.of(denial),
                    Optional.empty(),Optional.empty()));
                return;
            }
            var production=DirectorStandaloneMod.directorLifecycle().productionRuntime(server);
            if(production.isPresent()){
                try{
                    var actor=production.get().identity(query.actorSession()).orElseThrow();
                    var result=production.get().admin().query(query,actor);
                    if(result.status()==AdminStatus.SUCCEEDED)deliverAdmin(session,player,
                        fr.proutlost.director.admin.server.AdminFacade.snapshot(query,result,production.get().capabilities()));
                    else deliverAdmin(session,player,new AdminCommandResult(query.requestId(),result.status(),result.error().map(e->List.of(e.code())).orElseGet(()->List.of(ReasonCode.STALE_CONTEXT)),Optional.empty(),Optional.empty()));
                }catch(RuntimeException unavailable){deliverAdmin(session,player,new AdminCommandResult(query.requestId(),AdminStatus.DEFERRED,List.of(ReasonCode.STALE_CONTEXT),Optional.empty(),Optional.empty()));}
                return;
            }
            // J04 may become stale or throw during a recovery/projection fault.
            // Never let an authenticated network read unwind the Minecraft actor,
            // leak WAL internals, or publish unexpected capabilities.
            var reply=AdminQueryReplyFence.read(query,
                ()->fr.proutlost.director.bootstrap.DirectorNativeAdminReadBridge
                    .query(server,identity,true,query));
            if(reply.snapshot().isPresent()) {
                var snapshot=controlCapabilities(server,reply.snapshot().orElseThrow());
                if(deliverAdmin(session,player,snapshot))
                    System.out.println("DIRECTOR_NATIVE_J07_WAL_AUTH_READ"+
                        " panel="+query.panel()+" rows="+snapshot.rows().size());
            } else {
                session.rejected++;
                deliverAdmin(session,player,reply.rejection().orElseThrow());
            }
        } else {
            // Unknown C2S input never becomes an action or a journal entry.
            session.rejected++;
        }
    }
    private static void perceptionReply(ServerPlayer player,String text){
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(text));
    }
    private static void recordReply(ServerSession session,ServerPlayer player,String text){
        var id=new PlayerId(player.getUUID());long now=player.server.getTickCount();var last=session.recordReplyLast.get(id);
        if(last==null||now-last>=20){session.recordReplyLast.put(id,now);perceptionReply(player,text);}
    }
    private static void publishRecordedFact(MinecraftServer server,DirectorFact fact){
        try{net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new fr.proutlost.director.integration.quest.DirectorCommittedFactEvent(server,fact));}
        catch(RuntimeException adapterUnavailable){System.out.println("DIRECTOR_J06_RECORD_BRIDGE_DEFERRED");}
    }
    /** Actual targeted chat emission; exact C2S return acknowledges this RECORD envelope only. */
    private static void personalFactReply(ServerSession session,ServerPlayer player,DirectorFact fact,String text){
        var id=new PlayerId(player.getUUID());var identity=session.transport.identity(id).orElse(null);
        if(identity==null)return;
        final boolean offered;
        try{offered=session.factReceipts.emit(session.transport,identity,fact,()->session.effectSequence=Math.incrementExact(session.effectSequence),header->
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(text)
                .append(net.minecraft.network.chat.Component.literal(" [Consigner ce relevé]")
                    .withStyle(style->style.withInsertion(DirectorPersonalFactReceipts.command(fact.factId(),header))
                        .withClickEvent(new net.minecraft.network.chat.ClickEvent(
                        net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND,DirectorPersonalFactReceipts.command(fact.factId(),header)))))));}
        catch(RuntimeException unavailable){
            session.factReceipts.clear(id);session.personalJournals.remove(id);session.dimensions.remove(id);session.transport.close(id);
            System.out.println("DIRECTOR_J06_PERSONAL_RECORD_EMISSION_FAILED_SAFE");return;
        }
        if(!offered){
            perceptionReply(player,text);
            recordReply(session,player,"Référence de consignation indisponible dans cette session ; le relevé reste conservé.");
        }
    }
    private static void recordPerception(ServerSession session,ServerPlayer player,RecordObservation request){
        var id=new PlayerId(player.getUUID());
        var identity=session.transport.identity(id).orElseThrow();
        final DirectorFact[] receivedFact={null};
        boolean valid=session.transport.record(id,request,f->{
            var writer=DirectorStandaloneMod.directorLifecycle().runtimePerceptionStore(player.server).orElse(null);
            if(writer==null)return false;
            try{
                var source=session.factReceipts.source(session.transport,identity,request,writer::retainedFact);
                if(source.isEmpty())return false;
                receivedFact[0]=source.orElseThrow();return true;
            }catch(RuntimeException unavailable){return false;}
        },accepted->{
            long now=player.server.getTickCount();var last=session.recordLast.get(id);
            if(last!=null&&now-last<20){recordReply(session,player,"Relevé trop rapproché ; réessayez dans un instant.");return;}
            session.recordLast.put(id,now);
            if(session.pendingRecords.containsKey(id)){perceptionReply(player,"Un relevé est déjà en attente de sauvegarde.");return;}
            if(receivedFact[0]!=null){
                // Already the durable personal observation: no append and no Quest publication/progression.
                if(!DirectorRecordedPerception.sameReference(receivedFact[0],accepted)){
                    perceptionReply(player,"Ce relevé existe avec un contenu différent ; il reste inchangé.");return;
                }
                personalFactReply(session,player,receivedFact[0],"Relevé déjà conservé : "+receivedFact[0].factId().value());
                if(session.transport.identity(id).filter(identity::equals).isPresent())
                    System.out.println("DIRECTOR_J06_PERSONAL_FACT_REFERENCE_ACCEPTED no_append=true no_quest_publish=true new_renderer_receipt=false authorities="+session.transport.rememberedHeaders(id));
                return;
            }
            var writer=DirectorStandaloneMod.directorLifecycle().runtimePerceptionStore(player.server).orElse(null);
            var actual=session.transport.receivedEffect(id,accepted.header()).orElse(null);
            if(writer==null||actual==null){perceptionReply(player,"Relevé indisponible : provenance ou sauvegarde absente.");return;}
            try{
                var fact=DirectorRecordedPerception.fact(id,accepted,actual,System.currentTimeMillis());
                var prior=writer.retainedFact(fact.factId());
                if(prior.isPresent()){
                    if(!DirectorRecordedPerception.sameRequest(prior.orElseThrow(),id,accepted,actual)){
                        perceptionReply(player,"Ce relevé existe avec un contenu différent ; il reste inchangé.");return;
                    }
                    publishRecordedFact(player.server,prior.orElseThrow());
                    personalFactReply(session,player,prior.orElseThrow(),"Relevé déjà conservé : "+fact.factId().value());return;
                }
                var bound=writer.session();
                var batch=fr.proutlost.director.runtime.ResultTransaction.batch(new TransactionId(UUID.randomUUID()),bound.storeGeneration(),Map.of(),List.of(new DurableFact(fact)),List.of());
                session.pendingRecords.put(id,fact.factId());
                writer.append(batch).whenComplete((ack,error)->{
                    Runnable publish=()->{
                        session.pendingRecords.remove(id,fact.factId());
                        boolean durable=error==null&&ack!=null&&ack.ackStatus()==AckStatus.DURABLE&&bound.equals(writer.session())
                            &&ack.transactionId().equals(batch.transactionId())&&ack.transactionHash().equals(batch.transactionHash())
                            &&ack.storeGeneration().equals(bound.storeGeneration())&&ack.durableSequence().value()>0&&ack.permitAttempt().isEmpty()
                            &&ack.forcedBlobs().equals(DurableBlobReferences.collect(batch.records()))
                            &&SESSIONS.get(player.server)==session&&hasLiveWal(player.server,session);
                        if(durable)publishRecordedFact(player.server,fact);
                        var current=player.server.getPlayerList().getPlayer(id.value());
                        if(current!=null&&session.transport.identity(id).filter(identity::equals).isPresent()){
                            if(durable)personalFactReply(session,current,fact,"Relevé conservé : "+fact.factId().value());
                            else perceptionReply(current,"Sauvegarde du relevé non confirmée ; consultez le journal avant de réessayer.");
                        }
                    };
                    if(player.server.isSameThread())publish.run();else player.server.execute(publish);
                });
            }catch(RuntimeException unavailable){session.pendingRecords.remove(id);perceptionReply(player,"Relevé non confirmé ; aucune nouvelle connaissance publiée.");}
        });
        if(!valid){session.rejected++;recordReply(session,player,"Relevé refusé : perception reçue dans la session actuelle et débit autorisé requis.");}
    }
    private static void registerPerceptionCommands(net.neoforged.neoforge.event.RegisterCommandsEvent event){
        var journal=net.minecraft.commands.Commands.literal("journal").executes(c->perceptionJournal(c.getSource(),Optional.empty()));
        journal.then(net.minecraft.commands.Commands.argument("cursor",com.mojang.brigadier.arguments.StringArgumentType.word())
            .executes(c->perceptionJournal(c.getSource(),Optional.of(com.mojang.brigadier.arguments.StringArgumentType.getString(c,"cursor")))));
        event.getDispatcher().register(net.minecraft.commands.Commands.literal("proutlost")
            .then(net.minecraft.commands.Commands.literal("perception").then(journal)));
    }
    private static int perceptionJournal(net.minecraft.commands.CommandSourceStack source,Optional<String> cursor){
        if(!(source.getEntity() instanceof ServerPlayer player))return 0;
        var server=player.server;var session=SESSIONS.get(server);var id=new PlayerId(player.getUUID());
        if(!hasLiveWal(server,session)||session.transport.identity(id).isEmpty()){perceptionReply(player,"Journal indisponible : session actuelle requise.");return 0;}
        long now=server.getTickCount();var last=session.journalLastRead.get(id);
        if(session.journalReads.containsKey(id)||last!=null&&now-last<40){perceptionReply(player,"Lecture déjà en attente ou trop rapprochée.");return 0;}
        var writer=DirectorStandaloneMod.directorLifecycle().runtimePerceptionStore(server).orElse(null);
        if(writer==null)return 0;
        var identity=session.transport.identity(id).orElseThrow();var bound=writer.session();
        final java.util.concurrent.CompletionStage<Optional<fr.proutlost.director.persistence.JournalStore.RetainedFactPage>> reading;
        if(cursor.isPresent()){
            var selected=session.personalJournals.get(id);
            if(selected==null||!selected.cursor().equals(cursor.orElseThrow())||!selected.identity().equals(identity)
                    ||!selected.snapshot().session().equals(bound)||now-selected.lastReadTick()>1200){
                perceptionReply(player,"Cette page du journal n'est plus disponible ; rouvrez le journal.");return 0;
            }
            reading=writer.readRetainedFacts(selected.snapshot(),selected.after());
        }else{
            session.personalJournals.remove(id);
            reading=writer.readRetainedFacts(DirectorRecordedPerception.TYPE,new ScopeRef(ScopeKind.PLAYER,id.value()));
        }
        session.journalReads.put(id,identity);session.journalLastRead.put(id,now);
        reading.whenComplete((read,error)->{
            Runnable publish=()->{
                session.journalReads.remove(id,identity);var current=server.getPlayerList().getPlayer(id.value());
                if(current==null||SESSIONS.get(server)!=session||!hasLiveWal(server,session)||session.transport.identity(id).filter(identity::equals).isEmpty())return;
                try{
                    if(error!=null||read==null||read.isEmpty()||!bound.equals(writer.session())||!read.orElseThrow().snapshot().session().equals(bound))throw new IllegalStateException();
                    var page=read.orElseThrow();
                    if(page.next().isPresent()&&!session.personalJournals.containsKey(id)&&session.personalJournals.size()>=MAX_CLIENTS)throw new IllegalStateException("personal journal capacity");
                    // Validate the entire bounded page before publishing any row or optional Quest mirror.
                    List<String> lines=new ArrayList<>();
                    for(var fact:page.facts()){
                        if(!DirectorRecordedPerception.belongsTo(fact,id)
                            ||DirectorRecordedPerception.source(fact,id).filter(s->s.header().worldId().equals(identity.worldId())).isEmpty())
                            throw new IllegalStateException("personal fact scope/source mismatch");
                        lines.add("["+fact.factId().value()+"] "+DirectorRecordedPerception.text(fact));
                    }
                    page.facts().forEach(fact->publishRecordedFact(server,fact));
                    for(int i=0;i<lines.size();i++)personalFactReply(session,current,page.facts().get(i),lines.get(i));
                    if(session.transport.identity(id).filter(identity::equals).isEmpty())return;
                    if(lines.isEmpty())perceptionReply(current,page.next().isPresent()?"Aucun relevé personnel dans cette portion ; poursuivez la lecture.":"Aucun relevé personnel sur cette page.");
                    if(page.next().isPresent()){
                        var next=new PersonalJournal(page.snapshot(),identity,page.next().orElseThrow(),UUID.randomUUID().toString(),server.getTickCount());
                        session.personalJournals.put(id,next);
                        current.sendSystemMessage(net.minecraft.network.chat.Component.literal("Page suivante du journal")
                            .withStyle(style->style.withClickEvent(new net.minecraft.network.chat.ClickEvent(net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND,
                                "/proutlost perception journal "+next.cursor()))));
                    }else session.personalJournals.remove(id);
                }catch(RuntimeException unavailable){session.personalJournals.remove(id);perceptionReply(current,"Journal temporairement indisponible ; aucune absence déduite.");}
            };
            if(server.isSameThread())publish.run();else server.execute(publish);
        });return 1;
    }
    private static void tick(ServerTickEvent.Post event) {
        var server=event.getServer();
        if(!server.isSameThread())throw new IllegalStateException("J06 server actor");
        java.util.Optional<Epoch> present;
        try {
            present=DirectorStandaloneMod.directorLifecycle().verifiedBootEpoch(server);
        } catch(RuntimeException revokedWal) {
            revoke(server);
            return;
        }
        if(present.isEmpty()) {
            revoke(server);
            return;
        }
        ServerSession session=SESSIONS.get(server);
        if(session==null) {
            var identity=server.overworld().getDataStorage().computeIfAbsent(
                fr.proutlost.director.adapter.neoforge.server.lifecycle.DirectorSavedIdentity.FACTORY,
                fr.proutlost.director.adapter.neoforge.server.lifecycle.DirectorSavedIdentity.KEY);
            session=new ServerSession(server,identity.worldId(),present.orElseThrow());
            SESSIONS.put(server,session);
            System.out.println("DIRECTOR_NATIVE_J06_SESSION_BOUND_TO_RECOVERED_WAL");
        } else if(!session.boot.equals(present.orElseThrow())){
            revoke(server);
            return; // New boot must negotiate a new transport on a later tick.
        }
        session.personalJournals.values().removeIf(page->server.getTickCount()-page.lastReadTick()>1200);
        for(var player:server.getPlayerList().getPlayers())
            openIfChanged(session,player);
    }
    private static void openIfChanged(ServerSession session,ServerPlayer player) {
        var id=new PlayerId(player.getUUID());
        var dim=new DimensionId(player.serverLevel().dimension().location().toString());
        var previous=session.dimensions.get(id);
        boolean rotate=session.transport.readyForSessionReset(id);
        boolean awaiting=session.transport.sessionResetRequested(id);
        int remembered=session.transport.rememberedHeaders(id);
        boolean clientRefresh=awaiting&&session.transport.identity(id).isEmpty();
        if(!dim.equals(previous) || rotate || !awaiting&&session.transport.identity(id).isEmpty()) {
            session.factReceipts.clear(id);
            try {
                session.transport.open(id,dim);
                session.dimensions.put(id,dim);session.personalJournals.remove(id);
                if(rotate)System.out.println("DIRECTOR_J06_SESSION_ROTATED_BOUNDED_PROVENANCE headers="+remembered+" reason="+(clientRefresh?"CLIENT_REFRESH":"PROVENANCE_LIMIT"));
                System.out.println("DIRECTOR_NATIVE_J06_CLIENT_SESSION_OPEN total="
                    +session.dimensions.size());
            } catch(RuntimeException failedReset) {
                // Network send is ambiguous; the transport revokes its own
                // session. Never leave a stale dimension/F8 cache or crash
                // the Minecraft actor because one client disconnected.
                session.dimensions.remove(id);session.personalJournals.remove(id);
                session.rejected++;
                System.out.println("DIRECTOR_NATIVE_J06_RESET_SEND_FAILED_SAFE");
            }
        }
    }
    private static void dimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if(event.getEntity() instanceof ServerPlayer p) {
            var session=SESSIONS.get(p.server);
            if(hasLiveWal(p.server,session))openIfChanged(session,p);
            else revoke(p.server);
        }
    }
    private static void respawn(PlayerEvent.PlayerRespawnEvent event) {
        if(event.getEntity() instanceof ServerPlayer p) {
            var session=SESSIONS.get(p.server);
            if(hasLiveWal(p.server,session)){
                var id=new PlayerId(p.getUUID());
                session.transport.close(id);session.factReceipts.clear(id);
                session.dimensions.remove(id);session.personalJournals.remove(id);
                openIfChanged(session,p);
            } else revoke(p.server);
        }
    }
    private static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if(event.getEntity() instanceof ServerPlayer p) {
            var session=SESSIONS.get(p.server);
            if(session!=null){
                var id=new PlayerId(p.getUUID());
                session.dimensions.remove(id);session.journalReads.remove(id);session.personalJournals.remove(id);session.journalLastRead.remove(id);session.recordLast.remove(id);session.recordReplyLast.remove(id);session.factReceipts.clear(id);
                session.transport.close(id);
                System.out.println("DIRECTOR_NATIVE_J06_CLIENT_SESSION_CLOSE remaining="
                    +session.dimensions.size());
            }
        }
    }
    private static void stopped(ServerStoppedEvent event) {
        revoke(event.getServer());
    }
    /**
     * Real J06 recipient lookup for production coordinators (J03/J05).
     * Returns no recipient unless a physically connected player has negotiated
     * this exact kind and verified asset digest under the CURRENT WAL epoch.
     * This does NOT grant permission to expose an effect or call transport.upsert:
     * that still requires a separately forced J04 PREPARE and approved providers.
     * Empty production asset manifests deliberately return Optional.empty().
     */
    /** Server actor readback. A saved UI identity is never substituted for a live socket/dimension. */
    public static Optional<SessionIdentity> currentSession(MinecraftServer server,PlayerId player){
        if(!server.isSameThread())throw new IllegalStateException("J06 actor");
        var session=SESSIONS.get(server);if(!hasLiveWal(server,session))return Optional.empty();
        var actual=server.getPlayerList().getPlayer(player.value());
        if(actual==null||actual.server!=server)return Optional.empty();
        var dimension=new DimensionId(actual.serverLevel().dimension().location().toString());
        return session.transport.identity(player).filter(i->i.dimension().equals(dimension)
            &&dimension.equals(session.dimensions.get(player)));
    }
    public static boolean hasPerceptionCapacity(MinecraftServer server,PlayerId player,int newHeaders){
        if(currentSession(server,player).isEmpty())return false;
        return SESSIONS.get(server).transport.canSendNew(player,newHeaders);
    }
    /** Personal cleanup only. The existing SessionReset clears renderer/interaction/UI state;
     * successful packet queuing is NOT its ACK. No world state or another player is changed. */
    public static Optional<SessionIdentity> rescuePlayer(MinecraftServer server,PlayerId player){
        if(!server.isSameThread())throw new IllegalStateException("J06 actor");
        var session=SESSIONS.get(server);if(!hasLiveWal(server,session))return Optional.empty();
        var actual=server.getPlayerList().getPlayer(player.value());
        if(actual==null||actual.server!=server)return Optional.empty();
        var dimension=new DimensionId(actual.serverLevel().dimension().location().toString());
        try{
            session.factReceipts.clear(player);
            var expected=session.transport.open(player,dimension);session.dimensions.put(player,dimension);session.personalJournals.remove(player);
            return Optional.of(expected);
        }catch(RuntimeException uncertain){session.dimensions.remove(player);session.personalJournals.remove(player);session.rejected++;return Optional.empty();}
    }
    /** A capability reply is emitted only after client renderer cleanup accepted this exact epoch.
     * An empty negotiated inventory acknowledges cleanup but grants zero effects. */
    public static boolean sessionNegotiated(MinecraftServer server,SessionIdentity expected){
        return currentSession(server,expected.playerId()).filter(expected::equals).isPresent()
            &&SESSIONS.get(server).transport.capabilities(expected.playerId()).isPresent();
    }
    /** Called by the J05 warning binding only after its durable permit was consumed. */
    public static PerceptionHeader sendGameplayPerception(MinecraftServer server,PlayerId target,
            EffectIntent intent,PerceptionTransform transform,int ttl) {
        var assetId=DirectorGameplayAssets.asset(intent);
        var asset=Optional.ofNullable(DirectorGameplayAssets.specs().get(assetId)).orElseThrow(
            ()->new IllegalStateException("perception asset absent from shipped manifest"));
        var recipient=qualifiedRecipient(server,target,asset.kind(),assetId,asset.hash()).orElseThrow(
            ()->new IllegalStateException("client perception asset not negotiated"));
        var session=SESSIONS.get(server);
        var header=new PerceptionHeader(1,recipient.worldId(),recipient.bootEpoch(),
            recipient.connectionEpoch(),recipient.dimension(),intent.instanceId(),
            new EffectId(intent.operationToken().value()),new Sequence(++session.effectSequence),new DurationTicks(ttl));
        session.transport.upsert(DirectorPerceptionPrimitiveEncoder.encode(intent,
            Map.of("witness",target),recipient,header,transform,DirectorGameplayAssets.encoderAssets()));
        System.out.println("DIRECTOR_GAMEPLAY_PERCEPTION_SENT kind="+asset.kind()+" asset="+assetId.value()
            +" op="+intent.operationToken().value());
        return header;
    }
    public static PerceptionHeader sendGameplayWarning(MinecraftServer server,PlayerId target,
            EffectIntent intent,PerceptionTransform transform,int ttl) {
        var header=sendGameplayPerception(server,target,intent,transform,ttl);
        System.out.println("DIRECTOR_GAMEPLAY_WARN_SENT op="+intent.operationToken().value());
        return header;
    }
    /** Authenticated command bridge. A receipt is a prerequisite, never a player selection. */
    public static boolean acceptGameplayChoice(MinecraftServer server,PlayerId player,AnchorInteraction interaction,
            java.util.function.Predicate<AnchorInteraction> liveValidator,
            java.util.function.Consumer<AnchorInteraction> accepted) {
        if(!server.isSameThread())throw new IllegalStateException("J06 actor");
        var session=SESSIONS.get(server);
        if(!hasLiveWal(server,session)){revoke(server);return false;}
        return session.transport.received(player,interaction.header()) &&
            session.transport.interaction(player,interaction,liveValidator,accepted);
    }
    public static boolean liveWarningReceived(MinecraftServer server,PlayerId target,
        PerceptionHeader header,ResourceId profile,PerceptionTransform transform) {
    if(!server.isSameThread())throw new IllegalStateException("J06 actor");
    var session=SESSIONS.get(server);
    return hasLiveWal(server,session)
        &&session.transport.warningReceivedAndLive(target,header,profile,transform);
}

public static boolean warningReceived(MinecraftServer server,PlayerId target,PerceptionHeader header) {
        var session=SESSIONS.get(server);
        return hasLiveWal(server,session) && session.transport.received(target,header);
    }
    public static void stopGameplayWarning(MinecraftServer server,PlayerId target,PerceptionHeader old) {
        var session=SESSIONS.get(server);
        if(!hasLiveWal(server,session))return;
        if(session.transport.identity(target).filter(i->i.connectionEpoch().equals(old.connectionEpoch())).isEmpty())return;
        var header=new PerceptionHeader(1,old.worldId(),old.bootEpoch(),old.connectionEpoch(),
            old.dimension(),old.instanceId(),old.effectId(),new Sequence(++session.effectSequence),
            new DurationTicks(1));
        session.transport.stop(target,new PerceptionStop(header,false));
    }
    public static Optional<SessionIdentity> qualifiedRecipient(
            MinecraftServer server, PlayerId player, PerceptionKind kind,
            ResourceId asset, Fingerprint digest) {
        if(!server.isSameThread())throw new IllegalStateException("J06 actor");
        Objects.requireNonNull(player);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(asset);
        Objects.requireNonNull(digest);
        var session=SESSIONS.get(server);
        if(!hasLiveWal(server,session)) {
            revoke(server);
            return Optional.empty();
        }
        var nativePlayer=server.getPlayerList().getPlayer(player.value());
        if(nativePlayer==null || nativePlayer.server!=server)
            return Optional.empty();
        var dimension=new DimensionId(
            nativePlayer.serverLevel().dimension().location().toString());
        if(!dimension.equals(session.dimensions.get(player)))
            return Optional.empty();
        var identity=session.transport.identity(player);
        var negotiated=session.transport.capabilities(player);
        if(identity.isEmpty() || negotiated.isEmpty()
            || !DirectorRecipientAuthority.negotiated(identity.orElseThrow(),
                negotiated.orElseThrow(),session.world,session.boot,player,
                dimension,kind,asset,digest))
            return Optional.empty();
        return identity;
    }

    /** Actual negotiated client capability, never inferred from merely being online. */
    public static ClientCapabilitySnapshot capability(MinecraftServer server,PlayerId player) {
        if(!server.isSameThread())throw new IllegalStateException("J06 actor");
        var session=SESSIONS.get(server);
        if(!hasLiveWal(server,session)) {
            revoke(server);
            return new ClientCapabilitySnapshot(1,Set.of(),Map.of(),false);
        }
        var accepted=session.transport.capabilities(player);
        if(accepted.isEmpty())return new ClientCapabilitySnapshot(1,Set.of(),Map.of(),false);
        var dto=accepted.orElseThrow();
        var supported=new HashSet<ResourceId>();
        for(var kind:dto.supportedEffects())
            supported.add(new ResourceId("proutlost_director:"+kind.name().toLowerCase(Locale.ROOT)));
        return new ClientCapabilitySnapshot(1,supported,dto.assetHashes(),true);
    }
    public static Status status(MinecraftServer server) {
        if(!server.isSameThread())throw new IllegalStateException("J06 actor");
        var session=SESSIONS.get(server);
        if(!hasLiveWal(server,session)) {
            revoke(server);
            session=null;
        }
        return new Status(payloadsRegistered,session!=null,
            session==null?0:session.dimensions.size(),
            session==null?0:session.negotiations,
            session==null?0:session.rejected,false);
    }
}
