package fr.proutlost.director.bootstrap;
import fr.proutlost.director.api.model.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** Pure envelope construction around an ALREADY PROVEN original selected fact.
 * Creates no completion/abort fact, World effect, request binding, ACK or worldSequence.
 */
public final class NativeCatalogueFactOutbox {
 private NativeCatalogueFactOutbox(){}
 public static DurableOutbox completion(DirectorFact actual,DecisionId decision,Tick writtenAt){
  return create(actual,decision,writtenAt,"completion");
 }
 public static DurableOutbox abort(DirectorFact actual,DecisionId decision,Tick writtenAt){
  return create(actual,decision,writtenAt,"abort");
 }
 public static DurableOutbox create(DirectorFact actual,DecisionId decision,Tick writtenAt,String kind){
  Objects.requireNonNull(actual);Objects.requireNonNull(decision);Objects.requireNonNull(writtenAt);
  if(!Set.of("completion","abort").contains(kind)||!actual.durable()||actual.evidenceStatus()!=EvidenceStatus.CONFIRMED||actual.eventId().isEmpty()
      ||!new JsonValue.Text("CATALOGUE_SELECTED").equals(actual.payload().get("source"))
      ||!new JsonValue.Text(decision.value().toString()).equals(actual.payload().get("decision"))
      ||!(actual.payload().get("definition") instanceof JsonValue.Text))
   throw new IllegalArgumentException("actual original selected fact identity required");
  var event=actual.eventId().orElseThrow().value();boolean aborted=kind.equals("abort");
  var expected=new FactId(UUID.nameUUIDFromBytes((("catalogue-"+(aborted?"aborted":"completion")+"-v1\0")+event).getBytes(StandardCharsets.UTF_8)));
  if(!actual.factId().equals(expected)||aborted!=actual.factType().equals(new FactType("proutlost_director:catalogue_event_aborted")))
   throw new IllegalArgumentException("original selected completion/abort identity changed");
  if(aborted&&!new JsonValue.Bool(true).equals(actual.payload().get("no_physical_effect"))&&!new JsonValue.Bool(true).equals(actual.payload().get("recovery_complete")))
   throw new IllegalArgumentException("abort has no real absence/recovery proof");
  var worlds=actual.subjectRefs().stream().filter(scope->scope.kind()==ScopeKind.WORLD).toList();
  if(worlds.size()!=1)throw new IllegalArgumentException("actual single WORLD fact scope required");
  var world=new WorldId(worlds.getFirst().id());var message=new MessageId(UUID.nameUUIDFromBytes(("native-catalogue-fact-v1/"+event+"/"+kind).getBytes(StandardCharsets.UTF_8)));
  return new DurableOutbox(new DirectorEnvelope(1,message,world,new ResourceId("proutlost_director:native_catalogue"),
    "event/"+event+"/"+kind,worlds.getFirst(),new CorrelationId(decision.value()),Optional.empty(),new Sequence(1),
    Optional.empty(),writtenAt,PayloadType.DirectorFact,1,new FactPayload(actual)));
 }
}
