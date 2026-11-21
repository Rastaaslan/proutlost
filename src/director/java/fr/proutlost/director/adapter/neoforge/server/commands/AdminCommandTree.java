package fr.proutlost.director.adapter.neoforge.server.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.LiteralMessage;
import fr.proutlost.director.api.model.*;
import java.util.*;
import java.util.function.*;

/** Brigadier grammar shared by native registration and isolated parser tests. */
public final class AdminCommandTree<S> {
    private final BiFunction<S,Optional<String>,Integer> eventPages;
    private final ToIntFunction<S> permission;
    private final BiFunction<S, String, Set<PlayerId>> players;
    private final BiFunction<S, ResourceId, SnapshotBatch> fixtures;
    private final ToLongFunction<S> tick;
    private final BiFunction<S, Map.Entry<AdminOperation, AdminPayload>, Integer> dispatch;

    public AdminCommandTree(ToIntFunction<S> permission, BiFunction<S,String,Set<PlayerId>> players,
            Function<ResourceId,SnapshotBatch> fixtures, LongSupplier tick,
            BiFunction<S,Map.Entry<AdminOperation,AdminPayload>,Integer> dispatch,
            BiFunction<S,Optional<String>,Integer> eventPages) {
        this(permission,players,sourceFixtures(fixtures),sourceClock(tick),dispatch,eventPages);
    }
    /** Runtime lookup is source-scoped: registration may precede server startup. */
    public AdminCommandTree(ToIntFunction<S> permission, BiFunction<S,String,Set<PlayerId>> players,
            BiFunction<S,ResourceId,SnapshotBatch> fixtures, ToLongFunction<S> tick,
            BiFunction<S,Map.Entry<AdminOperation,AdminPayload>,Integer> dispatch,
            BiFunction<S,Optional<String>,Integer> eventPages) {
        this.eventPages=Objects.requireNonNull(eventPages);
        this.permission=Objects.requireNonNull(permission); this.players=Objects.requireNonNull(players);
        this.fixtures=Objects.requireNonNull(fixtures); this.tick=Objects.requireNonNull(tick);
        this.dispatch=Objects.requireNonNull(dispatch);
    }
    private static <S> BiFunction<S,ResourceId,SnapshotBatch> sourceFixtures(Function<ResourceId,SnapshotBatch> fixtures) {
        Objects.requireNonNull(fixtures);return (source,id)->fixtures.apply(id);
    }
    private static <S> ToLongFunction<S> sourceClock(LongSupplier tick) {
        Objects.requireNonNull(tick);return source->tick.getAsLong();
    }
    public void register(CommandDispatcher<S> dispatcher) {
        var root=LiteralArgumentBuilder.<S>literal("proutlost");
        root.then(leaf("status",0,AdminOperation.STATUS,false));
        root.then(leaf("debug",2,AdminOperation.DEBUG,false));
        root.then(leaf("stop",4,AdminOperation.STOP,true));
        root.then(leaf("rescue",0,AdminOperation.RESCUE,true));
        root.then(leaf("report",0,AdminOperation.REPORT,true));
        root.then(leaf("trigger",4,AdminOperation.TRIGGER,true));
        var director=LiteralArgumentBuilder.<S>literal("director").requires(s->permission.applyAsInt(s)>=4);
        for(var op:List.of(AdminOperation.STATUS,AdminOperation.UI,AdminOperation.PAUSE,AdminOperation.RESUME,AdminOperation.DRAIN))
            director.then(leaf(op.name().toLowerCase(Locale.ROOT),4,op,false));
        for(var op:List.of(AdminOperation.TENSION,AdminOperation.INTENSITY,AdminOperation.SIMULATE,
                AdminOperation.REPLAY,AdminOperation.REGION,AdminOperation.PLAYER,AdminOperation.CHAIN,AdminOperation.MUTATION_INSPECT))
            director.then(leaf(op.name().toLowerCase(Locale.ROOT),4,op,true));
        director.then(LiteralArgumentBuilder.<S>literal("event")
                .then(LiteralArgumentBuilder.<S>literal("list").executes(c->eventPages.apply(c.getSource(),Optional.empty()))
                        .then(RequiredArgumentBuilder.<S,String>argument("cursor",StringArgumentType.word()).executes(c->{
                            String cursor=StringArgumentType.getString(c,"cursor");
                            if(cursor.length()>512) throw new SimpleCommandExceptionType(new LiteralMessage("Curseur trop long")).create();
                            return eventPages.apply(c.getSource(),Optional.of(cursor));
                        }))).then(leaf("inspect",4,AdminOperation.EVENT_INSPECT,true)));
        director.then(leaf("disaster",4,AdminOperation.STABILIZE,true));
        director.then(LiteralArgumentBuilder.<S>literal("audit").then(leaf("export",4,AdminOperation.AUDIT_EXPORT,true)));
        root.then(director); dispatcher.register(root);
        dispatcher.register(leaf("proutlostdirectorui",4,AdminOperation.UI,false));
    }
    private LiteralArgumentBuilder<S> leaf(String name,int level,AdminOperation operation,boolean arguments) {
        var node=LiteralArgumentBuilder.<S>literal(name).requires(s->permission.applyAsInt(s)>=level);
        if(arguments) node.then(RequiredArgumentBuilder.<S,String>argument("arguments",StringArgumentType.greedyString())
                .executes(c->execute(c.getSource(),operation,StringArgumentType.getString(c,"arguments"))));
        else node.executes(c->execute(c.getSource(),operation,""));
        return node;
    }
    private int execute(S source,AdminOperation op,String text) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        try {
            if(text.length()>1000) throw new IllegalArgumentException("Argument trop long");
            var payload=parse(source,op,text);
            if(op==AdminOperation.RESCUE && payload instanceof RescuePayload r && !r.self() && permission.applyAsInt(source)<2)
                throw new IllegalArgumentException("AUTH_DENIED");
            return dispatch.apply(source,Map.entry(op,payload));
        } catch(IllegalArgumentException|ArithmeticException e) {
            throw new SimpleCommandExceptionType(new LiteralMessage("Arguments invalides: "+e.getMessage())).create();
        }
    }
    public AdminPayload parse(S source,AdminOperation op,String text) {
        String[] a=text.isBlank()?new String[0]:text.trim().split("\\s+");
        return switch(op) {
            case PAUSE -> new ControlPayload(RunControl.PAUSED);
            case RESUME -> new ControlPayload(RunControl.RUNNING);
            case DRAIN -> new ControlPayload(RunControl.DRAINING);
            case TENSION -> { count(a,2); long duration=Long.parseLong(a[1]); yield new TensionPayload(GlobalTension.valueOf(a[0].toUpperCase(Locale.ROOT)),new DurationTicks(duration),new Tick(Math.addExact(tick.applyAsLong(source),duration))); }
            case INTENSITY -> { count(a,1); yield new IntensityPayload(new ResourceId(a[0])); }
            case STOP -> { count(a,1); boolean all=a[0].equals("all"); yield new StopPayload(all?Optional.empty():Optional.of(EventInstanceId.parse(a[0])),all,"admin stop"); }
            case RESCUE -> { count(a,1); if(a[0].equals("self")) yield new RescuePayload(Optional.empty(),true); var targets=players.apply(source,a[0]); if(targets.size()!=1) throw new IllegalArgumentException("Un joueur requis"); yield new RescuePayload(Optional.of(targets.iterator().next()),false); }
            case REPORT -> new ReportPayload(text,Set.of());
            case EVENT_INSPECT -> { count(a,1); yield new EventInspectionPayload(EventInstanceId.parse(a[0])); }
            case MUTATION_INSPECT -> { count(a,1); yield new MutationInspectionPayload(MutationId.parse(a[0])); }
            case REGION -> { count(a,1); yield new RegionInspectionPayload(RegionId.parse(a[0])); }
            case PLAYER -> { count(a,1); yield new PlayerInspectionPayload(PlayerId.parse(a[0])); }
            case CHAIN -> { count(a,1); yield new ChainInspectionPayload(ChainId.parse(a[0])); }
            case STABILIZE -> { count(a,2); if(!a[1].equals("stabilize")) throw new IllegalArgumentException("stabilize requis"); yield new StabilizePayload(DisasterId.parse(a[0])); }
            case AUDIT_EXPORT -> { count(a,1); yield new AuditExportPayload(DecisionId.parse(a[0])); }
            case REPLAY -> { if(a.length!=1 && a.length!=2 || a.length==2 && !a[1].equals("without_ai")) throw new IllegalArgumentException("decisionId [without_ai]"); yield new ReplayPayload(DecisionId.parse(a[0]),a.length==2); }
            case SIMULATE -> { count(a,1); var id=new ResourceId(a[0]); var context=fixtures.apply(source,id); if(context==null) throw new IllegalArgumentException("Fixture inconnue"); yield new SimulationPayload(id,context,true); }
            case TRIGGER -> { if(a.length!=3 && a.length!=5 || a.length>=2 && !a[1].equals("region") || a.length==5 && !a[3].equals("targets")) throw new IllegalArgumentException("eventId region regionId [targets players]"); yield new TriggerPayload(new DefinitionId(a[0]),RegionId.parse(a[2]),a.length==5?players.apply(source,a[4]):Set.of(),Set.of()); }
            default -> { count(a,0); yield new EmptyAdminPayload(); }
        };
    }
    private static void count(String[] a,int n) { if(a.length!=n) throw new IllegalArgumentException("Nombre d'arguments attendu: "+n); }
}
