package fr.proutlost.quest.runtime;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import fr.proutlost.quest.interaction.Interactions;
import fr.proutlost.quest.core.*;
import java.util.*;

/** All mutations here are player-confirmed inventory contributions; terrain is only inspected. */
public final class CampaignCommands {
    private CampaignCommands(){}
    public static void register(RegisterCommandsEvent event){
        var root=Commands.literal("campaign");
        for(String action:List.of("record_arrangement","ancient_arrangement","compare_arrangement","observe_scene","listen","record_signal","reach_retreat","repair_transmitter","record_transmission","final_evolve","epilogue"))
            root.then(Commands.literal(action).executes(c->run(c,action)));
        root.then(Commands.literal("final_link").then(Commands.argument("first",EvidenceArguments.type()).then(Commands.argument("second",EvidenceArguments.type()).executes(c->run(c,"final_link")))));
        root.then(Commands.literal("final_reply").then(Commands.argument("sequence",StringArgumentType.greedyString()).executes(c->run(c,"final_reply"))));
        root.then(Commands.literal("prepare_arrangement").then(Commands.argument("observation",EvidenceArguments.type()).executes(c->run(c,"prepare_arrangement"))));
        root.then(Commands.literal("compare_signals").then(Commands.argument("first",EvidenceArguments.type()).then(Commands.argument("second",EvidenceArguments.type()).executes(c->run(c,"compare_signals")))));
        for(String action:List.of("explore","work","transmit"))root.then(Commands.literal(action).then(Commands.argument("option",StringArgumentType.word()).executes(c->run(c,action))));
        event.getDispatcher().register(Commands.literal("proutlost").then(Commands.literal("quest").requires(s->QuestConfig.ENABLED.get()).then(root)));
    }
    private static int run(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> c,String action) throws com.mojang.brigadier.exceptions.CommandSyntaxException{
        var p=c.getSource().getPlayerOrException();var d=QuestRuntime.data(p.server);var core=d.core();var actions=d.actions();long now=System.currentTimeMillis();
        try{
            String result="Relevé conservé.";
            switch(action){
                case "record_arrangement"->{
                    var site=d.sites().near(p,PreparedSites.Kind.CAMP,6).orElseThrow();var slots=new ArrayList<Interactions.Slot>();
                    // Three ordinary containers below the three prepared slots, west to east.
                    for(int i=0;i<3;i++){
                        var pos=site.getValue().pos().offset(i-1,-1,0);
                        if(!p.serverLevel().hasChunkAt(pos)||!(p.serverLevel().getBlockEntity(pos) instanceof Container container)||container.getItem(0).isEmpty())throw new IllegalStateException();
                        slots.add(new Interactions.Slot(i,BuiltInRegistries.ITEM.getKey(container.getItem(0).getItem()).toString()));
                    }
                    var admissible=fr.proutlost.quest.content.Investigation.content().ordinaryItems().keySet();
                    var arrangement=Interactions.recordArrangement(site.getKey(),slots,Set.of(p.getUUID()),now,admissible);
                    actions.recordArrangement(d.state(),arrangement,UUID.randomUUID().toString());
                }
                case "ancient_arrangement"->{var site=d.sites().near(p,PreparedSites.Kind.OLD_SHELTER,6).orElseThrow();fr.proutlost.quest.content.Investigation.requireAccessible(core,"B3",p.getUUID());CampaignPhysicalRuntime.ancientArrangement(p,d,site.getKey());var o=actions.examineAncientArrangement(d.state(),p.getUUID(),site.getKey(),now);result="Trace ancienne : "+o.text()+"\n"+EvidenceArguments.quoted(o.id());}
                case "prepare_arrangement"->actions.prepareArrangement(d.state(),p.getUUID(),StringArgumentType.getString(c,"observation"));
                case "compare_arrangement"->{var site=d.sites().near(p,PreparedSites.Kind.OLD_SHELTER,6).orElseThrow();result=actions.compareArrangement(d.state(),p.getUUID(),site.getKey(),now)?"La trace ancienne correspond exactement à votre arrangement.":"PARTIAL_RESPONSE";}
                case "observe_scene"->{var site=d.sites().near(p,PreparedSites.Kind.CAMP,6).orElseThrow();result=actions.dailyScene(d.state(),p.getUUID(),site.getKey(),actions.recentArrangement(),now);p.playNotifySound(SoundEvents.WOOD_STEP,SoundSource.AMBIENT,.5f,1f);}
                case "explore"->{String option=StringArgumentType.getString(c,"option");var kind=option.equals("REPAIR")?PreparedSites.Kind.PASSAGE:PreparedSites.Kind.DETOUR;var site=d.sites().near(p,kind,6).orElseThrow();result=actions.explorePassage(d.state(),p.getUUID(),option,site.getKey(),now);}
                case "work"->{
                    String option=StringArgumentType.getString(c,"option");var site=d.sites().near(p,option.equals("REPAIR")?PreparedSites.Kind.PASSAGE:PreparedSites.Kind.DETOUR,6).orElseThrow();
                    var pos=site.getValue().pos().below();var current=p.serverLevel().getBlockState(pos);var stack=p.getMainHandItem();
                    boolean changed=d.sites().changedPoint(site.getKey(),current.toString())&&current.is(BlockTags.PLANKS);
                    if(actions.work(d.state(),p.getUUID(),option,site.getKey(),changed,stack.is(ItemTags.PLANKS),now)){stack.shrink(1);QuestRuntime.played(p,d,now);}else result="Contribution déjà conservée.";
                }
                case "listen"->{var site=d.sites().near(p,PreparedSites.Kind.SIGNAL_REGION,6).orElseThrow();String region=p.level().dimension().location()+":"+Math.floorDiv(site.getValue().x(),128)+":"+Math.floorDiv(site.getValue().z(),128);result=actions.listen(d.state(),p.getUUID(),region,site.getKey(),now);p.playNotifySound(SoundEvents.NOTE_BLOCK_FLUTE.value(),SoundSource.AMBIENT,.5f,.6f);}
                case "record_signal"->{var o=actions.recordSignal(d.state(),p.getUUID());result=EvidenceArguments.quoted(o.id())+" — "+o.text();}
                case "compare_signals"->actions.compareSignals(d.state(),p.getUUID(),StringArgumentType.getString(c,"first"),StringArgumentType.getString(c,"second"),now);
                case "transmit"->{
                    var site=d.sites().near(p,PreparedSites.Kind.TRANSMITTER,6).orElseThrow();
                    if(d.sites().sites().values().stream().noneMatch(s->s.kind()==PreparedSites.Kind.RETREAT))throw new IllegalStateException("Prepare a retreat");
                    String option=StringArgumentType.getString(c,"option");result=actions.transmit(d.state(),p.getUUID(),option,site.getKey(),new java.security.SecureRandom().nextDouble()<QuestConfig.AMPLIFY_PERTURBATION_PROBABILITY.get(),now);
                    CampaignPhysicalRuntime.damageTransmitter(p,d);p.playNotifySound(SoundEvents.NOTE_BLOCK_FLUTE.value(),SoundSource.AMBIENT,.7f,.8f);
                }
                case "reach_retreat"->{var site=d.sites().near(p,PreparedSites.Kind.RETREAT,6).orElseThrow();actions.reachRetreat(d.state(),p.getUUID(),site.getKey(),now);}
                case "repair_transmitter"->{var site=d.sites().near(p,PreparedSites.Kind.TRANSMITTER,6).orElseThrow();var stack=p.getMainHandItem();if(actions.repair(d.state(),p.getUUID(),site.getKey(),stack.is(ItemTags.PLANKS)&&CampaignPhysicalRuntime.repairReady(p),now)){stack.shrink(1);QuestRuntime.played(p,d,now);}}
                case "record_transmission"->{var site=d.sites().near(p,PreparedSites.Kind.TRANSMITTER,6).orElseThrow();actions.recordFinalTransmission(d.state(),p.getUUID(),site.getKey(),now);}
                case "final_link"->actions.finalLink(d.state(),p.getUUID(),StringArgumentType.getString(c,"first"),StringArgumentType.getString(c,"second"),now);
                case "final_evolve"->{var site=d.sites().sites().entrySet().stream().filter(e->Set.of(PreparedSites.Kind.PASSAGE,PreparedSites.Kind.DETOUR).contains(e.getValue().kind())&&e.getValue().near(p,6)).findFirst().orElseThrow();
                    actions.verifyPassagePoint(d.state(),site.getKey(),FinalRelease.isOpen(p.serverLevel(),site.getValue()));actions.finalEvolve(d.state(),p.getUUID(),site.getKey(),now);}
                case "final_reply"->{var site=d.sites().near(p,PreparedSites.Kind.TRANSMITTER,6).orElseThrow();if(actions.transmissionMode().isEmpty())throw new IllegalStateException();actions.finalReply(d.state(),p.getUUID(),List.of(StringArgumentType.getString(c,"sequence").split("\\s+")),site.getKey(),now);}
                case "epilogue"->result=String.join("\n",actions.epilogue(d.state(),p.getUUID(),now));
                default->throw new IllegalArgumentException();
            }
            if(!Set.of("epilogue","work","repair_transmitter","record_transmission").contains(action))QuestRuntime.played(p,d,now);
            p.sendSystemMessage(Component.literal(result));d.setDirty();return 1;
        }catch(IllegalArgumentException|IllegalStateException|NoSuchElementException|NullPointerException e){p.sendSystemMessage(Component.literal("Action indisponible : vérifiez les prérequis, le lieu, les relevés et les matériaux."));return 0;}
    }
}
