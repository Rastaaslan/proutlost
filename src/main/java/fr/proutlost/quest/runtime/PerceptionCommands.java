package fr.proutlost.quest.runtime;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import fr.proutlost.quest.core.*;
import fr.proutlost.quest.perception.PerceptionSession;
import fr.proutlost.quest.interaction.Interactions;
import java.util.*;

/** Vanilla targeted sound/text transport. No fake entities, blocks or client implementation dependency. */
public final class PerceptionCommands {
    private PerceptionCommands(){}
    public static void register(RegisterCommandsEvent event){
        event.getDispatcher().register(Commands.literal("proutlost").then(Commands.literal("quest").requires(s->QuestConfig.ENABLED.get())
            .then(Commands.literal("supports").executes(c->run(c,"supports")))
            .then(Commands.literal("scene").executes(c->run(c,"play")))
            .then(Commands.literal("recall_perception").executes(c->run(c,"recall")))
            .then(Commands.literal("record_perception").then(Commands.argument("fragment",EvidenceArguments.type()).executes(c->run(c,"record"))))
            .then(Commands.literal("compare_perceptions").then(Commands.argument("first",EvidenceArguments.type()).then(Commands.argument("second",EvidenceArguments.type()).executes(c->run(c,"compare")))))
            .then(Commands.literal("sequence_input").then(Commands.argument("sequence",StringArgumentType.greedyString()).executes(c->run(c,"sequence"))))
            .then(Commands.literal("replace_witness").then(Commands.argument("absent",StringArgumentType.word()).executes(c->run(c,"replace"))))));
    }
    private static int run(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> c,String action) throws com.mojang.brigadier.exceptions.CommandSyntaxException{
        var player=c.getSource().getPlayerOrException();var data=QuestRuntime.data(player.server);long now=System.currentTimeMillis();
        try{
            var site=data.sites().near(player,PreparedSites.Kind.PERCEPTION,8).orElseThrow();
            if(data.core().state("C1")==QuestCore.State.LOCKED)throw new IllegalStateException();
            var content=fr.proutlost.quest.content.PerceptionContent.load();
            var scene=data.state().scene(site.getKey(),content.sounds(),content.symbols());
            switch(action){
                case "supports"->player.sendSystemMessage(Component.literal("Supports du lieu : "+content.supportPitch()));
                case "play"->{
                    if(scene.trial()>0&&data.core().state("C2")==QuestCore.State.LOCKED)throw new IllegalStateException();
                    var positions=new LinkedHashMap<UUID,PerceptionSession.Position>();
                    var participants=player.server.getPlayerList().getPlayers().stream().filter(p->!p.isSpectator()&&site.getValue().near(p,8)).toList();
                    for(var p:participants)positions.put(p.getUUID(),new PerceptionSession.Position(p.level().dimension().location().toString(),p.getX(),p.getY(),p.getZ()));
                    scene.play(positions,now);
                    for(var p:participants){
                        var fragment=scene.forPlayer(p.getUUID()).getLast();
                        if(fragment.family()==PerceptionSession.Family.SOUND){
                            PerceptionDelivery.schedule(p,fragment.sequence());
                        }
                        p.sendSystemMessage(Component.literal((fragment.family()==PerceptionSession.Family.SOUND?"Trois sons : ":"Trois symboles : ")+String.join(" · ",fragment.sequence())+"\nRelever volontairement : /proutlost quest record_perception "+EvidenceArguments.quoted(fragment.id())));
                    }
                    if(scene.trial()>=2){emit(data.core(),player.getUUID(),site.getKey(),"POSITIONS_CHANGED",now);emit(data.core(),player.getUUID(),site.getKey(),"PERCEPTIONS_REDISTRIBUTED",now);}
                }
                case "recall"->{
                    var fragments=scene.forPlayer(player.getUUID());
                    if(fragments.isEmpty())throw new IllegalStateException();
                    var fragment=fragments.getLast();
                    if(fragment.family()==PerceptionSession.Family.SOUND)PerceptionDelivery.schedule(player,fragment.sequence());
                    player.sendSystemMessage(Component.literal(String.join(" · ",fragment.sequence())+"\nRelever volontairement : /proutlost quest record_perception "+EvidenceArguments.quoted(fragment.id())));
                }
                case "record"->{
                    String fragment=StringArgumentType.getString(c,"fragment");
                    boolean first=data.core().evidence(player.getUUID(),fragment).isEmpty();
                    scene.record(player.getUUID(),fragment,data.core());
                    if(first)QuestRuntime.played(player,data,now);
                }
                case "replace"->{
                    UUID absent=UUID.fromString(StringArgumentType.getString(c,"absent"));
                    var active=data.core().recentPlayers(now,QuestConfig.WITNESS_ABSENCE_SECONDS.get()*1000L);
                    var fragment=scene.replaceWitness(absent,player.getUUID(),new PerceptionSession.Position(player.level().dimension().location().toString(),player.getX(),player.getY(),player.getZ()),active,now,
                        candidate -> active.stream().anyMatch(holder -> data.core().evidence(holder,candidate.id()).isPresent()));
                    if (fragment.isPresent()) {
                        var recovered=fragment.orElseThrow();
                        player.sendSystemMessage(Component.literal("Reprise : "+String.join(" · ",recovered.sequence())+"\n/proutlost quest record_perception "+EvidenceArguments.quoted(recovered.id())));
                    } else player.sendSystemMessage(Component.literal("Témoin remplacé. Une perception reste détenue par un témoin disponible ; elle peut être partagée volontairement. Déplacez-vous puis relancez la scène pour de nouvelles perceptions."));
                }
                case "compare"->{
                    var a=data.core().evidence(player.getUUID(),StringArgumentType.getString(c,"first")).orElseThrow();var b=data.core().evidence(player.getUUID(),StringArgumentType.getString(c,"second")).orElseThrow();
                    if(!a.origin().equals(site.getKey())||!b.origin().equals(site.getKey())||a.type().equals(b.type())||!Set.of("SOUND_RECORDED","SYMBOLS_RECORDED").containsAll(Set.of(a.type(),b.type())))throw new IllegalArgumentException();
                    if(scene.trial()==1&&!a.witness().equals(b.witness()))emit(data.core(),player.getUUID(),site.getKey(),"PERCEPTIONS_COMPARED",now);
                    else if(scene.comparesTrials(a.id(),b.id()))emit(data.core(),player.getUUID(),site.getKey(),"SECOND_TRIAL_COMPARED",now);
                    else throw new IllegalArgumentException("Compare recorded evidence from distinct trials including a repeated trial");
                }
                case "sequence"->{
                    if(data.core().state("C3")==QuestCore.State.LOCKED)throw new IllegalStateException();
                    var soundWitnesses=data.core().sharedWitnesses("SOUND_RECORDED",site.getKey());var symbolWitnesses=data.core().sharedWitnesses("SYMBOLS_RECORDED",site.getKey());
                    boolean shared=soundWitnesses.stream().anyMatch(a->symbolWitnesses.stream().anyMatch(b->!a.equals(b)));
                    var response=Interactions.sequenceInput(content.sequence(),List.of(StringArgumentType.getString(c,"sequence").split("\\s+")),shared);
                    player.sendSystemMessage(Component.literal(response.name()));
                    if(response==Interactions.Response.COMPLETE_RESPONSE){
                        QuestRuntime.played(player,data,now);
                        for(var p:player.server.getPlayerList().getPlayers())if(site.getValue().near(p,12))p.playNotifySound(SoundEvents.AMETHYST_BLOCK_CHIME,SoundSource.AMBIENT,1f,1f);
                        for(var type:List.of("FRAGMENTS_SHARED","SEQUENCE_CORRECT","COMMON_RESPONSE"))emit(data.core(),player.getUUID(),site.getKey(),type,now);
                    }
                }
                default->throw new IllegalArgumentException();
            }
            if(Set.of("play","compare","replace").contains(action))QuestRuntime.played(player,data,now);
            data.setDirty();return 1;
        }catch(IllegalArgumentException|IllegalStateException|NoSuchElementException|NullPointerException|SecurityException e){player.sendSystemMessage(Component.literal("Scène indisponible : vérifiez le lieu, les relevés, les participants et leurs positions."));return 0;}
    }
    private static void emit(QuestCore core,UUID actor,String location,String type,long now){core.accept(new WorldFact(UUID.randomUUID().toString(),type,"proutlost:perception",Set.of(),"","",location,now,Map.of("actor",actor.toString()),true,WorldFact.Category.NARRATIVE));core.evaluate(now);}
}
