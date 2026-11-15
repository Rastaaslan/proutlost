package fr.proutlost.director.bootstrap;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.*;
import java.util.*;

/** Composition only: domains are supplied by later jobs; unavailable ports remain explicit. */
public final class DirectorBootstrap {
 private DirectorBootstrap() {}
 public interface TestOnlyBackend {}
 public record Provider(Object implementation,ProviderMode mode,List<Provider> dependencies,List<CapabilityDescriptor> capabilities) {
  public Provider { Objects.requireNonNull(implementation);Objects.requireNonNull(mode);dependencies=List.copyOf(dependencies);capabilities=List.copyOf(capabilities); }
 }
 public record Ports(Optional<ObservationPort> observation,Optional<MemoryPort> memory,Optional<CatalogPort> catalog,
  Optional<DecisionPort> decision,Optional<AdmissionPort> admission,Optional<WorldEffectPort> worldEffects,
  Optional<DurableStore> durableStore,Optional<FactBusPort> factBus,Optional<QuestDirectorPort> quest,
  Optional<AiAdvisorPort> advisor,Optional<AdminPort> admin) {}
 public record Composition(ProviderMode mode,Ports ports,Map<CapabilityId,CapabilityDescriptor> capabilities,
  Map<PrimitiveId,DirectorPrimitive<?>> physicalPrimitives,Set<String> missingPorts) {
  public Composition {capabilities=Map.copyOf(capabilities);physicalPrimitives=Map.copyOf(physicalPrimitives);missingPorts=Set.copyOf(missingPorts);}
  public boolean available(CapabilityId id){var c=capabilities.get(id);return c!=null&&c.status()==CapabilityStatus.AVAILABLE;}
 }
 public static Composition compose(ProviderMode mode,List<Provider> roots) {
  Objects.requireNonNull(mode);Map<CapabilityId,CapabilityDescriptor> capabilities=new HashMap<>();
  Set<Provider> visited=Collections.newSetFromMap(new IdentityHashMap<>());
  List<Object> implementations=new ArrayList<>();
  Map<PrimitiveId,DirectorPrimitive<?>> physicalPrimitives=new HashMap<>();
  for(Provider provider:List.copyOf(roots))inspect(provider,mode,visited,capabilities,physicalPrimitives,implementations,0);
  Set<String> missing=new TreeSet<>();
  Ports ports=new Ports(find(ObservationPort.class,implementations,missing),find(MemoryPort.class,implementations,missing),find(CatalogPort.class,implementations,missing),find(DecisionPort.class,implementations,missing),find(AdmissionPort.class,implementations,missing),find(WorldEffectPort.class,implementations,missing),find(DurableStore.class,implementations,missing),find(FactBusPort.class,implementations,missing),find(QuestDirectorPort.class,implementations,missing),find(AiAdvisorPort.class,implementations,missing),find(AdminPort.class,implementations,missing));
  return new Composition(mode,ports,capabilities,physicalPrimitives,missing);
 }
 private static void inspect(Provider p,ProviderMode mode,Set<Provider> visited,Map<CapabilityId,CapabilityDescriptor> capabilities,
  Map<PrimitiveId,DirectorPrimitive<?>> physicalPrimitives,List<Object> implementations,int depth){
  Checks.require(depth<=32&&visited.size()<1024,"provider graph bound");
  if(!visited.add(p))return;
  // A TEST_ONLY graph must reject production bindings as well: native
  // effects cannot be laundered into an unapproved laboratory composition.
  Checks.require(p.mode()==mode,"provider composition mode mismatch");
  if(mode==ProviderMode.PRODUCTION)Checks.require(!(p.implementation() instanceof TestOnlyBackend)&&!p.implementation().getClass().getPackageName().contains("testkit"),"TEST_ONLY provider in production");
  if(p.implementation() instanceof ProviderProvenance provenance){
   var supported=Set.copyOf(Objects.requireNonNull(provenance.supportedModes(),"backend modes"));
   Checks.require(!supported.isEmpty()&&supported.contains(mode),"backend provenance mismatch");
  }else if(mode==ProviderMode.PRODUCTION){
   Checks.require(!isPort(p.implementation()),"unprovenanced production port");
  }
  if(p.implementation() instanceof DirectorPrimitive<?> primitive){
   var descriptor=Objects.requireNonNull(primitive.descriptor(),"primitive descriptor");
   Checks.require(descriptor.mode()==p.mode(),"primitive provenance mismatch");
   var previous=physicalPrimitives.putIfAbsent(descriptor.id(),primitive);
   Checks.require(previous==null || previous==primitive,"duplicate physical primitive provider");
  }
  implementations.add(p.implementation());
  for(var c:p.capabilities()){
   Checks.require(c.mode()==p.mode(),"capability provenance mismatch");
   if(mode==ProviderMode.PRODUCTION)Checks.require(c.mode()==ProviderMode.PRODUCTION,"TEST_ONLY capability in production");
   Checks.require(capabilities.putIfAbsent(c.id(),c)==null,"duplicate capability provider");
  }
  for(Provider dependency:p.dependencies())inspect(dependency,mode,visited,capabilities,physicalPrimitives,implementations,depth+1);
 }
 private static boolean isPort(Object implementation){
  return implementation instanceof ObservationPort || implementation instanceof MemoryPort ||
   implementation instanceof CatalogPort || implementation instanceof DecisionPort ||
   implementation instanceof AdmissionPort || implementation instanceof WorldEffectPort ||
   implementation instanceof DurableStore || implementation instanceof FactBusPort ||
   implementation instanceof QuestDirectorPort || implementation instanceof AiAdvisorPort ||
   implementation instanceof AdminPort;
 }
 private static <T> Optional<T> find(Class<T> type,List<Object> implementations,Set<String> missing){
  // Port ownership is defined by object identity. equals()/hashCode() are
  // user-code callbacks and must not collapse two distinct physical providers.
  Set<T> seen=Collections.newSetFromMap(new IdentityHashMap<>());
  List<T> matches=new ArrayList<>();
  for(Object implementation:implementations){
   if(type.isInstance(implementation)){
    T port=type.cast(implementation);
    if(seen.add(port))matches.add(port);
   }
  }
  Checks.require(matches.size()<=1,"duplicate port "+type.getSimpleName());
  if(matches.isEmpty()){missing.add(type.getSimpleName());return Optional.empty();}
  return Optional.of(matches.getFirst());
 }
 public static void main(String[] args){var c=compose(ProviderMode.PRODUCTION,List.of());System.out.println("G0 composition skeleton; missing capabilities and ports: "+c.missingPorts());}
}
