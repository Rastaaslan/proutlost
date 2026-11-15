package fr.proutlost.director.adapter.neoforge.server;

import fr.proutlost.director.adapter.neoforge.server.effects.*;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.bootstrap.*;
import fr.proutlost.director.runtime.DisasterRunner;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;

/**
 * Executes the real native LAB factories and their DefinitionLoader decode/registry checks.
 * Exit coordinates and mask content below are explicit TEST_ONLY DTO fixtures, not surveyed
 * terrain, provider execution, human warning receipts, production admission or G5 evidence.
 * No world, entity, client, lifecycle catalogue or WAL is mutated by these tests.
 */
@GameTestHolder("proutlost_director")
@PrefixGameTestTemplate(false)
public final class NativeFactoryCatalogGameTests {
 private NativeFactoryCatalogGameTests() {}
 private static final DimensionId OVERWORLD=new DimensionId("minecraft:overworld");
 private static final PrimitiveId WARNING=new PrimitiveId("proutlost:warning");
 private static final CapabilityId READABLE=new CapabilityId("warning.readable_v1");
 private static final List<RouteSegment> DTO_TEST_ONLY_EXITS=List.of(new RouteSegment(
  new BlockPosition(OVERWORLD,100,64,100),new BlockPosition(OVERWORLD,103,64,100),.4));

