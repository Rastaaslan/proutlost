package fr.proutlost.director.bootstrap;
import fr.proutlost.director.api.model.*;
import java.util.*;
import java.util.function.*;

/** One server actor owns camp observations and Trace state; only the existing J04 writer persists it. */
public final class DirectorNativeAnchorOwner {
 public record Lease(AnchorRecord before,OperationToken operation){
  public Lease{Objects.requireNonNull(before);Objects.requireNonNull(operation);}
 }
 private record CampChange(BlockPosition position,boolean active,Tick at,Set<RegionId> regions){
  private CampChange{regions=Set.copyOf(regions);}
 }
 private static final TagId CAMP=new TagId("proutlost:camp");
 private final Runnable actor;private final int anchorLimit,deferredLimit;
 private final Consumer<AnchorRecord> observedSink;
 private final Predicate<AnchorId> pendingJ01;private final BooleanSupplier traceCanStart;
 private final Map<AnchorId,AnchorRecord> heads=new HashMap<>(),durable=new HashMap<>();
 private final Map<BlockPosition,Set<AnchorId>> positions=new HashMap<>();
 private final ArrayDeque<CampChange> deferred=new ArrayDeque<>();
 private Lease lease;private Object metadataOwner;private AnchorRecord applied;private boolean invalidated,failed;
 private long durableSequence=-1;
 public DirectorNativeAnchorOwner(Runnable actor,Map<AnchorId,AnchorRecord> recovered,
   int anchorLimit,int deferredLimit,Consumer<AnchorRecord> observedSink,
   Predicate<AnchorId> pendingJ01,BooleanSupplier traceCanStart){
  this.actor=Objects.requireNonNull(actor);this.observedSink=Objects.requireNonNull(observedSink);
  this.pendingJ01=Objects.requireNonNull(pendingJ01);this.traceCanStart=Objects.requireNonNull(traceCanStart);
  if(anchorLimit<1||deferredLimit<1)throw new IllegalArgumentException("anchor owner bounds");
  this.anchorLimit=anchorLimit;this.deferredLimit=deferredLimit;actor.run();
  if(recovered.size()>anchorLimit)throw new IllegalArgumentException("recovered anchor limit");
  recovered.forEach((id,a)->{validate(id,a);replace(a);durable.put(id,a);});
 }
 private void validate(AnchorId id,AnchorRecord a){
  if(!id.equals(a.anchorId())||!a.dimension().equals(a.position().dimension()))
   throw new IllegalArgumentException("anchor identity/geometry");
 }
 private void replace(AnchorRecord a){
  var old=heads.get(a.anchorId());
  if(old==null&&heads.size()>=anchorLimit)throw new IllegalStateException("anchor owner capacity");
  if(old!=null&&!old.position().equals(a.position())){
   var ids=positions.get(old.position());ids.remove(old.anchorId());
   if(ids.isEmpty())positions.remove(old.position());
  }
  heads.put(a.anchorId(),a);positions.computeIfAbsent(a.position(),unused->new HashSet<>()).add(a.anchorId());
 }
 public Optional<AnchorRecord> current(AnchorId id){actor.run();return Optional.ofNullable(heads.get(id));}
 public Optional<AnchorRecord> at(BlockPosition p){
  actor.run();var records=positions.getOrDefault(p,Set.of()).stream().map(heads::get).filter(a->a.origin()==AnchorOrigin.CAMP).toList();
  if(records.isEmpty())return Optional.empty();
  var active=records.stream().filter(AnchorRecord::active).toList();
  if(active.size()>1)throw new IllegalStateException("ambiguous active camp position");
  if(active.size()==1)return Optional.of(active.getFirst());
  var latest=records.stream().map(a->a.header().updatedTick()).max(Tick::compareTo).orElseThrow();
  var choices=records.stream().filter(a->a.header().updatedTick().equals(latest)).toList();
  if(choices.size()!=1)throw new IllegalStateException("ambiguous retired camp position");
  return Optional.of(choices.getFirst());
 }
 public boolean traceBusy(){actor.run();return lease!=null||metadataOwner!=null||failed;}
 public boolean failed(){actor.run();return failed;}
 public void beginMetadata(Object owner){
  actor.run();Objects.requireNonNull(owner);if(metadataOwner==owner)return;
  if(traceBusy())throw new IllegalStateException("anchor metadata lane busy");metadataOwner=owner;
 }
 public void endMetadata(Object owner){
  actor.run();if(metadataOwner!=owner||lease!=null)throw new IllegalStateException("anchor metadata lane owner mismatch");
  metadataOwner=null;drainDeferred();
 }
 public int deferredChanges(){actor.run();return deferred.size();}
 public Set<AnchorId> retainedForWrite(){
  actor.run();var retained=new HashSet<AnchorId>();
  for(var entry:heads.entrySet())if(pendingJ01.test(entry.getKey())||!entry.getValue().equals(durable.get(entry.getKey())))retained.add(entry.getKey());
  if(lease!=null)retained.add(lease.before().anchorId());
  return Set.copyOf(retained);
 }
 public void mergeDurable(Map<AnchorId,AnchorRecord> recovered,long sequence){mergeDurable(recovered,sequence,false);}
 public void mergeDurable(Map<AnchorId,AnchorRecord> recovered,long sequence,boolean scoped){
  actor.run();if(sequence<durableSequence)throw new IllegalArgumentException("stale anchor projection");
  if(scoped){
   var retained=retainedForWrite();
   for(var id:List.copyOf(heads.keySet()))if(!recovered.containsKey(id)&&!retained.contains(id)){
    var old=heads.remove(id);durable.remove(id);var ids=positions.get(old.position());ids.remove(id);if(ids.isEmpty())positions.remove(old.position());
   }
  }
  for(var entry:recovered.entrySet()){
   var next=entry.getValue();validate(entry.getKey(),next);var old=durable.get(entry.getKey());
   if(old!=null&&(next.header().revision().compareTo(old.header().revision())<0||
      next.header().revision().equals(old.header().revision())&&!next.equals(old)))
    throw new IllegalStateException("conflicting durable anchor");
   var local=heads.get(entry.getKey());
   if(local==null||next.header().revision().compareTo(local.header().revision())>0)replace(next);
   else if(next.header().revision().equals(local.header().revision())&&!next.equals(local))
    throw new IllegalStateException("conflicting canonical anchor");
   else if(next.header().revision().compareTo(local.header().revision())<0&&!pendingJ01.test(entry.getKey())&&
      !(lease!=null&&lease.before().anchorId().equals(entry.getKey())))
    throw new IllegalStateException("unowned optimistic anchor");
   durable.put(entry.getKey(),next);
  }
  durableSequence=sequence;
 }
 public boolean observeCamp(BlockPosition position,boolean active,Tick at,Set<RegionId> regions){
  actor.run();if(failed)return false;var change=new CampChange(position,active,at,regions);
  try{
   if(lease!=null||metadataOwner!=null){
    if(deferred.size()>=deferredLimit)throw new IllegalStateException("deferred camp capacity");
    deferred.addLast(change);if(lease!=null&&applied==null&&position.equals(lease.before().position()))invalidated=true;
   }else observeNow(change);
   return true;
  }catch(RuntimeException unavailable){failed=true;return false;}
 }
 private void observeNow(CampChange c){
  var old=at(c.position()).orElse(null);if(old==null&&!c.active())return;
  if(old==null&&heads.size()>=anchorLimit)throw new IllegalStateException("anchor owner capacity");
  var id=old==null?new AnchorId(UUID.randomUUID()):old.anchorId();
  var tags=new HashSet<TagId>(old==null?Set.of():old.tags());tags.add(CAMP);
  var header=new AggregateHeader(1,old==null?id.value():old.header().id(),old==null?new Revision(1):old.header().revision().plus(1),
   old==null?new Sequence(0):old.header().lastSequence(),old!=null&&old.header().updatedTick().compareTo(c.at())>0?old.header().updatedTick():c.at());
  var next=new AnchorRecord(header,id,old==null?Optional.empty():old.locationId(),
   old==null?c.regions():old.regionIds(),c.position().dimension(),c.position(),
   old==null?"region.camp_radius_blocks":old.radiusProfile(),tags,AnchorOrigin.CAMP,
   old==null?EvidenceStatus.POSSIBLE:old.evidence(),c.active());
  observedSink.accept(next);replace(next);
 }
 public Lease acquireTrace(AnchorId id,OperationToken op){return acquireTrace(id,op,CAMP);}
 public boolean durablyCurrent(AnchorRecord value){actor.run();return !failed&&value.equals(heads.get(value.anchorId()))&&value.equals(durable.get(value.anchorId()))&&!pendingJ01.test(value.anchorId());}
 public Lease acquireTrace(AnchorId id,OperationToken op,TagId requiredTag){
  actor.run();if(traceBusy()||!traceCanStart.getAsBoolean()||pendingJ01.test(id))
   throw new IllegalStateException("anchor writers not quiescent");
  var before=Objects.requireNonNull(heads.get(id),"real anchor absent");
  boolean source=before.origin()==AnchorOrigin.CAMP&&before.tags().contains(CAMP) ||
   before.origin()==AnchorOrigin.IMPORTED&&before.locationId().isPresent()&&before.evidence()==EvidenceStatus.CONFIRMED;
  if(!source||!before.active()||!before.tags().contains(requiredTag)||!durablyCurrent(before))
   throw new IllegalStateException("required anchor is not durably current");
  lease=new Lease(before,op);applied=null;invalidated=false;return lease;
 }
 public boolean unchanged(Lease t){
  actor.run();return lease==t&&applied==null&&!invalidated&&!failed&&t.before().equals(heads.get(t.before().anchorId()))&&!pendingJ01.test(t.before().anchorId());
 }
 public void applyTrace(Lease t,AnchorRecord after){
  actor.run();if(!unchanged(t))throw new IllegalStateException("stale trace lease");
  var before=t.before();var h=before.header();
  var expected=new AnchorRecord(new AggregateHeader(1,h.id(),h.revision().plus(1),h.lastSequence(),after.header().updatedTick()),
   before.anchorId(),before.locationId(),before.regionIds(),before.dimension(),before.position(),before.radiusProfile(),
   after.tags(),before.origin(),before.evidence(),before.active());
  if(!after.equals(expected)||!after.tags().containsAll(before.tags())||after.tags().size()!=before.tags().size()+1||
     after.header().updatedTick().compareTo(h.updatedTick())<0)throw new IllegalArgumentException("Trace only adds one state tag");
  replace(after);applied=after;
 }
 public void releaseNotApplied(Lease t){
  actor.run();if(lease!=t||applied!=null)throw new IllegalStateException("trace outcome still owned");release();
 }
 public void releaseDurable(Lease t,AnchorRecord projected){
  actor.run();if(lease!=t||applied==null||!applied.equals(projected))throw new IllegalStateException("Trace result not projected");
  durable.put(projected.anchorId(),projected);release();
 }
 private void release(){
  lease=null;applied=null;invalidated=false;
  drainDeferred();
 }
 private void drainDeferred(){
  try{while(!deferred.isEmpty()){observeNow(deferred.getFirst());deferred.removeFirst();}}
  catch(RuntimeException unavailable){failed=true;throw unavailable;}
 }
 public void close(){actor.run();failed=true;}
}
