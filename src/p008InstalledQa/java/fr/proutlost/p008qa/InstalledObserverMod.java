package fr.proutlost.p008qa;

import java.lang.reflect.InvocationTargetException;
import net.neoforged.fml.common.Mod;

/** Separate TEST_ONLY server addon. No Director package, payload or writer.
 * Its fixed reflection entry remains inside the QA derivative of Director,
 * avoiding duplicate FQCNs and split Director packages across modules. */
@Mod(InstalledObserverMod.MOD_ID)
public final class InstalledObserverMod {
    public static final String MOD_ID="proutlost_p008_qa";
    public InstalledObserverMod(){
        if(!Boolean.getBoolean("proutlost.director.p008InstalledQaObserver"))
            throw new IllegalStateException("P008 TEST_ONLY observer requires its explicit JVM flag");
        try{
            Class.forName("fr.proutlost.director.adapter.neoforge.server.DirectorNativePagedLocationImportGameTests")
                .getMethod("install").invoke(null);
        }catch(InvocationTargetException failed){
            throw new IllegalStateException("P008 TEST_ONLY observer refused installation",failed.getCause());
        }catch(ReflectiveOperationException absent){
            throw new IllegalStateException("Exact P008 TEST_ONLY Director observer overlay is required",absent);
        }
    }
}
