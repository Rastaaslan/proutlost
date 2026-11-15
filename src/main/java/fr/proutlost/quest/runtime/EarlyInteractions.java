package fr.proutlost.quest.runtime;

import com.mojang.brigadier.arguments.StringArgumentType;
import fr.proutlost.quest.core.*;
import fr.proutlost.quest.content.Investigation;
import fr.proutlost.quest.interaction.Interactions;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import java.util.*;

/** First playable interactions on operator-bound, pre-existing sites. No automatic terrain edits. */
public final class EarlyInteractions {
    private EarlyInteractions() {}
    private static QuestSavedData data(ServerPlayer p){return QuestRuntime.data(p.server);}
    private static int say(ServerPlayer p,String text){p.sendSystemMessage(Component.literal(text));return 1;}
    private static void fact(ServerPlayer p,String type,String location){
        QuestRuntime.publish(p.server,new WorldFact(UUID.randomUUID().toString(),type,"proutlost:early",Set.of(),"",p.level().dimension().location().toString(),location,System.currentTimeMillis(),Map.of("actor",p.getUUID().toString()),true,WorldFact.Category.NARRATIVE));
        QuestRuntime.played(p,data(p),System.currentTimeMillis());
    }
    public static void register(RegisterCommandsEvent event){
        event.getDispatcher().register(Commands.literal("proutlost").then(Commands.literal("quest").requires(s->QuestConfig.ENABLED.get())
            .then(Commands.literal("bind").requires(s->s.hasPermission(4)).then(Commands.argument("id",StringArgumentType.word()).then(Commands.argument("kind",StringArgumentType.word()).executes(c->{
                var p=c.getSource().getPlayerOrException();var d=data(p);var pos=p.blockPosition();
                try{d.sites().bind(StringArgumentType.getString(c,"id"),new PreparedSites.Site(PreparedSites.Kind.valueOf(StringArgumentType.getString(c,"kind")),p.level().dimension().location().toString(),pos.getX(),pos.getY(),pos.getZ()));}
                catch(IllegalArgumentException e){return say(p,"Binding refusé : type/id invalide ou identifiant déjà lié.");}
                d.sites().captureBaseline(StringArgumentType.getString(c,"id"),p.serverLevel().getBlockState(pos.below()).toString());
                d.setDirty();return say(p,"Site préparé lié sans modification de blocs.");
            }))))
            .then(Commands.literal("reach_shore").executes(c->{var p=c.getSource().getPlayerOrException();var site=data(p).sites().near(p,PreparedSites.Kind.SAFE_SHORE,6);
                if(site.isEmpty())return say(p,"Rejoignez le rivage sûr préparé.");fact(p,"SAFE_SHORE_REACHED",site.get().getKey());return say(p,"Vous avez rejoint le rivage sûr.");}))
            .then(Commands.literal("examine").then(Commands.argument("support",StringArgumentType.word()).executes(c->{
                var p=c.getSource().getPlayerOrException();String support=StringArgumentType.getString(c,"support");
                PreparedSites.Kind kind;String type;
                if(support.equals("wreck_motif")){kind=PreparedSites.Kind.WRECK;type="WRECK_MOTIF";}
                else if(support.equals("camp_motif")){kind=PreparedSites.Kind.OLD_CAMP;type="OLD_CAMP_MOTIF";}
                else return say(p,"Support inconnu.");
                var d=data(p);var site=d.sites().near(p,kind,6);if(site.isEmpty())return say(p,"Approchez le support préparé.");
                String id=EvidenceArguments.documentId(p.getUUID(),support,site.get().getKey());
                if(d.core().evidence(p.getUUID(),id).isEmpty()){
                    d.core().observe(new QuestCore.Observation(id,p.getUUID(),type,Investigation.text("early.examine.observation"),site.get().getKey(),System.currentTimeMillis()));
                    QuestRuntime.played(p,d,System.currentTimeMillis());
                }
                d.setDirty();return say(p,"Relevé : "+EvidenceArguments.quoted(id)+" — une fleur dont un pétale est carré.");
            })))
            .then(Commands.literal("compare_prepare").then(Commands.argument("first",EvidenceArguments.type()).then(Commands.argument("second",EvidenceArguments.type()).executes(c->{
                var p=c.getSource().getPlayerOrException();var d=data(p);var first=StringArgumentType.getString(c,"first");var second=StringArgumentType.getString(c,"second");
                var a=d.core().evidence(p.getUUID(),first);var b=d.core().evidence(p.getUUID(),second);
                if(a.isEmpty()||b.isEmpty()||a.get().type().equals(b.get().type())||!Set.of("WRECK_MOTIF","OLD_CAMP_MOTIF").containsAll(Set.of(a.get().type(),b.get().type())))return say(p,"Deux relevés accessibles des motifs sont nécessaires.");
                String location=a.get().type().equals("OLD_CAMP_MOTIF")?a.get().origin():b.get().origin();
                d.sites().prepare(p.getUUID(),Interactions.prepareCompare(p.getUUID(),first,second,location));d.setDirty();return say(p,"Rapprochement préparé. Éprouvez-le dans l’ancien camp avec compare_here.");
            }))))
            .then(Commands.literal("compare_here").executes(c->{var p=c.getSource().getPlayerOrException();var d=data(p);var prepared=d.sites().comparison(p.getUUID());var site=d.sites().near(p,PreparedSites.Kind.OLD_CAMP,6);
                if(prepared.isEmpty()||site.isEmpty())return say(p,"Préparez deux relevés et rejoignez le camp concerné.");
                var a=d.core().evidence(p.getUUID(),prepared.get().firstObservation());var b=d.core().evidence(p.getUUID(),prepared.get().secondObservation());
                boolean exact=a.isPresent()&&b.isPresent()&&a.get().text().equals(b.get().text());
                if(exact){var firstSite=d.sites().sites().get(a.get().origin());var secondSite=d.sites().sites().get(b.get().origin());
                    exact=firstSite!=null&&secondSite!=null&&(!firstSite.dimension().equals(secondSite.dimension())||firstSite.pos().distSqr(secondSite.pos())>=64);}
                if(!Interactions.testCompare(prepared.get(),site.get().getKey(),exact).tested())return say(p,"PARTIAL_RESPONSE");
                fact(p,"MOTIF_EXACT_COMPARISON",site.get().getKey());return say(p,"Le détail inhabituel correspond exactement. Partagez vos relevés pour établir le constat collectif.");}))
            .then(Commands.literal("claim_camp").executes(c->{var p=c.getSource().getPlayerOrException();var d=data(p);var site=d.sites().near(p,PreparedSites.Kind.CAMP,12);
                if(site.isEmpty())return say(p,"Aucun emplacement de camp préparé à proximité.");
                var claimed=Interactions.claimCamp(site.get().getKey(),p.getUUID(),System.currentTimeMillis(),new Interactions.CampRules(QuestConfig.CAMP_ACTIVITY_SECONDS.get()*20L,QuestConfig.CAMP_VISITS.get()),()->scanCamp(p,site.get().getKey(),site.get().getValue()));
                if(claimed.isEmpty())return say(p,"Le camp nécessite activité, fréquentation, couchage, feu et stockage rempli.");
                d.state().rememberCamp(claimed.get());QuestRuntime.played(p,d,System.currentTimeMillis());d.setDirty();return say(p,"Camp reconnu et conservé.");}))
            .then(Commands.literal("activate_distress_signal").executes(c->{var p=c.getSource().getPlayerOrException();var d=data(p);var site=d.sites().near(p,PreparedSites.Kind.SIGNAL,12);
                if(site.isEmpty())return say(p,"Rejoignez un emplacement de signal préparé.");
                var target=d.sites().sites().entrySet().stream().filter(e->e.getValue().kind()==PreparedSites.Kind.OLD_CAMP&&e.getValue().dimension().equals(site.get().getValue().dimension())).findFirst();
                if(target.isEmpty())return say(p,"La scène de réponse n’est pas encore préparée.");
                if(!scanSignal(p,site.get().getValue()))return say(p,"Signal insuffisant : prévoyez plusieurs feux visibles ou un motif contrasté dégagé, près de l’eau ou en hauteur.");
                fact(p,"DISTRESS_SIGNAL_VALIDATED",site.get().getKey());
                var delta=target.get().getValue().pos().subtract(site.get().getValue().pos());
                String direction=Math.abs(delta.getX())>Math.abs(delta.getZ())?(delta.getX()>0?"est":"ouest"):(delta.getZ()>0?"sud":"nord");
                double length=Math.max(1,Math.hypot(delta.getX(),delta.getZ()));
                p.serverLevel().sendParticles(p,net.minecraft.core.particles.ParticleTypes.FLAME,true,p.getX()+6*delta.getX()/length,p.getEyeY()+1,p.getZ()+6*delta.getZ()/length,8,.3,.3,.3,.01);
                say(p,"Depuis l’intérieur de l’île, une brève lueur semble répondre vers le "+direction+".");
                fact(p,"INTERIOR_RESPONSE_OBSERVED",site.get().getKey());return 1;
            }))));
    }
    private static Interactions.CampSignals scanCamp(ServerPlayer p,String id,PreparedSites.Site site){
        var level=p.serverLevel();boolean bed=false,fire=false,supplies=false;
        for(var pos:BlockPos.betweenClosed(site.pos().offset(-5,-2,-5),site.pos().offset(5,3,5))){
            if(!level.hasChunkAt(pos))continue;var state=level.getBlockState(pos);
            bed|=state.is(BlockTags.BEDS);fire|=state.getBlock() instanceof CampfireBlock&&state.getValue(BlockStateProperties.LIT);
            if(level.getBlockEntity(pos) instanceof Container container&&!container.isEmpty())supplies=true;
        }
        return new Interactions.CampSignals(data(p).sites().activeTicks(id),data(p).sites().visits(id),bed,fire,supplies);
    }
    private static boolean scanSignal(ServerPlayer p,PreparedSites.Site site){
        return MinecraftDistressScan.inspect(p.serverLevel(),site.pos(),QuestConfig.SIGNAL_FIRE_COUNT.get()).valid();
    }
}
