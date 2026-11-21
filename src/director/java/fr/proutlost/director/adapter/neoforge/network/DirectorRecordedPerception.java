package fr.proutlost.director.adapter.neoforge.network;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Existing private DirectorFact representation of an explicit, provenance-checked collection.
 * No delivery, storage, progression or permission is granted by this pure conversion. */
public final class DirectorRecordedPerception {
 private DirectorRecordedPerception(){}
 public static final FactType TYPE=new FactType("proutlost_director:recorded_perception");
 public static DirectorFact fact(PlayerId witness,RecordObservation request,PerceptionUpsert actual,long recordedAt){
  Objects.requireNonNull(witness);Objects.requireNonNull(request);Objects.requireNonNull(actual);
  if(recordedAt<0||!request.header().equals(actual.header())||
     !request.observation().receivedEffectId().equals(Optional.of(actual.header().effectId()))||
     request.observation().receivedFactId().isPresent())throw new IllegalArgumentException("received effect identity required");
  var header=Base64.getEncoder().encodeToString(new ModelCodec<>(PerceptionHeader.class).encode(actual.header()));
  var id=new FactId(UUID.nameUUIDFromBytes(("j06-recorded-perception-v1/"+witness.value()+"/"+header).getBytes(StandardCharsets.UTF_8)));
  var fields=new LinkedHashMap<String,JsonValue>();
  fields.put("witness",new JsonValue.Text(witness.value().toString()));
  fields.put("recorded_at",new JsonValue.Text(Long.toString(recordedAt)));
  fields.put("source",new JsonValue.Text(Base64.getEncoder().encodeToString(new ModelCodec<>(PerceptionUpsert.class).encode(actual))));
  fields.put("kind",new JsonValue.Text(actual.kind().name()));fields.put("asset",new JsonValue.Text(actual.assetId().value()));
  fields.put("player_text",new JsonValue.Text(request.observation().playerText().orElse("")));
  return new DirectorFact(id,TYPE,List.of(new ScopeRef(ScopeKind.PLAYER,witness.value())),
   Optional.of(actual.header().instanceId()),EvidenceStatus.CONFIRMED,fields,true,Optional.empty());
 }
 public static boolean sameRequest(DirectorFact prior,PlayerId witness,RecordObservation request,PerceptionUpsert actual){
  try{return prior.equals(fact(witness,request,actual,Long.parseLong(((JsonValue.Text)prior.payload().get("recorded_at")).value())));}
  catch(RuntimeException invalid){return false;}
 }
 public static boolean belongsTo(DirectorFact fact,PlayerId witness){
  return fact.factType().equals(TYPE)&&fact.durable()&&fact.evidenceStatus()==EvidenceStatus.CONFIRMED
   &&fact.subjectRefs().equals(List.of(new ScopeRef(ScopeKind.PLAYER,witness.value())))
   &&new JsonValue.Text(witness.value().toString()).equals(fact.payload().get("witness"));
 }
 /** Decode only the existing canonical private fact, retaining its original source/epochs. */
 public static Optional<PerceptionUpsert> source(DirectorFact fact,PlayerId witness){
  try{
   if(!belongsTo(fact,witness))return Optional.empty();
   var encoded=((JsonValue.Text)fact.payload().get("source")).value();
   if(encoded.length()>32768)return Optional.empty();
   var actual=new ModelCodec<>(PerceptionUpsert.class).decode(Base64.getDecoder().decode(encoded));
   var note=((JsonValue.Text)fact.payload().get("player_text")).value();
   var original=new RecordObservation(actual.header(),new RecordObservationPayload(Optional.of(actual.header().effectId()),
    Optional.empty(),note.isEmpty()?Optional.empty():Optional.of(note)));
   return sameRequest(fact,witness,original,actual)?Optional.of(actual):Optional.empty();
  }catch(RuntimeException invalid){return Optional.empty();}
 }
 /** Referencing an already collected fact is idempotent; its annotation is never overwritten. */
 public static boolean sameReference(DirectorFact fact,RecordObservation request){
  try{
   String note=((JsonValue.Text)fact.payload().get("player_text")).value();
   return request.observation().receivedFactId().equals(Optional.of(fact.factId()))
    &&request.observation().receivedEffectId().isEmpty()
    &&request.observation().playerText().map(note::equals).orElse(true);
  }catch(RuntimeException invalid){return false;}
 }
 public static String text(DirectorFact fact){
  String kind=((JsonValue.Text)fact.payload().get("kind")).value();
  String note=((JsonValue.Text)fact.payload().get("player_text")).value();
  String label=switch(PerceptionKind.valueOf(kind)){
   case SOUND->"Son perçu";case PARTICLES->"Particules aperçues";case APPARITION->"Silhouette aperçue";
   case LOCAL_WEATHER->"Ambiance perçue";case GHOST->"Forme aperçue";case SYMBOL->"Symbole aperçu";
  };
  return label+(note.isBlank()?"":" — "+note);
 }
}
