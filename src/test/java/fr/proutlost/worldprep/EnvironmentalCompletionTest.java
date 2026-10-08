package fr.proutlost.worldprep;

import fr.proutlost.worldprep.ecology.*;
import fr.proutlost.worldprep.environment.*;
import fr.proutlost.worldprep.environment.RegistryContentCatalog.Family;
import fr.proutlost.worldprep.pipeline.*;
import fr.proutlost.worldprep.plan.PlanFingerprint;
import fr.proutlost.worldprep.storage.PagedPlanStore;
import fr.proutlost.worldprep.structure.*;
import fr.proutlost.worldprep.biome.v2.TerrainSemantic;
import fr.proutlost.worldprep.validation.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class EnvironmentalCompletionTest {
 private RegistryContentCatalog catalog(){var ids=Set.of("minecraft:dirt","minecraft:grass","minecraft:oak_log","minecraft:oak_leaves");return new RegistryContentCatalog(Map.of(Family.DIRT,List.of("minecraft:dirt"),Family.FLORA,List.of("minecraft:grass"),Family.LOG,List.of("minecraft:oak_log"),Family.LEAVES,List.of("minecraft:oak_leaves")),ids);}
 @Test void registryCatalogRejectsInstalledButIneligibleContent(){var c=catalog();assertEquals("minecraft:dirt",c.resolve(Family.DIRT,9).orElseThrow());assertFalse(c.eligible("mod:installed_only"));assertTrue(c.resolve(Family.MUD,0).isEmpty());}
 @Test void structureMaterializationIsCanonicalAndEnvelopeBounded(){var cap=new StructureCapability("test:hut",StructureSupportStatus.EXACT_TEMPLATE,"v1",3,3,3,1,1,1,Set.of(TerrainSemantic.LOWLAND));var b=new StructureInstancePlan.Bounds(0,0,0,2,2,2);var p=new StructureInstancePlan("test:hut","one",1,0,1,StructureInstancePlan.Rotation.NONE,StructureInstancePlan.Mirror.NONE,4,b,b,b);var m=new StructureMaterializer();var capture=List.of(new StructureMaterializer.CapturedBlock(2,0,0,"minecraft:air","minecraft:stone",null,null),new StructureMaterializer.CapturedBlock(0,0,0,"minecraft:air","minecraft:stone",null,null));assertEquals(0,m.materialize(p,cap,capture).mutations().getFirst().x());assertThrows(IllegalStateException.class,()->m.materialize(p,cap,List.of(new StructureMaterializer.CapturedBlock(3,0,0,"minecraft:air","minecraft:stone",null,null))));}
 @Test void reservationsExcludeFloraAndTrees(){var b=new StructureInstancePlan.Bounds(-5,0,-5,5,100,5);var r=new StructureReservationMap(List.of(new StructureReservationMap.Reservation("s",b,StructureReservationMap.Zone.CLEARANCE)));assertTrue(new FloraPlanner().plan(1,0,64,0,"minecraft:air",1,1,true,catalog(),r).isEmpty());assertTrue(new TreePlanner().plan(1,0,64,0,VegetationPlanner.TreeMode.DENSE,1,0,5,true,catalog(),r).isEmpty());}
 @Test void habitatUsesFinalEcologicalSignals(){var p=new HabitatPlanner();assertEquals(HabitatMap.Habitat.DEEP_MARINE,p.classify(new HabitatPlanner.Input(30,20,0,0,20,0,0,false)));assertEquals(HabitatMap.Habitat.DENSE_FOREST,p.classify(new HabitatPlanner.Input(0,20,.2,1,70,5,12,false)));}
 @Test void completePipelineIsPagedBoundedAndDeterministic(@TempDir Path temp)throws Exception{var input=new EnvironmentalPipeline.Input("test:area","minecraft:overworld","test",PlanFingerprint.of("source").value(),PlanFingerprint.of("pack").value(),42);var all=new EnumMap<PassId,List<EnvironmentalPipeline.Entry>>(PassId.class);for(var pass:PassGraph.order()){var values=new ArrayList<EnvironmentalPipeline.Entry>();for(int i=0;i<37;i++)values.add(new EnvironmentalPipeline.Entry(i,i%5,-i,pass.name()));all.put(pass,values);}var a=new EnvironmentalPipeline().publish(temp.resolve("a"),input,all,7);var b=new EnvironmentalPipeline().publish(temp.resolve("b"),input,all,7);assertEquals(a.run().plans(),b.run().plans());assertEquals(a.run().rootFingerprint(),b.run().rootFingerprint());assertEquals(PassGraph.order(),new ArrayList<>(a.manifests().keySet()));for(var manifest:a.manifests().values()){var metrics=PagedPlanStore.read(temp.resolve("a").resolve(manifest.planId().toString()),manifest,EnvironmentalPipeline.CODEC,x->{});assertEquals(1,metrics.maximumResidentPages());assertTrue(metrics.maximumResidentEntries()<=7);}}
 @Test void validationFailsClosedForCollisionAndMissingPlans(){var run=PipelineRun.create(UUID.randomUUID(),"a","d","p",PlanFingerprint.of("base").value(),Map.of(),PipelineRun.State.SEALED);var pos=new EnvironmentalMutation.Position(1,2,3);var mutations=List.of(new EnvironmentalMutation(PassId.FLORA,1,2,3,"minecraft:air","minecraft:grass",null,null,null),new EnvironmentalMutation(PassId.TREES,1,2,3,"minecraft:air","minecraft:oak_log",null,null,UUID.randomUUID()));var report=new EnvironmentalValidator().validate(new EnvironmentalValidator.Context(run,Map.of(),mutations,new StructureReservationMap(List.of()),Set.of(pos),Set.of("minecraft:grass","minecraft:oak_log"),false));assertFalse(report.allowsProductionApply());assertTrue(report.findings().stream().anyMatch(f->f.code().equals("PASS_COLLISION")));}
}
