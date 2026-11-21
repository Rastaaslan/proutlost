package fr.proutlost.director.bootstrap;
import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.engine.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
final class NativeExpandedContextTest {
 private static DecisionInput actual() throws Exception{
  try(var in=NativeExpandedContextTest.class.getResourceAsStream("/native-expanded-context/decision-input.json")){
   return new ModelCodec<>(DecisionInput.class).decode(Objects.requireNonNull(in).readAllBytes());
  }
 }
 @Test void fullActualSnapshotSurvivesDurableFactCodecWithoutBase64TextOverflow() throws Exception{
  var input=actual();var codec=new ModelCodec<>(SnapshotBatch.class);var bytes=codec.encode(input.context());
  assertTrue(Base64.getEncoder().encodeToString(bytes).length()>65536,"actual widened context reproduces old text-bound failure");
  assertThrows(IllegalArgumentException.class,()->new JsonValue.Text(Base64.getEncoder().encodeToString(bytes)));
  var payload=NativeDossierJson.encode(bytes);
  var fact=new DirectorFact(new FactId(UUID.randomUUID()),new FactType("test:expanded_context"),
      List.of(new ScopeRef(ScopeKind.WORLD,input.state().world().worldId().value())),
      Optional.empty(),EvidenceStatus.CONFIRMED,Map.of("plan",payload),true,Optional.empty());
  var factCodec=new ModelCodec<>(DirectorFact.class);var restored=factCodec.decode(factCodec.encode(fact));
  var recovered=NativeDossierJson.decode(restored.payload().get("plan"));
  assertArrayEquals(bytes,recovered);assertEquals(input.context(),codec.decode(recovered));
 }
 @Test void oldDossiersRemainReadableAndWrongPayloadShapesRemainRejected(){
  byte[] legacy="{\"old\":\"unchanged\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
  assertArrayEquals(legacy,NativeDossierJson.decode(new JsonValue.Text(Base64.getEncoder().encodeToString(legacy))));
  assertThrows(IllegalArgumentException.class,()->NativeDossierJson.decode(new JsonValue.Text("not base64 !")));
  assertThrows(IllegalArgumentException.class,()->NativeDossierJson.decode(new JsonValue.Array(List.of())));
  assertThrows(IllegalArgumentException.class,()->NativeDossierJson.encode("[]".getBytes()));
  assertThrows(IllegalArgumentException.class,()->NativeDossierJson.encode("{ \"x\":1}".getBytes()));
 }
 @Test void authenticCapturedMetadataContextHasEligibleMicroTargets() throws Exception{
  var input=actual();var generated=new CandidateGenerator().generate(input,new DecisionRandom(input.seed()));
  var micros=generated.candidates().stream().filter(c->c.definition().id().value().endsWith("/safe_micro")).toList();
  assertEquals(2,micros.size());
  for(var candidate:micros){
   var validation=new EligibilityEngine(ProviderMode.PRODUCTION).validateKnown(candidate,input,1);
   assertTrue(validation.valid(),candidate.roles()+" reasons="+validation.reasons());
  }
 }
}
