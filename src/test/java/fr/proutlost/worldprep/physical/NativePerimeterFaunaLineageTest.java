package fr.proutlost.worldprep.physical;

import fr.proutlost.worldprep.orchestration.PerimeterGroups;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativePerimeterFaunaLineageTest {
    private static final String CONFIG="s2-v5-child-physical-lineage";
    private final PerimeterGroups groups=new PerimeterGroups(
        UUID.fromString("5f71e31c-2c2b-5350-9bea-2fe075521e08"),
        "a".repeat(64),"minecraft:overworld",4496,-1666,5236,-943);
    private final PerimeterGroups.Group group=groups.group(0);
    private final NativeFaunaPass.Rule cod=new NativeFaunaPass.Rule(
        "minecraft:cod",Set.of("minecraft:ocean"),
        Set.of("minecraft:sand"),-64,319,-10f,10f,6,64,16,2,16,true,8,32);
    private final NativeFaunaPass.Rule cow=new NativeFaunaPass.Rule(
        "minecraft:cow",Set.of("minecraft:plains"),
        Set.of("minecraft:grass_block"),-64,319,-10f,10f,0,64,16,2,16,true,8,32);

    private NativeFaunaPass.Plan plan(NativeFaunaPass.Rule r,String config,
        WorldPrepSavedData.Area area,NativeFaunaPass.Animal animal) {
        return new NativeFaunaPass.Plan(1,UUID.fromString("d7d161ba-d0c7-5527-92b5-ff66cc257642"),
            area,config,r,List.of(animal));
    }
    private WorldPrepSavedData.Area area(String dim,int startX) {
        return new WorldPrepSavedData.Area("test/native-species-group",
            dim,startX,group.minZ(),startX+15,group.minZ()+15);
    }
    private NativeFaunaPass.Animal animal(int x){
        return new NativeFaunaPass.Animal(
            UUID.fromString("3b113248-a58b-5ff2-9b0e-807d5bed263c"),x,49,group.minZ()+6);
    }
    private NativeFaunaPass.Plan valid(){
        return plan(cod,CONFIG+"/child/"+group.operation(),
            area("minecraft:overworld",group.minX()+16),animal(group.minX()+20));
    }
    private void check(NativeFaunaPass.Plan p,NativeFaunaPass.Rule r,String config,String dimension)
            throws IOException {
        NativePerimeterFauna.requireSpeciesLineage(r,p,group,config,dimension);
    }
    @Test void expectedSpeciesGroupDimensionAndConfigAccepted() {
        assertDoesNotThrow(()->check(valid(),cod,CONFIG,"minecraft:overworld"));
    }
    @Test void changedSpeciesCannotReuseAnAuthenticOtherSpeciesReceipt() {
        assertThrows(IOException.class,()->check(valid(),cow,CONFIG,"minecraft:overworld"));
    }
    @Test void changedFrozenRequestCannotReuseAChildReceipt() {
        assertThrows(IOException.class,()->check(valid(),cod,"v5-substitution","minecraft:overworld"));
    }
    @Test void changedDimensionCannotReuseTheWrongWorldPopulation() {
        assertThrows(IOException.class,()->check(valid(),cod,CONFIG,"minecraft:the_nether"));
    }
    @Test void outOfGroupAnimalCannotPassEvenWhenPlanAreaLooksValid() {
        var area=area("minecraft:overworld",group.minX()+16);
        var forged=plan(cod,CONFIG+"/child/"+group.operation(),area,
            animal(group.maxX()+5));
        assertThrows(IOException.class,()->check(forged,cod,CONFIG,"minecraft:overworld"));
    }
    @Test void outsideGroupAreaCannotBeGraftedIntoTheChain() {
        var forged=plan(cod,CONFIG+"/child/"+group.operation(),
            area("minecraft:overworld",group.maxX()+17),animal(group.minX()+20));
        assertThrows(IOException.class,()->check(forged,cod,CONFIG,"minecraft:overworld"));
    }
}
