package fr.proutlost.worldprep;
import fr.proutlost.worldprep.override.ProtectionResolver;
import fr.proutlost.worldprep.area.WorldPrepArea;
import fr.proutlost.worldprep.location.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AuthoringSafetyTest {
 @Test void protectionPrecedenceIsFailClosed(){assertEquals(ProtectionResolver.Reason.LOCATION,ProtectionResolver.resolve(new ProtectionResolver.Input(true,true,true,true,false,false)));assertEquals(ProtectionResolver.Reason.BLOCK_ENTITY,ProtectionResolver.resolve(new ProtectionResolver.Input(false,false,false,true,true,true)));assertEquals(ProtectionResolver.Reason.UNKNOWN_CONTENT,ProtectionResolver.resolve(new ProtectionResolver.Input(false,false,false,false,false,true)));}
 @Test void negativeCoordinatesAndOverflowAreSafe(){var a=WorldPrepArea.between("proutlost:test","minecraft:overworld",-1,-2,-10,-20);assertTrue(a.contains(-5,-5));assertThrows(ArithmeticException.class,()->new WorldPrepArea("proutlost:huge","minecraft:overworld",Integer.MIN_VALUE,0,Integer.MAX_VALUE,1).width());}
 @Test void authoredLocationsPreserveTerrainUntilExplicitlyAuthorized(){
  var r=new LocationRegistry();var bounds=new WorldPrepArea("proutlost:authored","minecraft:overworld",-8,-8,7,7);
  r.create(new LocationDefinition(bounds.id(),LocationDefinition.Type.NARRATIVE_TRANSFORMABLE,bounds,Map.of()));
  assertTrue(r.protectedBlock(bounds.dimension(),-1,-1));assertTrue(r.protectedQuart(bounds.dimension(),-1,-1));assertFalse(r.protectedBlock(bounds.dimension(),8,8));
  var before=r.protectionFingerprint();r.anchor(bounds.id(),"center",new LocationDefinition.Anchor(bounds.dimension(),0,65,0));
  assertEquals(before,r.protectionFingerprint(),"anchor metadata does not authorize physical WorldPrep changes");
  assertThrows(IllegalArgumentException.class,()->r.anchor(bounds.id(),"outside",new LocationDefinition.Anchor(bounds.dimension(),8,65,0)));
  r.protect(bounds.id(),false);assertFalse(r.protectedBlock(bounds.dimension(),-1,-1));assertNotEquals(before,r.protectionFingerprint());
  var zone=new WorldPrepArea(bounds.id()+"/zone/manual",bounds.dimension(),0,0,3,3);r.zone(bounds.id(),"manual",zone,LocationZone.Kind.MANUAL_ENVIRONMENT);
  assertTrue(r.protectedBlock(bounds.dimension(),1,1));assertTrue(r.protectedQuart(bounds.dimension(),0,0));assertFalse(r.protectedBlock(bounds.dimension(),-1,-1));
  r.protect(bounds.id(),true);assertTrue(r.protectedBlock(bounds.dimension(),-1,-1));r.delete(bounds.id());assertFalse(r.protectedBlock(bounds.dimension(),1,1));
 }
 @Test void canonicalLocationExportMatchesTheActualFrozenDirectorCodec(){
  var r=new LocationRegistry();var b=new WorldPrepArea("proutlost:export_fixture","minecraft:overworld",-4,-4,4,4);
  r.create(new LocationDefinition(b.id(),LocationDefinition.Type.HIDDEN,b,Map.of()));r.anchor(b.id(),"entrance",new LocationDefinition.Anchor(b.dimension(),1,65,0));
  var world=UUID.fromString("11111111-2222-3333-4444-555555555555");var bytes=r.directorJson(world,b.dimension(),-64,320);
  assertArrayEquals(bytes,r.directorJson(world,b.dimension(),-64,320));
  var codec=new fr.proutlost.director.api.codec.ModelCodec<>(fr.proutlost.director.api.model.LocationImport.class);var decoded=codec.decode(bytes);
  assertEquals(decoded.exportDigest(),fr.proutlost.director.integration.locations.StandaloneLocationImporter.digest(decoded));
  assertEquals(world,decoded.worldId().value());assertTrue(decoded.regions().isEmpty());assertTrue(decoded.locations().getFirst().regionIds().isEmpty());
  assertEquals(decoded.locations().getFirst().locationId(),decoded.anchors().getFirst().locationId());
  var id=decoded.locations().getFirst().locationId();r.protect(b.id(),false);var next=codec.decode(r.directorJson(world,b.dimension(),-64,320));
  assertEquals(id,next.locations().getFirst().locationId());assertTrue(next.exportRevision().compareTo(decoded.exportRevision())>0);
  assertFalse(r.validate(b.dimension(),-64,64).findings().isEmpty(),"actual invalid anchor height must be reported");
  assertThrows(IllegalStateException.class,()->r.directorJson(world,b.dimension(),-64,64));
 }
}
