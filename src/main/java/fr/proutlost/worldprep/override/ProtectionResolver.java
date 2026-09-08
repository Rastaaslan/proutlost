package fr.proutlost.worldprep.override;
import java.util.Objects;
/** One central precedence decision. Unknown custom content and block entities are protected by default. */
public final class ProtectionResolver {
 public enum Reason { LOCATION, PROTECTED_ZONE, MANUAL_ENVIRONMENT, BLOCK_ENTITY, UNKNOWN_CONTENT, PASS_RULE, NONE }
 public record Input(boolean location,boolean protectedZone,boolean manualEnvironment,boolean blockEntity,boolean knownReplaceable,boolean passAllows){}
 public static Reason resolve(Input i){Objects.requireNonNull(i);if(i.location())return Reason.LOCATION;if(i.protectedZone())return Reason.PROTECTED_ZONE;if(i.manualEnvironment())return Reason.MANUAL_ENVIRONMENT;if(i.blockEntity())return Reason.BLOCK_ENTITY;if(!i.knownReplaceable())return Reason.UNKNOWN_CONTENT;if(!i.passAllows())return Reason.PASS_RULE;return Reason.NONE;}
 private ProtectionResolver(){}
}
