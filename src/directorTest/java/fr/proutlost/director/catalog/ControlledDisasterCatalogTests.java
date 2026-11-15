package fr.proutlost.director.catalog;

import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import java.math.BigDecimal;
import java.util.*;
import static fr.proutlost.director.catalog.ContentJson.*;

/** Real J02 parsing/registry validation only; no Minecraft or provider qualification. */
public final class ControlledDisasterCatalogTests {
 private static int assertions;
 private static final PrimitiveId WATER=new PrimitiveId("proutlost:controlled_water"),FIRE=new PrimitiveId("proutlost:controlled_fire");
 private static final ResourceId WATER_MASK=new ResourceId("proutlost_director:controlled_water_mask_v1"),FIRE_MASK=new ResourceId("proutlost_director:fire_mask_v1");
 private static final ResourceId FIRE_LEDGER=new ResourceId("proutlost_director:fire_hit_ledger_v1"),STATE=new ResourceId("proutlost_director:block_state_v1");
 private static final ResourceId WATER_RECIPE=new ResourceId("proutlost_director:test_water_catalog"),FIRE_RECIPE=new ResourceId("proutlost_director:test_fire_catalog");
 private static final ResourceId FIRE_DAMAGE=new ResourceId("proutlost_director:test_fire_catalog_damage");
 private static final ResourceId WATER_FACT=new ResourceId("proutlost_director:lab_water_catalog_observed"),FIRE_FACT=new ResourceId("proutlost_director:lab_fire_catalog_observed");
 private static final DefinitionLoader.Limits LIMITS=new DefinitionLoader.Limits(256,65536,4*1024*1024,512,512);
 private static void check(boolean value,String why){assertions++;if(!value)throw new AssertionError(why);}
 private static JsonValue.Text str(Object v){return new JsonValue.Text(v.toString());}
 private static JsonValue.Number n(long v){return new JsonValue.Number(BigDecimal.valueOf(v));}
 private static JsonValue change(JsonValue root,String pointer,JsonValue value){
  if(pointer.isEmpty())return value;var split=pointer.substring(1).split("/",2);var tail=split.length==1?"":"/"+split[1];
  if(root instanceof JsonValue.ObjectValue o){var next=new TreeMap<>(o.values());next.put(split[0],change(next.get(split[0]),tail,value));return obj(next);}
  var next=new ArrayList<>(array(root));int at=Integer.parseInt(split[0]);next.set(at,change(next.get(at),tail,value));return new JsonValue.Array(next);
 }
 private static DefinitionLoader.Source altered(DefinitionLoader.Source source,String pointer,JsonValue value){
  return new DefinitionLoader.Source(source.path(),CanonicalJsonV1.encode(change(CanonicalJsonV1.decode(source.bytes()),pointer,value)));
 }
 private static BlobContent mask(boolean fire){
  var dim=new DimensionId("minecraft:overworld");var a=new BlockPosition(dim,0,64,0);var b=new BlockPosition(dim,1,64,0);
  return BlobContent.capture(fire?FIRE_MASK:WATER_MASK,1,new ModelCodec<>(Footprint.class).encode(
   new Footprint(dim,Set.of(new ChunkRef(dim,0,0)),Set.of(a,b),List.of())));
 }
 private static JsonValue maskJson(boolean fire){
  var b=mask(fire).reference();return obj(Map.of("hash",str(b.hash().value()),"bytes",n(b.bytes()),"codec_version",n(b.codecVersion()),"codec_id",str(b.codecId().value())));
 }
 private static DefinitionLoader.Source definition(DefinitionLoader.Source template,boolean fire){
  JsonValue root=CanonicalJsonV1.decode(template.bytes());String name=fire?"fire":"water";
  root=change(root,"/id",str("proutlost_director:lab/controlled_"+name+"_catalog"));
  root=change(root,"/eligibility",obj(Map.of("type",str("players_present"),"minimum",n(1))));
  root=change(root,"/safety/required_capabilities",new JsonValue.Array((fire?
   List.of("warning.readable_v1","mutation.controlled_fire_v1","danger.controlled_fire_v1"):
   List.of("warning.readable_v1","fluid.controlled_v1")).stream().map(v->(JsonValue)str(v)).toList()));
  root=change(root,"/safety/mutation_profile",str((fire?FIRE_RECIPE:WATER_RECIPE).value()));
  root=change(root,"/budget/mutation_units",n(4));
  root=change(root,"/steps/1/primitive",str((fire?FIRE:WATER).value()));
  var args=new TreeMap<String,JsonValue>();args.put("recipe",str((fire?FIRE_RECIPE:WATER_RECIPE).value()));args.put("propagation_mask",maskJson(fire));
  if(fire){args.put("energy",n(400));args.put("damage_profile",str(FIRE_DAMAGE.value()));}
  else{args.put("transfer_units",n(32));args.put("max_level",n(8));}
  root=change(root,"/steps/1/args",obj(args));root=change(root,"/completion/fact_type",str((fire?FIRE_FACT:WATER_FACT).value()));
  root=change(root,"/completion/durable_trace",JsonValue.Nil.INSTANCE);root=change(root,"/completion/persistence_probability",n(0));
  return new DefinitionLoader.Source("data/proutlost_director/director/events/lab/controlled_"+name+"_catalog.json",CanonicalJsonV1.encode(root));
 }
 private static PrimitiveDescriptor descriptor(boolean fire){
  var caps=fire?Set.of(new CapabilityId("mutation.controlled_fire_v1"),new CapabilityId("danger.controlled_fire_v1")):Set.of(new CapabilityId("fluid.controlled_v1"));
  return new PrimitiveDescriptor(fire?FIRE:WATER,1,CatalogFixtures.id(fire?"codec_controlled_fire":"codec_controlled_water"),caps,CatalogFixtures.cost(0),
   CatalogFixtures.id("footprint"),Set.of(STATE,fire?FIRE_MASK:WATER_MASK),Set.of(OwnershipDomain.BLOCK),
   fire?Set.of(NoninvertibleEffect.DAMAGE):Set.of(),new DurationTicks(6000),PausePolicy.STABILIZE,RecoveryMode.RECOVER,CatalogFixtures.id("cleanup"),Optional.empty(),Optional.empty(),ProviderMode.TEST_ONLY);
 }
 private static PrimitiveRegistry registry(){
  var entries=new ArrayList<>(CatalogFixtures.primitives().descriptors().values());entries.add(descriptor(false));entries.add(descriptor(true));return new PrimitiveRegistry(entries,ProviderMode.TEST_ONLY);
 }
 private static void add(Map<ContentReferences.Kind,Set<ResourceId>> known,ContentReferences.Kind kind,ResourceId... ids){
  var values=new HashSet<>(known.getOrDefault(kind,Set.of()));values.addAll(List.of(ids));known.put(kind,Set.copyOf(values));
 }
 private static ContentReferences references(){
  var base=CatalogFixtures.references();var known=new EnumMap<ContentReferences.Kind,Set<ResourceId>>(ContentReferences.Kind.class);known.putAll(base.known());
  add(known,ContentReferences.Kind.FACT,WATER_FACT,FIRE_FACT);add(known,ContentReferences.Kind.CODEC,STATE,WATER_MASK,FIRE_MASK,FIRE_LEDGER,CatalogFixtures.id("codec_controlled_water"),CatalogFixtures.id("codec_controlled_fire"));
  add(known,ContentReferences.Kind.DAMAGE_TYPE,new ResourceId("minecraft:in_fire"));
  var mutations=new HashMap<>(base.mutations());var template=mutations.get(CatalogFixtures.id("test_bounded_landslide"));
  for(boolean fire:List.of(false,true)){
   var recipe=fire?FIRE_RECIPE:WATER_RECIPE;var primitive=fire?FIRE:WATER;
   mutations.put(recipe,new MutationProfile(recipe,1,Set.of(primitive),Set.of(STATE),template.footprintResolver(),template.preconditions(),
    template.propagationMaskStrategy(),CatalogFixtures.cost(4),template.neighborRadius(),template.safeExits(),PlayerEditPolicy.YIELD,Optional.empty(),primitive,
    fire?Set.of(NoninvertibleEffect.DAMAGE):Set.of(),RecoveryMode.RECOVER,Set.of("TEST_ONLY:T-MUT")));
  }
  var damages=new HashMap<>(base.damages());damages.put(FIRE_DAMAGE,new DamageProfile(FIRE_DAMAGE,Set.of(new ResourceId("minecraft:in_fire")),2,6,new DurationTicks(20),List.of(),Set.of(ReasonCode.CAPABILITY_MISSING),FIRE_LEDGER));
  var caps=new HashSet<>(base.capabilities());caps.addAll(descriptor(false).capabilities());caps.addAll(descriptor(true).capabilities());
  return new ContentReferences(ProviderMode.TEST_ONLY,known,mutations,base.warnings(),damages,caps,base.claims());
 }
 private static DefinitionLoader loader(ConfigView config,PrimitiveRegistry registry,ContentReferences refs){return new DefinitionLoader(config,registry,refs,ProviderMode.TEST_ONLY,LIMITS);}
 private static void invalid(ConfigView config,DefinitionLoader.Source good,DefinitionLoader.Source bad,String why,String pointer){
  invalid(config,good,bad,registry(),references(),why,pointer);
 }
 private static void invalid(ConfigView config,DefinitionLoader.Source good,DefinitionLoader.Source bad,PrimitiveRegistry registry,ContentReferences refs,String why,String pointer){
  var loader=loader(config,registry,refs);var baseline=loader.reload(List.of(good));check(baseline.installed(),why+" baseline "+baseline.diagnostics());
  var before=loader.current();var result=loader.reload(List.of(bad));
  check(!result.installed()&&!result.diagnostics().isEmpty(),why+" rejected");
  check(loader.current()==before&&result.snapshot()==before,why+" prior populated snapshot retained");
  check(result.diagnostics().stream().allMatch(d->d.code()==ReasonCode.CATALOG_INVALID),why+" typed diagnostic");
  if(pointer!=null)check(result.diagnostics().stream().anyMatch(d->d.pointer().startsWith(pointer)),why+" diagnostic pointer "+result.diagnostics());
 }
 public static void run(ConfigView config,List<DefinitionLoader.Source> baseline){
  assertions=0;var template=baseline.stream().filter(s->s.path().endsWith("/controlled_landslide.json")).findFirst().orElseThrow();
  var water=definition(template,false);var fire=definition(template,true);var refs=references();
  var unchanged=loader(config,registry(),refs).reload(baseline);
  check(unchanged.installed()&&unchanged.snapshot().events().size()==9&&unchanged.snapshot().chains().size()==2,"extended descriptors preserve nine events/two chains");
  var loaded=loader(config,registry(),refs).reload(List.of(water,fire));check(loaded.installed(),"typed water/fire load "+loaded.diagnostics());
  for(var event:loaded.snapshot().events().values()){
   var args=event.steps().stream().filter(s->!s.primitive().value().equals("proutlost:warning")).findFirst().orElseThrow().args();
   if(args instanceof ControlledWater a)check(a.recipe().equals(WATER_RECIPE)&&a.propagationMask().equals(mask(false).reference())&&a.transferUnits()==32&&a.maxLevel()==8,"typed water values");
   else if(args instanceof ControlledFire a)check(a.recipe().equals(FIRE_RECIPE)&&a.propagationMask().equals(mask(true).reference())&&a.energy().value()==400&&a.damageProfile().equals(FIRE_DAMAGE),"typed fire values");
   else throw new AssertionError("no alias argument");
   var codec=new ModelCodec<>(EventDefinition.class);check(codec.decode(codec.encode(event)).equals(event),"existing G0 persistence roundtrip "+event.id());
   check(event.budget().mutationsInFlight()==4,"volume/energy are not mutation-cell counts");
  }
  var safe=altered(water,"/safety/risk",str("SAFE"));safe=altered(safe,"/steps",new JsonValue.Array(List.of(
   change(array(object(CanonicalJsonV1.decode(safe.bytes())).get("steps")).get(1),"/after",new JsonValue.Array(List.of())))));
  var safeLoaded=loader(config,registry(),refs).reload(List.of(safe));check(safeLoaded.installed(),"non-damaging water SAFE does not acquire a new WARN requirement "+safeLoaded.diagnostics());
  var drain=altered(water,"/steps/1/args/transfer_units",n(0));check(loader(config,registry(),refs).reload(List.of(drain)).installed(),"zero volume supported for real cleanup recipes; no physical success asserted");
  for(var good:List.of(water,fire)){
   invalid(config,good,altered(good,"/steps/1/args/recipe",str("missing:recipe")),"unknown recipe","/steps/1/args/recipe");
   invalid(config,good,altered(good,"/steps/1/args/propagation_mask/codec_id",str("missing:mask")),"uncapturable mask","/steps/1/args/propagation_mask");
   invalid(config,good,altered(good,"/steps/1/args/propagation_mask/bytes",n(0)),"empty mask","/steps/1/args/propagation_mask");
   invalid(config,good,altered(good,"/steps/1/args/propagation_mask/codec_version",n(0)),"unversioned mask","/steps/1/args/propagation_mask");
   invalid(config,good,altered(good,"/steps/1/args/propagation_mask/hash",str("not-a-sha256")),"invalid mask hash","/steps/1/args/propagation_mask");
   invalid(config,good,altered(good,"/steps/1/after",new JsonValue.Array(List.of())),"controlled danger requires WARN dependency","/steps/1/after");
   invalid(config,good,altered(good,"/steps/1/delay_ticks",n(399)),"controlled danger preserves lead","/steps/1/after");
   invalid(config,good,altered(good,"/steps/0/on_failure",str("SKIP_SAFE")),"controlled danger WARN cannot be skipped","/steps/1/after");
   invalid(config,good,altered(good,"/budget/mutation_units",n(3)),"recipe cell envelope cannot be underbudget","/budget/mutation_units");
  }
  invalid(config,water,altered(water,"/steps/1/args/max_level",n(9)),"water level bound","/steps/1/args/max_level");
  invalid(config,water,altered(water,"/steps/1/args/transfer_units",n(-1)),"negative volume","/steps/1/args/transfer_units");
  invalid(config,fire,altered(fire,"/steps/1/args/energy",n(0)),"zero fire energy","/steps/1/args/energy");
  invalid(config,fire,altered(fire,"/steps/1/args/damage_profile",str("missing:damage")),"unknown fire damage profile","/steps/1/args/damage_profile");
  invalid(config,fire,altered(fire,"/safety/risk",str("SAFE")),"fire cannot evade WARN through SAFE","/safety/risk");
  var damages=new HashMap<>(refs.damages());var registered=damages.get(FIRE_DAMAGE);
  damages.put(FIRE_DAMAGE,new DamageProfile(FIRE_DAMAGE,Set.of(new ResourceId("minecraft:falling_block")),registered.perHit(),registered.maxPerPlayer(),registered.hitGap(),registered.excludedZones(),registered.pauseReasons(),registered.hitLedgerCodec()));
  var wrongFireType=new ContentReferences(refs.mode(),refs.known(),refs.mutations(),refs.warnings(),damages,refs.capabilities(),refs.claims());
  invalid(config,water,fire,registry(),wrongFireType,"registered unrelated damage type cannot stand in for native fire","/steps/1/args/damage_profile");
  var absent=loader(config,CatalogFixtures.primitives(),refs).reload(List.of(water));check(!absent.installed(),"argument codec alone never registers a provider");
  System.out.println("PASS T-CAT controlled-water/fire assertions="+assertions+" TEST_ONLY; physical/client qualification not asserted");
 }
 private ControlledDisasterCatalogTests(){}
}
