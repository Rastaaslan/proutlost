package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.catalog.*;
import java.util.*;

/** Real bundled perception candidates, only for explicit OP4 LAB execution. */
public final class DirectorLabPerceptionCatalog implements DirectorBootstrap.TestOnlyBackend {
 private final CatalogSnapshot snapshot;
 private static ResourceId resource(String value){return new ResourceId(value);}
 private static PrimitiveDescriptor descriptor(String primitive,String capability,boolean sound){
  return new PrimitiveDescriptor(new PrimitiveId("proutlost:"+primitive),1,
   resource("proutlost:codec_"+primitive),Set.of(new CapabilityId(capability)),BudgetMath.zero(),
   resource("proutlost:footprint"),Set.of(),Set.of(OwnershipDomain.PERCEPTION),
   sound?Set.of(NoninvertibleEffect.SOUND_EXPOSURE):Set.of(),new DurationTicks(6000),
   PausePolicy.CLEANUP,RecoveryMode.RECOVER,resource("proutlost:cleanup"),
   Optional.empty(),Optional.empty(),ProviderMode.TEST_ONLY);
 }
 public DirectorLabPerceptionCatalog(ConfigView config){
  if(config.calibration()!=Calibration.DEFAULT_FOR_TESTING)throw new IllegalArgumentException("perception LAB calibration required");
  var names=Set.of("safe_micro","local_mist","parallel_steps");
  var paths=new HashSet<String>();
  names.forEach(name->paths.add("data/proutlost_director/director/events/candidate/"+name+".json"));
  var documents=new DirectorNativeCandidateCatalog(config).candidateDocuments().stream()
   .filter(source->paths.contains(source.path())).toList();
  if(documents.size()!=3)throw new IllegalStateException("bundled perception candidates missing");
  var capabilities=Set.of(new CapabilityId("perception.sound_v1"),
   new CapabilityId("perception.local_weather_v1"),new CapabilityId("perception.silhouette_v1"));
  var registry=new PrimitiveRegistry(List.of(
   descriptor("target_sound","perception.sound_v1",true),
   descriptor("local_weather","perception.local_weather_v1",false),
   descriptor("apparition","perception.silhouette_v1",false)),ProviderMode.TEST_ONLY);
  var known=new EnumMap<ContentReferences.Kind,Set<ResourceId>>(ContentReferences.Kind.class);
  known.put(ContentReferences.Kind.FACT,Set.of(resource("proutlost:safe_micro_observed"),
   resource("proutlost:local_mist_observed"),resource("proutlost:parallel_steps_observed")));
  known.put(ContentReferences.Kind.SOUND,Set.of(resource("minecraft:block.campfire.crackle"),resource("minecraft:block.gravel.step")));
  known.put(ContentReferences.Kind.MODEL,Set.of(resource("proutlost:test_silhouette")));
  known.put(ContentReferences.Kind.LOCAL_WEATHER,Set.of(resource("proutlost:test_mist")));
  known.put(ContentReferences.Kind.CODEC,Set.of(resource("proutlost:codec_target_sound"),
   resource("proutlost:codec_local_weather"),resource("proutlost:codec_apparition")));
  known.put(ContentReferences.Kind.RESOLVER,Set.of(resource("proutlost:footprint")));
  known.put(ContentReferences.Kind.CLEANUP,Set.of(resource("proutlost:cleanup")));
  var references=new ContentReferences(ProviderMode.TEST_ONLY,known,Map.of(),Map.of(),Map.of(),
   capabilities,Set.of("PERCEPTION:WITNESS"));
  var loader=new DefinitionLoader(config,registry,references,ProviderMode.TEST_ONLY,
   new DefinitionLoader.Limits(32,65536,4*1024*1024,128,128));
  var result=loader.reload(documents);
  if(!result.installed()||!result.diagnostics().isEmpty()||result.snapshot().events().size()!=3||
   !result.snapshot().chains().isEmpty())throw new IllegalStateException(
     "J02 perception candidates cannot compile "+result.diagnostics());
  snapshot=result.snapshot();
  for(var name:names) {
   var event=snapshot.events().get(new DefinitionId("proutlost_director:candidate/"+name));
   if(event==null||event.calibration()!=Calibration.DEFAULT_FOR_TESTING)
    throw new IllegalStateException("J02 perception candidate mismatch "+name);
  }
 }
 public CatalogSnapshot snapshot(){return snapshot;}
}
