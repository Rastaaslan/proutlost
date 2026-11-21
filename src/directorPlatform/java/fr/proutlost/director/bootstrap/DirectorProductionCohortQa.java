package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.model.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.util.FakePlayer;

/** Independent, pinned zero-human FULL_MC24 qualification. It never creates a
 * player, J06 session, effect, permission, journal or operator approval. Baseline
 * has this policy only; Director uses the unchanged typed nine native ports. */
public final class DirectorProductionCohortQa {
 public static final String FLAG="proutlost.director.cohortQa",ROOT="proutlost.director.cohortQaRoot";
 public static final String PLAN="proutlost.director.cohortQaPlan",PLAN_PIN="proutlost.director.cohortQaPlanSha256";
 public static final String FIXTURE_PIN="proutlost.director.cohortQaFixtureSha256",BRANCH="proutlost.director.cohortQaBranch";
 public static final String MARKER="PROUTLOST_DECLARED_ZERO_HUMAN_COHORT_QA_V1";
 public static final String MARKER_FILE="DECLARED_ZERO_HUMAN_COHORT_TEST_ONLY";
 private static final Map<MinecraftServer,DirectorProductionCohortQa> OWNERS=new IdentityHashMap<>();
 private final MinecraftServer server;private final Path root;private final DeclaredCohortQaPlan plan;
 private final String planSha;private final Fingerprint fixture;private final Map<UUID,String> profiles;
 private DirectorProductionCohortQa(MinecraftServer server,Path root,DeclaredCohortQaPlan plan,String planSha,Fingerprint fixture){
  this.server=server;this.root=root;this.plan=plan;this.planSha=planSha;this.fixture=fixture;profiles=plan.actualProfiles();
 }
 public static boolean requested(){return Boolean.getBoolean(FLAG);}
 public static Optional<DirectorProductionCohortQa> current(MinecraftServer server){require(server!=null&&server.isSameThread(),"cohort server actor required");return Optional.ofNullable(OWNERS.get(server));}
 public static DirectorProductionCohortQa open(MinecraftServer server){
  require(server!=null&&server.isSameThread()&&requested(),"explicit cohort QA server actor required");
  var old=OWNERS.get(server);if(old!=null){old.requireWorld();return old;}
  require(!Boolean.getBoolean(DirectorProductionRuntimeQa.FLAG)&&!Boolean.getBoolean("proutlost.director.nativeLab")
   &&System.getProperty("proutlost.director.productionManifestSha256","").isBlank()
   &&!Boolean.getBoolean("proutlost.quest.neutralLab")&&!Boolean.getBoolean("proutlost.quest.publicGroupQa")
   &&!Boolean.getBoolean("proutlost.quest.realGroupClientsQa"),"cohort must be separate from pair/LAB/GROUP/approved modes");
  try{
   Path actual=server.getWorldPath(LevelResource.ROOT).toRealPath();Path selected=absolute(ROOT);
   require(selected.toRealPath().equals(actual),"exact independent cohort QA world required");
   Path marker=actual.resolve(MARKER_FILE);
   require(Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(marker)&&Files.size(marker)<=128
    &&Files.readString(marker).trim().equals(MARKER),"explicit distinct zero-human cohort marker required");
   for(String forbidden:List.of("DECLARED_SYNTHETIC_RUNTIME_TEST_ONLY","PUBLIC_GROUP_TEST_ONLY","REAL_GROUP_CLIENTS_TEST_ONLY",
    "data/proutlost_quest_v1.dat","data/proutlost_quest_neutral_qa_v1.dat","data/proutlost_quest_public_group_qa_v1.dat",
    "data/proutlost_quest_real_group_clients_qa_v1.dat","proutlost/director-profile/manifest.json"))
    require(!Files.exists(actual.resolve(forbidden)),"separate cohort root required: "+forbidden);
   Path file=regular(absolute(PLAN),65536);byte[] raw=Files.readAllBytes(file);String sha=hash(raw);
   require(sha.equals(shaProperty(PLAN_PIN)),"exact cohort plan SHA required");
   var plan=new ModelCodec<>(DeclaredCohortQaPlan.class).decode(raw);
   require(plan.branch().name().equals(System.getProperty(BRANCH,"")),"explicit matching baseline/Director branch required");
   require(plan.pins().get("profile_sha256").equals(DirectorLabObservationProfile.load().provenanceHash().value()),"unchanged DEFAULT_FOR_TESTING profile pin required");
   require(plan.pins().get("sources_sha256").equals(shaProperty("proutlost.director.cohortQaSourcesSha256")),"cohort source pin mismatch");
   verifyFile("proutlost.director.cohortQaCandidateManifest",plan.pins().get("candidate_sha256"),1048576);
   verifyFile("proutlost.director.cohortQaActionPlan",plan.pins().get("action_plan_sha256"),1048576);
   Path jar=regular(absolute("proutlost.director.cohortQaDirectorJar"),64L*1024*1024);
   String jarSha=shaProperty("proutlost.director.cohortQaDirectorSha256");
   require(jarSha.equals(plan.pins().get("director_jar_sha256"))&&hashFile(jar).equals(jarSha),"installed Director cohort candidate differs");
   verifyLoaded(jar,List.of(DirectorProductionCohortQa.class,DeclaredCohortQaPlan.class,DeclaredCohortQaPlan.Actor.class,DeclaredCohortQaPlan.Branch.class,fr.proutlost.director.runtime.DeclaredCohortAutomaticGate.class,
    DirectorProductionRuntimeQa.class,DirectorProductionRuntime.class,DirectorRuntimeContent.class,DirectorServerLifecycle.class,
    DirectorNativeTerrainDisasterGameplay.class,DirectorNativeEffectRouter.class,DirectorNativeObservationQualification.class,fr.proutlost.director.runtime.SingleSlicePrimitive.class));
   var fixture=fixtureFingerprint(plan,sha);require(fixture.value().equals(shaProperty(FIXTURE_PIN)),"exact separate cohort fixture pin required");
   var result=new DirectorProductionCohortQa(server,actual,plan,sha,fixture);result.requireWorld();OWNERS.put(server,result);
   System.out.println("DIRECTOR_COHORT_QA_BOUND branch="+plan.branch()+" target_actual_clients="+plan.actualPlayersTarget()
    +" human_operators_declared=0 mode="+plan.mode()+" plan_sha256="+sha+" fixture_sha256="+fixture.value()+" operator_approval=false");
   return result;
  }catch(IOException|NoSuchAlgorithmException invalid){throw new IllegalStateException("cohort QA provenance unavailable",invalid);}
 }
 /** Can be computed before boot from the exact manifest, without a world. */
 public static Fingerprint fixtureFingerprint(DeclaredCohortQaPlan plan,String planSha){
  Objects.requireNonNull(plan);require(planSha!=null&&planSha.matches("[0-9a-f]{64}"),"exact plan pin required");
  return BlobContent.hash(CanonicalJsonV1.encode(new JsonValue.ObjectValue(Map.of(
   "marker",new JsonValue.Text(MARKER),"plan_sha256",new JsonValue.Text(planSha),
   "branch",new JsonValue.Text(plan.branch().name()),"mode",new JsonValue.Text(plan.mode()),
   "pair_fixture_base_sha256",new JsonValue.Text(DirectorProductionRuntimeQa.fixtureFingerprint().value()),
   "max_actual_clients",new JsonValue.Number(java.math.BigDecimal.valueOf(plan.actualPlayersTarget())),
   "human_operators_declared",new JsonValue.Number(java.math.BigDecimal.ZERO),
   "operator_approval_obtained",new JsonValue.Bool(false)))));
 }
 /** No filesystem IO or generated identities on the tick/observer path. */
 public void requireWorld(){
  require(server.isSameThread()&&requested()&&!Boolean.getBoolean(DirectorProductionRuntimeQa.FLAG)
   &&!Boolean.getBoolean("proutlost.director.nativeLab")&&System.getProperty("proutlost.director.productionManifestSha256","").isBlank()
   &&!Boolean.getBoolean("proutlost.quest.neutralLab")&&!Boolean.getBoolean("proutlost.quest.publicGroupQa")&&!Boolean.getBoolean("proutlost.quest.realGroupClientsQa")
   &&server.getPort()==25579&&server.getPlayerList().isUsingWhitelist()&&server.getPlayerList().getPlayers().size()<=plan.actualPlayersTarget()
   &&root.equals(server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize()),"current private cohort root/port/whitelist/count required");
  for(var player:server.getPlayerList().getPlayers())require(!(player instanceof FakePlayer)&&player.server==server
   &&server.getPlayerList().getPlayer(player.getUUID())==player&&Objects.equals(profiles.get(player.getUUID()),player.getGameProfile().getName()),
   "actual admitted cohort profile in real PlayerList required");
 }
 public DeclaredCohortQaPlan.Branch branch(){return plan.branch();}public DeclaredCohortQaPlan plan(){return plan;}
 public String planSha(){return planSha;}public Fingerprint fixture(){return fixture;}public Path root(){return root;}
 public static void release(MinecraftServer server){require(server.isSameThread(),"cohort server actor");OWNERS.remove(server);}
 private static Path absolute(String key){String value=System.getProperty(key,"");Path p=Path.of(value);require(!value.isBlank()&&p.isAbsolute()&&!Files.isSymbolicLink(p),"absolute unlinked cohort input: "+key);return p;}
 private static Path regular(Path path,long maximum)throws IOException{require(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(path)&&Files.size(path)>0&&Files.size(path)<=maximum,"bounded regular cohort input required");return path;}
 private static String shaProperty(String key){String value=System.getProperty(key,"");require(value.matches("[0-9a-f]{64}")&&!value.equals("0".repeat(64)),"cohort SHA256 required: "+key);return value;}
 private static void verifyFile(String property,String pin,long maximum)throws IOException,NoSuchAlgorithmException{require(hashFile(regular(absolute(property),maximum)).equals(pin),"cohort file pin mismatch: "+property);}
 private static String hash(byte[] raw)throws NoSuchAlgorithmException{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));}
 private static String hashFile(Path file)throws IOException,NoSuchAlgorithmException{var digest=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(file)){byte[] block=new byte[65536];for(int n;(n=in.read(block))!=-1;)digest.update(block,0,n);}return HexFormat.of().formatHex(digest.digest());}
 private static void verifyLoaded(Path file,List<Class<?>> types)throws IOException{
  try(var jar=new java.util.jar.JarFile(file.toFile())){for(var type:types){String name=type.getName().replace('.','/')+".class";
   try(var loaded=type.getResourceAsStream("/"+name)){require(loaded!=null&&jar.getJarEntry(name)!=null,"cohort loaded class missing from candidate");
    try(var packed=jar.getInputStream(jar.getJarEntry(name))){byte[] a=loaded.readNBytes(1048577),b=packed.readNBytes(1048577);require(a.length<=1048576&&b.length<=1048576&&Arrays.equals(a,b),"loaded cohort class differs from pinned Director candidate");}}
  }}
 }
 private static void require(boolean ok,String why){if(!ok)throw new IllegalStateException(why);}
}
