package fr.proutlost.worldprep;

import fr.proutlost.worldprep.orchestration.*;
import fr.proutlost.worldprep.physical.*;
import java.nio.file.*;
import java.util.*;

/** Fork-only worker for true abrupt-process crash windows in disposable test WALs.
 * Never runs Minecraft or physical population.
 */
public final class MultiSpeciesAbruptExitWorker {
    private static final UUID PARENT=UUID.fromString("5f71e31c-2c2b-5350-9bea-2fe075521e08");

    private static MultiSpeciesFaunaPlan.Manifest manifest(){
        var cow=new NativeFaunaPass.Rule("minecraft:cow",
            Set.of("minecraft:beach","minecraft:plains"),
            Set.of("minecraft:dirt","minecraft:grass_block"),-64,319,
            -10f,10f,0,64,16,2,16,true,8,32);
        var cod=new NativeFaunaPass.Rule("minecraft:cod",
            Set.of("minecraft:ocean","minecraft:deep_ocean"),
            Set.of("minecraft:sand"),-64,319,
            -10f,10f,6,64,16,2,16,true,8,32);
        return MultiSpeciesFaunaPlan.prepare(PARENT,List.of(cow,cod));
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=2||!Set.of("beforeOpen","before","after","afterBoth","recover").contains(args[0]))
            throw new IllegalArgumentException("Explicit test phase and directory required");
        Path root=Path.of(args[1]).toRealPath();
        if(!root.getFileName().toString().startsWith("junit-abrupt-v5-"))
            throw new IllegalArgumentException("Only disposable JUnit directory allowed");
        var store=new PhysicalStore(root.resolve("parent"));
        var plan=manifest();
        var factory=(MultiSpeciesStageExecution.Factory)stage->{
            var child=new PhysicalStore(root.resolve("child-"+stage.ordinal()));
            if(args[0].equals("beforeOpen")&&stage.ordinal()==0)
                Runtime.getRuntime().halt(74); // parent stage intent persisted, no child
            child.write("intent",stage);
            return ()->{
                String evidence="NATIVE_CHILD_DURABLE:"+stage.species()+":"+stage.childBatch();
                child.write("evidence",evidence);
                if(args[0].equals("before")&&stage.ordinal()==0) {
                    // Abrupt JVM death with child evidence durable but no parent receipt.
                    Runtime.getRuntime().halt(72);
                }
                return Optional.of(evidence);
            };
        };
        var validator=(MultiSpeciesStageExecution.Validator)(stage,evidence)->{
            var child=new PhysicalStore(root.resolve("child-"+stage.ordinal()));
            String expected="NATIVE_CHILD_DURABLE:"+stage.species()+":"+stage.childBatch();
            if(!expected.equals(evidence)||!child.read("evidence",String.class).equals(evidence))
                throw new java.io.IOException("Child native WAL disagrees with v5 parent");
        };
        try(var runner=new MultiSpeciesStageExecution(plan,store,factory,validator)) {
            if(args[0].equals("after")) {
                runner.step(); // parent stage-0 intent durable, child opened
                runner.step(); // parent stage-0 receipt persisted
                if(runner.stageCursor()!=1)throw new IllegalStateException("Missing durable stage-0");
                Runtime.getRuntime().halt(73);
            }
            if(args[0].equals("afterBoth")){
                for(int i=0;i<16&&runner.stageCursor()<2;i++)runner.step();
                if(runner.stageCursor()!=2||store.exists("aggregate"))
                    throw new IllegalStateException("Both child receipts not durable");
                Runtime.getRuntime().halt(75); // both receipts persisted, aggregate absent
            }
            for(int i=0;i<16;i++)if(runner.step().isPresent()){
                var receipt=store.read("aggregate",MultiSpeciesStageExecution.Aggregate.class);
                if(receipt.stages()!=2||!receipt.parent().equals(PARENT)
                    ||!"ALL_SPECIES_RECEIPTS_COMPLETE_NOT_MAP_READY".equals(receipt.status()))
                    throw new IllegalStateException("Incorrect recovered v5 aggregate");
                System.out.println("ABRUPT_V5_RESTART_PROOF_PASS stages=2");
                return;
            }
            throw new IllegalStateException("v5 WAL did not complete recovery");
        }
    }
    private MultiSpeciesAbruptExitWorker(){}
}
