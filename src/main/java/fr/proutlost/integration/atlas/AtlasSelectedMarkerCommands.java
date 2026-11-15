package fr.proutlost.integration.atlas;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CancellationException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** Player-initiated, single-marker consent, using only genuine command network sessions. */
@EventBusSubscriber(modid="proutlost")
public final class AtlasSelectedMarkerCommands {
    private static final Map<MinecraftServer,State> STATES=Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile CompletableFuture<Atlas8Adapter> verified;
    private AtlasSelectedMarkerCommands(){}
    private record Connection(Object listener,UUID token) {}
    private static final class State {
        final UUID session=UUID.randomUUID();final AtlasShareGate gate=new AtlasShareGate();
        final Map<UUID,Connection> connections=new HashMap<>();
        AtlasShareGate.Actor actor(MinecraftServer server,ServerPlayer player,Atlas8Adapter adapter){
            actual(server,player);
            for(var entry:new ArrayList<>(connections.entrySet())){
                var online=server.getPlayerList().getPlayer(entry.getKey());
                if(online==null||online.connection!=entry.getValue().listener()){
                    connections.remove(entry.getKey());gate.disconnected(entry.getKey());
                }
            }
            var prior=connections.get(player.getUUID());
            if(prior==null){
                AtlasShareGate.require(connections.size()<128,"Trop de sessions de partage.");
                prior=new Connection(player.connection,UUID.randomUUID());connections.put(player.getUUID(),prior);
            }
            return new AtlasShareGate.Actor(player.getUUID(),session,prior.token(),adapter.heldAtlas(player),
                player.serverLevel().dimension().location().toString());
        }
    }
    private static void actual(MinecraftServer server,ServerPlayer player){
        AtlasShareGate.require(server.isSameThread()&&player!=null&&!(player instanceof FakePlayer)
            &&player.getServer()==server&&player.connection!=null
            &&server.getPlayerList().getPlayer(player.getUUID())==player,"Joueur reel connecte requis.");
    }
    /** Native gates may observe linkage only; READY grants no player/marker authority. */
    public static String verificationStatus(){
        var check=verified;
        if(check==null)return "NOT_STARTED";
        if(!check.isDone())return "PENDING";
        return check.isCompletedExceptionally()||check.getNow(null)==null?"FAILED":"READY";
    }
    public static void requireServerApiReady(){
        var check=verified;String status=verificationStatus();
        if(status.equals("READY"))return;
        if(check!=null&&check.isDone()){
            try{check.getNow(null);}
            catch(CompletionException|CancellationException failure){
                throw new IllegalStateException("Atlas server API verification failed",Atlas8Adapter.rootCause(failure));
            }
        }
        throw new IllegalStateException("Atlas server API verification "+status);
    }
    private static Atlas8Adapter adapter(){
        String status=verificationStatus();
        AtlasShareGate.require(status.equals("READY"),
            "Partage Atlas indisponible : verification "+status+".");
        return Objects.requireNonNull(verified.getNow(null));
    }
    @SubscribeEvent public static void register(RegisterCommandsEvent event){
        if(!ModList.get().isLoaded("antiqueatlas")||!ModList.get().isLoaded("unionlib"))return;
        if(verified==null)verified=Atlas8Adapter.verifyAsync();
        register(event.getDispatcher());
    }
    private static void register(CommandDispatcher<CommandSourceStack> dispatcher){
        dispatcher.register(Commands.literal("plsatlas").requires(s->s.getEntity() instanceof ServerPlayer)
            .then(Commands.literal("list").executes(c->run(c,()->list(c))))
            .then(Commands.literal("share").then(Commands.argument("player",EntityArgument.player())
                .then(Commands.argument("marker",IntegerArgumentType.integer(0))
                    .executes(c->run(c,()->share(c))))))
            .then(Commands.literal("accept").then(Commands.argument("token",StringArgumentType.word())
                .executes(c->run(c,()->accept(c))))));
    }
    @FunctionalInterface private interface Action {int run() throws CommandSyntaxException;}
    private static int run(CommandContext<CommandSourceStack> context,Action action) throws CommandSyntaxException {
        try{return action.run();}
        catch(IllegalArgumentException refused){context.getSource().sendFailure(Component.literal(refused.getMessage()));return 0;}
        catch(RuntimeException uncertain){
            System.err.println("PROUTLOST_ATLAS_SHARE_UNAVAILABLE "+uncertain.getClass().getSimpleName());
            context.getSource().sendFailure(Component.literal("Partage interrompu. Verifie ton atlas avant une nouvelle proposition."));return 0;
        }
    }
    private static State state(MinecraftServer server){return STATES.computeIfAbsent(server,s->new State());}
    private static long now(MinecraftServer server){return Integer.toUnsignedLong(server.getTickCount());}
    private static void reply(CommandContext<CommandSourceStack> context,String text){context.getSource().sendSuccess(()->Component.literal(text),false);}
    private static int list(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var player=context.getSource().getPlayerOrException();var server=context.getSource().getServer();
        var adapter=adapter();var actor=state(server).actor(server,player,adapter);
        var rows=adapter.all(player,actor.atlas()).stream().filter(m->!m.global()&&m.dimension().equals(actor.dimension())).toList();
        reply(context,rows.isEmpty()?"Aucun marqueur personnel dans cet atlas et cette dimension.":"Marqueurs de ton atlas :");
        for(var marker:rows)reply(context,marker.id()+" — "+marker.label().replaceAll("[\\p{Cntrl}]"," "));
        return rows.size();
    }
    private static int share(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var source=context.getSource().getPlayerOrException();var target=EntityArgument.getPlayer(context,"player");
        var server=context.getSource().getServer();var adapter=adapter();var state=state(server);
        var sender=state.actor(server,source,adapter);var recipient=state.actor(server,target,adapter);
        var marker=adapter.selected(source,sender.atlas(),IntegerArgumentType.getInteger(context,"marker"));
        var offer=state.gate.offer(sender,recipient,marker,adapter.all(target,recipient.atlas()).size(),now(server));
        source.sendSystemMessage(Component.literal("Proposition envoyee a "+target.getScoreboardName()+" : "+marker.label()));
        target.sendSystemMessage(Component.literal(source.getScoreboardName()+" propose le marqueur « "+marker.label()+" ». Pour l'importer dans ton atlas tenu en main : /plsatlas accept "+offer.token()+" (60 secondes)."));
        return 1;
    }
    private static int accept(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var target=context.getSource().getPlayerOrException();var server=context.getSource().getServer();
        var adapter=adapter();var state=state(server);actual(server,target);
        UUID token;
        try{token=UUID.fromString(StringArgumentType.getString(context,"token"));}
        catch(IllegalArgumentException invalid){throw new IllegalArgumentException("Proposition absente ou expiree.");}
        var offer=state.gate.peek(token,now(server)).orElseThrow(()->new IllegalArgumentException("Proposition absente ou expiree."));
        AtlasShareGate.require(offer.target().player().equals(target.getUUID()),"Proposition absente ou expiree.");
        var source=server.getPlayerList().getPlayer(offer.source().player());actual(server,source);
        var sender=state.actor(server,source,adapter);var recipient=state.actor(server,target,adapter);
        var marker=adapter.selected(source,sender.atlas(),offer.marker().id());
        var accepted=state.gate.claim(token,sender,recipient,marker,adapter.all(target,recipient.atlas()).size(),now(server));
        AtlasShareGate.deliver(accepted,()->adapter.appendOnly(target,recipient.atlas(),marker),(recipientId,created)->{
            AtlasShareGate.require(recipientId.equals(target.getUUID()),"Destinataire modifie.");
            actual(server,target);adapter.sendOnly(target,recipient.atlas(),created);
        });
        reply(context,"Le marqueur « "+marker.label()+" » a ete importe dans ton atlas.");return 1;
    }
    @SubscribeEvent public static void disconnected(PlayerEvent.PlayerLoggedOutEvent event){
        if(!(event.getEntity() instanceof ServerPlayer player)||player.getServer()==null)return;
        var state=STATES.get(player.getServer());if(state==null)return;
        var current=state.connections.get(player.getUUID());
        if(current!=null&&current.listener()==player.connection){state.connections.remove(player.getUUID());state.gate.disconnected(player.getUUID());}
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event){STATES.remove(event.getServer());}
}
