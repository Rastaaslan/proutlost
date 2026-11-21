package fr.proutlost.director.bootstrap;

import fr.proutlost.director.adapter.neoforge.server.effects.ServerEffectPort;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.*;
import fr.proutlost.director.runtime.SingleSlicePrimitive;
import fr.proutlost.director.runtime.AdmissionService;
import java.util.*;

/** Explicit provenance for the unchanged native algorithms. No LAB object is
 * a dependency of a production instance. Immutable operator content is validated
 * before this actor-owned assembly is created. */
public final class DirectorRuntimeContent implements CatalogPort,ProviderProvenance {
 private final ProviderMode mode;private final ConfigView config;private final CatalogSnapshot catalog;
 private final DirectorProductionRuntimeQa.Inputs declaredQa;
 private final Map<ResourceId,ConfigView> profiles;
 private final DirectorNativeEffectRouter effects=new DirectorNativeEffectRouter(1024);
 private final Map<PrimitiveId,NativePrimitive> primitives=new HashMap<>();
 private final Map<OperationToken,PreparedEffect> captures=new HashMap<>();
 private final Map<DecisionId,AdmissionService> admissions=new HashMap<>();
 private final Map<DecisionId,EventInstanceId> admissionEvents=new HashMap<>();
 private final AdmissionPort admission=new NativeAdmissionRouter();
 private final class NativeAdmissionRouter implements AdmissionPort,ProviderProvenance {
  public Set<ProviderMode> supportedModes(){return Set.of(mode);}
  public AdmissionResult admit(AdmissionRequest request){actor();var service=Objects.requireNonNull(admissions.get(request.proposalId()),"native admission not routed");return service.admit(request);}
 }
 private final Thread actor=Thread.currentThread();
 private java.util.concurrent.Executor archiveWorker;
 private DirectorNativeEventEnvelopes envelopes;
 public DirectorRuntimeContent(ProviderMode mode,ConfigView config,CatalogSnapshot catalog){this(mode,config,catalog,Map.of());}
 public DirectorRuntimeContent(ProviderMode mode,ConfigView config,CatalogSnapshot catalog,Map<ResourceId,ConfigView> profiles){
  this(mode,config,catalog,profiles,null);
 }
 static DirectorRuntimeContent declaredSynthetic(DirectorProductionRuntimeQa.Inputs token,net.minecraft.server.MinecraftServer server){
  Objects.requireNonNull(token).require(server);
  return new DirectorRuntimeContent(ProviderMode.TEST_ONLY,token.configuration(),token.catalog(),token.profiles(),token);
 }
 private DirectorRuntimeContent(ProviderMode mode,ConfigView config,CatalogSnapshot catalog,Map<ResourceId,ConfigView> profiles,DirectorProductionRuntimeQa.Inputs declaredQa){
  this.declaredQa=declaredQa;
  this.profiles=Map.copyOf(profiles);
  this.mode=Objects.requireNonNull(mode);this.config=Objects.requireNonNull(config);this.catalog=Objects.requireNonNull(catalog);
  if(config.calibration()!=(mode==ProviderMode.PRODUCTION?Calibration.APPROVED:Calibration.DEFAULT_FOR_TESTING))
   throw new IllegalArgumentException("runtime calibration/provenance mismatch");
  if(profiles.values().stream().anyMatch(p->p.calibration()!=config.calibration()))throw new IllegalArgumentException("mixed intensity profile provenance");
  if(catalog.events().values().stream().anyMatch(e->e.calibration()!=config.calibration())
   ||catalog.chains().values().stream().anyMatch(c->c.calibration()!=config.calibration())
   ||catalog.primitives().values().stream().anyMatch(p->p.mode()!=mode))
   throw new IllegalArgumentException("mixed native content provenance");
  if(production())catalog.primitives().forEach((id,d)->primitives.put(id,new NativePrimitive(d)));
 }
 private void actor(){if(Thread.currentThread()!=actor)throw new IllegalStateException("runtime content actor");}
 public Set<ProviderMode> supportedModes(){return Set.of(mode);}
 public CatalogSnapshot current(){return catalog;}
 public void bindEnvelopes(fr.proutlost.director.persistence.JournalStore store,java.util.function.Supplier<Tick> clock,Epoch floor){
  actor();if(!production())throw new IllegalStateException("production envelope only");
  if(envelopes==null)envelopes=new DirectorNativeEventEnvelopes(store,this,clock,floor);else envelopes.restoreFloor(floor);
 }
 public boolean envelopesReady(){actor();return envelopes!=null;}
 public DirectorNativeEventEnvelopes envelopes(){actor();return Objects.requireNonNull(envelopes,"world envelope recovery not bound");}
 public void archiveWorker(java.util.concurrent.Executor worker){actor();if(archiveWorker!=null&&archiveWorker!=worker)throw new IllegalStateException("archive worker owner changed");archiveWorker=Objects.requireNonNull(worker);}
 public java.util.concurrent.Executor archiveWorker(){actor();return Objects.requireNonNull(archiveWorker,"bounded archive worker required");}
 public ProviderMode mode(){return mode;}
 /** Native orchestrator branch; declared QA retains TEST_ONLY data provenance. */
 public boolean production(){return mode==ProviderMode.PRODUCTION||declaredQa!=null;}
 public boolean declaredSynthetic(){return declaredQa!=null;}
 DirectorProductionRuntimeQa.Inputs qualificationInputs(){return Objects.requireNonNull(declaredQa,"closed QA inputs unavailable");}
 boolean acceptsHistoricalDefinition(EventDefinition value){return value!=null&&(declaredQa==null?value.calibration()==Calibration.APPROVED:value.calibration()==Calibration.DEFAULT_FOR_TESTING&&value.equals(catalog.events().get(value.id())));}
 public ConfigView config(){return config;}public CatalogSnapshot catalog(){return catalog;}
 public Map<ResourceId,ConfigView> profiles(){return profiles;}
 public boolean acceptsConfig(ConfigView value){return value.equals(config)||profiles.values().stream().anyMatch(value::equals);}
 public EventDefinition definition(DefinitionId id){return Objects.requireNonNull(catalog.events().get(id),"native definition unavailable");}
 public void requireLaboratory(){if(mode!=ProviderMode.TEST_ONLY||declaredQa!=null)throw new IllegalStateException("TEST_ONLY entry point");}
 public DirectorNativeEffectRouter effects(){return effects;}
 public List<DirectorPrimitive<?>> primitives(){actor();return List.copyOf(primitives.values());}
 /** Planning uses the original actor-captured immutable native plan. The
  * registered primitive is actually invoked by CandidatePlanner here. */
 public PlannedPrimitive plan(PlanningRequest request,PreparedEffect captured){
  actor();if(!production())throw new IllegalStateException("production planning adapter required");
  var binding=request.snapshot().bindings().orElseThrow();
  if(!binding.operationToken().equals(captured.intent().operationToken())
   ||!request.snapshot().snapshot().equals(captured.snapshot())
   ||!ObservedWeatherArguments.matches(request.step().args(),captured.intent().typedArgs(),captured.preconditions())
   ||!request.step().primitive().equals(captured.intent().primitive()))throw new IllegalArgumentException("native capture identity");
  var old=captures.putIfAbsent(binding.operationToken(),captured);
  if(old!=null&&!old.equals(captured))throw new IllegalStateException("captured native operation changed");
  if(captures.size()>1024){captures.remove(binding.operationToken());throw new IllegalStateException("native planning capacity");}
  var primitive=Objects.requireNonNull(primitives.get(request.step().primitive()),"native primitive missing");
  try{return new PlannedPrimitive(binding,primitive.plan(captured.intent().typedArgs(),request.snapshot()));}finally{captures.remove(binding.operationToken());}
 }
 public WorldEffectPort attach(PreparedEffect plan,BlobBundle blobs,ServerEffectPort actual){
  return attach(plan,blobs,actual,false);
 }
 public WorldEffectPort attach(PreparedEffect plan,BlobBundle blobs,ServerEffectPort actual,boolean selectedForward){
  actor();if(!production())return actual;
  if(!catalog.primitives().containsKey(plan.intent().primitive()))throw new IllegalArgumentException("foreign production primitive");
  effects.attach(plan,blobs,actual,()->{
   if(selectedForward){fr.proutlost.director.runtime.PhysicalSiteExecutionGuards.requireForward(plan.snapshot().scopes().worldId(),plan.intent());return envelopes().forwardAllowed(plan.intent().instanceId());}
   fr.proutlost.director.runtime.PhysicalSiteExecutionGuards.requireHistorical(plan.snapshot().scopes().worldId(),plan.intent());return true;
  });return effects;
 }
 public AdmissionPort admission(){return admission;}
 public AdmissionResult admit(AdmissionService actual,AdmissionRequest request){
  actor();if(actual.mode()!=mode)throw new IllegalArgumentException("native admission provenance mismatch");
  var previous=admissions.get(request.proposalId());
  if(previous!=null&&previous!=actual)throw new IllegalStateException("native admission route changed");
  if(previous==null&&admissions.size()>=1024)throw new IllegalStateException("native admission routes full");
  admissions.put(request.proposalId(),actual);admissionEvents.put(request.proposalId(),request.plan().effects().getFirst().instanceId());
  return admission.admit(request);
 }
 /** Inspect the real local arbiters. No token is recreated from persisted DTOs. */
 public Optional<List<ResourceClaim>> liveClaims(EventInstanceId event,Tick now){
  actor();var inspected=effects.inspectedReservations(event);if(inspected.isEmpty())return Optional.empty();
  var tokens=new LinkedHashMap<ReservationId,ReservationToken>();
  for(var token:inspected.get())tokens.put(token.id(),token);
  Set<AdmissionService> seen=Collections.newSetFromMap(new IdentityHashMap<>());
  for(var service:admissions.values())if(seen.add(service))for(var token:service.snapshotReservations())if(token.instanceId().equals(event)){
   var previous=tokens.putIfAbsent(token.id(),token);if(previous!=null&&!previous.equals(token))return Optional.empty();
  }
  var claims=new LinkedHashSet<ResourceClaim>();
  if(envelopes!=null)claims.addAll(envelopes.claims(event));
  for(var token:tokens.values())if(now.compareTo(token.expiryTick())<0)claims.addAll(token.claims());
  return Optional.of(claims.stream().sorted(Comparator.comparing(c->c.resource().toString())).toList());
 }
 public void retire(EventInstanceId event){actor();if(envelopes!=null)envelopes.releaseAfterTerminal(event);effects.retire(event);captures.entrySet().removeIf(e->e.getValue().intent().instanceId().equals(event));
  var ids=admissionEvents.entrySet().stream().filter(e->e.getValue().equals(event)).map(Map.Entry::getKey).toList();
  ids.forEach(id->{admissionEvents.remove(id);admissions.remove(id);});}

