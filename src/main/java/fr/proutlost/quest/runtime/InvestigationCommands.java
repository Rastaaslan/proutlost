package fr.proutlost.quest.runtime;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import fr.proutlost.quest.content.Investigation;
import java.util.*;

public final class InvestigationCommands {
    private InvestigationCommands() {}
    public static void register(RegisterCommandsEvent event){
        event.getDispatcher().register(Commands.literal("proutlost").then(Commands.literal("quest").requires(s->QuestConfig.ENABLED.get())
            .then(Commands.literal("inspect").then(Commands.argument("support",StringArgumentType.word()).executes(c->handle(c,"inspect"))))
            .then(Commands.literal("relate").then(Commands.argument("comparison",StringArgumentType.word())
                .then(Commands.argument("first",EvidenceArguments.type()).then(Commands.argument("second",EvidenceArguments.type()).executes(c->handle(c,"relate"))))))
            .then(Commands.literal("test_relation").executes(c->handle(c,"test")))
            .then(Commands.literal("synthesis").then(Commands.argument("quest",StringArgumentType.word()).executes(c->handle(c,"synthesis"))))));
    }
    private static boolean departureTrace(net.minecraft.server.level.ServerPlayer player,PreparedSites.Site site){
        boolean rope=false,supplies=false;
        for(var pos:net.minecraft.core.BlockPos.betweenClosed(site.pos().offset(-3,-2,-3),site.pos().offset(3,2,3))){
            if(!player.serverLevel().hasChunkAt(pos))continue;
            var state=player.serverLevel().getBlockState(pos);
            rope|=state.is(net.minecraft.world.level.block.Blocks.CHAIN)||state.is(net.minecraft.tags.BlockTags.FENCES);
            if(player.serverLevel().getBlockEntity(pos) instanceof net.minecraft.world.Container c&&!c.isEmpty())supplies=true;
        }
        return rope&&supplies;
    }
    private static int handle(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> c,String action) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var p=c.getSource().getPlayerOrException();var d=QuestRuntime.data(p.server);long now=System.currentTimeMillis();
        try {
            boolean useful=true;
            switch(action){
                case "inspect"->{String id=StringArgumentType.getString(c,"support");var support=Investigation.content().supports().stream().filter(s->s.id().equals(id)).findFirst().orElseThrow();
                    var site=d.sites().near(p,PreparedSites.Kind.valueOf(support.siteKind()),6).orElseThrow();
                    if(id.equals("departure_trace")&&!departureTrace(p,site.getValue()))throw new IllegalStateException("Physical trace missing");
                    useful=d.core().evidence(p.getUUID(),EvidenceArguments.documentId(p.getUUID(),id,site.getKey())).isEmpty();
                    var o=d.investigation().examine(d.core(),p.getUUID(),id,site.getValue().kind().name(),site.getKey(),now);
                    p.sendSystemMessage(Component.literal(EvidenceArguments.quoted(o.id())+" — "+o.text()));}
                case "relate"->{d.investigation().prepare(d.core(),p.getUUID(),StringArgumentType.getString(c,"comparison"),StringArgumentType.getString(c,"first"),StringArgumentType.getString(c,"second"));p.sendSystemMessage(Component.literal("Rapprochement préparé. Éprouvez-le sur le lieu concerné avec test_relation."));}
                case "test"->{var site=d.sites().near(p,PreparedSites.Kind.valueOf(d.investigation().preparedSiteKind(p.getUUID())),6).orElseThrow();p.sendSystemMessage(Component.literal(d.investigation().compareHere(d.core(),p.getUUID(),site.getValue().kind().name(),site.getKey(),now)));}
                case "synthesis"->{
                    String quest=StringArgumentType.getString(c,"quest");
                    useful=d.core().state(quest,p.getUUID())!=fr.proutlost.quest.core.QuestCore.State.RESOLVED;
                    d.investigation().synthesis(d.core(),p.getUUID(),quest,now).forEach(s->p.sendSystemMessage(Component.literal(s)));
                }
                default->throw new IllegalArgumentException();
            }
            if(useful)QuestRuntime.played(p,d,now);d.setDirty();return 1;
        }catch(IllegalArgumentException|IllegalStateException|NoSuchElementException|NullPointerException e){p.sendSystemMessage(Component.literal("Action indisponible : consultez le journal, les relevés et le lieu requis."));return 0;}
    }
}
