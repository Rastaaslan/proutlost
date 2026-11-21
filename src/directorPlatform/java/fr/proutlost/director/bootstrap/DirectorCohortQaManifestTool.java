package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.DeclaredCohortQaPlan;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Local preparation only: no MinecraftServer, process, network or file writer.
 * File pins inside the plan are verified again by the native policy at boot. */
public final class DirectorCohortQaManifestTool {
 private DirectorCohortQaManifestTool(){}
 public static void main(String[]args)throws Exception{
  if(args.length!=1)throw new IllegalArgumentException("ONE_EXISTING_PINNED_COHORT_PLAN_JSON");
  Path file=Path.of(args[0]);
  if(!file.isAbsolute()||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(file)||Files.size(file)<=0||Files.size(file)>65536)
   throw new IllegalArgumentException("bounded absolute real cohort manifest required");
  byte[] bytes=Files.readAllBytes(file);var plan=new ModelCodec<>(DeclaredCohortQaPlan.class).decode(bytes);
  String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  System.out.println("COHORT_PLAN_DECLARATION_ONLY branch="+plan.branch()+" target_actual_connections="+plan.actualPlayersTarget()+" human_operators_declared="+plan.humanOperators());
  System.out.println("plan_sha256="+sha);System.out.println("fixture_sha256="+DirectorProductionCohortQa.fixtureFingerprint(plan,sha).value());
  System.out.println("NO_LAUNCH_NO_PLAYER_NO_WORLD_NO_RECEIPT_NO_ACCEPTANCE");
 }
}