 private PrimitiveDescriptor pinnedPrimitiveDescriptor(PrimitiveDescriptor descriptor){
  actor();if(!Objects.equals(descriptor,catalog.primitives().get(descriptor.id())))throw new IllegalArgumentException("native primitive differs from pinned catalogue");
  if(declaredQa!=null){declaredQa.owner().requireWorld();if(mode!=ProviderMode.TEST_ONLY||descriptor.mode()!=ProviderMode.TEST_ONLY)throw new IllegalStateException("closed synthetic primitive provenance required");}
  return descriptor;
 }
 private final class NativePrimitive extends SingleSlicePrimitive<PrimitiveArgs> {
  private NativePrimitive(PrimitiveDescriptor descriptor){super(DirectorRuntimeContent.this.pinnedPrimitiveDescriptor(descriptor),effects,
   p->new PreparedSlice(new SliceId(UUID.nameUUIDFromBytes(("native-slice/"+p.intent().operationToken().value()).getBytes(java.nio.charset.StandardCharsets.UTF_8))),
    p.intent().operationToken(),p.intent().expectedFootprint(),p.preconditions(),List.of(p.intent()),p.budgetEstimate()),effects::blobs,
    DirectorRuntimeContent.this.declaredQa==null?DescriptorProvenance.PRODUCTION:DescriptorProvenance.DECLARED_SYNTHETIC_TEST_ONLY);}
  public PreparedEffect plan(PrimitiveArgs args,PlanningSnapshot snapshot){
   actor();var binding=snapshot.bindings().orElseThrow();
   var prepared=Objects.requireNonNull(captures.get(binding.operationToken()),"actual native capture absent");
   if(!prepared.intent().primitive().equals(descriptor().id())||!prepared.intent().typedArgs().equals(args)
    ||!prepared.snapshot().equals(snapshot.snapshot()))throw new IllegalArgumentException("native captured plan substituted");
   return prepared;
  }
 }
}
