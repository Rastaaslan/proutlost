package fr.proutlost.director.bootstrap;
import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.memory.MemoryProjection;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/** Regression from the actual P005 two-client failure; no world fixture repair. */
final class NativeImportLogicalClockTest {
 private static DirectorWorldState recovered() throws Exception {
  try(var in=NativeImportLogicalClockTest.class.getResourceAsStream("/native-import-clock/actual-paused-world.json")){
   return new ModelCodec<>(DirectorWorldState.class).decode(Objects.requireNonNull(in).readAllBytes());
  }
 }
 @Test void completeAuthoredAdmissionCannotCreateFutureDatedMetadata() throws Exception {
  var world=recovered();long logicalNow=310435;
  var tick=DirectorNativeLocationImports.importTick(world,List.of(new WorldAggregate(world)),logicalNow);
  LocationImport authentic;
  try(var in=NativeImportLogicalClockTest.class.getResourceAsStream("/authentic-contra-g0/revision3.json")){
   authentic=new ModelCodec<>(LocationImport.class).decode(Objects.requireNonNull(in).readAllBytes());
  }
  var scopes=new ScopeSet(authentic.worldId(),Set.of(new ScopeRef(ScopeKind.WORLD,authentic.worldId().value())));
  var context=new DirectorNativeLocationImports.Context(new Sequence(0),tick,
      MemoryProjection.of(scopes,List.of()),Map.of(),Set.of());
  var plan=DirectorNativeLocationImports.preflight(authentic.worldId(),authentic,null,context,Map.of(),Set.of());
  assertEquals(64,plan.states().size());
  for(var state:plan.states().values()){
   assertEquals(logicalNow,state.header().updatedTick().value());
   assertTrue(state.header().updatedTick().value()<logicalNow+1,
       "The next J01 reduction must not see imported metadata in its future");
  }
 }
 @Test void futureDurableMetadataRequiresRecoveryInsteadOfInventingAClock() throws Exception {
  var world=recovered();var futureHeader=new AggregateHeader(1,UUID.randomUUID(),new Revision(1),new Sequence(1),new Tick(318720));
  var future=new RegionMemoryAggregate(new RegionMemory(futureHeader,new RegionId(futureHeader.id()),
      Map.of(),new Tick(0),new Tick(0),Set.of(),Set.of(),new RegionActivityState(Map.of(),Map.of())));
  var refused=assertThrows(IllegalArgumentException.class,()->
      DirectorNativeLocationImports.importTick(world,List.of(future),310435));
  assertEquals("import context ahead of Director logical clock",refused.getMessage());
  assertEquals(318720,DirectorNativeLocationImports.importTick(world,List.of(future),318720).value());
 }
 @Test void aFutureWorldStateCannotBeHiddenByAnEmptyMetadataSet() throws Exception {
  var world=recovered();
  assertThrows(IllegalArgumentException.class,()->
      DirectorNativeLocationImports.importTick(world,List.of(),world.simTick().value()-1));
 }
}
