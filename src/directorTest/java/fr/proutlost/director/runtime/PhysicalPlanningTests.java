package fr.proutlost.director.runtime;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** Domain-only physics/lease tests; not a claim that Minecraft fluids/fire are qualified. */
public final class PhysicalPlanningTests {
    static int assertions;
    static final DimensionId DIM=new DimensionId("minecraft:overworld");
    static BlockPosition p(int x,int y,int z){return new BlockPosition(DIM,x,y,z);}
    static void check(boolean b,String reason){if(!b)throw new AssertionError(reason);assertions++;}
    static void water(){
        var source=p(0,1,0);var down=p(0,0,0);var side=p(1,1,0);var yielded=p(-1,1,0);
        var map=Map.of(source,new ControlledWaterPlanner.Cell(8,new Epoch(1),false),down,new ControlledWaterPlanner.Cell(0,new Epoch(1),false),side,new ControlledWaterPlanner.Cell(0,new Epoch(1),false),yielded,new ControlledWaterPlanner.Cell(0,new Epoch(2),true));
        var mask=new HashSet<>(map.keySet());mask.add(p(0,1,1));
        var first=ControlledWaterPlanner.plan(map,mask,8,4,123);
        check(first.after().get(down).volume()==4&&first.after().get(side).volume()==0,"down before lateral transfer");
        check(first.remainingVolume()==8&&first.transfers().stream().mapToInt(ControlledWaterPlanner.Transfer::units).sum()==4,"volume and transfer budget");
        check(first.after().get(yielded).volume()==0&&!first.after().containsKey(p(0,1,1)),"yielded and unknown cells impermeable");
        Map<BlockPosition,ControlledWaterPlanner.Cell> state=map;
        for(int i=0;i<32;i++) {
            var result=ControlledWaterPlanner.plan(state,mask,8,8,i);
            check(result.remainingVolume()==8,"no volume creation/loss");
            check(result.transfers().stream().allMatch(t->mask.contains(t.from())&&mask.contains(t.to())&&!t.beforeTo().yielded()),"transfers remain owned in mask");
            // Replay identical input with a different map insertion order.
            var shuffled=new LinkedHashMap<BlockPosition,ControlledWaterPlanner.Cell>();List<BlockPosition> keys=new ArrayList<>(state.keySet());Collections.reverse(keys);for(var k:keys)shuffled.put(k,state.get(k));
            check(result.equals(ControlledWaterPlanner.plan(shuffled,mask,8,8,i)),"seeded replay independent of map insertion order");
            state=result.after();
        }
        check(ControlledWaterPlanner.plan(map,mask,8,0,123).after().equals(map),"zero work budget leaves input intact");
    }
    static void fire(){
        var source=p(0,0,0);var target=p(1,0,0);var protectedCell=p(-1,0,0);
        var cells=Map.of(source,new ControlledFirePlanner.Fuel(100,new Epoch(1),true,false),target,new ControlledFirePlanner.Fuel(0,new Epoch(1),true,false),protectedCell,new ControlledFirePlanner.Fuel(0,new Epoch(2),true,true));
        var plan=ControlledFirePlanner.plan(cells,cells.keySet(),new DurationTicks(5),1,2,10,123,false);
        check(plan.ignitions().size()==1&&plan.ignitions().getFirst().target().equals(target),"only known unyielded fuel ignites");
        check(plan.remainingEnergy().values().stream().mapToLong(Long::longValue).sum()==95,"ignition transfers finite energy after decay");
        check(ControlledFirePlanner.plan(cells,cells.keySet(),new DurationTicks(5),1,2,10,123,true).ignitions().isEmpty(),"stabilization stops propagation");
        check(ControlledFirePlanner.plan(cells,cells.keySet(),new DurationTicks(100),1,2,10,123,false).ignitions().isEmpty(),"spent energy cannot spread");
        check(ControlledFirePlanner.plan(cells,cells.keySet(),new DurationTicks(0),0,2,10,123,false).ignitions().isEmpty(),"zero spread probability");
    }
    static void spawn(){
        var registry=new SpawnLeases(8,3);var world=new WorldId(new UUID(0,1));var scope=new ScopeSet(world,Set.of(new ScopeRef(ScopeKind.WORLD,world.value())));var species=new ResourceId("minecraft:sheep");
        var lease=new SpawnLeases.Lease(new OperationToken(new UUID(0,2)),new ResourceId("proutlost_director:test_spawn"),scope,species,2,new Tick(10),new Revision(1));
        registry.install(lease,new Tick(1));
        check(registry.weight(species,scope,4,true,true,new Tick(1))==8,"contextual weight applied");
        check(registry.weight(species,scope,4,false,true,new Tick(1))==0,"other mod denial respected");
        check(registry.weight(species,scope,4,true,false,new Tick(1))==0,"vanilla/server cap never bypassed");
        check(registry.weight(species,scope,4,true,true,new Tick(10))==4,"TTL revokes modifier");
        check(!registry.revoke(lease.token(),new Revision(0)),"stale revoke rejected");
        check(registry.revoke(lease.token(),lease.revision())&&registry.weight(species,scope,4,true,true,new Tick(2))==4,"stop restores baseline rules");
    }
    public static void main(String[] args){water();fire();spawn();System.out.println("PASS physical planning/lease assertions="+assertions);System.out.println("Planning only: native water/fire/spawn behavior NOT_RUN.");}
}
