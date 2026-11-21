package fr.proutlost.quest.runtime;

import fr.proutlost.quest.core.QuestCore;
import java.util.Objects;
import net.minecraft.server.level.ServerPlayer;

/** Trusted native team adapter. No group identifier is accepted from a fact or packet. */
public final class QuestServerTeams {
 private QuestServerTeams(){}
 public static String assign(QuestCore core,ServerPlayer player){
  Objects.requireNonNull(core);Objects.requireNonNull(player);
  if(!player.server.isSameThread()||player.server.getPlayerList().getPlayer(player.getUUID())!=player)
   throw new IllegalStateException("actual connected server player required for Quest team binding");
  String group=player.getTeam()==null?null:player.getTeam().getName();
  core.assignGroup(player.getUUID(),group);return group;
 }
}
