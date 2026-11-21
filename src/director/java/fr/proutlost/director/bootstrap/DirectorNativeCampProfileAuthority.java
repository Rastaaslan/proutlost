package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.model.Calibration;
import fr.proutlost.director.api.model.ProviderMode;
import java.util.Objects;

/** Profile policy only. The private native constructor supplies declaredInputsVerified
 * only after the unforgeable factory Inputs token verifies its actual server/world
 * and exact configuration. This helper is not an entry point or an authority token. */
final class DirectorNativeCampProfileAuthority {
    private DirectorNativeCampProfileAuthority(){}
    static void verify(ProviderMode mode,Calibration calibration,boolean nativeLab,
            boolean verifiedLabProfile,boolean declaredInputsVerified){
        Objects.requireNonNull(mode);Objects.requireNonNull(calibration);
        if(declaredInputsVerified){
            if(mode!=ProviderMode.TEST_ONLY||calibration!=Calibration.DEFAULT_FOR_TESTING)
                throw new IllegalArgumentException("closed camp QA requires TEST_ONLY factory inputs");
            return;
        }
        if(mode==ProviderMode.TEST_ONLY){
            if(!nativeLab||!verifiedLabProfile||calibration!=Calibration.DEFAULT_FOR_TESTING)
                throw new IllegalArgumentException("verified private LAB profile required");
        }else if(calibration!=Calibration.APPROVED){
            throw new IllegalArgumentException("approved production camp profile required");
        }
    }
}
