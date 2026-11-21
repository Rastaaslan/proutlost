package fr.proutlost.quest.runtime;

import fr.proutlost.quest.core.QuestCore;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.BlockTags;
import java.util.*;

/** Idempotent environmental adapter: stops known routine replays and verifies an actually open path. */
public final class FinalRelease {
    private FinalRelease(){}
    public static boolean isOpen(net.minecraft.server.level.ServerLevel level,PreparedSites.Site site){
        return level.hasChunkAt(site.pos())&&level.getBlockState(site.pos().below()).is(BlockTags.PLANKS)
            &&level.getBlockState(site.pos()).getCollisionShape(level,site.pos()).isEmpty()
            &&level.getBlockState(site.pos().above()).getCollisionShape(level,site.pos().above()).isEmpty();
    }
    public static void tick(MinecraftServer server,QuestSavedData data,long now){
        var core=data.core();
        if(data.actions().released()){
            fr.proutlost.ambient.AmbientRuntime.suppress(server,data.routineLocations());return;
        }
        if(core.state("F4")==QuestCore.State.LOCKED||!core.hasCollective("FRAGMENTS_LINKED")||!core.hasCollective("PLACE_EVOLVED")||!core.hasCollective("COMPLETE_REPLY"))return;
        var vote=data.state().vote("D3").orElse(null);if(vote==null||vote.result().isEmpty())return;
        var points=data.actions().retainedPassagePoints(data.state());if(points.size()<2)return;
        for(var point:points){
            var site=data.sites().sites().get(point);if(site==null)return;
            for(var level:server.getAllLevels())if(level.dimension().location().toString().equals(site.dimension())&&level.hasChunkAt(site.pos())) {
                data.actions().verifyPassagePoint(data.state(),point,isOpen(level,site));data.setDirty();
            }
        }
        if(!data.actions().passageVerified(data.state()))return;
        if(server.getPlayerList().getPlayers().isEmpty()||!core.convergenceReady("F4",now))return;
        fr.proutlost.ambient.AmbientRuntime.suppress(server,data.routineLocations());
        data.actions().confirmRelease(data.state(),server.getPlayerList().getPlayers().getFirst().getUUID(),true,true,!data.actions().transmissionMode().isEmpty(),now);
        data.setDirty();
    }
}
