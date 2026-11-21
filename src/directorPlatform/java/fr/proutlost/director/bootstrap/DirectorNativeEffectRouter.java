package fr.proutlost.director.bootstrap;

import fr.proutlost.director.adapter.neoforge.server.effects.*;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.permission.CommitPermit;
import java.util.*;

/** Actor-owned adapter to the existing per-operation native ports. It does not
 * issue permits, acknowledge a write, or apply an operation itself. Each route
 * stays attached to the exact native Authority that created its runner. */
public final class DirectorNativeEffectRouter implements InstalledEffectPort,fr.proutlost.director.api.port.ProviderProvenance,fr.proutlost.director.api.port.ReservationInspectionPort {
 private record Route(PreparedEffect plan,BlobBundle blobs,ServerEffectPort port,java.util.function.BooleanSupplier forwardGuard) {}
 private final Thread actor=Thread.currentThread();
 private final int capacity;
 private final Map<EventInstanceId,List<java.util.function.Supplier<List<ReservationToken>>>> reservationViews=new HashMap<>();
 private final Set<EventInstanceId> inspectionUnavailable=new HashSet<>();
 private final Map<OperationToken,Route> routes=new HashMap<>();
 private final Map<PrimitiveId,ServerEffectPort.BindingView> installed=new HashMap<>();
 public DirectorNativeEffectRouter(int capacity){
  if(capacity<1||capacity>65536)throw new IllegalArgumentException("native route capacity");this.capacity=capacity;
 }
 private void actor(){if(Thread.currentThread()!=actor)throw new IllegalStateException("native effect actor");}
 public void attach(PreparedEffect plan,BlobBundle blobs,ServerEffectPort actual){attach(plan,blobs,actual,()->true);}
 public void attach(PreparedEffect plan,BlobBundle blobs,ServerEffectPort actual,java.util.function.BooleanSupplier forwardGuard){
  actor();Objects.requireNonNull(plan);Objects.requireNonNull(blobs);Objects.requireNonNull(actual);Objects.requireNonNull(forwardGuard);
  if(!actual.supportedModes().contains(ProviderMode.PRODUCTION))throw new IllegalArgumentException("TEST_ONLY native authority cannot be routed as production");
  var bindings=actual.installedBindingDescriptors();var binding=bindings.get(plan.intent().primitive());
  if(binding==null||binding.mode()!=ProviderMode.PRODUCTION||!binding.qualified()
   ||!binding.capabilities().equals(plan.capabilities()))throw new IllegalArgumentException("actual native binding required");
  // Re-running hydrate preserves its exact blob-reference and substitution checks.
  actual.hydrate(plan,blobs);
  var token=plan.intent().operationToken();var old=routes.get(token);
  if(old!=null){if(old.port()!=actual||!old.plan().equals(plan)||!old.blobs().equals(blobs))
   throw new IllegalStateException("native route identity conflict");return;}
  if(routes.size()>=capacity)throw new IllegalStateException("native route capacity");
  routes.put(token,new Route(plan,blobs,actual,forwardGuard));
  bindings.forEach((id,view)->{
   var prior=installed.get(id);
   if(prior!=null&&!prior.equals(view))throw new IllegalStateException("native binding inventory changed");
   installed.put(id,view);
  });
 }
 public void inspectReservations(EventInstanceId event,java.util.function.Supplier<List<ReservationToken>> snapshot){
  actor();var views=reservationViews.computeIfAbsent(event,id->new ArrayList<>());
  if(views.size()>=capacity){inspectionUnavailable.add(event);return;}views.add(snapshot);
 }
 public Optional<List<ReservationToken>> inspectedReservations(EventInstanceId event){
  actor();if(inspectionUnavailable.contains(event))return Optional.empty();
  var result=new LinkedHashMap<ReservationId,ReservationToken>();
  for(var source:reservationViews.getOrDefault(event,List.of()))for(var token:List.copyOf(source.get())){
   if(!token.instanceId().equals(event))continue;var old=result.putIfAbsent(token.id(),token);
   if(old!=null&&!old.equals(token))return Optional.empty();
  }
  return Optional.of(List.copyOf(result.values()));
 }
 public Set<ProviderMode> supportedModes(){return Set.of(ProviderMode.PRODUCTION);}
 public Map<PrimitiveId,ServerEffectPort.BindingView> installedBindingDescriptors(){actor();return Map.copyOf(installed);}
 private Route route(OperationToken token){actor();return Objects.requireNonNull(routes.get(token),"native operation not attached");}
 private ServerEffectPort port(PreparedSlice slice){
  var result=route(slice.operationToken()).port();
  for(var operation:slice.operations())if(route(operation.operationToken()).port()!=result)
   throw new IllegalArgumentException("native slice spans independent authorities");
  return result;
 }
 public EffectPreparation prepare(EffectIntent intent){
  var r=route(intent.operationToken());if(!r.plan().intent().equals(intent))throw new IllegalArgumentException("native intent changed");
  return new EffectPreparation(intent,new ValidationResult(true,List.of(),r.plan().preconditions()),Optional.of(r.plan()),r.blobs());
 }
 public SliceResult apply(PreparedSlice slice,CommitPermit permit){
  var actual=port(slice);
  for(var operation:slice.operations())if(!route(operation.operationToken()).forwardGuard().getAsBoolean())
   throw new IllegalStateException("production event envelope is not durably current");
  return actual.apply(slice,permit);
 }
 public SliceResult stabilize(PreparedSlice slice,CommitPermit.StabilizationPermit permit,StabilizationLiveSnapshot live){return port(slice).stabilize(slice,permit,live);}
 public ReconcileResult reconcile(RecoveryProbe probe){return route(probe.durableIntent().intent().operationToken()).port().reconcile(probe);}
 public CleanupResult cleanup(CleanupIntent intent,CommitPermit permit){
  return route(intent.operationToken()).port().cleanup(intent,permit);
 }
 public BlobBundle blobs(PreparedEffect plan){var r=route(plan.intent().operationToken());if(!r.plan().equals(plan))throw new IllegalArgumentException("native plan changed");return r.blobs();}
 public void retire(EventInstanceId event){actor();routes.entrySet().removeIf(e->e.getValue().plan().intent().instanceId().equals(event));reservationViews.remove(event);inspectionUnavailable.remove(event);}
}
