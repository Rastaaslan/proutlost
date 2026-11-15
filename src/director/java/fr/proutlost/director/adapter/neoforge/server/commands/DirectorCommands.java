package fr.proutlost.director.adapter.neoforge.server.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.StringReader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.AdminPort;
import java.util.*;
import java.util.function.*;

/** Register from RegisterCommandsEvent. All callbacks are supplied by trusted bootstrap. */
public final class DirectorCommands {
    private DirectorCommands() {}
    /** Actor-owned runtime of the command's server; no client-supplied authority. */
    public interface Access {
        AdminPort admin();
        ActorIdentity identity(CommandSourceStack source);
        Revision revision();
        long directorTick();
        SnapshotBatch fixture(ResourceId id);
        void openUi(ServerPlayer player);
        Optional<String> serverExportPath(SessionId session,RequestId request);
    }
    /** Compatibility for callers which already own a single recovered runtime. */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, AdminPort admin,
            Function<CommandSourceStack,ActorIdentity> identity, Supplier<Revision> revision,
            LongSupplier directorTick, Function<ResourceId,SnapshotBatch> fixtures, Consumer<ServerPlayer> openUi,
            BiFunction<SessionId,RequestId,Optional<String>> serverExportPath) {
        Objects.requireNonNull(admin);Objects.requireNonNull(identity);Objects.requireNonNull(revision);
        Objects.requireNonNull(directorTick);Objects.requireNonNull(fixtures);Objects.requireNonNull(openUi);
        Objects.requireNonNull(serverExportPath);
        var access=new Access() {
            public AdminPort admin(){return admin;}
            public ActorIdentity identity(CommandSourceStack source){return identity.apply(source);}
            public Revision revision(){return revision.get();}
            public long directorTick(){return directorTick.getAsLong();}
            public SnapshotBatch fixture(ResourceId id){return fixtures.apply(id);}
            public void openUi(ServerPlayer player){openUi.accept(player);}
            public Optional<String> serverExportPath(SessionId session,RequestId request){return serverExportPath.apply(session,request);}
        };
        register(dispatcher,source->Optional.of(access));
    }
    /** Resolve after startup on each source; no cross-server or stale boot authority. */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
            Function<CommandSourceStack,Optional<Access>> runtime) {
        Objects.requireNonNull(runtime);
        new AdminCommandTree<CommandSourceStack>(DirectorCommands::permission, (source,selector)-> {
            try {
                var selected=new EntitySelectorParser(new StringReader(selector),source.hasPermission(2)).parse().findPlayers(source);
                if(selected.size()>50) throw new IllegalArgumentException("Trop de cibles");
                Set<PlayerId> ids=new HashSet<>(); selected.forEach(p->ids.add(new PlayerId(p.getUUID()))); return Set.copyOf(ids);
            } catch(com.mojang.brigadier.exceptions.CommandSyntaxException e) { throw new IllegalArgumentException("Joueur inconnu"); }
        },(source,id)->require(runtime,source).fixture(id),source->require(runtime,source).directorTick(),(source,request)-> {
            final Access access;
            final ActorIdentity actor;
            final AdminCommand command;
            try {
                access=require(runtime,source);actor=access.identity(source);
                command=new AdminCommand(actor.session(),new RequestId(UUID.randomUUID()),access.revision(),request.getKey(),request.getValue());
            } catch(RuntimeException unavailable) {
                source.sendFailure(Component.literal("REJECTED CAPABILITY_MISSING"));return 0;
            }
            if(!AdminContracts.authorized(command,actor) || request.getKey()==AdminOperation.UI && source.getPlayer()==null) {
                source.sendFailure(Component.literal("AUTH_DENIED")); return 0;
            }
            final java.util.concurrent.CompletionStage<AdminResult> result;
            try {result=Objects.requireNonNull(access.admin().submit(command,actor));}
            catch(RuntimeException failure){source.sendFailure(Component.literal("FAILED_SAFE INTERNAL_FAULT"));return 0;}
            source.sendSuccess(()->Component.literal("PENDING "+command.requestId().value()),false);
            result.whenComplete((value,failure)->source.getServer().execute(()-> {
                var player=source.getPlayer();
                if(player!=null && source.getServer().getPlayerList().getPlayer(player.getUUID())!=player) return;
                final Access latest;
                final ActorIdentity current;
                try {latest=require(runtime,source);current=latest.identity(source);}
                catch(RuntimeException unavailable){return;}
                if(!current.session().equals(actor.session()) || !AdminContracts.authorized(command,current)) return;
                if(failure!=null || value==null || !value.requestId().equals(command.requestId())) { source.sendFailure(Component.literal("FAILED_SAFE INTERNAL_FAULT")); return; }
                CommandResultOutput.deliver(command,value,current,line->source.sendSuccess(()->Component.literal(line),false));
                if(value.status()==AdminStatus.SUCCEEDED && command.operation()==AdminOperation.AUDIT_EXPORT && current.permissionLevel()>=4)
                    latest.serverExportPath(current.session(),command.requestId()).ifPresent(path->source.sendSuccess(()->Component.literal("Export serveur : "+path),false));
                if(value.status()==AdminStatus.SUCCEEDED && command.operation()==AdminOperation.UI) latest.openUi(source.getPlayer());
            }));
            return 1;
        },(source,cursor)-> {
            final Access access;
            final ActorIdentity actor;
            try {access=require(runtime,source);actor=access.identity(source);}
            catch(RuntimeException unavailable){source.sendFailure(Component.literal("REJECTED CAPABILITY_MISSING"));return 0;}
            if(actor.permissionLevel()<4 || !actor.serverAuthenticated()) {source.sendFailure(Component.literal("AUTH_DENIED"));return 0;}
            var query=new AdminQuery(actor.session(),new RequestId(UUID.randomUUID()),AdminPanel.ACTIVE,
                    new EventFilter(Optional.empty(),Set.of()),cursor,Optional.empty(),50);
            final AdminReadResult result;
            try {result=Objects.requireNonNull(access.admin().query(query,actor));}
            catch(RuntimeException failure){source.sendFailure(Component.literal("FAILED_SAFE INTERNAL_FAULT"));return 0;}
            if(result.status()!=AdminStatus.SUCCEEDED) {
                source.sendFailure(Component.literal(result.status()+result.error().map(e->" "+e.code()).orElse("")));return 0;
            }
            for(var row:result.data()) {
                String summary=switch(row.data()) {
                    case EventAdminData e -> e.event().instanceId().value()+" "+e.event().state()+" "+e.event().phase();
                    case DisasterAdminData d -> d.disaster().disasterId().value()+" "+d.disaster().stage();
                    default -> row.scope().id().toString();
                };
                source.sendSuccess(()->Component.literal(summary+" • révision "+row.revision().value()),false);
            }
            if(result.data().isEmpty()) source.sendSuccess(()->Component.literal("Aucun actif"),false);
            result.nextCursor().ifPresent(next->source.sendSuccess(()->Component.literal("Page suivante : /proutlost director event list "+next),false));
            return 1;
        }).register(dispatcher);
    }
    private static Access require(Function<CommandSourceStack,Optional<Access>> runtime,CommandSourceStack source) {
        return Objects.requireNonNull(runtime.apply(source)).orElseThrow(()->new IllegalArgumentException("CAPABILITY_MISSING"));
    }
    private static int permission(CommandSourceStack source) {
        for(int i=4;i>=0;i--) if(source.hasPermission(i)) return i;
        return 0;
    }
}