 private static BlobRef dtoTestOnlyMask(ResourceId codec,String label) {
  return BlobContent.capture(codec,1,("TEST_ONLY_J02_DTO_NOT_PHYSICAL_MASK:"+label)
   .getBytes(StandardCharsets.UTF_8)).reference();
 }
 private static void actor(GameTestHelper h) {
  h.assertTrue(h.getLevel().getServer()!=null&&h.getLevel().getServer().isSameThread(),"J02 native actor required");
 }
 private static Set<CapabilityId> caps(String... names) {
  var result=new HashSet<CapabilityId>();for(var name:names)result.add(new CapabilityId(name));return Set.copyOf(result);
 }
 private static Map<String,String> candidateBytes(ConfigView config) {
  var candidate=new DirectorNativeCandidateCatalog(config);
  if(candidate.status().verifiedEvents()!=9||candidate.status().verifiedChains()!=2
    ||candidate.dependencies().requiredPrimitives().size()!=10
    ||candidate.dependencies().requiredCapabilities().size()!=13
    ||candidate.status().validatedForProduction()||candidate.status().gameplayAdmitted())
   throw new IllegalStateException("original nine-event/two-chain candidate contract changed");
  var result=new TreeMap<String,String>();
  try {
   for(var source:candidate.candidateDocuments())
    result.put(source.path(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.bytes())));
  }catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
  if(result.size()!=11)throw new IllegalStateException("original candidate documents missing");
  return Map.copyOf(result);
 }
 private static void unchanged(GameTestHelper h,ConfigView config,Map<String,String> before) {
  h.assertTrue(before.equals(candidateBytes(config)),"LAB factory changed original candidate document bytes or dependency graph");
  h.assertTrue(new DirectorNativeCandidateCatalog(config).rejectsUnqualifiedProviders(config),
   "new LAB factories must not grant the original pack absent real providers");
 }
 private static EventDefinition definition(GameTestHelper h,CatalogSnapshot snapshot,DefinitionId id,int count,Set<CapabilityId> expectedCaps) {
  h.assertTrue(snapshot.events().size()==count&&snapshot.chains().isEmpty()&&snapshot.events().containsKey(id),
   "actual DefinitionLoader did not install exactly the expected LAB events");
  h.assertTrue(snapshot.primitives().values().stream().allMatch(p->p.mode()==ProviderMode.TEST_ONLY),
   "LAB descriptor promoted to production");
  var event=snapshot.events().get(id);
  h.assertTrue(event.id().equals(id)&&event.calibration()==Calibration.DEFAULT_FOR_TESTING,
   "definition identity/calibration changed during real J02 decode");
  h.assertTrue(event.safety().requiredCapabilities().equals(expectedCaps),"decoded capability declaration drifted");
  var registered=new HashSet<CapabilityId>();snapshot.primitives().values().forEach(p->registered.addAll(p.capabilities()));
  h.assertTrue(registered.equals(expectedCaps),"factory registry does not exactly provide its declared capabilities");
  var warnings=event.steps().stream().filter(s->s.primitive().equals(WARNING)).toList();
  h.assertTrue(warnings.size()==1&&warnings.getFirst().args() instanceof Warning,"WARN lost its typed argument codec");
  var warning=(Warning)warnings.getFirst().args();
  h.assertTrue(warning.minimumLeadTicks().value()==400&&event.safety().warningProfile().orElseThrow().equals(warning.profile()),
   "typed WARN profile/lead not resolved by J02");
  return event;
 }
 private static EventStep only(GameTestHelper h,EventDefinition event,PrimitiveId primitive) {
  var matches=event.steps().stream().filter(s->s.primitive().equals(primitive)).toList();
  h.assertTrue(matches.size()==1,"expected exactly one decoded physical step: "+primitive.value());
  return matches.getFirst();
 }

 @GameTest(templateNamespace="proutlost_director",template="smoke_empty",timeoutTicks=200)
 public static void realTerrainFactoriesDecodeLandslideAndCollapse(GameTestHelper h) {
  actor(h);var config=DirectorLabObservationProfile.load();var before=candidateBytes(config);
  var expected=caps("warning.readable_v1","mutation.terrain_v1","danger.debris_v1");
  var slide=new PrimitiveId("proutlost:landslide");
  var landslide=new DirectorLabTerrainDisasterCatalog(config,DisasterRunner.Family.LANDSLIDE,DTO_TEST_ONLY_EXITS);
  h.assertTrue(landslide.definitionId().equals(new DefinitionId("proutlost_director:candidate/controlled_landslide")),"LANDSLIDE must retain its exact candidate identity");
  var first=definition(h,landslide.snapshot(),landslide.definitionId(),1,expected);
  var a=only(h,first,slide).args();h.assertTrue(a instanceof Landslide,"native LANDSLIDE args not decoded");
  var args=(Landslide)a;
  h.assertTrue(args.recipe().equals(new ResourceId("proutlost:test_bounded_landslide"))&&args.maxBlocks()==1024
   &&args.damageProfile().equals(new ResourceId("proutlost:test_debris_damage"))
   &&first.safety().mutationProfile().orElseThrow().equals(args.recipe()),"exact candidate landslide recipe/profile drift");
  var collapse=new DirectorLabTerrainDisasterCatalog(config,DisasterRunner.Family.COLLAPSE,DTO_TEST_ONLY_EXITS);
  h.assertTrue(collapse.definitionId().equals(new DefinitionId("proutlost_director:lab/controlled_collapse")),"COLLAPSE must use distinct LAB identity");
  var second=definition(h,collapse.snapshot(),collapse.definitionId(),1,expected);
  var b=only(h,second,slide).args();h.assertTrue(b instanceof Landslide,"COLLAPSE args not decoded");
  var collapsed=(Landslide)b;
  h.assertTrue(collapsed.recipe().equals(new ResourceId("proutlost:test_bounded_collapse"))&&collapsed.maxBlocks()==4
   &&collapsed.damageProfile().equals(args.damageProfile())
   &&second.safety().mutationProfile().orElseThrow().equals(collapsed.recipe()),"four-cell collapse recipe/profile drift");
  h.assertTrue(collapse.snapshot().primitives().get(slide).noninvertibleEffects().equals(Set.of(NoninvertibleEffect.DAMAGE)),
   "terrain debris capability lost its actual DAMAGE requirement");
  unchanged(h,config,before);System.out.println("DIRECTOR_NATIVE_J02_TERRAIN_FACTORIES_REAL_LOADER_PASS");h.succeed();
 }

 @GameTest(templateNamespace="proutlost_director",template="smoke_empty",timeoutTicks=200)
 public static void realFloodFactorySeparatesImportAndPulseProfiles(GameTestHelper h) {
  actor(h);var config=DirectorLabObservationProfile.load();var before=candidateBytes(config);
  var mask=dtoTestOnlyMask(ControlledWaterBinding.MASK_CODEC,"water");
  var factory=new DirectorLabFloodDisasterCatalog(config,DTO_TEST_ONLY_EXITS,mask,26);
  var expected=caps("warning.readable_v1","fluid.controlled_v1");
  h.assertTrue(factory.definitionId().equals(new DefinitionId("proutlost_director:lab/controlled_flood"))
   &&factory.pulseDefinitionId().equals(new DefinitionId("proutlost_director:lab/controlled_flood_pulse")),"distinct IMPORT/PULSE definition identities");
  var imported=definition(h,factory.snapshot(),factory.definitionId(),2,expected);
  var pulsed=definition(h,factory.snapshot(),factory.pulseDefinitionId(),2,expected);
  var a=only(h,imported,ControlledWaterBinding.ID).args();var b=only(h,pulsed,ControlledWaterBinding.ID).args();
  h.assertTrue(a instanceof ControlledWater&&b instanceof ControlledWater,"controlled water typed codec missing");
  var in=(ControlledWater)a;var pulse=(ControlledWater)b;
  h.assertTrue(in.recipe().equals(ControlledWaterBinding.IMPORT)&&in.transferUnits()==26&&in.maxLevel()==8
   &&pulse.recipe().equals(ControlledWaterBinding.PULSE)&&pulse.transferUnits()==1&&pulse.maxLevel()==8,"finite import/pulse arguments drift");
  h.assertTrue(in.propagationMask().equals(mask)&&pulse.propagationMask().equals(mask),"J02 mask hash/length/codec reference changed");
  h.assertTrue(imported.safety().mutationProfile().orElseThrow().equals(in.recipe())
   &&pulsed.safety().mutationProfile().orElseThrow().equals(pulse.recipe()),"G0 single mutation profile must match each actual recipe");
  h.assertTrue(factory.snapshot().primitives().get(ControlledWaterBinding.ID).capturableCodecs().contains(mask.codecId())
   &&factory.snapshot().primitives().get(ControlledWaterBinding.ID).noninvertibleEffects().isEmpty(),"water must capture mask and claim no debris/fire damage");
  unchanged(h,config,before);System.out.println("DIRECTOR_NATIVE_J02_FLOOD_IMPORT_PULSE_REAL_LOADER_PASS");h.succeed();
 }

 @GameTest(templateNamespace="proutlost_director",template="smoke_empty",timeoutTicks=200)
 public static void realFireFactoryResolvesMaskEnergyAndDamageProfile(GameTestHelper h) {
  actor(h);var config=DirectorLabObservationProfile.load();var before=candidateBytes(config);
  var mask=dtoTestOnlyMask(ControlledFireWaveBinding.MASK_CODEC,"fire");
  var factory=new DirectorLabFireDisasterCatalog(config,DTO_TEST_ONLY_EXITS,mask);
  var expected=caps("warning.readable_v1","mutation.controlled_fire_v1","danger.controlled_fire_v1");
  h.assertTrue(factory.definitionId().equals(new DefinitionId("proutlost_director:lab/controlled_fire")),"FIRE LAB identity");
  var event=definition(h,factory.snapshot(),factory.definitionId(),1,expected);
  var raw=only(h,event,ControlledFireWaveBinding.FIRE).args();
  h.assertTrue(raw instanceof ControlledFire,"fire typed codec missing");var fire=(ControlledFire)raw;
  h.assertTrue(fire.recipe().equals(DirectorLabFireDisasterCatalog.RECIPE)&&fire.energy().value()==400
   &&fire.propagationMask().equals(mask)&&fire.damageProfile().equals(factory.damageProfile().id())
   &&event.safety().mutationProfile().orElseThrow().equals(fire.recipe()),"fire recipe/mask/energy/damage refs changed");
  var descriptor=factory.snapshot().primitives().get(ControlledFireWaveBinding.FIRE);
  h.assertTrue(descriptor.capturableCodecs().containsAll(Set.of(mask.codecId(),ControlledFireWaveBinding.HIT_CODEC))
   &&descriptor.noninvertibleEffects().equals(Set.of(NoninvertibleEffect.DAMAGE)),"FIRE hit ledger or DAMAGE declaration missing");
  unchanged(h,config,before);System.out.println("DIRECTOR_NATIVE_J02_FIRE_FACTORY_REAL_LOADER_PASS");h.succeed();
 }

 @GameTest(templateNamespace="proutlost_director",template="smoke_empty",timeoutTicks=200)
 public static void realStormFactoryDecodesGlobalRainAndWindWithoutDebris(GameTestHelper h) {
  actor(h);var config=DirectorLabObservationProfile.load();var before=candidateBytes(config);
  var lease=new GlobalWeatherLease(OVERWORLD,new ResourceId("minecraft:rain"),new DurationTicks(400),new Revision(17));
  var factory=new DirectorLabStormCatalog(config,lease);
  var expected=caps("warning.readable_v1","weather.global_lease_v1","perception.sound_v1");
  h.assertTrue(factory.definitionId().equals(new DefinitionId("proutlost_director:lab/global_rain_lease")),"STORM LAB identity");
  var event=definition(h,factory.snapshot(),factory.definitionId(),1,expected);
  var weather=new PrimitiveId("proutlost:global_weather_lease");var rain=only(h,event,weather);
  h.assertTrue(rain.args() instanceof GlobalWeatherLease&&rain.args().equals(lease),"GlobalWeatherLease dimension/weather/duration/revision round trip");
  var wind=only(h,event,new PrimitiveId("proutlost:target_sound"));
  h.assertTrue(wind.args() instanceof TargetSound,"wind typed sound codec missing");var sound=(TargetSound)wind.args();
  h.assertTrue(sound.sound().equals(new ResourceId("minecraft:entity.breeze.idle_air"))&&sound.repeats()==1
   &&wind.delayTicks().value()==400&&rain.after().equals(List.of(wind.id())),"actual wind dependency or sound ref drift");
  h.assertTrue(event.safety().risk()==Risk.SAFE
   &&event.safety().mutationProfile().orElseThrow().equals(new ResourceId("proutlost:test_global_storm_lease"))
   &&factory.snapshot().primitives().get(weather).ownershipDomains().equals(Set.of(OwnershipDomain.WEATHER))
   &&factory.snapshot().primitives().get(weather).noninvertibleEffects().isEmpty(),"global rain must not claim local footprint or debris/lightning damage");
  unchanged(h,config,before);System.out.println("DIRECTOR_NATIVE_J02_STORM_FACTORY_REAL_LOADER_PASS");h.succeed();
 }
}
