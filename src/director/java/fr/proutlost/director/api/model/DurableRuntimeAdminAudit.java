package fr.proutlost.director.api.model;
import java.util.Objects;
/** Persist atomically with actor control changes before publishing terminal success. */
public record DurableRuntimeAdminAudit(RuntimeAdminAudit audit) implements DurableRecord {
 public DurableRuntimeAdminAudit {Objects.requireNonNull(audit);}
}
