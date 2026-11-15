package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.catalog.*;
import java.util.*;

/** Hash-verified J02 chain metadata only. This class installs no event provider or production CatalogPort. */
public final class DirectorLabChainCatalog implements DirectorBootstrap.TestOnlyBackend {
 private final ConfigView config;private final Map<DefinitionId,EventDefinition> events;
 private final Map<DefinitionId,ChainDefinition> chains;
 public DirectorLabChainCatalog(ConfigView config){
  this.config=Objects.requireNonNull(config);
  if(config.calibration()!=Calibration.DEFAULT_FOR_TESTING)throw new IllegalArgumentException("manual LAB chains only");
  var codec=new DefinitionCodec(config);var es=new TreeMap<DefinitionId,EventDefinition>();var cs=new TreeMap<DefinitionId,ChainDefinition>();
  for(var source:new DirectorNativeCandidateCatalog(config).candidateDocuments()){
   if(source.path().contains("/events/")){var e=codec.event(source.bytes());es.put(e.id(),e);}
   else{var c=codec.chain(source.bytes());cs.put(c.id(),c);}
  }
  if(es.size()!=9||cs.size()!=2)throw new IllegalStateException("exact candidate pack required");
  var known=new EnumMap<ContentReferences.Kind,Set<ResourceId>>(ContentReferences.Kind.class);
  known.put(ContentReferences.Kind.FACT,ids("camp_regrowth_observed","parallel_steps_observed","shared_choice_observed",
   "controlled_landslide_observed","animal_migration_observed","local_mist_observed","context_lost"));
  known.put(ContentReferences.Kind.TAG,ids("clearing","fauna_corridor","scar_still_relevant","remembered_camp","recovering_landscape","unfinished_recovery"));
  var refs=new ContentReferences(ProviderMode.TEST_ONLY,known,Map.of(),Map.of(),Map.of(),Set.of(),Set.of());
  var validator=new DefinitionValidator(new PrimitiveRegistry(List.of(),ProviderMode.TEST_ONLY),refs,ProviderMode.TEST_ONLY);
  cs.values().forEach(c->validator.chain(c,es));events=Map.copyOf(es);chains=Map.copyOf(cs);
 }
 private static Set<ResourceId> ids(String... paths){var result=new HashSet<ResourceId>();for(var p:paths)result.add(new ResourceId("proutlost:"+p));return Set.copyOf(result);}
 public ConfigView config(){return config;}
 public ChainDefinition definition(String name){return Objects.requireNonNull(chains.get(new DefinitionId("proutlost_director:candidate/"+name)),"unknown exact chain");}
 public EventDefinition event(DefinitionId id){return Objects.requireNonNull(events.get(id),"unknown exact event");}
 public DefinitionRef reference(ChainDefinition chain){return new DefinitionRef(chain.id(),chain.contentRevision(),BlobContent.hash(new ModelCodec<>(ChainDefinition.class).encode(chain)));}
 /** Metadata for pure condition evaluation only; never pass this empty provider map to AdmissionService. */
 public CatalogSnapshot conditionCatalogue(EventDefinition event,ChainDefinition chain){
  return new CatalogSnapshot(new Epoch(1),reference(chain).hash(),Map.of(event.id(),event),Map.of(chain.id(),chain),Map.of());
 }
}
