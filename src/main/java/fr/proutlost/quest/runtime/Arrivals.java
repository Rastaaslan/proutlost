package fr.proutlost.quest.runtime;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** Initial common arrival only in an explicitly enabled, prepared campaign world. */
public final class Arrivals {
    private Arrivals(){}
    public static void joined(PlayerEvent.PlayerLoggedInEvent event){
        if(QuestConfig.ENABLED.get()&&event.getEntity() instanceof ServerPlayer player)arrive(player);
    }
    public static void register(RegisterCommandsEvent event){
        event.getDispatcher().register(Commands.literal("proutlost").then(Commands.literal("quest").requires(s->QuestConfig.ENABLED.get())
            .then(Commands.literal("arrive").executes(c->arrive(c.getSource().getPlayerOrException())?1:0))));
    }
    private static boolean arrive(ServerPlayer player){
        var data=QuestRuntime.data(player.server);if(data.arrivals().contains(player.getUUID()))return false;
        var site=data.sites().sites().entrySet().stream().filter(e->e.getValue().kind()==PreparedSites.Kind.WRECK).sorted(java.util.Map.Entry.comparingByKey()).map(java.util.Map.Entry::getValue).findFirst().orElse(null);
        if(site==null)return false;
        for(var level:player.server.getAllLevels())if(level.dimension().location().toString().equals(site.dimension())){
            if(!level.hasChunkAt(site.pos())||!level.getBlockState(site.pos()).getCollisionShape(level,site.pos()).isEmpty()
                ||!level.getBlockState(site.pos().above()).getCollisionShape(level,site.pos().above()).isEmpty()
                ||level.getBlockState(site.pos().below()).getCollisionShape(level,site.pos().below()).isEmpty())return false;
            player.teleportTo(level,site.x()+.5,site.y(),site.z()+.5,player.getYRot(),player.getXRot());
            data.arrivals().add(player.getUUID());data.setDirty();
            player.sendSystemMessage(Component.literal("Vous vous retrouvez près de l’épave. Rejoignez le rivage sûr ; le carnet indique les actions disponibles."));return true;
        }
        return false;
    }
}
