package fr.proutlost.worldprep.orchestration;
import java.util.UUID;
/** Qualification fixture evidence only; never grants population replacement or global readiness. */
public record FaunaRestartEvidence(int version,String planSha256,UUID entity,UUID jvm,String entityNbtSha256,String status) {
    public FaunaRestartEvidence {
        if(version!=3||planSha256==null||!planSha256.matches("[0-9a-f]{64}")||entity==null||jvm==null||entityNbtSha256==null||!entityNbtSha256.matches("[0-9a-f]{64}")
            ||!"POST_CLEANUP_SAVE_READBACK_NOT_GLOBAL_QUALIFICATION".equals(status))throw new IllegalArgumentException("Post-cleanup disk evidence required");
    }
    public void requireRestart(String plan,UUID expected,UUID currentJvm){
        if(!planSha256.equals(plan)||!entity.equals(expected)||currentJvm==null||jvm.equals(currentJvm))throw new IllegalStateException("Same plan/UUID and different JVM required");
    }
}
