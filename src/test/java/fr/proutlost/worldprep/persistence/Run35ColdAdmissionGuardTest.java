package fr.proutlost.worldprep.persistence;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class Run35ColdAdmissionGuardTest {
    private static final String MODE=PagedSavedDataRuntime.MODE_KEY;
    private static final String WORLD=PagedSavedDataRuntime.WORLD_KEY;

    @Test void rejectsUnexpectedModeNeverEnablesProductionImplicitly() {
        String old=System.getProperty(MODE);
        try {
            System.setProperty(MODE,"CONTRA_RUN35_UNVERIFIED_PLEASE_SKIP");
            assertThrows(IllegalStateException.class,PagedSavedDataRuntime::configured);
        }finally{
            if(old==null)System.clearProperty(MODE);else System.setProperty(MODE,old);
        }
    }
    @Test void wrongWorldCannotAuthorizeRun35OrFallbackToEmptyMetadata(){
        String beforeMode=System.getProperty(MODE),beforeWorld=System.getProperty(WORLD);
        try{
            System.setProperty(MODE,PagedSavedDataRuntime.RESTART_MODE);
            System.setProperty(WORLD,"/tmp/nonexistent-run35-test-world");
            assertThrows(IllegalStateException.class,
                ()->PagedSavedDataRuntime.withStore(
                    Path.of("/tmp/nonexistent-run35-test-world"),
                    WorldPrepSavedData.FILE_ID,()->"INVALID"));
        }finally{
            if(beforeMode==null)System.clearProperty(MODE);else System.setProperty(MODE,beforeMode);
            if(beforeWorld==null)System.clearProperty(WORLD);else System.setProperty(WORLD,beforeWorld);
        }
    }
    @Test void absentAdmissionCannotAuthorizeExistingDevWorld(){
        String beforeMode=System.getProperty(MODE),beforeWorld=System.getProperty(WORLD);
        Path world=Path.of("/home/damien/contra-run22-inner-paged-nvme-game-20261028/Contra-inner-paged-nvme-run22");
        try{
            System.setProperty(MODE,PagedSavedDataRuntime.RESTART_MODE);
            System.setProperty(WORLD,world.toString());
            assertThrows(IllegalStateException.class,
                ()->PagedSavedDataRuntime.withStore(world,WorldPrepSavedData.FILE_ID,()->"INVALID"));
        }finally{
            if(beforeMode==null)System.clearProperty(MODE);else System.setProperty(MODE,beforeMode);
            if(beforeWorld==null)System.clearProperty(WORLD);else System.setProperty(WORLD,beforeWorld);
        }
    }
    @Test void originalLegacyNoModeStillDoesNotChangeNBT(){
        String old=System.getProperty(MODE);
        try{
            System.clearProperty(MODE);
            assertFalse(PagedSavedDataRuntime.configured());
            assertEquals("UNALTERED",PagedSavedDataRuntime.withStore(
                Path.of("/does-not-exist"),WorldPrepSavedData.FILE_ID,()->"UNALTERED"));
        }finally{
            if(old==null)System.clearProperty(MODE);else System.setProperty(MODE,old);
        }
    }
}
