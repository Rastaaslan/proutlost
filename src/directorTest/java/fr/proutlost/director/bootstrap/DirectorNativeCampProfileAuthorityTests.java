package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.model.Calibration;
import fr.proutlost.director.api.model.ProviderMode;

/** Pure authority-policy regressions, not a Minecraft/world/operator approval. */
public final class DirectorNativeCampProfileAuthorityTests {
    private static int assertions;
    private static void allowed(ProviderMode mode,Calibration calibration,boolean lab,boolean profile,boolean token){
        DirectorNativeCampProfileAuthority.verify(mode,calibration,lab,profile,token);assertions++;
    }
    private static void refused(ProviderMode mode,Calibration calibration,boolean lab,boolean profile,boolean token){
        try{DirectorNativeCampProfileAuthority.verify(mode,calibration,lab,profile,token);}
        catch(IllegalArgumentException|NullPointerException expected){assertions++;return;}
        throw new AssertionError("foreign profile route unexpectedly accepted: "+mode+" "+calibration+" "+lab+" "+profile+" "+token);
    }
    public static void main(String[] args){
        // Reproduce the native misrouting: DEFAULT_FOR_TESTING with no LAB and no typed factory token.
        refused(ProviderMode.TEST_ONLY,Calibration.DEFAULT_FOR_TESTING,false,false,false);
        // The actual native factory must verify its world-local Inputs before supplying this route.
        allowed(ProviderMode.TEST_ONLY,Calibration.DEFAULT_FOR_TESTING,false,false,true);
        // Flags or the profile alone grant no closed-QA authority.
        refused(ProviderMode.TEST_ONLY,Calibration.DEFAULT_FOR_TESTING,false,true,false);
        refused(ProviderMode.TEST_ONLY,Calibration.DEFAULT_FOR_TESTING,true,false,false);
        allowed(ProviderMode.TEST_ONLY,Calibration.DEFAULT_FOR_TESTING,true,true,false);
        // These are enum-policy cases only; no APPROVED ConfigView, file, or operator attestation is produced.
        refused(ProviderMode.TEST_ONLY,Calibration.APPROVED,true,true,false);
        refused(ProviderMode.TEST_ONLY,Calibration.APPROVED,false,false,true);
        refused(ProviderMode.PRODUCTION,Calibration.DEFAULT_FOR_TESTING,false,false,false);
        refused(ProviderMode.PRODUCTION,Calibration.DEFAULT_FOR_TESTING,true,true,false);
        refused(ProviderMode.PRODUCTION,Calibration.DEFAULT_FOR_TESTING,false,false,true);
        refused(ProviderMode.PRODUCTION,Calibration.APPROVED,false,false,true);
        allowed(ProviderMode.PRODUCTION,Calibration.APPROVED,false,false,false);
        allowed(ProviderMode.PRODUCTION,Calibration.APPROVED,true,false,false);
        refused(null,Calibration.DEFAULT_FOR_TESTING,false,false,true);
        refused(ProviderMode.TEST_ONLY,null,false,false,true);
        System.out.println("DIRECTOR_CAMP_PROFILE_AUTHORITY_POLICY_PASS assertions="+assertions+" native_factory_and_pair_retest=NOT_RUN");
    }
}
