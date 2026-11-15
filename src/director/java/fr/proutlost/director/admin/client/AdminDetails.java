package fr.proutlost.director.admin.client;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.codec.*;
import java.util.*;

/** Bounded, structured detail text for all closed AdminData variants. */
public final class AdminDetails {
    private AdminDetails() {}
    public static List<String> lines(AdminRow row,int limit) {
        if(limit<2) throw new IllegalArgumentException("line limit");
        var json=CanonicalJsonV1.decode(new ModelCodec<>(AdminRow.class).encode(row));
        List<String> lines=new ArrayList<>();
        if(row.data() instanceof WorldAdminData data)worldSummary(data,lines,limit);
        if(row.data() instanceof EventAdminData data)eventSummary(data,lines,limit);
        if(row.data() instanceof DisasterAdminData data)disasterSummary(data.disaster(),lines,limit);
        if(row.data() instanceof MutationAdminData data)mutationSummary(data,lines,limit);
        append(json,"",lines,limit);
        if(lines.size()==limit) lines.set(limit-1,"Détail limité : utiliser l'export audit pour le document complet.");
        return List.copyOf(lines);
    }
    private static void append(JsonValue value,String path,List<String> lines,int limit) {
        if(lines.size()>=limit) return;
        switch(value) {
            case JsonValue.ObjectValue object -> object.values().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->append(e.getValue(),path.isEmpty()?label(e.getKey()):path+" / "+label(e.getKey()),lines,limit));
            case JsonValue.Array array -> { if(array.values().isEmpty()) lines.add(path+" : —");else for(int i=0;i<array.values().size()&&lines.size()<limit;i++) append(array.values().get(i),path+" ["+(i+1)+"]",lines,limit); }
            case JsonValue.Text text -> lines.add(path+" : "+text.value());
            case JsonValue.Number number -> lines.add(path+" : "+number.value());
            case JsonValue.Bool bool -> lines.add(path+" : "+(bool.value()?"oui":"non"));
            case JsonValue.Nil ignored -> lines.add(path+" : —");
        }
    }
    private static void worldSummary(WorldAdminData data,List<String> lines,int limit) {
        var world=data.state();var budget=data.budgets();
        boolean lab=world.intensityProfileId().value().equals("proutlost_director:lab_only");
        add(lines,limit,"MONDE / controle durable : "+world.runControl()+" / sante : "+world.healthMode());
        if(lab)add(lines,limit,"LAB / TEST_ONLY - aucune approbation de production");
        add(lines,limit,"Pacing : "+world.pacingState()+" / tension : "+world.tension());
        add(lines,limit,"Profil durable : "+world.intensityProfileId().value());
        add(lines,limit,"Revision : "+world.header().revision().value()+" / tick : "+world.simTick().value());
        add(lines,limit,"Boot : "+world.bootEpoch().value()+" / generation : "+world.storeGeneration().value());
        var engine=world.engineControl();
        long remaining=Math.max(0L,engine.pacingDeadlineTick().value()-world.simTick().value());
        add(lines,limit,"Phase depuis tick : "+engine.pacingEnteredTick().value()+" / prochaine transition tick : "+engine.pacingDeadlineTick().value());
        add(lines,limit,"Delai restant : "+remaining+" ticks de simulation / controle : "+world.runControl());
        add(lines,limit,"Moteur revision : "+engine.revision().value()+" / credit service requis : "+engine.requiredServiceCredit().value());
        add(lines,limit,"BUDGET / reserve physique WAL / capacite du profil");
        var used=budget.reserved();var cap=budget.capacity();
        add(lines,limit,"Entites vivantes : "+used.liveEntities()+" / "+cap.liveEntities());
        add(lines,limit,"Entites persistantes : "+used.persistentEntities()+" / "+cap.persistentEntities());
        add(lines,limit,"Mutations : "+used.mutationsInFlight()+" / "+cap.mutationsInFlight());
        add(lines,limit,"Regions avec debit projete : "+budget.scopes().size());
        if(lab){
            add(lines,limit,"Capacites LAB : tick ms="+cap.tickMillis()+" ; intensite globale="+cap.globalIntensity()+" ; regionale="+cap.regionalIntensity());
            add(lines,limit,"Capacites LAB : file IO="+cap.queuedIoBytes()+" ; unites client="+cap.clientUnits()+" ; payload="+cap.payloadBytes()+" ; navigation="+cap.navigationNodes());
            add(lines,limit,"Reservations runtime CPU/client/IO non jointes ; les zeros bruts ne prouvent pas une absence de charge.");
            add(lines,limit,"Files d'attente et erreurs runtime non jointes a cette lecture.");
        }
    }
    private static void eventSummary(EventAdminData data,List<String> lines,int limit) {
        var event=data.event();
        add(lines,limit,"EVENEMENT / etat durable : "+event.state());
        add(lines,limit,"Recette originale : "+event.definition().id().value());
        add(lines,limit,"Evenement : "+event.instanceId().value());
        add(lines,limit,"Phase native : "+event.phase());
        add(lines,limit,"Mutations jointes a la meme coupe J04 : "+data.mutations().size());
        add(lines,limit,"Reservations : "+event.reservationRefs().size()+" references WAL presentes");
        if(!event.reservationRefs().isEmpty() && data.claims().isEmpty())
            add(lines,limit,"Details claims non joints ; liste vide ne signifie pas absence de reservation.");
        else add(lines,limit,"Claims joints par cette vue : "+data.claims().size());
        add(lines,limit,"Effets : "+event.effectRefs().size()+" references WAL ; details d'effet non joints");
        add(lines,limit,"Faits joints : "+data.facts().size()+" ; liste vide ne prouve pas absence dans le WAL.");
        add(lines,limit,"Lecture J04 ; ces references ne donnent aucune autorite d'execution.");
    }
    private static void disasterSummary(DisasterInstance disaster,List<String> lines,int limit) {
        add(lines,limit,"CATASTROPHE / phase durable : "+disaster.stage());
        add(lines,limit,"Recette : "+disaster.recipeId().value());
        add(lines,limit,"Catastrophe : "+disaster.disasterId().value());
        add(lines,limit,"Evenement lie : "+disaster.eventInstanceId().value());
        add(lines,limit,"Revision : "+disaster.header().revision().value()+" / tick : "+disaster.header().updatedTick().value());
        var warning=disaster.warningEvidence();
        add(lines,limit,"WARN WAL : lisible="+warning.readable()+" / perimetre="+warning.persistentPerimeter()+
            " / delai="+warning.leadTime().value()+" ticks");
        add(lines,limit,"Public affecte : "+disaster.affectedAudience().size()+" / sorties : "+disaster.evacuationRoutes().size());
        add(lines,limit,"Stabilisation : "+disaster.stabilizationState());
        add(lines,limit,"References mutations : "+disaster.mutationRefs().size()+
            " / IDs consequences (faits non joints) : "+disaster.durableConsequenceRefs().size());
        add(lines,limit,"Etat lu dans J04 ; cette page ne donne aucune autorite d'execution.");
    }
    private static void mutationSummary(MutationAdminData data,List<String> lines,int limit) {
        var mutation=data.mutation();
        add(lines,limit,"MUTATION / lecture J04 ; état : "+mutation.state());
        add(lines,limit,"Mutation : "+mutation.mutationId().value());
        add(lines,limit,"Evenement réel lié : "+mutation.instanceId().value()+" (sans EventAggregate requis)");
        add(lines,limit,"Primitive : "+mutation.primitiveId().value()+" / tranche : "+mutation.sliceId().value());
        add(lines,limit,"PRE : "+mutation.preStateRef().codecId().value()+" / "+mutation.preStateRef().hash().value());
        add(lines,limit,"POST : "+mutation.postStateRef().codecId().value()+" / "+mutation.postStateRef().hash().value());
        add(lines,limit,"Owner mutation : "+mutation.ownerToken().value()+" / epoch : "+mutation.ownerEpoch().value());
        for(var resource:data.inspectedResources().stream().sorted(Comparator.comparing(ResourceKey::toString)).toList()) {
            var owner=data.ownership().records().get(resource);
            add(lines,limit,"Ressource : "+resource.domain()+" / "+resource.dimension().value()+" / "+resource.key());
            if(owner==null) {
                add(lines,limit,"Propriétaire courant : ABSENT dans la coupe J04");
            } else {
                add(lines,limit,"Propriétaire courant : "+owner.disposition()+" / creationMutationId : "+owner.creationMutationId().value());
                add(lines,limit,"currentOwnerIsSameMutation="+owner.creationMutationId().equals(mutation.mutationId())+
                    " / epoch : "+owner.epoch().value()+" / override : "+owner.playerOverrideEpoch().value());
            }
        }
        add(lines,limit,"Références uniquement ; aucun contenu blob téléchargé, aucun droit d'exécution.");
    }
    private static void add(List<String> lines,int limit,String line) {if(lines.size()<limit)lines.add(line);}
    private static String label(String key) {return key.replace('_',' ');}
}