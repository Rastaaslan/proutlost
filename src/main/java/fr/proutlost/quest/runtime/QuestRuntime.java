package fr.proutlost.quest.runtime;

import com.mojang.brigadier.arguments.StringArgumentType;
import fr.proutlost.quest.core.*;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import java.util.*;

/** Text journal fallback: no FTB implementation imports and no physical world mutation. */
public final class QuestRuntime {
    private static final Map<UUID,String> LAST_POSITION=new HashMap<>();
    private QuestRuntime() {}
    public static QuestSavedData data(MinecraftServer server) {
        var data=server.overworld().getDataStorage().computeIfAbsent(QuestSavedData.FACTORY,QuestSavedData.FILE_ID);
        CampaignPhysicalRuntime.ensure(server,data);
        ActionReadiness.refresh(data.core(),data.sites(),fr.proutlost.ambient.AmbientRuntime.ENABLED.get());
        if(!CampaignPhysicalRuntime.healthy(server))data.core().actionReadiness(id->false);
        data.actions().configure(new fr.proutlost.quest.content.CampaignActions.Calibration(QuestConfig.SCENE_INTERVAL_SECONDS.get()*1000L,QuestConfig.TRANSMISSION_INTERVAL_SECONDS.get()*1000L,QuestConfig.STABILIZATION_SECONDS.get()*1000L,QuestConfig.WORK_CONTRIBUTORS.get()));
        return data;
    }
    /** Record a validated explicit player interaction, never a passive event or menu. */
    public static void played(ServerPlayer player,QuestSavedData data,long now) {
        if(!player.server.isSameThread())throw new IllegalStateException("Quest activity requires server thread");
        if(player.isSpectator())return;
        data.core().active(player.getUUID(),now);data.setDirty();
    }
    /** Passive server API. AMBIENT producers need not call this API at all. */
    public static void publish(MinecraftServer server, WorldFact fact) {
        if (!QuestConfig.ENABLED.get()) return;
        if (!server.isSameThread()) throw new IllegalStateException("Quest facts require server thread");
        var data=data(server); data.core().accept(fact); data.core().evaluate(System.currentTimeMillis()); data.setDirty();
    }
    public static void tick(ServerTickEvent.Post event) {
        if (!QuestConfig.ENABLED.get() || event.getServer().getTickCount()%20!=0) return;
        var server=event.getServer(); var data=data(server); long now=System.currentTimeMillis();
        for (var p:server.getPlayerList().getPlayers()) {
            QuestServerTeams.assign(data.core(),p);
            String position=p.level().dimension().location()+":"+p.blockPosition().asLong();
            String prior=LAST_POSITION.put(p.getUUID(),position);
            boolean moved=prior!=null && !position.equals(prior) && !p.isSpectator();
            if (moved) data.core().active(p.getUUID(),now);
            if (!p.isSpectator()) data.sites().activity(p,moved);
        }
        LAST_POSITION.keySet().retainAll(server.getPlayerList().getPlayers().stream().map(ServerPlayer::getUUID).toList());
        data.core().evaluate(now); FinalRelease.tick(server,data,now); data.setDirty();
    }
    public static void routine(fr.proutlost.ambient.CampRoutineEvent event) {
        if(!QuestConfig.ENABLED.get())return;
        var data=data(event.server);data.routineLocations().add(event.replay.location());data.setDirty();
        for(var witness:event.replay.witnesses())publish(event.server,new WorldFact(UUID.randomUUID().toString(),"CAMP_ROUTINE_REPLAY_OBSERVED",
            "proutlost:ambient",Set.of(),"","",event.replay.location(),event.replay.timestamp(),Map.of("actor",witness.toString()),true,WorldFact.Category.HYBRID));
    }
    public static void stopped(ServerStoppedEvent event) { LAST_POSITION.clear(); }
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("proutlost").then(Commands.literal("quest")
            .requires(s -> QuestConfig.ENABLED.get())
            .then(Commands.literal("journal").executes(c -> {
                var p=c.getSource().getPlayerOrException();
                for (var page:data(p.server).core().journal(p.getUUID())) {
                    p.sendSystemMessage(Component.literal(page.id()+" — "+page.title()+" ["+page.state()+"]\n"+
                        (page.summary().isEmpty()?page.context()+"\n"+page.action():page.summary())));
                    if(page.state()!=QuestCore.State.RESOLVED)for(var action:JournalActions.forQuest(page.id()))p.sendSystemMessage(Component.literal(action));
                    for (var evidence:page.evidence()) p.sendSystemMessage(Component.literal("["+EvidenceArguments.quoted(evidence.observation().id())+"] "+evidence.observation().text()));
                }
                for(var evidence:data(p.server).core().recordedPerceptions(p.getUUID()))
                    p.sendSystemMessage(Component.literal("["+EvidenceArguments.quoted(evidence.observation().id())+"] "+evidence.observation().text()));
                return 1;
            }))
            .then(Commands.literal("catch_up").executes(c -> {
                var p=c.getSource().getPlayerOrException();
                for(var page:data(p.server).core().catchUp(p.getUUID())) {
                    p.sendSystemMessage(Component.literal(page.title()+" — "+page.summary()+"\n"+page.action()));
                    for(var e:page.evidence())p.sendSystemMessage(Component.literal(e.observation().text()));
                }
                return 1;
            }))
            .then(Commands.literal("news").executes(c -> {
                var p=c.getSource().getPlayerOrException(); var d=data(p.server);
                for (var news:d.core().news(p.getUUID(),System.currentTimeMillis(),true)) p.sendSystemMessage(Component.literal(news.summary()));
                d.setDirty(); return 1;
            }))
            .then(Commands.literal("share").then(Commands.argument("observation",EvidenceArguments.type())
                .then(Commands.argument("recipients",EntityArgument.players()).executes(c -> {
                    var p=c.getSource().getPlayerOrException(); var d=data(p.server);
                    var recipients=new HashSet<UUID>(); EntityArgument.getPlayers(c,"recipients").forEach(r -> recipients.add(r.getUUID()));
                    try { d.core().share(p.getUUID(),StringArgumentType.getString(c,"observation"),recipients); }
                    catch (IllegalArgumentException | NullPointerException | SecurityException e) { c.getSource().sendFailure(Component.literal("Relevé inconnu ou témoin différent.")); return 0; }
                    long now=System.currentTimeMillis();
                    if(recipients.stream().anyMatch(id->!id.equals(p.getUUID())))played(p,d,now);
                    d.core().evaluate(now); d.setDirty(); return 1;
                }))))
            .then(Commands.literal("test").requires(s -> s.hasPermission(4) && QuestConfig.TEST_COMMANDS.get())
                .then(Commands.literal("fact").then(Commands.argument("type",StringArgumentType.word()).executes(c -> {
                    var type=StringArgumentType.getString(c,"type");
                    if (Campaign.load().stream().noneMatch(q -> q.observations().contains(type))) {
                        c.getSource().sendFailure(Component.literal("Type de fait inconnu.")); return 0;
                    }
                    var actor=c.getSource().getEntity();
                    publish(c.getSource().getServer(),new WorldFact(UUID.randomUUID().toString(),type,"proutlost:test",Set.of(),"","test","test",
                        System.currentTimeMillis(),actor==null?Map.of():Map.of("actor",actor.getUUID().toString()),true,WorldFact.Category.NARRATIVE));
                    c.getSource().sendSuccess(() -> Component.literal("Fait SYNTHÉTIQUE enregistré ; ceci ne valide pas le gameplay."),false); return 1;
                }))))));
    }
}
