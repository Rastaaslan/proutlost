package fr.proutlost.director.admin.client;

import fr.proutlost.director.api.model.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import java.util.*;
import java.util.function.*;

/** Native Minecraft 1.21.1 screen. Bootstrap supplies typed network dispatch callbacks;
 * all deliveries must be marshalled to the client main thread by the transport. */
public final class DirectorScreen extends Screen {
    private static final String[] TABS={"MONDE","ACTIFS","MÉMOIRE","DÉCISIONS","LABORATOIRE"};
    private final SessionId actorSession;
    private final SessionIdentity networkSession;
    private final Consumer<AdminQuery> queries;
    private final Consumer<AdminCommand> commands;
    private final BiFunction<AdminOperation,String,AdminPayload> payloads;
    private final Consumer<ResourceId> simulations;
    private ResourceId requestedSimulation;
    private boolean simulationAwaitingRefresh;
    private final AdminPageState page=new AdminPageState();
    private final AdminCommandState commandState=new AdminCommandState(64);
    private final int refreshTicks,commandGapTicks,pageRows;
    private AdminPanel panel=AdminPanel.WORLD;
    private int operationIndex, rowIndex, detailOffset, ticks, nextQuery, nextCommand;
    private EditBox filter,arguments;
    private Button operationButton,sendButton;
    private String message="";
    private RequestId lastCommand;
    private AdminRow renderedRow;
    private int renderedWidth;
    private List<net.minecraft.util.FormattedCharSequence> renderedLines=List.of();

