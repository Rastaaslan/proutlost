package fr.proutlost.quest.runtime;

import com.mojang.brigadier.arguments.StringArgumentType;
import fr.proutlost.quest.choice.CollectiveVote;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Ordinary player actions; electorate and deadlines are enforced by canonical state. */
public final class VoteCommands {
    private VoteCommands() {}
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("proutlost").then(Commands.literal("quest").requires(s->QuestConfig.ENABLED.get())
            .then(Commands.literal("choice").then(Commands.argument("id",StringArgumentType.word())
                .then(Commands.literal("dossier").executes(c->action(c,"dossier","")))
                .then(Commands.literal("open").executes(c->action(c,"open","")))
                .then(Commands.literal("close").executes(c->action(c,"close","")))
                .then(Commands.literal("acknowledge").executes(c->action(c,"acknowledge","")))
                .then(Commands.literal("status").executes(c->action(c,"status","")))
                .then(Commands.literal("confirm").then(Commands.argument("option",StringArgumentType.word()).executes(c->action(c,"confirm",StringArgumentType.getString(c,"option")))))))));
    }
    private static int action(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> c,String action,String option) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var p=c.getSource().getPlayerOrException();var d=QuestRuntime.data(p.server);String id=StringArgumentType.getString(c,"id");long now=System.currentTimeMillis();
        try {
            var vote=d.state().vote(id).orElse(null);
            if(action.equals("dossier")) {
                vote=d.state().prepareVote(id,new CollectiveVote.Rules(QuestConfig.DISCUSSION_SECONDS.get()*1000L,QuestConfig.BALLOT_SECONDS.get()*1000L,QuestConfig.VOTE_PARTICIPATION.get()),now);
                var dossier=vote.consult(p.getUUID());p.sendSystemMessage(Component.literal(String.join(" / ",dossier.options())+"\n"+dossier.knownConsequences()+"\n"+dossier.establishedFacts()+"\n"+dossier.warning()+"\n"+vote.audit()));
            } else {
                if(vote==null)throw new IllegalStateException("Consultez d’abord le dossier accessible.");
                switch(action) {
                    case "open" -> vote.open(d.core().activePlayers(now),now);
                    case "close" -> { if(vote.close(now)==CollectiveVote.Phase.DECIDED)d.state().applyVote(id,now); }
                    case "confirm" -> vote.confirm(p.getUUID(),option,now);
                    case "acknowledge" -> { if(!id.equals("F3"))throw new IllegalArgumentException();d.state().acknowledgeMemoryChoice(p.getUUID(),now); }
                    case "status" -> { }
                    default -> throw new IllegalArgumentException();
                }
                p.sendSystemMessage(Component.literal(vote.phase()+" — "+vote.audit()));
            }
            if(action.equals("confirm")||action.equals("acknowledge"))QuestRuntime.played(p,d,now);
            d.setDirty();return 1;
        } catch(IllegalArgumentException|IllegalStateException|SecurityException e) {
            p.sendSystemMessage(Component.literal("Action indisponible : vérifiez les prérequis, le dossier et la fenêtre de vote."));return 0;
        }
    }
}
