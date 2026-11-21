package fr.proutlost.director.bootstrap;

import java.nio.file.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/** New private-world fence. Startup waits for real connections; never relaxes
 * the separate zero-client harness's flag, marker or guard. */
public final class DirectorRealGroupClientsQaFence {
 private DirectorRealGroupClientsQaFence(){}
 public static Path require(MinecraftServer server){
  if(!server.isSameThread()||!Boolean.getBoolean("proutlost.quest.realGroupClientsQa")
   ||!Boolean.getBoolean("proutlost.director.nativeLab")||Boolean.getBoolean("proutlost.quest.neutralLab")
   ||Boolean.getBoolean("proutlost.quest.publicGroupQa")||server.getPort()!=25579
   ||!server.getPlayerList().isUsingWhitelist()||server.getPlayerList().getPlayers().size()>2)
   throw new IllegalStateException("distinct private real-client GROUP QA required");
  try{
   String configured=System.getProperty("proutlost.quest.realGroupClientsQaRoot","");
   Path requested=Path.of(configured),actual=server.getWorldPath(LevelResource.ROOT).toRealPath();
   if(configured.isBlank()||!requested.isAbsolute()||!requested.toRealPath().equals(actual))throw new IllegalStateException("real-client GROUP QA world path");
   Path marker=actual.resolve("REAL_GROUP_CLIENTS_TEST_ONLY");
   if(!Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(marker)||Files.size(marker)>96
    ||!Files.readString(marker).trim().equals("PROUTLOST_REAL_GROUP_CLIENTS_QA_V1")
    ||Files.exists(actual.resolve("data/proutlost_quest_v1.dat"))
    ||Files.exists(actual.resolve("data/proutlost_quest_neutral_qa_v1.dat"))
    ||Files.exists(actual.resolve("data/proutlost_quest_public_group_qa_v1.dat")))
    throw new IllegalStateException("new real-client GROUP QA marker/state required");
   return actual;
  }catch(java.io.IOException invalid){throw new IllegalStateException("real-client GROUP QA world unreadable",invalid);}
 }
}
