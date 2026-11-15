package fr.proutlost.director.api.model;

import fr.proutlost.director.api.codec.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Small regional-lease recovery index in the existing J04 journal. No authority is persisted. */
public final class RegionalWeatherJournal {
 private RegionalWeatherJournal(){}
 public static final FactType TYPE=new FactType("proutlost_director:regional_weather_journal_v1");
 public record Request(EventInstanceId event,OperationToken operation,Footprint footprint,DurationTicks duration,Fingerprint authorization){
  public Request {Objects.requireNonNull(event);Objects.requireNonNull(operation);Objects.requireNonNull(footprint);Objects.requireNonNull(duration);Objects.requireNonNull(authorization);
   Checks.require(duration.value()>0&&duration.value()<=72000&&footprint.bounds().size()==1&&footprint.cells().isEmpty(),"bounded regional request");}
 }
 public record Bytes(BlobRef reference,String base64){
  public Bytes {Objects.requireNonNull(reference);Objects.requireNonNull(base64);Checks.require(reference.bytes()<=65536&&base64.length()<=87384,"bounded regional blob");
   var bytes=Base64.getDecoder().decode(base64);Checks.require(Base64.getEncoder().encodeToString(bytes).equals(base64),"canonical regional blob");new BlobContent(reference,bytes);}
  public BlobContent content(){return new BlobContent(reference,Base64.getDecoder().decode(base64));}
 }
 public record State(int schemaVersion,long ordinal,Request request,OwnershipRecord owner,
   Optional<PreparedEffect> forward,List<Bytes> blobs,boolean uncertain,boolean completed,String diagnostic){
  public State {Objects.requireNonNull(request);Objects.requireNonNull(owner);Objects.requireNonNull(forward);blobs=List.copyOf(blobs);Objects.requireNonNull(diagnostic);
   Checks.require(schemaVersion==1&&ordinal>0&&blobs.size()<=8&&diagnostic.length()<=512,"regional journal shape");
   Checks.require(owner.resourceKey().equals(new ResourceKey(OwnershipDomain.WEATHER,request.footprint().dimension(),"region:"+request.event().value())),"regional owner dimension");
   Checks.require(forward.isPresent()==!blobs.isEmpty(),"regional forward bytes");
   if(forward.isPresent()){
    var p=forward.orElseThrow();Checks.require(p.intent().instanceId().equals(request.event())&&p.intent().operationToken().equals(request.operation())&&p.intent().expectedFootprint().equals(request.footprint()),"regional original plan identity");
    var refs=blobs.stream().map(Bytes::reference).collect(java.util.stream.Collectors.toSet());
    Checks.require(refs.size()==blobs.size()&&refs.equals(DurableBlobReferences.collect(p.durableBlobs())),"regional exact plan references");
   }
  }
 }
 public static State state(DirectorFact f){
  Checks.require(f.factType().equals(TYPE)&&f.durable()&&f.evidenceStatus()==EvidenceStatus.CONFIRMED&&f.payload().keySet().equals(Set.of("regional")),"regional fact fields");
  var s=new ModelCodec<>(State.class).decode(CanonicalJsonV1.encode(f.payload().get("regional")));
  Checks.require(f.factId().equals(id(s))&&f.eventId().equals(Optional.of(s.request().event()))&&f.subjectRefs().equals(List.of(new ScopeRef(ScopeKind.WORLD,world(f)))),"regional fact identity");
  return s;
 }
 private static UUID world(DirectorFact f){Checks.require(f.subjectRefs().size()==1&&f.subjectRefs().getFirst().kind()==ScopeKind.WORLD,"regional world");return f.subjectRefs().getFirst().id();}
 public static FactId id(State s){return new FactId(UUID.nameUUIDFromBytes(("regional-journal-v1/"+s.request().event().value()+"/"+s.ordinal()).getBytes(StandardCharsets.UTF_8)));}
 public static DirectorFact fact(WorldId world,State state){
  return new DirectorFact(id(state),TYPE,List.of(new ScopeRef(ScopeKind.WORLD,world.value())),Optional.of(state.request().event()),EvidenceStatus.CONFIRMED,
    Map.of("regional",CanonicalJsonV1.decode(new ModelCodec<>(State.class).encode(state))),true,Optional.empty());
 }
 public static Set<BlobRef> references(DirectorFact f){return state(f).blobs().stream().map(Bytes::reference).collect(java.util.stream.Collectors.toUnmodifiableSet());}
 public static List<Bytes> seal(BlobBundle b){return b.contents().stream().map(c->new Bytes(c.reference(),Base64.getEncoder().encodeToString(c.bytes()))).toList();}
}