    public DirectorScreen(SessionId actorSession,SessionIdentity networkSession,
            Consumer<AdminQuery> queries,Consumer<AdminCommand> commands,
            BiFunction<AdminOperation,String,AdminPayload> payloads,int refreshTicks,int commandGapTicks,int pageRows) {
        this(actorSession,networkSession,queries,commands,payloads,id->{throw new IllegalArgumentException("Server simulation route absent");},refreshTicks,commandGapTicks,pageRows);
    }
    public DirectorScreen(SessionId actorSession,SessionIdentity networkSession,
            Consumer<AdminQuery> queries,Consumer<AdminCommand> commands,
            BiFunction<AdminOperation,String,AdminPayload> payloads,Consumer<ResourceId> simulations,
            int refreshTicks,int commandGapTicks,int pageRows) {
        super(Component.literal("World Director"));
        this.actorSession=Objects.requireNonNull(actorSession);this.networkSession=Objects.requireNonNull(networkSession);
        this.queries=Objects.requireNonNull(queries);this.commands=Objects.requireNonNull(commands);this.payloads=Objects.requireNonNull(payloads);this.simulations=Objects.requireNonNull(simulations);
        if(refreshTicks<1||commandGapTicks<1||pageRows<1||pageRows>50) throw new IllegalArgumentException("limits");
        this.refreshTicks=refreshTicks;this.commandGapTicks=commandGapTicks;this.pageRows=pageRows;
        page.reset(networkSession);commandState.reset(actorSession);
    }
    @Override protected void init() {
        String savedFilter=filter==null?"":filter.getValue();
        String savedArguments=arguments==null?"":arguments.getValue();
        boolean compact=height<360;
        int tabWidth=Math.max(20,(width-20)/5);
        for(var tab:AdminPanel.values()) addRenderableWidget(Button.builder(Component.literal(TABS[tab.ordinal()]),b->{
            clearSimulationSelection();panel=tab;operationIndex=0;rowIndex=0;detailOffset=0;filter.setValue("");page.invalidate();updateOperation();
        }).bounds(10+tab.ordinal()*tabWidth,25,tabWidth-2,20).build());
        filter=addRenderableWidget(new EditBox(font,10,51,Math.max(30,width-180),20,Component.literal("Filtre (UUID, ou player UUID)")));
        filter.setMaxLength(128);
        filter.setValue(savedFilter);
        filter.setHint(Component.literal("UUID ; mutation UUID ; player UUID ; chain UUID ; fixture"));
        addRenderableWidget(Button.builder(Component.literal("Rechercher"),b->request(Optional.empty())).bounds(width-165,51,85,20).build());
        addRenderableWidget(Button.builder(Component.literal("Suivante"),b->page.snapshot().flatMap(AdminSnapshot::nextCursor).ifPresent(c->request(Optional.of(c))))
                .bounds(width-77,51,67,20).build());
        operationButton=addRenderableWidget(Button.builder(Component.literal(""),b->{if(!operations().isEmpty()){operationIndex=(operationIndex+1)%operations().size();updateOperation();}})
                .bounds(10,height-57,110,20).build());
        arguments=addRenderableWidget(new EditBox(font,125,height-57,Math.max(30,width-215),20,Component.literal("Paramètres de l'action")));
        arguments.setMaxLength(1000);
        arguments.setValue(savedArguments);
        sendButton=addRenderableWidget(Button.builder(Component.literal("Envoyer"),b->send()).bounds(width-85,height-57,75,20).build());
        addRenderableWidget(Button.builder(Component.literal("< Objet"),b->{clearSimulationSelection();rowIndex=Math.max(0,rowIndex-1);detailOffset=0;}).bounds(10,height-82,compact?55:75,20).build());
        addRenderableWidget(Button.builder(Component.literal("Objet >"),b->{clearSimulationSelection();rowIndex=Math.min(page.snapshot().map(s->Math.max(0,s.rows().size()-1)).orElse(0),rowIndex+1);detailOffset=0;}).bounds(compact?70:90,height-82,compact?55:75,20).build());
        addRenderableWidget(Button.builder(Component.literal("↑"),b->detailOffset=Math.max(0,detailOffset-5)).bounds(compact?130:170,height-82,25,20).build());
        addRenderableWidget(Button.builder(Component.literal("↓"),b->detailOffset+=5).bounds(compact?160:200,height-82,25,20).build());
        if(!compact)addRenderableWidget(Button.builder(Component.literal("< Résultat"),b->selectResult(-1)).bounds(10,height-115,80,20).build());
        addRenderableWidget(Button.builder(Component.literal(compact?"Résultats":"Résultat >"),b->selectResult(1)).bounds(compact?195:95,height-(compact?82:115),compact?75:80,20).build());
        addRenderableWidget(Button.builder(Component.literal("Ouvrir"),b->openResult()).bounds(compact?275:180,height-(compact?82:115),60,20).build());
        addRenderableWidget(Button.builder(Component.literal("Classer"),b->{
            if(lastCommand!=null && commandState.dismiss(lastCommand)) {lastCommand=null;selectResult(-1);}
        }).bounds(compact?340:245,height-(compact?82:115),compact?70:60,20).build());
        updateOperation();
    }
    private List<AdminOperation> operations() {
        return AdminClientOperationMenu.forPanel(panel);
    }
    private void updateOperation() {
        var available=operations();
        if(operationButton!=null) {
            operationButton.active=!available.isEmpty();
            operationButton.setMessage(Component.literal(available.isEmpty()
                ? "LECTURE SEULE" : available.get(operationIndex).name()));
        }
        if(sendButton!=null)sendButton.active=!available.isEmpty();
        if(filter!=null)
            filter.setHint(Component.literal(panel==AdminPanel.LABORATORY
                ? "proutlost_director:candidate/safe_micro ou camp_regrowth"
                : "UUID ; mutation UUID ; player UUID ; chain UUID ; fixture"));
    }
    private AdminFilter typedFilter() {
        String value=filter.getValue().trim();
        return switch(panel) {
            case WORLD -> new WorldFilter();
            // "chain" lists actual J04-backed chains; "chain UUID" selects
            // one. Blank lists events. Neither view grants execution rights.
            case ACTIVE -> value.equalsIgnoreCase("chain")?
                    new ChainFilter(Optional.empty()):
                    value.startsWith("mutation ")?new MutationFilter(MutationId.parse(value.substring(9).trim())):
                    value.startsWith("chain ")?new ChainFilter(
                        Optional.of(ChainId.parse(value.substring(6).trim()))):
                    new EventFilter(value.isEmpty()?Optional.empty():
                        Optional.of(EventInstanceId.parse(value)),Set.of());
            case MEMORY -> value.startsWith("player ")?new PlayerFilter(PlayerId.parse(value.substring(7))):
                    new RegionFilter(value.isEmpty()?Optional.empty():Optional.of(RegionId.parse(value)),Set.of());
            case DECISIONS -> new DecisionFilter(value.isEmpty()?Optional.empty():Optional.of(DecisionId.parse(value)));
            case LABORATORY -> new LaboratoryFilter(value.isEmpty()?Optional.empty():Optional.of(new ResourceId(value)));
        };
    }
    private void request(Optional<String> cursor) {
        if(ticks<nextQuery || !page.canRetry(ticks)) {message="Rafraîchissement limité";return;}
        final AdminQuery query;
        try {
            var expected=cursor.isPresent()?page.snapshot().map(AdminSnapshot::snapshotRevision):Optional.<Revision>empty();
            query=new AdminQuery(actorSession,new RequestId(UUID.randomUUID()),panel,typedFilter(),cursor,expected,pageRows);
        } catch(IllegalArgumentException e) { message="Filtre invalide";return; }
        if(requestedSimulation!=null&&!(query.filter() instanceof LaboratoryFilter f&&f.fixtureId().filter(requestedSimulation::equals).isPresent()))clearSimulationSelection();
        simulationAwaitingRefresh=false;
        nextQuery=ticks+refreshTicks;rowIndex=0;detailOffset=0;message="Chargement…";
        if(!AdminQueryDispatch.send(page,networkSession,query,queries,ticks,refreshTicks))
            message="Envoi de requête impossible — réessayer avec Rechercher";
    }
    private void send() {
        if(ticks<nextCommand) {message="Débit de commandes limité";return;}
        var snapshot=page.snapshot();
        if(operations().isEmpty()) {
            message="Onglet de consultation seulement ; aucune action qualifiée";return;
        }
        var op=operations().get(operationIndex);
        if(!AdminClientOperationMenu.available(panel,op)) {
            message="Action non qualifiée : lecture seule actuellement";return;
        }
        // Native TEST_ONLY J04 projections advertise no mutation capabilities.
        // Six reviewed inspection commands may use a current page, but the
        // server still reauthenticates the actor as OP4 for each request.
        if(!AdminClientOperationMenu.canSend(panel,op,snapshot)) {
            message="Lecture J04 indisponible";return;
        }
        try {
            if(op==AdminOperation.SIMULATE){sendSimulation();return;}
            var payload=op==AdminOperation.TENSION?
                AdminClientReadPayloads.parse(op,arguments.getValue(),snapshot.orElseThrow().capturedTick()):payloads.apply(op,arguments.getValue());
            var command=new AdminCommand(actorSession,new RequestId(UUID.randomUUID()),snapshot.orElseThrow().snapshotRevision(),op,payload);
            var outcome=AdminClientReadPayloads.readOnly(op)?AdminCommandDispatch.sendReadOnly(commandState,command,commands):
                AdminCommandDispatch.send(commandState,command,commands);
            if(outcome==AdminCommandDispatch.Outcome.NOT_ACCEPTED) {message="Trop de commandes en attente";return;}
            nextCommand=ticks+commandGapTicks;
            if(outcome==AdminCommandDispatch.Outcome.READ_DELIVERY_FAILED) {
                // The request may have reached the server, but cannot change
                // the world. Free the local slot; retry is explicit only.
                message="Envoi de lecture incertain — relancer si nécessaire";
                return;
            }
            lastCommand=command.requestId();
            message=(outcome==AdminCommandDispatch.Outcome.DELIVERY_UNCERTAIN?
                "Livraison incertaine ; conserver la meme requete ":"PENDING ")+lastCommand.value();
        } catch(IllegalArgumentException|ArithmeticException e) {message="Paramètres invalides";}
    }
    private void clearSimulationSelection(){requestedSimulation=null;simulationAwaitingRefresh=false;}
    private void sendSimulation(){
        var fixture=AdminClientReadPayloads.simulationFixture(arguments.getValue());
        requestedSimulation=fixture;simulationAwaitingRefresh=true;nextCommand=ticks+commandGapTicks;
        filter.setValue(fixture.value());rowIndex=0;detailOffset=0;page.invalidate();
        try{simulations.accept(fixture);message="Simulation envoyee, non confirmee ici. Rechercher : dernier resultat disponible de "+fixture.value();}
        catch(RuntimeException uncertain){message="Envoi de simulation incertain. Verifier la reponse canonique ; Rechercher lit le dernier resultat.";}
    }
    public void receive(SessionIdentity session,AdminSnapshot snapshot) {
        if(requestedSimulation!=null&&!snapshot.rows().isEmpty()&&!AdminClientReadPayloads.simulationResultMatches(snapshot,requestedSimulation)){
            if(page.reject(session,snapshot.requestId(),fr.proutlost.director.api.error.ReasonCode.STALE_CONTEXT,ticks,refreshTicks))
                message="Resultat d'une autre fixture refuse ; Rechercher pour reprendre";
            return;
        }
        if(page.receive(session,snapshot)){
            message=snapshot.rows().isEmpty()?(snapshot.nextCursor().isPresent()?"Fenetre WAL sans resultat ; Suivante pour poursuivre":"Aucun resultat"):"";
            if(requestedSimulation!=null)message=snapshot.rows().isEmpty()?"Aucun calcul disponible ; Rechercher apres la reponse canonique":
                "Dernier calcul disponible de "+requestedSimulation.value()+" ; cette lecture ne confirme pas le nouvel envoi.";
        }
    }
    /** Transport forwards query rejections with the original request ID on the client thread. */
    public void receiveQueryFailure(SessionIdentity session,RequestId requestId,fr.proutlost.director.api.error.ReasonCode reason) {
        Objects.requireNonNull(reason);
        if(page.reject(session,requestId,reason,ticks,refreshTicks)) {
            message=reason+" — réessayer avec Rechercher";
            nextQuery=Math.max(nextQuery,ticks+refreshTicks);
        }
    }
    public void receive(SessionIdentity session,AdminCommandResult result) {
        if(!networkSession.equals(session)) return;
        if(commandState.receive(actorSession,result) && result.commandId().equals(lastCommand)) showResult();
    }
    private void selectResult(int direction) {
        var ids=commandState.requestIds();
        if(ids.isEmpty()) {lastCommand=null;message="Aucune commande conservée";return;}
        int current=ids.indexOf(lastCommand);
        lastCommand=ids.get(current<0?(direction<0?ids.size()-1:0):Math.floorMod(current+direction,ids.size()));
        showResult();
    }
    private void showResult() {
        if(lastCommand==null) return;
        commandState.result(lastCommand).ifPresent(result->message=result.status()+" "+result.commandId().value()+" "+result.reasonCodes()
                +result.resultRef().map(ref->" → "+ref.kind()+" "+ref.id()).orElse(""));
    }
    private void openResult() {
        if(lastCommand==null) return;
        var destination=commandState.resultQuery(lastCommand,new RequestId(UUID.randomUUID()),pageRows);
        if(destination.isEmpty()) {message="Résultat sans filtre disponible ; référence conservée";return;}
        var query=destination.get();
        clearSimulationSelection();panel=query.panel();operationIndex=0;rowIndex=0;detailOffset=0;
        String value=switch(query.filter()) {
            case WorldFilter ignored -> "";
            case DecisionFilter f -> f.decisionId().map(id->id.value().toString()).orElse("");
            case LaboratoryFilter f -> f.fixtureId().map(ResourceId::value).orElse("");
            case ChainFilter f -> f.chainId().map(id->"chain "+id.value()).orElse("");
            case RegionFilter f -> f.regionId().map(id->id.value().toString()).orElse("");
            case PlayerFilter f -> "player "+f.playerId().value();
            case MutationFilter f -> "mutation "+f.mutationId().value();
            case EventFilter f -> f.instanceId().map(id->id.value().toString()).orElse("");
        };
        filter.setValue(value);updateOperation();page.invalidate();request(Optional.empty());
    }
    public void invalidate(SessionIdentity session,AdminDelta delta) { if(networkSession.equals(session)){clearSimulationSelection();page.invalidate();} }
    private String hint() {
        if(operations().isEmpty())return "Consultation J04 en lecture seule ; aucune action J03/J05 autorisée";
        return switch(operations().get(operationIndex)) {
            case TENSION -> "CALM|UNUSUAL|TENSE|EXCEPTIONAL duréeTicks";
            case INTENSITY -> "Identifiant du profil approuvé";
            case STOP -> "UUID d'instance ou all — arrêt sûr";
            case RESCUE -> "UUID joueur ou self";
            case REPORT -> "Message (1000 caractères maximum)";
            case TRIGGER -> "eventId region UUID [targets UUID,UUID] — admission normale";
            case SIMULATE -> "fixtureId serveur enregistree ; Rechercher apres le retour canonique";
            case REPLAY -> "decisionId [without_ai] — calcul uniquement";
            case STABILIZE -> "disasterId stabilize";
            case MUTATION_INSPECT -> "Mutation UUID — lecture J04 et propriétaires courants";
            case EVENT_INSPECT, REGION, PLAYER, CHAIN, AUDIT_EXPORT -> "UUID à inspecter ou exporter";
            default -> "Aucun paramètre";
        };
    }
    @Override public void tick() {
        super.tick();ticks++;
        if(page.needsQuery()&&!simulationAwaitingRefresh&&ticks>=nextQuery)request(Optional.empty());
        if(sendButton!=null)sendButton.active=!operations().isEmpty()&&AdminClientOperationMenu.canSend(panel,operations().get(operationIndex),page.snapshot());
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { clearSimulationSelection();page.close();commandState.close();super.onClose(); }
    @Override public void renderBackground(GuiGraphics graphics,int mouseX,int mouseY,float partialTick) {
        graphics.fill(0,0,width,height,0xf0182028);
    }
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partialTick) {
        // Screen.render draws background before widgets in pinned NeoForge 1.21.1.
        // Draw custom text afterwards so the default blur never erases the content.
        super.render(graphics,mouseX,mouseY,partialTick);
        boolean compact=height<360;
        graphics.drawString(font,title,10,10,0xffffff);
        if(page.snapshot().isEmpty()) {
            String state=page.pending()?"Chargement J04…":page.failureReason()
                .map(r->"Lecture refusée : "+r).orElse("Aucune donnée ; Rechercher pour charger");
            int y=94;
            for(var line:font.split(Component.literal(state),Math.max(20,width-20))) {
                if(y>=height-(compact?95:145))break;
                graphics.drawString(font,line,10,y,0xffcc88);y+=10;
            }
        }
        page.snapshot().ifPresent(snapshot->{
            graphics.drawString(font,"Révision "+snapshot.snapshotRevision().value()+" • capturé au tick "+snapshot.capturedTick().value()+" • durable "+snapshot.lastDurableSequence().value(),10,77,0xaacccc);
            if(panel==AdminPanel.LABORATORY)
                graphics.drawString(font,"Simulation : calcul uniquement ; declenchement soumis a admission serveur.",10,94,0xffcc88);
            if(!snapshot.rows().isEmpty()) {
                var row=snapshot.rows().get(Math.min(rowIndex,snapshot.rows().size()-1));
                if(renderedRow!=row||renderedWidth!=width) {
                    renderedRow=row;renderedWidth=width;
                    renderedLines=AdminDetails.lines(row,512).stream().flatMap(line->font.split(Component.literal(line),Math.max(20,width-25)).stream()).toList();
                }
                var lines=renderedLines;
                detailOffset=Math.min(detailOffset,Math.max(0,lines.size()-1));
                int y=panel==AdminPanel.LABORATORY?110:94;
                for(int i=detailOffset;i<lines.size()&&y<height-(compact?95:145);i++,y+=10) graphics.drawString(font,lines.get(i),10,y,0xdddddd);
            }
        });
        if(!compact && lastCommand!=null) commandState.result(lastCommand).ifPresent(result->{
            String reference=result.resultRef().map(ref->ref.kind()+" "+ref.id()).orElse("Sans référence");
            graphics.drawString(font,font.plainSubstrByWidth(result.status()+" • "+lastCommand.value(),Math.max(20,width-20)),10,height-140,0xaacccc);
            graphics.drawString(font,font.plainSubstrByWidth(reference,Math.max(20,width-20)),10,height-128,0xaacccc);
        });
        graphics.drawString(font,font.plainSubstrByWidth(message,Math.max(20,width-20)),10,height-35,0xffcc88);
        graphics.drawString(font,font.plainSubstrByWidth(hint(),Math.max(20,width-20)),10,height-17,0xaaaaaa);
    }
}
