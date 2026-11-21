package fr.proutlost.worldprep.physical;

import java.nio.file.Path;
import java.util.UUID;

/** Disposable child JVM: deliberately halts AFTER a forced immutable WAL write.
 * Never starts Minecraft, opens a production world or creates an entity.
 */
public final class NativeFaunaCrashChild {
    public static void main(String[] args)throws Exception {
        if(args.length!=2||!(args[0].equals("intent")||args[0].equals("issued")))
            throw new IllegalArgumentException("Only explicit throwaway crash windows");
        var animal=new NativeFaunaPass.Animal(
            UUID.fromString("d370d082-ef03-5aa4-9b7c-875598370121"),4956,81,-1364);
        var store=new PhysicalStore(Path.of(args[1]).resolve("fauna-wal"));
        store.write("intent-"+animal.uuid(),animal);
        if(args[0].equals("intent"))Runtime.getRuntime().halt(72);
        store.write("issued-"+animal.uuid(),animal);
        Runtime.getRuntime().halt(73);
    }
    private NativeFaunaCrashChild(){}
}
