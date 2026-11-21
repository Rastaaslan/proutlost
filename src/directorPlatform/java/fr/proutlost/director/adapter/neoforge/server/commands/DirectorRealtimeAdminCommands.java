package fr.proutlost.director.adapter.neoforge.server.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import fr.proutlost.director.adapter.neoforge.network.DirectorAnchorInteractionCommands;
import fr.proutlost.director.api.model.ResourceId;
import fr.proutlost.director.bootstrap.DirectorProductionRuntime;
import java.util.function.Supplier;
import fr.proutlost.director.neoforge.DirectorStandaloneMod;
import java.util.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * First J07 live server-facing status and monitoring lane. Read-only, operator
 * gated, bounded subscribers, server-scoped. Not the full five-tab dashboard.
 */
public final class DirectorRealtimeAdminCommands {
    private DirectorRealtimeAdminCommands() {}
    private static int physical(CommandSourceStack source,String input,String receipt){
        var owner=DirectorStandaloneMod.directorLifecycle().physicalCatalogues(source.getServer());
        if(owner.isEmpty()){source.sendFailure(Component.literal("Production physical owner unavailable"));return 0;}
        try{String result=input==null?owner.orElseThrow().status():owner.orElseThrow().prepare(source,input,receipt);
            source.sendSuccess(()->Component.literal(result),false);return 1;
        }catch(IllegalArgumentException|IllegalStateException refused){source.sendFailure(Component.literal("Physical catalogue refused: "+refused.getMessage()));return 0;}
    }
    private static final int MAX_SUBSCRIBERS=16;
    private static final long PUSH_INTERVAL=100;
    private static final Map<MinecraftServer, Set<UUID>> watching=new IdentityHashMap<>();

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher=event.getDispatcher();
        dispatcher.register(Commands.literal("proutlost").then(Commands.literal("director").requires(source->source.hasPermission(4))
            .then(Commands.literal("locations")
                .then(Commands.literal("status").executes(c->runtimeLocations(c.getSource(),"status","")))
                .then(Commands.literal("import").then(Commands.argument("name",StringArgumentType.word()).executes(c->runtimeLocations(c.getSource(),"import",StringArgumentType.getString(c,"name")))))
                .then(Commands.literal("cancel").then(Commands.argument("name",StringArgumentType.word()).executes(c->runtimeLocations(c.getSource(),"cancel",StringArgumentType.getString(c,"name"))))))));
        dispatcher.register(Commands.literal("proutlost")
            .then(Commands.literal("choice").then(choiceSelection(false))));
        dispatcher.register(Commands.literal("proutlost")
            .then(Commands.literal("director").requires(s -> s.hasPermission(4))
                .then(Commands.literal("physical")
                    .then(Commands.literal("status").executes(c->physical(c.getSource(),null,null)))
                    .then(Commands.literal("prepare").then(Commands.argument("input",StringArgumentType.word())
                        .then(Commands.argument("receipt",StringArgumentType.word()).executes(c->physical(c.getSource(),StringArgumentType.getString(c,"input"),StringArgumentType.getString(c,"receipt")))))))
                .then(Commands.literal("status").executes(c -> status(c.getSource())))
                .then(Commands.literal("health").executes(c -> health(c.getSource())))
                .then(Commands.literal("sensors").executes(c -> sensors(c.getSource())))
                .then(Commands.literal("catalog").executes(c -> catalog(c.getSource())))
                .then(Commands.literal("memory").executes(c -> memory(c.getSource())))
                .then(Commands.literal("wal").executes(c -> wal(c.getSource())))
                .then(Commands.literal("decision").executes(c -> decision(c.getSource())))
                .then(Commands.literal("lab")
                    .then(Commands.literal("catalogue")
                        .then(Commands.literal("configure").executes(c->labCatalogue(c.getSource(),"configure","")))
                        .then(Commands.literal("on").executes(c->labCatalogue(c.getSource(),"on","")))
                        .then(Commands.literal("off").executes(c->labCatalogue(c.getSource(),"off","")))
                        .then(Commands.literal("status").executes(c->labCatalogue(c.getSource(),"status","")))
                        .then(Commands.literal("event").then(Commands.argument("definition",StringArgumentType.word())
                            .executes(c->labCatalogue(c.getSource(),"event",StringArgumentType.getString(c,"definition")))))
                        .then(Commands.literal("auto")
                            .then(Commands.literal("on").executes(c->labCatalogue(c.getSource(),"auto","on")))
                            .then(Commands.literal("off").executes(c->labCatalogue(c.getSource(),"auto","off"))))
                        .then(Commands.literal("require_signal").then(Commands.argument("key",StringArgumentType.word())
                            .executes(c->labCatalogue(c.getSource(),"require_signal",StringArgumentType.getString(c,"key"))))))
                    .then(Commands.literal("locations")
                        .then(Commands.literal("status").executes(c->labLocations(c.getSource(),"")))
                        .then(Commands.literal("import").then(Commands.argument("name",StringArgumentType.word()).executes(c->labLocations(c.getSource(),StringArgumentType.getString(c,"name"))))))
                    .then(Commands.literal("waterprobe")
                        .then(Commands.literal("start").executes(c->labWaterProbe(c.getSource(),true)))
                        .then(Commands.literal("status").executes(c->labWaterProbe(c.getSource(),false))))
                    .then(Commands.literal("chain")
                        .then(Commands.literal("camp_short_arc").executes(c->labChain(c.getSource(),"camp_short_arc","")))
                        .then(Commands.literal("living_scar").executes(c->labChain(c.getSource(),"living_scar","")))
                        .then(Commands.literal("status").executes(c->labChain(c.getSource(),"status","")))
                        .then(Commands.literal("resume").then(Commands.argument("chain",StringArgumentType.word())
                            .executes(c->labChain(c.getSource(),"resume",StringArgumentType.getString(c,"chain")))))
                        .then(Commands.literal("suspend").then(Commands.argument("chain",StringArgumentType.word())
                            .executes(c->labChain(c.getSource(),"suspend",StringArgumentType.getString(c,"chain"))))))
                    .then(Commands.literal("disaster")
                        .then(Commands.literal("flood").executes(c->labDisaster(c.getSource(),"FLOOD",false)))
                        .then(Commands.literal("fire").executes(c->labDisaster(c.getSource(),"FIRE",false)))
                        .then(Commands.literal("storm").executes(c->labDisaster(c.getSource(),"STORM",false)))
                        .then(Commands.literal("landslide").executes(c->labDisaster(c.getSource(),"LANDSLIDE",false)))
                        .then(Commands.literal("collapse").executes(c->labDisaster(c.getSource(),"COLLAPSE",false)))
                        .then(Commands.literal("stop").executes(c->labDisaster(c.getSource(),"LANDSLIDE",true))))
                    .then(Commands.literal("growth").executes(c->labGrowth(c.getSource())))
                    .then(Commands.literal("goat").executes(c->labGoat(c.getSource()))
                        .then(Commands.literal("stop").executes(c->labGoatStop(c.getSource()))))
                    .then(Commands.literal("fauna").executes(c->labFauna(c.getSource(),false))
                        .then(Commands.literal("stop").executes(c->labFauna(c.getSource(),true))))
                    .then(Commands.literal("choice")
 .executes(c->labChoice(c.getSource(),"open","",""))
 .then(Commands.literal("status").executes(c->labChoice(c.getSource(),"status","","")))
 .then(choiceSelection(true)))
                    .then(Commands.literal("trace").executes(c->labTrace(c.getSource(),false))
                        .then(Commands.literal("status").executes(c->labTrace(c.getSource(),true))))
                    .then(Commands.literal("landslide").executes(c->labLandslide(c.getSource(),false))
                        .then(Commands.literal("stop").executes(c->labLandslide(c.getSource(),true))))
                    .then(Commands.literal("sound").executes(c->labPerception(c.getSource(),"safe_micro")))
                    .then(Commands.literal("mist").executes(c->labPerception(c.getSource(),"local_mist")))
                    .then(Commands.literal("steps").executes(c->labPerception(c.getSource(),"parallel_steps")))
                    .then(Commands.literal("status").executes(c->labStatus(c.getSource()))))
                .then(Commands.literal("watch")
                    .then(Commands.literal("on").executes(c -> watch(c.getSource(),true)))
                    .then(Commands.literal("off").executes(c -> watch(c.getSource(),false))))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> choiceSelection(boolean laboratory){
        var action=Commands.argument("action",StringArgumentType.word())
            .executes(c->laboratory?labChoice(c.getSource(),"select",StringArgumentType.getString(c,"instance"),StringArgumentType.getString(c,"action")):
                participantChoice(c.getSource(),StringArgumentType.getString(c,"instance"),StringArgumentType.getString(c,"action")));
        action.then(Commands.argument("anchor",StringArgumentType.word())
            .then(Commands.argument("effect",StringArgumentType.word())
                .then(Commands.argument("sequence",LongArgumentType.longArg(0))
                    .executes(c->contextChoice(c.getSource(),laboratory,StringArgumentType.getString(c,"instance"),
                        StringArgumentType.getString(c,"action"),StringArgumentType.getString(c,"anchor"),
                        StringArgumentType.getString(c,"effect"),LongArgumentType.getLong(c,"sequence"))))));
        return Commands.literal("select").then(Commands.argument("instance",StringArgumentType.word()).then(action));
    }
    /** Older 1.1.0 clients can still execute the button as a command; this is never C2S proof. */
    private static int contextChoice(CommandSourceStack source,boolean laboratory,String instance,String action,String anchor,String effect,long sequence){
        var game=laboratory?DirectorStandaloneMod.directorLifecycle().labGameplay(source.getServer()):
            DirectorStandaloneMod.directorLifecycle().runtimeGameplay(source.getServer());
        if(game.isEmpty()||!(source.getEntity() instanceof ServerPlayer player)||!player.isAlive()||laboratory&&!player.hasPermissions(4)){
            source.sendFailure(Component.literal("Joueur connecté, offre actuelle et autorisation requises."));return 0;
        }
        try{var reply=game.orElseThrow().selectChoiceContext(player,DirectorAnchorInteractionCommands.parse(instance,anchor,effect,sequence),DirectorAnchorInteractionCommands.action(action));
            source.sendSuccess(()->Component.literal("[World Director] "+reply),false);return 1;
        }catch(RuntimeException refused){source.sendFailure(Component.literal("Interaction refusée : référence actuelle requise."));return 0;}
    }
    private static int participantChoice(CommandSourceStack source,String instance,String action){
        var game=DirectorStandaloneMod.directorLifecycle().runtimeGameplay(source.getServer());
        if(game.isEmpty()||!(source.getEntity() instanceof ServerPlayer player)||!player.isAlive()){source.sendFailure(Component.literal("Joueur connecte et offre actuelle requis."));return 0;}
        try{var selected=switch(action){case "listen"->new ResourceId("proutlost:listen");case "leave_trace"->new ResourceId("proutlost:leave_trace");default->throw new IllegalArgumentException("Action inconnue.");};
            var reply=game.get().selectChoice(player,UUID.fromString(instance),selected);source.sendSuccess(()->Component.literal("[World Director] "+reply),false);return 1;
        }catch(RuntimeException rejected){source.sendFailure(Component.literal("Interaction refusee: "+rejected.getMessage()));return 0;}
    }
    private static int labCatalogue(CommandSourceStack source,String action,String value){
        var game=DirectorStandaloneMod.directorLifecycle().labGameplay(source.getServer());
        if(game.isEmpty()||!(source.getEntity() instanceof ServerPlayer player)||!player.hasPermissions(4)){
            source.sendFailure(Component.literal("Joueur OP4 et laboratoire WAL requis."));return 0;
        }
        try{var reply=game.get().catalogueCommand(player,action,value);
            source.sendSuccess(()->Component.literal("[Director TEST_ONLY] "+reply),false);return 1;
        }catch(RuntimeException refused){source.sendFailure(Component.literal("Catalogue refuse: "+refused.getMessage()));return 0;}
    }
    private static int runtimeLocations(CommandSourceStack source,String operation,String name){
        var game=DirectorStandaloneMod.directorLifecycle().runtimeGameplay(source.getServer());
        if(game.isEmpty()||!(source.getEntity() instanceof ServerPlayer player)||!player.hasPermissions(4)){
            source.sendFailure(Component.literal("Joueur OP4 et proprietaire J04 actif requis."));return 0;
        }
        try{String reply=switch(operation){case "status"->game.get().locationImportStatus();case "cancel"->game.get().cancelLocationImport(player,name);default->game.get().importLocations(player,name);};
            source.sendSuccess(()->Component.literal(reply),false);return 1;
        }catch(RuntimeException refused){source.sendFailure(Component.literal("Import refuse: "+refused.getMessage()));return 0;}
    }
    private static int labLocations(CommandSourceStack source,String name){
        var game=DirectorStandaloneMod.directorLifecycle().labGameplay(source.getServer());
        if(game.isEmpty()||!(source.getEntity() instanceof ServerPlayer player)||!player.hasPermissions(4)){source.sendFailure(Component.literal("Joueur OP4 et laboratoire WAL requis."));return 0;}
        try{var reply=name.isEmpty()?game.get().locationImportStatus():game.get().importLocations(player,name);source.sendSuccess(()->Component.literal("[Director LAB] "+reply),false);return 1;}
        catch(RuntimeException refused){source.sendFailure(Component.literal("Import refuse: "+refused.getMessage()));return 0;}
    }
    private static int labWaterProbe(CommandSourceStack source,boolean start){
        var game=DirectorStandaloneMod.directorLifecycle().labGameplay(source.getServer());
        if(game.isEmpty()||!(source.getEntity() instanceof ServerPlayer player)||!player.hasPermissions(4)){source.sendFailure(Component.literal("Joueur OP4 et laboratoire WAL requis."));return 0;}
        try{var reply=game.get().waterProbe(player,start);source.sendSuccess(()->Component.literal("[Director LAB] "+reply),false);return 1;}
        catch(RuntimeException refused){source.sendFailure(Component.literal("Mesure de nage refusee: "+refused.getMessage()));return 0;}
    }
    private static int labGrowth(CommandSourceStack source) {
        var coordinator=DirectorStandaloneMod.directorLifecycle().labGameplay(source.getServer());
        if(coordinator.isEmpty() || !(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Joueur OP4 et laboratoire J04 récupéré requis."));return 0;
        }
        try {
            var message=coordinator.get().begin(player);
            source.sendSuccess(()->Component.literal("[Director TEST_ONLY] "+message),false);return 1;
        } catch(RuntimeException rejected) {
            source.sendFailure(Component.literal("Événement refusé : "+rejected.getMessage()));return 0;
        }
    }
    private static int labGoat(CommandSourceStack source) {
        var coordinator=DirectorStandaloneMod.directorLifecycle().labGameplay(source.getServer());
        if(coordinator.isEmpty() || !(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Joueur OP4 et laboratoire WAL requis."));return 0;
        }
        try {
            var message=coordinator.orElseThrow().beginGoat(player);
            source.sendSuccess(()->Component.literal("[Director TEST_ONLY] "+message),false);return 1;
        }catch(RuntimeException refused){
            source.sendFailure(Component.literal("Goat LAB refusé : "+refused.getMessage()));return 0;
        }
    }
    private static int labGoatStop(CommandSourceStack source) {
        var coordinator=DirectorStandaloneMod.directorLifecycle().labGameplay(source.getServer());
        if(coordinator.isEmpty() || !(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Joueur OP4 et laboratoire WAL requis."));return 0;
        }
        try {
            var message=coordinator.orElseThrow().cancelGoat(player);
            source.sendSuccess(()->Component.literal("[Director TEST_ONLY] "+message),false);return 1;
        }catch(RuntimeException refused){
            source.sendFailure(Component.literal("Stop goat refusé : "+refused.getMessage()));return 0;
        }
    }
    private static int labFauna(CommandSourceStack source,boolean stop) {
        var game=DirectorStandaloneMod.directorLifecycle().labGameplay(source.getServer());
        if(game.isEmpty()||!(source.getEntity() instanceof ServerPlayer player)){
            source.sendFailure(Component.literal("Joueur OP4 et laboratoire WAL requis."));return 0;
        }
        try {
            var message=stop?game.get().cancelFauna(player):game.get().beginFauna(player);
            source.sendSuccess(()->Component.literal("[Director TEST_ONLY] "+message),false);return 1;
        }catch(RuntimeException refused){
            source.sendFailure(Component.literal("Migration LAB refusee : "+refused.getMessage()));return 0;
        }
    }
    private static int labChain(CommandSourceStack source,String mode,String id){
        var game=DirectorStandaloneMod.directorLifecycle().labGameplay(source.getServer());
        if(game.isEmpty()||!(source.getEntity() instanceof ServerPlayer player)||!player.hasPermissions(4)){
            source.sendFailure(Component.literal("Joueur OP4 et laboratoire WAL requis."));return 0;
        }
        try{
            var message=mode.equals("status")?game.get().chainStatus():
                mode.equals("suspend")?game.get().suspendChain(player,UUID.fromString(id)):
                mode.equals("resume")?game.get().resumeChain(player,UUID.fromString(id)):game.get().beginChain(player,mode);
            source.sendSuccess(()->Component.literal("[Director TEST_ONLY] "+message),false);return 1;
        }catch(RuntimeException refused){source.sendFailure(Component.literal("Chaine LAB refusee: "+refused.getMessage()));return 0;}
    }
    private static int labChoice(CommandSourceStack source,String mode,String instance,String action){
 var game=DirectorStandaloneMod.directorLifecycle().labGameplay(source.getServer());
 if(game.isEmpty()||!(source.getEntity() instanceof ServerPlayer player)||!player.hasPermissions(4)){
  source.sendFailure(Component.literal("Joueur OP4 et laboratoire WAL requis."));return 0;
 }
 try{
  String reply;
  if(mode.equals("open"))reply=game.get().beginChoice(player);
  else if(mode.equals("status"))reply=game.get().choiceStatus();
  else{
   ResourceId selected=switch(action){
    case "listen"->new ResourceId("proutlost:listen");
    case "leave_trace"->new ResourceId("proutlost:leave_trace");
    default->throw new IllegalArgumentException("listen ou leave_trace requis");
   };
   reply=game.get().selectChoice(player,UUID.fromString(instance),selected);
  }
  var message=reply;source.sendSuccess(()->Component.literal("[Director TEST_ONLY] "+message),false);return 1;
 }catch(RuntimeException rejected){
  source.sendFailure(Component.literal("Choix LAB refuse: "+rejected.getMessage()));return 0;
 }
}
    private static int labTrace(CommandSourceStack source,boolean status) {
        var life=DirectorStandaloneMod.directorLifecycle();var gameplay=life.labGameplay(source.getServer());
        if(gameplay.isEmpty()||!(source.getEntity() instanceof ServerPlayer player)){
            source.sendFailure(Component.literal("Joueur OP4 et laboratoire WAL requis."));return 0;
        }
        try{
            String message;
            if(!status)message=gameplay.get().beginTrace(player);
            else {
                var view=gameplay.get().labTraceStatus();message="Trace "+view.phase()+" / "+view.outcome();
                var after=view.after();
                if(after.isPresent()){
                    var anchor=life.labMemoryView(source.getServer()).map(m->m.anchors().get(after.get().anchorId())).orElse(null);
                    if(anchor!=null)message+=" | ancre="+anchor.anchorId().value()+" revision="+anchor.header().revision().value()
                        +" active="+anchor.active()+" tags="+anchor.tags();
                }
            }
            var reply=message;source.sendSuccess(()->Component.literal("[Director TEST_ONLY] "+reply),false);return 1;
        }catch(RuntimeException refused){
            source.sendFailure(Component.literal("Trace LAB refusee : "+refused.getMessage()));return 0;
        }
    }
    private static int labDisaster(CommandSourceStack source,String family,boolean stop) {
        var game=DirectorStandaloneMod.directorLifecycle().labGameplay(source.getServer());
        if(game.isEmpty()||!(source.getEntity() instanceof ServerPlayer player)||!player.hasPermissions(4)){
            source.sendFailure(Component.literal("Joueur OP4 et laboratoire WAL requis."));return 0;
        }
        try{
            var message=stop?game.get().cancelDisaster(player):game.get().beginDisaster(player,
                fr.proutlost.director.runtime.DisasterRunner.Family.valueOf(family));
            source.sendSuccess(()->Component.literal("[Director TEST_ONLY] "+message),false);return 1;
        }catch(RuntimeException refused){
            source.sendFailure(Component.literal("Catastrophe LAB refusee : "+refused.getMessage()));return 0;
        }
    }

    private static int labLandslide(CommandSourceStack source,boolean stop) {
        var coordinator=DirectorStandaloneMod.directorLifecycle().labGameplay(source.getServer());
        if(coordinator.isEmpty()||!(source.getEntity() instanceof ServerPlayer player)){
            source.sendFailure(Component.literal("Joueur OP4 et laboratoire WAL requis."));return 0;
        }
        try{
            var g=coordinator.orElseThrow();var message=stop?g.cancelLandslide(player):g.beginLandslide(player);
            source.sendSuccess(()->Component.literal("[Director TEST_ONLY] "+message),false);return 1;
        }catch(RuntimeException refused){
            source.sendFailure(Component.literal("Eboulement LAB refuse : "+refused.getMessage()));return 0;
        }
    }
    private static int labPerception(CommandSourceStack source,String name) {
        var coordinator=DirectorStandaloneMod.directorLifecycle().labGameplay(source.getServer());
        if(coordinator.isEmpty() || !(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Joueur OP4 et laboratoire WAL requis."));return 0;
        }
        try {
            var message=coordinator.orElseThrow().beginPerception(player,name);
            source.sendSuccess(()->Component.literal("[Director TEST_ONLY] "+message),false);return 1;
        }catch(RuntimeException refused){
            source.sendFailure(Component.literal("Perception LAB refusee : "+refused.getMessage()));return 0;
        }
    }
    private static int labStatus(CommandSourceStack source) {
        var message=DirectorStandaloneMod.directorLifecycle().labGameplay(source.getServer())
            .map(g->g.status()).orElse("DISABLED");
        source.sendSuccess(()->Component.literal("[Director LAB] "+message),false);return 1;
    }
    private static int productionRead(CommandSourceStack source,DirectorProductionRuntime runtime,Supplier<String> message){
        try{var text=message.get();source.sendSuccess(()->Component.literal(text),false);return 1;}
        catch(RuntimeException unavailable){source.sendFailure(Component.literal("[World Director PRODUCTION] DEFERRED phase="+runtime.phase()+" diagnostic="+runtime.diagnostic()));return 0;}
    }
    private static boolean unavailableProduction(CommandSourceStack source){
        var life=DirectorStandaloneMod.directorLifecycle();
        if(!life.productionConfigured())return false;
        var view=life.snapshot(source.getServer());source.sendFailure(Component.literal("[World Director PRODUCTION] runtime indisponible | phase="+view.phase()+" ports_manquants="+view.missingPortCount()));return true;
    }
    private static String report(MinecraftServer server) {
        var lifecycle=DirectorStandaloneMod.directorLifecycle();
        var production=lifecycle.productionRuntime(server);
        if(production.isPresent()){var runtime=production.get();return "[World Director] mode=PRODUCTION phase="+runtime.phase()+" ticks="+runtime.clock()+" ports_manquants="+runtime.missingPorts()+" | "+runtime.diagnostic();}
        if(lifecycle.productionConfigured()){var state=lifecycle.snapshot(server);return "[World Director] mode=PRODUCTION phase="+state.phase()+" ports_manquants="+state.missingPortCount()+" runtime=INDISPONIBLE";}
        var view=lifecycle.snapshot(server);
        var auto=lifecycle.autoSnapshot(server);
        return "[World Director] " + view.phase() +
            " | auto-checks=" + auto.evaluations() +
            " | ticks=" + view.observedTicks() +
            " | joueurs=" + auto.onlinePlayers() +
            " | ports manquants=" + view.missingPortCount() +
            " | admission=NON | calibration=EN_ATTENTE";
    }
    private static int status(CommandSourceStack source) {
        String message=report(source.getServer());
        source.sendSuccess(() -> Component.literal(message),false);
        return 1;
    }
    private static int health(CommandSourceStack source) {
        var production=DirectorStandaloneMod.directorLifecycle().productionRuntime(source.getServer());
        if(production.isPresent())return productionRead(source,production.get(),()->{var runtime=production.get();var world=runtime.worldState();
            return "[World Director] mode=PRODUCTION J04="+(runtime.storeReadOnlySafe()?"READ_ONLY_SAFE":"WRITABLE")+" phase="+runtime.phase()+" santé="+world.healthMode()+" contrôle="+world.runControl()+" révision="+world.header().revision().value()+" ports_manquants="+runtime.missingPorts()+"; qualification humaine/G5 distincte.";});
        if(unavailableProduction(source))return 0;
        var state=DirectorStandaloneMod.directorLifecycle().labStoreSnapshot(source.getServer());
        var detail=state.map(s -> s.phase().name()).orElse("AUCUN_STORE");
        source.sendSuccess(() -> Component.literal("[World Director] J04="+detail+
            "; qualification physique/clients/admin/calibration G5 incomplète; " +
            "aucune exécution automatique autorisée."),false);
        return 1;
    }
    private static int sensors(CommandSourceStack source) {
        var production=DirectorStandaloneMod.directorLifecycle().productionRuntime(source.getServer());
        if(production.isPresent())return productionRead(source,production.get(),()->{var view=production.get().observationStatus().orElseThrow();
            return "[World Director J01] mode=PRODUCTION échantillons="+view.samples()+" joueurs_observés="+view.currentPlayers()+" clients_négociés="+view.negotiatedClients()+" anchors_non_persistés="+view.pendingCampAnchors()+" tick="+view.sampledAtTick()+" capteurs_indisponibles="+view.unavailable();});
        if(unavailableProduction(source))return 0;
        var data=DirectorStandaloneMod.directorLifecycle().observationView(source.getServer());
        if(data.isEmpty()){
            source.sendFailure(Component.literal("J01 en attente du journal J04 vérifié."));
            return 0;
        }
        var view=data.orElseThrow();
        source.sendSuccess(()->Component.literal("[World Director J01] échantillons="+
            view.samples()+" joueurs_observés="+view.currentPlayers()+
            " clients_négociés="+view.negotiatedClients()+
            " anchors_non_persistés="+view.pendingCampAnchors()+
            " profil=TEST uniquement; événements=NON"),false);
        return 1;
    }
    private static int catalog(CommandSourceStack source){
        var production=DirectorStandaloneMod.directorLifecycle().productionRuntime(source.getServer());
        if(production.isPresent())return productionRead(source,production.get(),()->{var runtime=production.get();var view=runtime.catalogue();
            return "[World Director J02] catalogue_PRODUCTION=INSTALLÉ génération="+view.generation().value()+" hash="+view.hash().value()+" événements="+view.events().size()+" chaînes="+view.chains().size()+" primitives="+view.primitives().size()+" ports_manquants="+runtime.missingPorts()+" qualification_candidate="+runtime.releaseDiagnostic().map(report->Boolean.toString(report.qualificationCandidate())).orElse("INDISPONIBLE")+"; qualification humaine/G5 distincte.";});
        if(unavailableProduction(source))return 0;
        var guard=DirectorStandaloneMod.directorLifecycle().candidateCatalogView(source.getServer());
        if(guard.isEmpty()){
            source.sendFailure(Component.literal("J02 candidat non vérifié / indisponible."));
            return 0;
        }
        var view=guard.orElseThrow();
        var graph=DirectorStandaloneMod.directorLifecycle()
            .candidateDependencies(source.getServer());
        if(graph.isEmpty()){
            source.sendFailure(Component.literal("J02 dépendances non vérifiées."));
            return 0;
        }
        var index=graph.orElseThrow();
        source.sendSuccess(()->Component.literal("[World Director J02] SHA256 OK: "+
            view.verifiedEvents()+" événements candidats, "+view.verifiedChains()+
            " chaînes ; "+index.requiredPrimitives().size()+
            " primitives distinctes, "+index.requiredCapabilities().size()+
            " capacités requises; fournisseurs J05/J06 NON QUALIFIÉS. "+
            "Aucun catalogue PRODUCTION installé."),false);
        return 1;
    }
    private static int memory(CommandSourceStack source){
        boolean production=DirectorStandaloneMod.directorLifecycle().productionRuntime(source.getServer()).isPresent();
        if(!production&&unavailableProduction(source))return 0;
        var guard=DirectorStandaloneMod.directorLifecycle().memoryProjectionView(source.getServer());
        if(guard.isEmpty()){
            source.sendFailure(Component.literal("J04 mémoire non récupérée."));
            return 0;
        }
        var view=guard.orElseThrow();
        if(!view.complete()||view.failClosed()){
            source.sendFailure(Component.literal("J04 mémoire indisponible / fail-closed."));
            return 0;
        }
        source.sendSuccess(()->Component.literal("[World Director J04] mémoire vérifiée: "+
            view.observedPlayers()+" joueurs, "+view.observedRegionMemories()+
            " mémoires régionales; séquence durable="+view.lastDurableSequence()+
            "; scans="+view.completedScans()+(production?"; mode=PRODUCTION, lecture seule.":"; TEST lecture seule, admission=NON.")),false);
        return 1;
    }
    private static int wal(CommandSourceStack source){
        var production=DirectorStandaloneMod.directorLifecycle().productionRuntime(source.getServer());
        if(production.isPresent())return productionRead(source,production.get(),()->{var view=production.get().ingestionStatus().orElseThrow();
            return "[World Director J01/J04] mode=PRODUCTION "+view.phase()+" lots="+view.durableBatches()+"/"+view.attemptedBatches()+" observations="+view.durableObservations()+" séquence="+view.projectedSequence()+"; lecture seule.";});
        if(unavailableProduction(source))return 0;
        var guard=DirectorStandaloneMod.directorLifecycle().labIngestionView(source.getServer());
        if(guard.isEmpty()){
            source.sendFailure(Component.literal("J01 -> J04 WAL de qualification indisponible."));
            return 0;
        }
        var view=guard.orElseThrow();
        source.sendSuccess(()->Component.literal("[World Director J01/J04] "+
            view.phase()+" lots="+view.durableBatches()+"/"+view.attemptedBatches()+
            " observations="+view.durableObservations()+
            " séquence="+view.projectedSequence()+
            " ; sans exécution physique, statut LAB ONLY."),false);
        return 1;
    }
    private static int decision(CommandSourceStack source) {
        var production=DirectorStandaloneMod.directorLifecycle().productionRuntime(source.getServer());
        if(production.isPresent())return productionRead(source,production.get(),()->{var runtime=production.get();var world=runtime.worldState();
            var scheduler=runtime.gameplay().flatMap(game->game.coordinator().runtime()).map(value->value.status().replace("automatic_lab=","automatic=")).orElse("RECOVERING");
            return "[World Director J03] mode=PRODUCTION phase="+runtime.phase()+" contrôle="+world.runControl()+" ports_manquants="+runtime.missingPorts()+" | "+scheduler;});
        if(unavailableProduction(source))return 0;
        var state=fr.proutlost.director.bootstrap.DirectorNativeDecisionReadiness
            .inspect(source.getServer());
        String reason=String.join(", ",new TreeSet<>(state.blockers()));
        source.sendSuccess(()->Component.literal(
            "[World Director J03] evaluation="+
            (state.readyForPureEvaluation()?"PREREQUIS_VERIFIES":"BLOQUEE")+
            " | J04_monde_persistant="+state.persistedWorldStateAvailable()+
            " | memoire="+state.memoryAvailable()+
            " | observations="+state.observationAvailable()+
            " | catalogue_PRODUCTION="+state.qualifiedCatalogAvailable()+
            " | admission=NON | blocages="+reason),false);
        return 1;
    }
    private static int watch(CommandSourceStack source,boolean enabled) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Réservé à un administrateur joueur connecté."));
            return 0;
        }
        var server=source.getServer();
        var group=watching.computeIfAbsent(server,unused->new LinkedHashSet<>());
        if(enabled) {
            if(!group.contains(player.getUUID()) && group.size()>=MAX_SUBSCRIBERS) {
                source.sendFailure(Component.literal("Surveillance saturée (maximum 16)."));
                return 0;
            }
            group.add(player.getUUID());
        } else group.remove(player.getUUID());
        source.sendSuccess(() -> Component.literal("[World Director] Surveillance lecture seule "+
            (enabled?"activée (5 s)":"désactivée")),false);
        return 1;
    }

    public static void tick(ServerTickEvent.Post event) {
        var server=event.getServer();
        if(!server.isSameThread())return;
        var group=watching.get(server);
        if(group==null||group.isEmpty())return;
        var status=DirectorStandaloneMod.directorLifecycle().snapshot(server);
        if(status.observedTicks()%PUSH_INTERVAL!=0)return;
        var message=Component.literal(report(server));
        var iterator=group.iterator();
        while(iterator.hasNext()) {
            UUID id=iterator.next();
            ServerPlayer player=server.getPlayerList().getPlayer(id);
            if(player==null || !player.createCommandSourceStack().hasPermission(4))
                iterator.remove();
            else player.sendSystemMessage(message);
        }
        if(group.isEmpty())watching.remove(server);
    }
    public static void stopped(ServerStoppedEvent event) {
        watching.remove(event.getServer());
    }
    public static int watchers(MinecraftServer server) {
        if(!server.isSameThread())throw new IllegalStateException("actor");
        return watching.getOrDefault(server,Set.of()).size();
    }
}
