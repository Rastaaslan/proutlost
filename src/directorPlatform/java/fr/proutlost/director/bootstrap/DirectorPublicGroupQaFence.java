package fr.proutlost.director.bootstrap;

import java.nio.file.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/** Separate, explicit TEST_ONLY world; never a production/laboratory opt-out. */
public final class DirectorPublicGroupQaFence {
 private DirectorPublicGroupQaFence(){}
 public static Path require(MinecraftServer server){
  if(!server.isSameThread()||!Boolean.getBoolean("proutlost.quest.publicGroupQa")
   ||!Boolean.getBoolean("proutlost.director.nativeLab")||Boolean.getBoolean("proutlost.quest.neutralLab")
   ||server.getPort()!=25579||!server.getPlayerList().isUsingWhitelist()
   ||!server.getPlayerList().getPlayers().isEmpty())
   throw new IllegalStateException("private zero-client public GROUP QA required");
  try{
   String configured=System.getProperty("proutlost.quest.publicGroupQaRoot","");
   Path requested=Path.of(configured),actual=server.getWorldPath(LevelResource.ROOT).toRealPath();
   if(configured.isBlank()||!requested.isAbsolute()||!requested.toRealPath().equals(actual))throw new IllegalStateException("public GROUP QA world path");
   Path marker=actual.resolve("PUBLIC_GROUP_TEST_ONLY");
   if(!Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(marker)||Files.size(marker)>96
    ||!Files.readString(marker).trim().equals("PROUTLOST_PUBLIC_GROUP_QA_V1")
    ||Files.exists(actual.resolve("data/proutlost_quest_v1.dat"))
    ||Files.exists(actual.resolve("data/proutlost_quest_neutral_qa_v1.dat")))
    throw new IllegalStateException("distinct public GROUP QA marker/state required");
   return actual;
  }catch(java.io.IOException invalid){throw new IllegalStateException("public GROUP QA world unreadable",invalid);}
 }
}
