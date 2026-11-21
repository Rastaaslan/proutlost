package fr.proutlost.director.runtime;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.AdminProjectionPort;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Immutable coherent publication; readers never access actor/world state. J07 authorizes reads. */
public final class AdminProjectionService implements AdminProjectionPort {
 private record Publication(AdminSnapshotStamp stamp,List<AdminRow> rows){}
 private final Thread actor=Thread.currentThread();
 private final int capacity;
 private volatile Publication publication;
 public AdminProjectionService(int capacity){if(capacity<1||capacity>65536)throw new IllegalArgumentException("projection capacity");this.capacity=capacity;}
 public void publish(AdminSnapshotStamp stamp,List<AdminRow> rows){
  if(Thread.currentThread()!=actor)throw new IllegalStateException("server actor required");
  Objects.requireNonNull(stamp);var copy=List.copyOf(rows);if(copy.size()>capacity)throw new IllegalArgumentException("projection capacity");
  if(copy.stream().anyMatch(r->r.capturedTick().compareTo(stamp.capturedTick())>0||r.revision().compareTo(stamp.snapshotRevision())>0))throw new IllegalArgumentException("incoherent publication");
  var previous=publication;
  if(previous!=null&&(!stamp.worldId().equals(previous.stamp().worldId())||!stamp.bootEpoch().equals(previous.stamp().bootEpoch())||stamp.snapshotRevision().compareTo(previous.stamp().snapshotRevision())<=0||stamp.capturedTick().compareTo(previous.stamp().capturedTick())<0||stamp.lastDurableSequence().compareTo(previous.stamp().lastDurableSequence())<0))throw new IllegalArgumentException("stale publication");
  publication=new Publication(stamp,copy);
 }
 private static boolean matches(AdminFilter filter,AdminData data){return switch(filter){
  case WorldFilter f -> data instanceof WorldAdminData;
  case EventFilter f -> data instanceof EventAdminData d&&f.instanceId().map(id->id.equals(d.event().instanceId())).orElse(true)&&(f.states().isEmpty()||f.states().contains(d.event().state()));
  case MutationFilter f -> data instanceof MutationAdminData d&&f.mutationId().equals(d.mutation().mutationId());
  case ChainFilter f -> data instanceof ChainAdminData d&&f.chainId().map(id->id.equals(d.chain().chainId())).orElse(true);
  case RegionFilter f -> data instanceof RegionAdminData d&&f.regionId().map(id->id.equals(d.region().regionId())).orElse(true)&&d.tags().containsAll(f.tags());
  case PlayerFilter f -> data instanceof PlayerAdminData d&&f.playerId().equals(d.operational().playerId());
  case DecisionFilter f -> data instanceof DecisionAdminData d&&f.decisionId().map(id->id.equals(d.decisionId())).orElse(true);
  case LaboratoryFilter f -> data instanceof LaboratoryAdminData d&&f.fixtureId().map(id->id.equals(d.fixtureId())).orElse(true);
 };}
 private static AdminReadResult deferred(){return new AdminReadResult(AdminStatus.DEFERRED,List.of(),Optional.empty(),Optional.empty(),Optional.empty());}
 public AdminReadResult query(AdminQuery query){
  var p=publication;if(p==null||query.expectedSnapshotRevision().filter(r->!r.equals(p.stamp().snapshotRevision())).isPresent())return deferred();
  var bound=new AdminQuery(query.actorSession(),query.requestId(),query.panel(),query.filter(),Optional.empty(),Optional.of(p.stamp().snapshotRevision()),query.limit());
  var binding=BlobContent.hash(new ModelCodec<>(AdminQuery.class).encode(bound)).value();
  String prefix=p.stamp().bootEpoch().value()+":"+p.stamp().snapshotRevision().value()+":"+binding+":";
  int offset=0;
  if(query.cursor().isPresent()){
   try{String cursor=new String(Base64.getUrlDecoder().decode(query.cursor().orElseThrow()),StandardCharsets.UTF_8);if(!cursor.startsWith(prefix))return deferred();offset=Integer.parseInt(cursor.substring(prefix.length()));if(offset<0)return deferred();}catch(IllegalArgumentException invalid){return deferred();}
  }
  var filtered=p.rows().stream().filter(r->matches(query.filter(),r.data())).toList();if(offset>filtered.size())return deferred();
  int end=Math.min(filtered.size(),offset+query.limit());
  Optional<String> next=end==filtered.size()?Optional.empty():Optional.of(Base64.getUrlEncoder().withoutPadding().encodeToString((prefix+end).getBytes(StandardCharsets.UTF_8)));
  return new AdminReadResult(AdminStatus.SUCCEEDED,filtered.subList(offset,end),Optional.empty(),next,Optional.of(p.stamp()));
 }
}
